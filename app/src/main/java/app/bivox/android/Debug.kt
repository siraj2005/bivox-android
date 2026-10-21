package app.bivox.android

import android.content.Context
import android.os.Build
import android.provider.Settings
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// سجل تشخيص في الذاكرة فقط (آخر 300 حدث): لا يُكتب على القرص ولا يُرسل لأي مكان، وينسخه المستخدم يدويًا
// In-memory diagnostics log (last 300 events): never written to disk or sent anywhere; the user copies it by hand
object Dbg {
    private const val MAX = 300
    private val buf = ArrayList<String>()
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val last = HashMap<String, Long>()

    fun log(tag: String, msg: String) {
        val line = synchronized(fmt) { fmt.format(Date()) } + "  " + tag.padEnd(5) + " " + msg
        synchronized(buf) {
            buf.add(line)
            while (buf.size > MAX) buf.removeAt(0)
        }
    }

    // يسجّل أحداثًا متكررة بحد أدنى للفاصل (ملّي ثانية) حتى لا تغمر السجل
    // Logs frequent events with a minimum interval (ms) so they don't flood the log
    fun logEvery(tag: String, minGapMs: Long, msg: String) {
        val now = System.currentTimeMillis()
        synchronized(last) {
            val p = last[tag] ?: 0L
            if (now - p < minGapMs) return
            last[tag] = now
        }
        log(tag, msg)
    }

    fun clear() {
        synchronized(buf) { buf.clear() }
    }

    fun events(): List<String> = synchronized(buf) { ArrayList(buf) }

    // لقطة عن الجهاز والأذونات والمحركات، ثم الأحداث / a snapshot of device, permissions and engines, then the events
    fun report(c: Context): String {
        val sb = StringBuilder()
        sb.append("BiVox ").append(version(c)).append('\n')
        sb.append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
            .append(" · Android ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")\n")
        sb.append("mic perm: ").append(Mic.granted(c)).append(" · recognizer: ").append(Mic.available(c)).append('\n')
        sb.append("recognizer service: ").append(secure(c, "voice_recognition_service")).append('\n')
        sb.append("recognizers: ").append(recognizers(c)).append('\n')
        sb.append("tts engine: ").append(secure(c, "tts_default_synth")).append('\n')
        sb.append("overlay: ").append(BubbleService.canOverlay(c))
            .append(" · bubble: ").append(BubbleService.running)
            .append(" · a11y: ").append(BivoxAccessibilityService.instance != null).append('\n')
        sb.append("online: ").append(Net.online(c))
            .append(" · langs: ").append(Langs.CODES[Prefs.my(c)]).append(" ⇄ ").append(Langs.CODES[Prefs.their(c)]).append('\n')
        sb.append("──────── events (").append(events().size).append(") ────────\n")
        for (e in events()) sb.append(e).append('\n')
        return sb.toString()
    }

    private fun version(c: Context): String =
        try { c.packageManager.getPackageInfo(c.packageName, 0).versionName ?: "?" } catch (e: Exception) { "?" }

    private fun secure(c: Context, key: String): String =
        try { Settings.Secure.getString(c.contentResolver, key) ?: "-" } catch (e: Exception) { "?" }

    private fun recognizers(c: Context): String =
        try {
            val l = c.packageManager.queryIntentServices(android.content.Intent("android.speech.RecognitionService"), 0)
            if (l.isEmpty()) "none" else l.joinToString(", ") { it.serviceInfo.packageName }
        } catch (e: Exception) { "?" }
}
