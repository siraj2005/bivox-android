package app.bivox.android

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

// خدمة إمكانية الوصول: تقرأ النصوص الظاهرة ومواضعها عند الطلب فقط، ولا تحتفظ بشيء ولا ترسل شيئًا بنفسها
// Accessibility service: reads the visible texts and their positions only on request; keeps nothing and sends nothing by itself
class BivoxAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val e = event ?: return
        if (e.packageName?.toString() == packageName) return   // نتجاهل طبقاتنا / ignore our own layers
        when (e.eventType) {
            AccessibilityEvent.TYPE_VIEW_SCROLLED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> listener?.invoke(e.eventType)
        }
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        if (instance === this) instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    // يجمع كل النصوص المرئية في نوافذ التطبيقات (لا نافذتنا) / collects every visible text in app windows (not ours)
    fun collect(): List<TextBox> {
        val roots = ArrayList<AccessibilityNodeInfo>()
        try {
            for (w in windows) {
                if (w.type == AccessibilityWindowInfo.TYPE_APPLICATION) {
                    val r = w.root
                    if (r != null) roots.add(r)
                }
            }
        } catch (e: Exception) {
            // نرجع للنافذة النشطة / fall back to the active window
        }
        if (roots.isEmpty()) {
            val r = try { rootInActiveWindow } catch (e: Exception) { null }
            if (r != null) roots.add(r)
        }
        val out = ArrayList<TextBox>()
        val seen = HashSet<String>()
        for (r in roots) {
            try {
                walk(r, 0, out, seen)
            } catch (e: Exception) {
                // نكتفي بما جُمع / keep what was collected
            }
        }
        return out
    }

    private fun walk(n: AccessibilityNodeInfo, depth: Int, out: ArrayList<TextBox>, seen: HashSet<String>) {
        if (depth > 60 || out.size >= 400) return
        if (!n.isVisibleToUser) return
        if (n.packageName?.toString() == packageName) return
        val raw: CharSequence? = n.text ?: (if (n.childCount == 0) n.contentDescription else null)
        val txt = raw?.toString()?.trim()
        if (!txt.isNullOrEmpty() && txt.length <= 1000 && !n.isPassword && txt.any { it.isLetter() }) {
            val r = Rect()
            n.getBoundsInScreen(r)
            // نتجاهل نسخة متداخلة لنفس النص (حاوية وابنها) / skip a nested duplicate of the same text (a container and its child)
            if (r.width() > 4 && r.height() > 4 && out.none { it.text == txt && Rect.intersects(it.rect, r) } &&
                seen.add("${r.left},${r.top},${r.right},${r.bottom}|$txt")) {
                out.add(TextBox(txt, r))
            }
        }
        for (i in 0 until n.childCount) {
            val c = n.getChild(i) ?: continue
            walk(c, depth + 1, out, seen)
            @Suppress("DEPRECATION")
            c.recycle()
        }
    }

    companion object {
        // النسخة العاملة (null = الخدمة غير مفعّلة) / the running instance (null = service not enabled)
        @Volatile
        var instance: BivoxAccessibilityService? = null

        // مستمع الأحداث (على خيط الواجهة) / event listener (on the main thread)
        @Volatile
        var listener: ((Int) -> Unit)? = null
    }
}
