package app.bivox.android

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

// الترجمة: Google أولًا ثم MyMemory احتياطًا، بمكتبات النظام فقط
// Translation: Google first, MyMemory as fallback, using system libraries only
class Translator {

    // يجب استدعاؤها من خيط خلفي، لا من خيط الواجهة
    // Must be called from a background thread, never the UI thread
    fun translate(text: String, src: String, dst: String): String {
        val t = text.trim()
        if (t.isEmpty()) return ""
        val engines = listOf(this::google, this::myMemory)
        for (engine in engines) {
            try {
                val r = engine(t, src, dst)
                if (r.isNotEmpty()) return r
            } catch (e: Exception) {
                // نجرب المحرك التالي / try the next engine
            }
        }
        throw IOException("translation failed")
    }

    // ترجمة مع كشف تلقائي للغة المصدر (للنص المقروء من الشاشة)؛ احتياطيًا نستعمل لغة الطرف الآخر
    // Translation with automatic source-language detection (for text read from the screen); falls back to the other person's language
    fun translateAuto(text: String, fallbackSrc: String, dst: String): String {
        val t = text.trim()
        if (t.isEmpty()) return ""
        try {
            val r = google(t, "auto", dst)
            if (r.isNotEmpty()) return r
        } catch (e: Exception) {
            // نجرب المحرك التالي / try the next engine
        }
        try {
            val r = myMemory(t, fallbackSrc, dst)
            if (r.isNotEmpty()) return r
        } catch (e: Exception) {
            // نفشل بعد قليل / fail below
        }
        throw IOException("translation failed")
    }

    private fun httpGet(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 8000
            c.readTimeout = 8000
            c.setRequestProperty("User-Agent", "Mozilla/5.0")
            if (c.responseCode !in 200..299) throw IOException("HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    private fun google(text: String, src: String, dst: String): String {
        val url = "$GOOGLE?client=gtx&sl=$src&tl=$dst&dt=t&q=${enc(text)}"
        val parts = JSONArray(httpGet(url)).getJSONArray(0)
        val sb = StringBuilder()
        for (i in 0 until parts.length()) {
            val p = parts.getJSONArray(i)
            if (!p.isNull(0)) sb.append(p.getString(0))
        }
        return sb.toString()
    }

    private fun myMemory(text: String, src: String, dst: String): String {
        val pair = "${mmCode(src)}|${mmCode(dst)}"
        val url = "$MYMEMORY?q=${enc(text)}&langpair=${enc(pair)}"
        return JSONObject(httpGet(url)).getJSONObject("responseData").getString("translatedText")
    }

    // MyMemory يطلب رمز الصينية كاملًا / MyMemory wants the full Chinese code
    private fun mmCode(c: String) = if (c == "zh") "zh-CN" else c

    companion object {
        private const val GOOGLE = "https://translate.googleapis.com/translate_a/single"
        private const val MYMEMORY = "https://api.mymemory.translated.net/get"
    }
}
