package app.bivox.android

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

// النطق (اختياري): إن غاب المحرك أو اللغة لا ينطق شيئًا ولا يتعطل التطبيق
// Speech (optional): if the engine or language is missing, nothing is spoken and nothing breaks
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

    fun say(text: String, lang: String) {
        val e = engine
        if (!available || e == null || text.isEmpty()) return
        try {
            val r = e.setLanguage(Locale(lang))
            if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) return
            e.speak(text, TextToSpeech.QUEUE_FLUSH, null, "bivox")
        } catch (ex: Exception) {
            // نتجاهل: النطق ميزة إضافية / ignore: speech is a bonus feature
        }
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
