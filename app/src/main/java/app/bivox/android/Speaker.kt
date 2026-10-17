package app.bivox.android

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import java.util.Locale

// نتيجة محاولة النطق / result of a speak attempt
enum class SpeakResult { OK, NO_ENGINE, NO_VOICE, EMPTY }

// النطق عبر كل محركات النطق المثبتة (Google ثم الافتراضي ثم البقية).
// لكل لغة نختار صوتًا منزَّلًا فعلًا من أي محرك؛ فلا نعتمد على المحرك الافتراضي وحده.
// Speech across ALL installed TTS engines (Google, then the default, then the rest).
// For each language we pick a voice that is really installed in any engine, instead of relying on the default engine only.
class Speaker(
    context: Context,
    private val onReady: () -> Unit,
    private val onFail: (String) -> Unit,
) {

    // محرك نطق واحد مع حالته / one TTS engine with its state
    private class Eng(val pkg: String, val label: String) {
        var tts: TextToSpeech? = null
        @Volatile
        var ready: Boolean = false
    }

    private val appCtx = context.applicationContext
    private val engines = ArrayList<Eng>()
    private var defaultPkg: String? = null

    // يلتقط أخطاء النطق الفعلية التي يبلغ عنها المحرك / catches real speech errors reported by the engine
    private val listener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {}
        override fun onDone(utteranceId: String?) {}

        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) {
            onFail("error")
        }

        override fun onError(utteranceId: String?, errorCode: Int) {
            onFail("code $errorCode")
        }
    }

    // هل يعمل محرك واحد على الأقل؟ / is at least one engine working?
    val available: Boolean
        get() = engines.any { it.ready }

    init {
        start()
    }

    // يعيد بناء المحركات: يُستدعى بعد تنزيل صوت أو تغيير المحرك الافتراضي
    // Rebuilds the engines: call after a voice download or a default-engine change
    fun refresh() {
        start()
    }

    private fun rank(pkg: String): Int = when (pkg) {
        GOOGLE -> 0
        defaultPkg -> 1
        else -> 2
    }

    private fun start() {
        shutdown()
        val pm = appCtx.packageManager
        defaultPkg = try {
            Settings.Secure.getString(appCtx.contentResolver, "tts_default_synth")
        } catch (e: Exception) {
            null
        }

        val found = ArrayList<Eng>()
        try {
            val services = pm.queryIntentServices(Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE), 0)
            for (ri in services) {
                val si = ri.serviceInfo ?: continue
                found.add(Eng(si.packageName, ri.loadLabel(pm).toString()))
            }
        } catch (e: Exception) {
            // نكمل بالمحرك الافتراضي / continue with the default engine
        }
        // إن لم نجد قائمة نستعمل المحرك الافتراضي للنظام / no list: use the system default engine
        if (found.isEmpty()) found.add(Eng("", "default"))
        found.sortBy { rank(it.pkg) }

        for (eng in found) {
            try {
                val onInit = TextToSpeech.OnInitListener { status ->
                    if (status == TextToSpeech.SUCCESS) {
                        eng.ready = true
                        eng.tts?.setOnUtteranceProgressListener(listener)
                    }
                    onReady()
                }
                eng.tts = if (eng.pkg.isEmpty()) {
                    TextToSpeech(appCtx, onInit)
                } else {
                    TextToSpeech(appCtx, onInit, eng.pkg)
                }
            } catch (e: Exception) {
                eng.tts = null
            }
            engines.add(eng)
        }
    }

    // أفضل صوت منزَّل للغة: دون إنترنت أولًا ثم الأعلى جودة
    // Best installed voice for the language: offline first, then highest quality
    private fun pickVoice(t: TextToSpeech, lang: String): Voice? {
        val all: Set<Voice> = try {
            t.voices ?: return null
        } catch (e: Exception) {
            return null
        }
        return all
            .filter { it.locale.language == lang && !it.features.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) }
            .sortedWith(compareBy<Voice>({ it.isNetworkConnectionRequired }, { -it.quality }))
            .firstOrNull()
    }

    fun say(text: String, lang: String): SpeakResult {
        if (text.isEmpty()) return SpeakResult.EMPTY
        if (!available) return SpeakResult.NO_ENGINE
        for (eng in engines) {
            val t = eng.tts
            if (!eng.ready || t == null) continue
            try {
                val v = pickVoice(t, lang)
                val ok = if (v != null) {
                    t.setVoice(v) == TextToSpeech.SUCCESS
                } else {
                    // محركات لا تعرض قائمة الأصوات: نجرب اللغة مباشرة / engines without a voice list: try the language directly
                    t.setLanguage(Locale(lang)) >= TextToSpeech.LANG_AVAILABLE
                }
                if (ok && t.speak(text, TextToSpeech.QUEUE_FLUSH, null, "bivox") == TextToSpeech.SUCCESS) {
                    return SpeakResult.OK
                }
            } catch (e: Exception) {
                // نجرب المحرك التالي / try the next engine
            }
        }
        return SpeakResult.NO_VOICE
    }

    // تقرير للمستخدم: المحركات وحالة كل لغة (✓ جاهز، ⬇ يحتاج تنزيل، ✗ غير موجود)
    // Report for the user: the engines and the state of every language (✓ ready, ⬇ needs download, ✗ none)
    fun report(): String {
        val sb = StringBuilder()
        if (engines.isEmpty()) sb.append("(no engines)\n")
        for (e in engines) {
            val count = try { e.tts?.voices?.size ?: 0 } catch (ex: Exception) { 0 }
            val tag = if (e.pkg == defaultPkg) " [default]" else ""
            val state = if (e.ready) "ready, $count voices" else "not ready"
            sb.append("• ").append(e.label).append(tag).append(" — ").append(state).append("\n")
        }
        sb.append("\n")
        for (code in Langs.CODES) sb.append(langLine(code)).append("\n")
        return sb.toString().trimEnd()
    }

    private fun langLine(lang: String): String {
        val name = Langs.NAMES.getValue(lang)
        var needs: String? = null
        for (e in engines) {
            val t = e.tts
            if (!e.ready || t == null) continue
            val vs: Set<Voice> = try { t.voices ?: continue } catch (ex: Exception) { continue }
            val mine = vs.filter { it.locale.language == lang }
            if (mine.any { !it.features.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) }) {
                return "✓ $name — ${e.label}"
            }
            if (mine.isNotEmpty() && needs == null) needs = e.label
        }
        return if (needs != null) "⬇ $name — $needs" else "✗ $name"
    }

    fun shutdown() {
        for (e in engines) {
            try {
                e.tts?.stop()
                e.tts?.shutdown()
            } catch (ex: Exception) {
            }
            e.ready = false
            e.tts = null
        }
        engines.clear()
    }

    companion object {
        private const val GOOGLE = "com.google.android.tts"
    }
}
