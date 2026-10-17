package app.bivox.android

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

// نتيجة محاولة النطق / result of a speak attempt
enum class SpeakResult { OK, NO_ENGINE, NO_VOICE, EMPTY }

// النطق (اختياري): إن غاب المحرك أو صوت اللغة نخبر المستخدم ولا يتعطل التطبيق
// Speech (optional): if the engine or the language voice is missing we tell the user and nothing breaks
class Speaker(context: Context, private val onReady: () -> Unit) {

    private var engine: TextToSpeech? = null

    @Volatile
    var available: Boolean = false
        private set

    init {
        try {
            engine = TextToSpeech(context.applicationContext) { status ->
                available = status == TextToSpeech.SUCCESS
                onReady()
            }
        } catch (e: Exception) {
            engine = null
            available = false
        }
    }

    fun say(text: String, lang: String): SpeakResult {
        val e = engine
        if (!available || e == null) return SpeakResult.NO_ENGINE
        if (text.isEmpty()) return SpeakResult.EMPTY
        return try {
            if (!selectVoice(e, lang)) {
                SpeakResult.NO_VOICE
            } else {
                e.speak(text, TextToSpeech.QUEUE_FLUSH, null, "bivox")
                SpeakResult.OK
            }
        } catch (ex: Exception) {
            SpeakResult.NO_ENGINE
        }
    }

    // نجرب اللغة العامة ثم كل نسخ الدول التي يعرضها المحرك (مثل ar-SA)
    // Try the plain language, then every country variant the engine lists (e.g. ar-SA)
    private fun selectVoice(e: TextToSpeech, lang: String): Boolean {
        val candidates = ArrayList<Locale>()
        candidates.add(Locale(lang))
        try {
            val all = e.availableLanguages
            if (all != null) {
                for (l in all) if (l.language == lang) candidates.add(l)
            }
        } catch (ex: Exception) {
            // بعض المحركات لا تعرض القائمة / some engines do not expose the list
        }
        for (l in candidates) {
            val r = e.setLanguage(l)
            if (r >= TextToSpeech.LANG_AVAILABLE) return true
        }
        return false
    }

    fun shutdown() {
        try {
            engine?.stop()
            engine?.shutdown()
        } catch (e: Exception) {
        }
        engine = null
        available = false
    }
}
