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
        val key = "$src|$dst|$t"
        cacheGet(key)?.let { return it }
        val engines = listOf("google" to this::google, "mymemory" to this::myMemory)
        val t0 = System.currentTimeMillis()
        for ((name, engine) in engines) {
            // محاولتان لكل محرك: الفشل العابر شائع على الشبكات الضعيفة / two tries per engine: transient failures are common on weak networks
            for (attempt in 0..1) {
                try {
                    val r = engine(t, src, dst)
                    if (r.isNotEmpty()) {
                        cachePut(key, r)
                        Dbg.log("tr", name + " ok " + src + "→" + dst + " " + (System.currentTimeMillis() - t0) + "ms")
                        return r
                    }
                    break
                } catch (e: Exception) {
                    Dbg.log("tr", name + " fail try " + (attempt + 1) + ": " + e.javaClass.simpleName)
                    if (attempt == 0) pause()
                }
            }
        }
        throw IOException("translation failed")
    }

    // ترجمة «تلقائية»: يكتشف لغة النص؛ إن كانت لغتي ترجمه للطرف الآخر، وإلا ترجمه إلى لغتي.
    // يعيد الترجمة ولغتها / "Smart" translation: detects the text's language; if it is mine, translate to the other person, otherwise to mine.
    // Returns the translation and its language
    fun translateSmart(text: String, my: String, their: String): Pair<String, String> {
        val t = text.trim()
        if (t.isEmpty()) return Pair("", their)
        try {
            val first = retry { googleFull(t, "auto", their) }
            val det = first.second?.substringBefore('-')
            if (det == my) return Pair(first.first, their)
            val toMy = if (det == their) retry { googleFull(t, their, my) } else retry { googleFull(t, "auto", my) }
            return Pair(toMy.first, my)
        } catch (e: Exception) {
            // Google تعذّر: نفترض أنه بلغتي ونترجم للطرف الآخر / Google failed: assume it is in my language and translate to the other person
        }
        return Pair(translate(t, my, their), their)
    }

    private fun <T> retry(block: () -> T): T {
        try {
            return block()
        } catch (e: Exception) {
            pause()
            return block()
        }
    }

    private fun pause() {
        try { Thread.sleep(250) } catch (e: InterruptedException) { }
    }

    private fun cacheGet(k: String): String? = synchronized(cache) { cache[k] }

    private fun cachePut(k: String, v: String) {
        synchronized(cache) { cache[k] = v }
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

    private fun google(text: String, src: String, dst: String): String = googleFull(text, src, dst).first

    // الترجمة مع اللغة المكتشفة للمصدر (العنصر الثالث في رد Google) / the translation plus the detected source language (3rd element of Google's reply)
    private fun googleFull(text: String, src: String, dst: String): Pair<String, String?> {
        val url = "$GOOGLE?client=gtx&sl=$src&tl=$dst&dt=t&q=${enc(text)}"
        val root = JSONArray(httpGet(url))
        val parts = root.getJSONArray(0)
        val sb = StringBuilder()
        for (i in 0 until parts.length()) {
            val p = parts.getJSONArray(i)
            if (!p.isNull(0)) sb.append(p.getString(0))
        }
        val det: String? = if (root.length() > 2 && !root.isNull(2)) root.getString(2) else null
        return Pair(sb.toString(), det)
    }

    private fun myMemory(text: String, src: String, dst: String): String {
        val pair = "${mmCode(src)}|${mmCode(dst)}"
        val url = "$MYMEMORY?q=${enc(text)}&langpair=${enc(pair)}"
        return JSONObject(httpGet(url)).getJSONObject("responseData").getString("translatedText")
    }

    // MyMemory يطلب رمز الصينية كاملًا / MyMemory wants the full Chinese code
    private fun mmCode(c: String) = if (c == "zh") "zh-CN" else c

    companion object {
        // ذاكرة مؤقتة مشتركة للجمل المتكررة / shared cache for repeated sentences
        private val cache = object : LinkedHashMap<String, String>(128, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean = size > 300
        }
        private const val GOOGLE = "https://translate.googleapis.com/translate_a/single"
        private const val MYMEMORY = "https://api.mymemory.translated.net/get"
    }
}
