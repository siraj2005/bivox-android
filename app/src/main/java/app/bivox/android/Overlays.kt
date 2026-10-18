package app.bivox.android

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.text.TextPaint
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

// ══════════════ طبقة النتائج: الترجمة فوق النص الأصلي ══════════════
// ══════════════ Result layer: translations drawn over the original text ══════════════
class ResultOverlay(private val env: Env) {
    private var view: ResultView? = null
    var shownAt = 0L
        private set

    val showing: Boolean get() = view != null

    fun show(items: List<Item>, busy: String?) {
        close()
        env.measure()
        val v = ResultView(env.ctx, env.u)
        v.items = items
        v.busy = busy
        try {
            // لا تلمس ولا تركيز: التطبيق تحتها يبقى قابلًا للاستعمال
            // No touch and no focus: the app underneath stays usable
            env.wm.addView(v, env.fullLp(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE))
            view = v
            shownAt = android.os.SystemClock.uptimeMillis()
        } catch (e: Exception) {
            view = null
        }
    }

    fun update(busy: String?) {
        view?.busy = busy
        view?.invalidate()
    }

    fun close() {
        val v = view ?: return
        try { env.wm.removeView(v) } catch (e: Exception) { }
        view = null
    }
}

class ResultView(ctx: Context, private val u: Float) : View(ctx) {
    var items: List<Item> = emptyList()
    var busy: String? = null
    private val bg = Drawing.fill(Color.argb(242, 19, 19, 24))
    private val tp: TextPaint = Drawing.textPaint(Col.TEXT)
    private val pillBg = Drawing.fill(Color.argb(235, 28, 28, 36))
    private val loc = IntArray(2)

    override fun onDraw(canvas: Canvas) {
        getLocationOnScreen(loc)
        val ox = loc[0]
        val oy = loc[1]
        for (it in items) {
            val tr = it.trans ?: continue
            if (tr.trim().equals(it.orig.trim(), ignoreCase = true)) continue   // لغتك نفسها: لا نغطيه / already your language: don't cover it
            Drawing.translation(canvas, (it.rect.left - ox).toFloat(), (it.rect.top - oy).toFloat(),
                it.rect.width().toFloat(), it.rect.height().toFloat(), tr, u, bg, tp)
        }
        val b = busy
        if (b != null) Drawing.pill(canvas, b, width / 2f, u * 7f, u, pillBg, tp)
    }
}

// ══════════════ طبقة الاختيار: مستطيلات حول كل نص، اضغط أو اسحب ══════════════
// ══════════════ Pick layer: rectangles around every text; tap or drag ══════════════
class PickOverlay(private val env: Env) {
    private var view: PickView? = null

    val showing: Boolean get() = view != null

    fun show(boxes: List<TextBox>, hint: String, onPick: (List<TextBox>) -> Unit, onCancel: () -> Unit) {
        close()
        env.measure()
        val v = PickView(env.ctx, env.u, boxes, hint, onPick, onCancel)
        try {
            env.wm.addView(v, env.fullLp(0))
            view = v
        } catch (e: Exception) {
            view = null
        }
    }

    fun close() {
        val v = view ?: return
        try { env.wm.removeView(v) } catch (e: Exception) { }
        view = null
    }
}

class PickView(
    ctx: Context,
    private val u: Float,
    private val boxes: List<TextBox>,
    private val hint: String,
    private val onPick: (List<TextBox>) -> Unit,
    private val onCancel: () -> Unit,
) : View(ctx) {

    private val dim = Drawing.fill(0x40000000)
    private val boxFill = Drawing.fill(Color.argb(40, 91, 141, 239))
    private val boxStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Col.PRIMARY
        strokeWidth = max(1f, u * 0.3f)
    }
    private val selFill = Drawing.fill(Color.argb(80, 63, 163, 122))
    private val pillBg = Drawing.fill(Color.argb(235, 28, 28, 36))
    private val tp: TextPaint = Drawing.textPaint(Col.TEXT)
    private val loc = IntArray(2)
    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
    private val cancelR = RectF()

    private var downX = 0f
    private var downY = 0f
    private var curX = 0f
    private var curY = 0f
    private var dragging = false
    private var onCancelBtn = false

    override fun onDraw(canvas: Canvas) {
        getLocationOnScreen(loc)
        val ox = loc[0]
        val oy = loc[1]
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dim)
        for (b in boxes) {
            val r = RectF((b.rect.left - ox).toFloat(), (b.rect.top - oy).toFloat(),
                (b.rect.right - ox).toFloat(), (b.rect.bottom - oy).toFloat())
            canvas.drawRect(r, boxFill)
            canvas.drawRect(r, boxStroke)
        }
        if (dragging) {
            canvas.drawRect(min(downX, curX), min(downY, curY), max(downX, curX), max(downY, curY), selFill)
        }
        Drawing.pill(canvas, hint, width / 2f, u * 7f, u, pillBg, tp)
        // زر الإلغاء أعلى اليمين / cancel button at the top right
        val d = u * 8f
        cancelR.set(width - d - u * 3f, u * 14f, width - u * 3f, u * 14f + d)
        canvas.drawRoundRect(cancelR, d / 2, d / 2, pillBg)
        tp.textSize = u * 4.6f
        val tw = tp.measureText("✕")
        canvas.drawText("✕", cancelR.centerX() - tw / 2, cancelR.centerY() - (tp.ascent() + tp.descent()) / 2, tp)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x; downY = e.y; curX = e.x; curY = e.y
                dragging = false
                onCancelBtn = cancelR.contains(e.x, e.y)
            }
            MotionEvent.ACTION_MOVE -> {
                curX = e.x; curY = e.y
                if (!onCancelBtn && !dragging && (abs(curX - downX) > slop || abs(curY - downY) > slop)) dragging = true
                if (dragging) invalidate()
            }
            MotionEvent.ACTION_UP -> {
                getLocationOnScreen(loc)
                if (onCancelBtn) {
                    if (cancelR.contains(e.x, e.y)) onCancel()
                } else if (dragging) {
                    val sel = Rect(
                        (min(downX, curX) + loc[0]).toInt(), (min(downY, curY) + loc[1]).toInt(),
                        (max(downX, curX) + loc[0]).toInt(), (max(downY, curY) + loc[1]).toInt())
                    val hit = boxes.filter { Rect.intersects(it.rect, sel) }
                    dragging = false
                    if (hit.isEmpty()) invalidate() else onPick(hit)
                } else {
                    // ضغطة: أصغر مستطيل يحوي النقطة / tap: the smallest rectangle containing the point
                    val px = (e.x + loc[0]).toInt()
                    val py = (e.y + loc[1]).toInt()
                    val best = boxes.filter { it.rect.contains(px, py) }
                        .minByOrNull { it.rect.width().toLong() * it.rect.height() }
                    if (best != null) onPick(listOf(best))
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                dragging = false
                invalidate()
            }
        }
        return true
    }
}
