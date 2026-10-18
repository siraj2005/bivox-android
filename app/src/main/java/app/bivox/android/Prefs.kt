package app.bivox.android

import android.content.Context

// تفضيلات مشتركة بين الشاشة الرئيسية وخدمة الفقاعة (لغة الواجهة واللغتان وموضع الفقاعة)
// Preferences shared by the main screen and the bubble service (UI language, the two languages, bubble position)
object Prefs {
    private fun sp(c: Context) =
        c.applicationContext.getSharedPreferences("bivox", Context.MODE_PRIVATE)

    fun uiLang(c: Context): String {
        val v = try { sp(c).getString("ui", "ar") } catch (e: Exception) { "ar" }
        return if (v == "en") "en" else "ar"
    }

    fun setUiLang(c: Context, v: String) {
        sp(c).edit().putString("ui", v).apply()
    }

    // فهرس لغتي / index of "my language"
    fun my(c: Context): Int =
        sp(c).getInt("my", Langs.CODES.indexOf("ar")).coerceIn(0, Langs.CODES.size - 1)

    // فهرس لغة الطرف الآخر / index of "other person's language"
    fun their(c: Context): Int =
        sp(c).getInt("their", Langs.CODES.indexOf("en")).coerceIn(0, Langs.CODES.size - 1)

    fun setLangs(c: Context, my: Int, their: Int) {
        sp(c).edit().putInt("my", my).putInt("their", their).apply()
    }

    // هل يريد المستخدم الفقاعة تعمل؟ / does the user want the bubble running?
    fun bubbleOn(c: Context): Boolean = sp(c).getBoolean("bubble_on", false)

    fun setBubbleOn(c: Context, on: Boolean) {
        sp(c).edit().putBoolean("bubble_on", on).apply()
    }

    // موضع الفقاعة (-1 = لم يُحفظ) / bubble position (-1 = not saved yet)
    fun bubbleX(c: Context): Int = sp(c).getInt("bx", -1)
    fun bubbleY(c: Context): Int = sp(c).getInt("by", -1)

    // موضع اللوحة المصغّرة (-1 = لم يُحفظ) / mini panel position (-1 = not saved yet)
    fun panelX(c: Context): Int = sp(c).getInt("px", -1)
    fun panelY(c: Context): Int = sp(c).getInt("py", -1)

    fun setPanelPos(c: Context, x: Int, y: Int) {
        sp(c).edit().putInt("px", x).putInt("py", y).apply()
    }

    fun setBubblePos(c: Context, x: Int, y: Int) {
        sp(c).edit().putInt("bx", x).putInt("by", y).apply()
    }

    // مستطيل العدسة وتثبيتها / lens rectangle and its pin state
    fun lens(c: Context): IntArray? {
        val p = sp(c)
        if (!p.contains("lr")) return null
        return intArrayOf(p.getInt("ll", 0), p.getInt("lt", 0), p.getInt("lr", 0), p.getInt("lb", 0))
    }

    fun setLens(c: Context, l: Int, t: Int, r: Int, b: Int) {
        sp(c).edit().putInt("ll", l).putInt("lt", t).putInt("lr", r).putInt("lb", b).apply()
    }

    fun lensPinned(c: Context): Boolean = sp(c).getBoolean("lens_pin", false)

    fun setLensPinned(c: Context, v: Boolean) {
        sp(c).edit().putBoolean("lens_pin", v).apply()
    }
}
