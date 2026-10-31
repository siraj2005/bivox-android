package app.bivox.android

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.SystemClock
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

// واجهة مشتركة لجلسات الاستماع (محرك الهاتف أو Google عبر الإنترنت)
// Common interface for listening sessions (the phone's engine or Google over the internet)
interface MicRun {
    fun start(code: String): Boolean
    fun stop()       // ينهي الاستماع ويسلّم ما سُمع / ends listening and delivers what was heard
    fun cancel()     // إلغاء بلا نتيجة / cancel without a result
}

// استماع بمحرك Google عبر الإنترنت (كما في نسخة الكمبيوتر): نسجّل الصوت بأنفسنا،
// نكشف بداية الكلام ونهايته، ثم نرسله كمقطع واحد ونقرأ النص.
// Listening with Google's engine over the internet (like the desktop version): we record the audio ourselves,
// detect where speech starts and ends, then send it as one clip and read back the text.
class WebMicSession(
    private val ctx: Context,
    private val main: Handler,
    private val onFinal: (String) -> Unit,
    private val onFail: (String, Int) -> Unit,   // (مفتاح الرسالة، رمز) — الرمز -1 = تعذّر الوصول للخدمة فنرجع لمحرك الهاتف / (message key, code) — code -1 = service unreachable, so we fall back to the phone engine
    private val onLevel: (Float) -> Unit,
    private val onSending: () -> Unit,
) : MicRun {

    @Volatile private var cancelled = false
    @Volatile private var stopReq = false
    @Volatile var peak = -10f
    private var worker: Thread? = null

    override fun start(code: String): Boolean {
        val t = Thread { runSession(code) }
        t.name = "bivox-webmic"
        worker = t
        t.start()
        return true
    }

    override fun stop() { stopReq = true }

    override fun cancel() {
        cancelled = true
        stopReq = true
    }

    private fun fail(key: String, code: Int) {
        Dbg.log("mic", "web: fail " + key + " (" + code + ")")
        main.post { if (!cancelled) onFail(key, code) }
    }

    private fun runSession(lang: String) {
        val t0 = SystemClock.uptimeMillis()
        val minBuf = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) { fail("mic_err", -2); return }
        val frame = RATE / 50                           // 20 ملّي ثانية / 20 ms
        val rec: AudioRecord? = try {
            AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, max(minBuf, frame * 2 * 8))
        } catch (e: Exception) { null }
        if (rec == null || rec.state != AudioRecord.STATE_INITIALIZED) {
            try { rec?.release() } catch (e: Exception) { }
            fail("mic_err", -3)
            return
        }
        try {
            rec.startRecording()
        } catch (e: Exception) {
            try { rec.release() } catch (x: Exception) { }
            fail("mic_err", -4)
            return
        }
        Dbg.log("mic", "web: recording started, lang=" + Mic.tag(lang))

        val pcm = ByteArrayOutputStream()
        val pre = ArrayList<ShortArray>()               // ذاكرة قبل بدء الكلام (300 ملّي ثانية) / pre-roll before speech starts (300 ms)
        val buf = ShortArray(frame)
        var noise = 0.0
        var frames = 0
        var speaking = false
        var loud = 0
        var silent = 0
        var speechFrames = 0
        var reason = "stop"
        try {
            while (!cancelled) {
                val n = rec.read(buf, 0, frame)
                if (n < 0) { reason = "read error " + n; break }
                if (n == 0) continue
                frames++
                var sum = 0.0
                for (i in 0 until n) sum += buf[i].toDouble() * buf[i].toDouble()
                val rms = sqrt(sum / n)
                val db = 20f * log10(max(rms, 1.0).toFloat() / 32768f)
                val lvl = ((db + 55f) / 55f) * 12f - 2f
                if (lvl > peak) peak = lvl
                main.post { if (!cancelled) onLevel(lvl) }

                // أول 300 ملّي ثانية: نقدّر ضجيج الخلفية / the first 300 ms: estimate the background noise
                if (frames <= 15) noise = (noise * (frames - 1) + rms) / frames
                val thr = max(noise * 2.5, 250.0)

                if (!speaking) {
                    pre.add(buf.copyOf(n))
                    if (pre.size > 15) pre.removeAt(0)
                    if (frames > 15 && rms > thr) loud++ else loud = 0
                    if (loud >= 2) {
                        speaking = true
                        for (f in pre) write(pcm, f)
                        pre.clear()
                        Dbg.log("mic", "web: speech begins (+" + (SystemClock.uptimeMillis() - t0) + "ms, level>" + thr.toInt() + ")")
                    } else if (frames > 400) {            // 8 ثوانٍ بلا كلام / 8 s with no speech
                        reason = "no speech"
                        break
                    }
                } else {
                    write(pcm, buf.copyOf(n))
                    speechFrames++
                    if (rms > thr) silent = 0 else silent++
                    if (silent >= 75) { reason = "silence"; break }       // 1.5 ثانية صمت / 1.5 s of silence
                    if (speechFrames >= 1000) { reason = "max length"; break }  // 20 ثانية / 20 s
                }
                if (stopReq) { reason = "stopped by user"; break }
            }
        } catch (e: Exception) {
            reason = "error " + e.javaClass.simpleName
        } finally {
            try { rec.stop() } catch (e: Exception) { }
            try { rec.release() } catch (e: Exception) { }
        }
        if (cancelled) return
        Dbg.log("mic", "web: recording ended (" + reason + "), speech " + (speechFrames * 20) + "ms, peak=" + "%.1f".format(peak))
        if (!speaking && pcm.size() == 0) { fail("mic_nospeech", 6); return }

        main.post { if (!cancelled) onSending() }
        val bytes = pcm.toByteArray()
        val tUp = SystemClock.uptimeMillis()
        val text = try {
            upload(bytes, Mic.tag(lang))
        } catch (e: Exception) {
            Dbg.log("mic", "web: upload failed: " + e.javaClass.simpleName + " " + (e.message ?: ""))
            fail("mic_net", -1)
            return
        }
        Dbg.log("mic", "web: response in " + (SystemClock.uptimeMillis() - tUp) + "ms, " + bytes.size + " bytes → \"" + text + "\"")
        if (cancelled) return
        if (text.isEmpty()) { fail("mic_nospeech", 7); return }
        main.post { if (!cancelled) onFinal(text) }
    }

    private fun write(out: ByteArrayOutputStream, s: ShortArray) {
        val b = ByteArray(s.size * 2)
        for (i in s.indices) {
            b[2 * i] = (s[i].toInt() and 0xFF).toByte()
            b[2 * i + 1] = ((s[i].toInt() shr 8) and 0xFF).toByte()
        }
        // audio/l16 يتوقع big-endian: نبدّل البايتين / audio/l16 expects big-endian: swap the two bytes
        for (i in s.indices) {
            val a = b[2 * i]; b[2 * i] = b[2 * i + 1]; b[2 * i + 1] = a
        }
        out.write(b, 0, b.size)
    }

    // يرسل الصوت ويعيد أفضل نص (أو "" إن لم يفهم) / sends the audio and returns the best text ("" if nothing understood)
    private fun upload(pcm: ByteArray, tag: String): String {
        val url = URL(ENDPOINT + "?output=json&lang=" + tag + "&maxresults=3&key=" + KEY)
        val c = url.openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.doOutput = true
            c.connectTimeout = 8000
            c.readTimeout = 15000
            c.setRequestProperty("Content-Type", "audio/l16; rate=$RATE")
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) BiVox")
            c.outputStream.use { it.write(pcm) }
            val code = c.responseCode
            if (code != 200) {
                Dbg.log("mic", "web: HTTP " + code)
                throw java.io.IOException("HTTP $code")
            }
            val body = c.inputStream.bufferedReader().use { it.readText() }
            for (line in body.lines()) {
                if (line.isBlank()) continue
                val arr = (try { JSONObject(line).optJSONArray("result") } catch (e: Exception) { null }) ?: continue
                if (arr.length() == 0) continue
                val alts = arr.getJSONObject(0).optJSONArray("alternative") ?: continue
                if (alts.length() == 0) continue
                return alts.getJSONObject(0).optString("transcript", "").trim()
            }
            return ""
        } finally {
            c.disconnect()
        }
    }

    companion object {
        private const val RATE = 16000
        private const val ENDPOINT = "https://www.google.com/speech-api/v2/recognize"
        // مفتاح Chromium العام المعروف (نفسه الذي تستعمله مكتبات Python ونسخة الكمبيوتر)، وليس سرًّا لنا
        // The well-known public Chromium key (the same one Python libraries and the desktop version use); not a secret of ours
        private const val KEY = "AIzaSyBOti4mM-6x9WDnZIjIeyEU21OpBXqWBgw"
    }
}
