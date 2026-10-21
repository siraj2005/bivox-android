package app.bivox.android

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

// الميكروفون: تعرّف على الكلام عبر محرك أندرويد المدمج (SpeechRecognizer) دون مكتبات إضافية
// Microphone: speech recognition through Android's built-in engine (SpeechRecognizer), no extra libraries
object Mic {
    // وسوم اللغات التي يفهمها محرك التعرف / language tags the recognizer understands
    private val TAGS = mapOf(
        "ar" to "ar-SA", "en" to "en-US", "fr" to "fr-FR", "es" to "es-ES",
        "de" to "de-DE", "tr" to "tr-TR", "ru" to "ru-RU", "zh" to "zh-CN",
        "hi" to "hi-IN", "pt" to "pt-BR", "it" to "it-IT",
    )

    fun tag(code: String): String = TAGS[code] ?: code

    // هل يوجد محرك تعرف على الجهاز؟ / is there a recognition engine on the device?
    fun available(c: Context): Boolean =
        try { SpeechRecognizer.isRecognitionAvailable(c) } catch (e: Exception) { false }

    fun granted(c: Context): Boolean =
        try { c.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED }
        catch (e: Exception) { false }

    // يحوّل رمز خطأ المحرك إلى مفتاح نص في الواجهة / maps a recognizer error code to a UI text key
    fun errorKey(code: Int): String = when (code) {
        6, 7 -> "mic_nospeech"          // لا كلام / no speech
        9 -> "mic_denied"               // لا إذن / no permission
        1, 2, 4 -> "mic_net"            // شبكة أو خادم / network or server
        8 -> "mic_busy"                 // المحرك مشغول / recognizer busy
        12, 13 -> "mic_lang"            // لغة غير مدعومة أو غير جاهزة / language unsupported or unavailable
        else -> "mic_err"
    }
}

// جلسة استماع واحدة: تُنشأ وتُستدعى على خيط الواجهة / one listening session: created and called on the main thread
class MicSession(
    private val ctx: Context,
    private val onPartial: (String) -> Unit,
    private val onFinal: (String) -> Unit,
    private val onFail: (String, Int) -> Unit,
    private val onLevel: (Float) -> Unit = {},
) {
    private var rec: SpeechRecognizer? = null
    private var done = false
    private var t0 = 0L
    private var gotPartial = false
    @Volatile var peak = -10f            // أعلى مستوى صوت التقطه الميكروفون / the highest sound level the mic picked up

    private fun ms(): Long = SystemClock.uptimeMillis() - t0

    fun start(code: String): Boolean {
        t0 = SystemClock.uptimeMillis()
        return try {
            val r = SpeechRecognizer.createSpeechRecognizer(ctx)
            rec = r
            r.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) { Dbg.log("mic", "ready for speech (+" + ms() + "ms)") }
                override fun onBeginningOfSpeech() { Dbg.log("mic", "speech begins (+" + ms() + "ms)") }
                override fun onRmsChanged(rmsdB: Float) {
                    if (rmsdB > peak) peak = rmsdB
                    if (!done) onLevel(rmsdB)
                }
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() { Dbg.log("mic", "speech ends (+" + ms() + "ms)") }
                override fun onEvent(eventType: Int, params: Bundle?) {}
                override fun onPartialResults(partialResults: Bundle?) {
                    if (done) return
                    val s = first(partialResults)
                    if (s.isNotEmpty()) {
                        if (!gotPartial) { gotPartial = true; Dbg.log("mic", "first partial: \"" + s + "\" (+" + ms() + "ms)") }
                        onPartial(s)
                    }
                }
                override fun onResults(results: Bundle?) {
                    if (done) return
                    done = true
                    val s = first(results)
                    val all = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    Dbg.log("mic", "final: \"" + s + "\" alts=" + (all?.size ?: 0) + " peak=" + "%.1f".format(peak) + " (+" + ms() + "ms)")
                    release()
                    if (s.isEmpty()) onFail("mic_nospeech", 7) else onFinal(s)
                }
                override fun onError(error: Int) {
                    if (done) return
                    done = true
                    Dbg.log("mic", "ERROR code=" + error + " (" + Mic.errorKey(error) + ") peak=" + "%.1f".format(peak) + " (+" + ms() + "ms)")
                    release()
                    onFail(Mic.errorKey(error), error)
                }
            })
            val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Mic.tag(code))
            i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            // صبر أطول كي تُقبل الكلمات القصيرة والجمل القصيرة (قد يتجاهلها بعض المحركات)
            // More patience so short words and short sentences are accepted (some engines may ignore these)
            i.putExtra("android.speech.extras.SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS", 2000L)
            i.putExtra("android.speech.extras.SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS", 2000L)
            i.putExtra("android.speech.extras.SPEECH_INPUT_MINIMUM_LENGTH_MILLIS", 500L)
            i.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, ctx.packageName)
            r.startListening(i)
            true
        } catch (e: Exception) {
            Dbg.log("mic", "start failed: " + e)
            release()
            false
        }
    }

    // الضغط ثانية: ينهي الاستماع ويسلّم ما سمعه / pressing again: ends listening and delivers what it heard
    fun stop() {
        try { rec?.stopListening() } catch (e: Exception) { }
    }

    // إلغاء بلا نتيجة / cancel without a result
    fun cancel() {
        done = true
        release()
    }

    private fun release() {
        val r = rec ?: return
        rec = null
        try { r.setRecognitionListener(null) } catch (e: Exception) { }
        try { r.cancel() } catch (e: Exception) { }
        try { r.destroy() } catch (e: Exception) { }
    }

    private fun first(b: Bundle?): String {
        val l = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        return l?.firstOrNull()?.trim() ?: ""
    }
}

// نشاط شفاف لطلب إذن الميكروفون (الخدمة لا تستطيع طلب الأذونات بنفسها)
// Transparent activity that asks for the microphone permission (a service cannot ask by itself)
class MicPermissionActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 23) {
            try {
                requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 7)
            } catch (e: Exception) {
                finishWith(false)
            }
        } else {
            finishWith(true)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        finishWith(grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED)
    }

    private fun finishWith(g: Boolean) {
        val cb = callback
        callback = null
        finish()
        cb?.invoke(g)
    }

    companion object {
        // يُستدعى على خيط الواجهة بعد جواب المستخدم / called on the main thread after the user answers
        @Volatile
        var callback: ((Boolean) -> Unit)? = null

        fun request(c: Context, cb: (Boolean) -> Unit): Boolean {
            callback = cb
            return try {
                c.startActivity(Intent(c, MicPermissionActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                true
            } catch (e: Exception) {
                callback = null
                false
            }
        }
    }
}
