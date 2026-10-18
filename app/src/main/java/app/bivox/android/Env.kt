package app.bivox.android

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.Layout
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

// ألوان مشتركة للطبقات العائمة / shared colors for the floating layers
object Col {
    val PRIMARY = Color.parseColor("#5B8DEF")
    val ACCENT = Color.parseColor("#3FA37A")
    val TEXT = Color.parseColor("#ECECF1")
}

// نص مقروء من الشاشة مع مستطيله بإحداثيات الشاشة / a text read from the screen with its rectangle in screen coordinates
class TextBox(val text: String, val rect: Rect)

// عنصر ترجمة: الأصل ومستطيله والترجمة (تصل لاحقًا) / a translation item: original, its rectangle, and the translation (arrives later)
class Item(val orig: String, val rect: Rect) {
    @Volatile
    var trans: String? = null
}

// بيئة مشتركة للطبقات: مدير النوافذ وقياس الشاشة وخيوط العمل
// Shared environment for the layers: window manager, screen metrics, worker threads
class Env(val ctx: Context) {
    val wm: WindowManager = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    val main = Handler(Looper.getMainLooper())
    val readPool: ExecutorService = Executors.newSingleThreadExecutor()   // قراءة الشاشة / screen reading
    var u = 4f

    fun screen(): Pair<Int, Int> {
        return if (Build.VERSION.SDK_INT >= 30) {
            val b = wm.currentWindowMetrics.bounds
            Pair(b.width(), b.height())
        } else {
            val dm = android.util.DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(dm)
            Pair(dm.widthPixels, dm.heightPixels)
        }
    }

    fun measure() {
        val s = screen()
        u = max(3f, min(s.first, s.second) / 100f)
    }

    fun type(): Int =
        if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

    fun texts(): Map<String, String> = Ui.TEXTS.getValue(Prefs.uiLang(ctx))

    // نافذة بملء الشاشة بلا تركيز / a full-screen window without focus
    fun fullLp(extraFlags: Int): WindowManager.LayoutParams {
        val lp = WindowManager.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, type(),
            extraFlags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT)
        lp.gravity = Gravity.TOP or Gravity.LEFT
        applyCutout(lp)
        return lp
    }

    // كي لا تُزاح النوافذ بسبب ثقب الكاميرا / so windows are not shifted by the camera cutout
    fun applyCutout(lp: WindowManager.LayoutParams) {
        if (Build.VERSION.SDK_INT >= 28) {
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
    }

    fun shutdown() {
        readPool.shutdownNow()
    }
}

// مركز الترجمة: 4 خيوط متوازية + ذاكرة مؤقتة كي لا تُترجم الجملة نفسها مرتين
// Translation hub: 4 parallel threads + a cache so the same sentence is never translated twice
class TranslateHub(private val translator: Translator, private val main: Handler) {
    private val pool: ExecutorService = Executors.newFixedThreadPool(4)
    private val cache = object : LinkedHashMap<String, String>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean = size > 500
    }
    private val pending = HashMap<String, MutableList<(String?) -> Unit>>()   // خيط الواجهة فقط / main thread only

    fun cached(text: String, dst: String): String? = synchronized(cache) { cache["$dst|$text"] }

    // يُستدعى من خيط الواجهة؛ done يُستدعى على خيط الواجهة أيضًا
    // Call from the main thread; done is also called on the main thread
    fun request(text: String, dst: String, fallbackSrc: String, done: (String?) -> Unit) {
        val key = "$dst|$text"
        val hit = cached(text, dst)
        if (hit != null) { done(hit); return }
        val waiting = pending[key]
        if (waiting != null) { waiting.add(done); return }
        pending[key] = mutableListOf(done)
        try {
            pool.execute {
                val out: String? = try {
                    translator.translateAuto(text, fallbackSrc, dst)
                } catch (e: Exception) {
                    null
                }
                if (out != null) synchronized(cache) { cache[key] = out }
                main.post { pending.remove(key)?.forEach { it(out) } }
            }
        } catch (e: Exception) {
            pending.remove(key)
            done(null)
        }
    }

    fun shutdown() {
        pool.shutdownNow()
    }
}

// رسم الترجمة فوق مستطيل النص الأصلي / drawing a translation over the original text's rectangle
object Drawing {
    fun fill(color: Int): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        this.color = color
    }

    fun textPaint(color: Int): TextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }

    // يملأ المستطيل بخلفية معتمة ويكتب الترجمة بأكبر خط يتسع فيه (لا يقل عن حد أدنى مقروء)
    // Covers the rectangle with an opaque background and writes the translation in the largest font that fits (never below a readable minimum)
    fun translation(c: Canvas, left: Float, top: Float, width: Float, height: Float,
                    text: String, u: Float, bg: Paint, tp: TextPaint) {
        val pad = max(2f, u * 0.5f)
        val w = max(width, u * 12f)
        val minSize = u * 2.4f
        var size = min(u * 5f, max(minSize, height * 0.75f))
        fun build(sz: Float): StaticLayout {
            tp.textSize = sz
            return StaticLayout.Builder
                .obtain(text, 0, text.length, tp, max(1, (w - 2 * pad).toInt()))
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setTextDirection(TextDirectionHeuristics.FIRSTSTRONG_LTR)
                .setIncludePad(false)
                .build()
        }
        var layout = build(size)
        while (layout.height > height - pad && size > minSize) {
            size = max(minSize, size - u * 0.3f)
            layout = build(size)
        }
        val h = max(height, layout.height + 2 * pad)
        val rad = u * 0.8f
        c.drawRoundRect(left, top, left + w, top + h, rad, rad, bg)
        c.save()
        c.translate(left + pad, top + pad)
        layout.draw(c)
        c.restore()
    }

    // كبسولة نص صغيرة (تلميح أو تقدّم) / a small text pill (hint or progress)
    fun pill(c: Canvas, text: String, cx: Float, top: Float, u: Float, bg: Paint, tp: TextPaint) {
        tp.textSize = u * 3.8f
        val tw = tp.measureText(text)
        val w = tw + u * 6f
        val h = u * 7f
        c.drawRoundRect(cx - w / 2, top, cx + w / 2, top + h, h / 2, h / 2, bg)
        c.drawText(text, cx - tw / 2, top + h / 2 - (tp.ascent() + tp.descent()) / 2, tp)
    }
}
