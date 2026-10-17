package app.bivox.android

import android.app.Activity
import android.app.AlertDialog
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

// شاشة المترجم الرئيسية: نفس تجربة النموذج التجريبي (وجهًا لوجه) بواجهة أصلية
// Main translator screen: same experience as the prototype (face to face) in a native UI
class MainActivity : Activity() {

    // ── الألوان (من النموذج التجريبي) / Colors (from the prototype) ──
    private val cBg = Color.parseColor("#131318")
    private val cText = Color.parseColor("#ECECF1")
    private val cMuted = Color.parseColor("#9A9AA8")
    private val cOrig = Color.parseColor("#B5B5C2")
    private val cCard = Color.parseColor("#1C1C24")
    private val cCardBorder = Color.parseColor("#2B2B36")
    private val cField = Color.parseColor("#23232D")
    private val cFieldBorder = Color.parseColor("#34343F")
    private val cBtn = Color.parseColor("#2A2A36")
    private val cBtnBorder = Color.parseColor("#3A3A47")
    private val cBtnPressed = Color.parseColor("#34344A")
    private val cPrimary = Color.parseColor("#5B8DEF")
    private val cPrimaryPressed = Color.parseColor("#4A78D0")
    private val cAccent = Color.parseColor("#3FA37A")
    private val cAccentPressed = Color.parseColor("#34896A")
    private val cDisabled = Color.parseColor("#22222B")
    private val cDisabledText = Color.parseColor("#666672")

    // ── الحالة (تبقى عند تدوير الشاشة) / State (survives rotation) ──
    private var uiLang = "ar"
    private var myIdx = Langs.CODES.indexOf("ar")
    private var theirIdx = Langs.CODES.indexOf("en")
    private var lastOrig = ""
    private var lastTrans = ""
    private val history = ArrayList<String>()   // الأحدث أولًا، حتى 20 / newest first, max 20
    private var statusKey = "ready"
    private var busy = false
    private var draft = ""

    private var u = 4f                           // وحدة القياس = 1% من الضلع القصير / unit = 1% of short side
    private lateinit var t: Map<String, String>

    private val translator = Translator()
    private lateinit var speaker: Speaker
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    // ── عناصر الواجهة / Views ──
    private lateinit var input: EditText
    private lateinit var myPicker: TextView
    private lateinit var theirPicker: TextView
    private lateinit var origLbl: TextView
    private lateinit var transLbl: TextView
    private lateinit var statusLbl: TextView
    private lateinit var meBtn: Button
    private lateinit var theyBtn: Button
    private lateinit var histBox: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        speaker = Speaker(this) { main.post { refreshStatus() } }
        buildUi()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        rebuildUi()   // أحجام جديدة بعد التدوير / new sizes after rotation
    }

    override fun onDestroy() {
        speaker.shutdown()
        io.shutdownNow()
        super.onDestroy()
    }

    // ── أدوات القياس والتنسيق / Sizing and styling helpers ──
    private fun px(units: Float): Int = max(1, (u * units).toInt())

    private fun rounded(fill: Int, stroke: Int?): GradientDrawable = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = u * 2.5f
        if (stroke != null) setStroke(max(1, (u * 0.3f).toInt()), stroke)
    }

    private fun stateBg(normal: Int, pressed: Int, border: Int?): StateListDrawable =
        StateListDrawable().apply {
            addState(intArrayOf(-android.R.attr.state_enabled), rounded(cDisabled, null))
            addState(intArrayOf(android.R.attr.state_pressed), rounded(pressed, null))
            addState(intArrayOf(), rounded(normal, border))
        }

    private fun sizeU(v: TextView, units: Float) {
        v.setTextSize(TypedValue.COMPLEX_UNIT_PX, u * units)
    }

    // يضيف عنصرًا بمسافة علوية / adds a child with a top gap
    private fun add(parent: LinearLayout, v: View, top: Int = 0, weight: Float = 0f,
                    width: Int = ViewGroup.LayoutParams.MATCH_PARENT) {
        val lp = LinearLayout.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT, weight)
        lp.topMargin = top
        parent.addView(v, lp)
    }

    private fun label(text: String, sizeUnits: Float, color: Int, bold: Boolean = false): TextView =
        TextView(this).apply {
            this.text = text
            setTextColor(color)
            sizeU(this, sizeUnits)
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }

    private fun smallButton(text: String, onClick: () -> Unit): Button =
        Button(this).apply {
            this.text = text
            isAllCaps = false
            minHeight = 0; minimumHeight = 0; minWidth = 0; minimumWidth = 0
            setPadding(px(3.5f), px(2.5f), px(3.5f), px(2.5f))
            setTextColor(cText)
            sizeU(this, 4.2f)
            background = stateBg(cBtn, cBtnPressed, cBtnBorder)
            setOnClickListener { onClick() }
        }

    private fun bigButton(text: String, color: Int, pressed: Int, onClick: () -> Unit): Button =
        Button(this).apply {
            this.text = text
            isAllCaps = false
            minHeight = 0; minimumHeight = 0; minWidth = 0; minimumWidth = 0
            setPadding(px(2.5f), px(6.5f), px(2.5f), px(6.5f))
            setTypeface(typeface, Typeface.BOLD)
            sizeU(this, 4.2f)
            setTextColor(ColorStateList(
                arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
                intArrayOf(cDisabledText, Color.WHITE)))
            background = stateBg(color, pressed, null)
            setOnClickListener { onClick() }
        }

    // حقل اختيار اللغة: يفتح قائمة (يعمل على كل الأجهزة بلا Spinner)
    // Language picker: opens a list (works on all devices without Spinner)
    private fun picker(title: String, getIdx: () -> Int, setIdx: (Int) -> Unit): TextView =
        TextView(this).apply {
            setTextColor(cText)
            sizeU(this, 4.2f)
            gravity = Gravity.CENTER_VERTICAL
            minHeight = px(12f)
            setPadding(px(2.5f), px(2.5f), px(2.5f), px(2.5f))
            background = rounded(cField, cFieldBorder)
            isClickable = true
            setOnClickListener {
                val names = Array(Langs.CODES.size) { Langs.NAMES.getValue(Langs.CODES[it]) }
                AlertDialog.Builder(this@MainActivity, android.R.style.Theme_Material_Dialog_Alert)
                    .setTitle(title)
                    .setSingleChoiceItems(names, getIdx()) { d, which ->
                        setIdx(which)
                        updatePickers()
                        d.dismiss()
                    }
                    .show()
            }
        }

    private fun updatePickers() {
        myPicker.text = Langs.NAMES.getValue(Langs.CODES[myIdx]) + "  ▾"
        theirPicker.text = Langs.NAMES.getValue(Langs.CODES[theirIdx]) + "  ▾"
    }

    // ── بناء الواجهة / Build the UI ──
    private fun rebuildUi() {
        if (::input.isInitialized) draft = input.text.toString()
        buildUi()
    }

    private fun buildUi() {
        val dm = resources.displayMetrics
        u = max(3f, min(dm.widthPixels, dm.heightPixels) / 100f)
        t = Ui.TEXTS.getValue(uiLang)
        val rtl = uiLang == "ar"

        val root = FrameLayout(this)
        root.setBackgroundColor(cBg)
        root.layoutDirection = if (rtl) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR

        // كل المحتوى داخل تمرير عمودي: لا يُقصّ شيء مهما كانت الشاشة قصيرة
        // All content in a vertical scroll: nothing is ever clipped on short screens
        val scroll = ScrollView(this)
        scroll.isFillViewport = true
        scroll.isVerticalScrollBarEnabled = false
        root.addView(scroll, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val m = px(3.5f)
        val gap = px(2.5f)
        val body = LinearLayout(this)
        body.orientation = LinearLayout.VERTICAL
        body.setPadding(m, px(6f), m, m)   // هامش علوي أكبر لشريط الحالة / bigger top margin for status bar
        scroll.addView(body, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // العنوان + زر لغة الواجهة / title + UI-language button
        val head = LinearLayout(this)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        add(head, label("${t.getValue("title")}  v${BuildConfig.VERSION_NAME}", 5.2f, cText, true),
            weight = 1f, width = 0)
        val uiBtn = smallButton(t.getValue("ui_lang")) {
            uiLang = if (uiLang == "ar") "en" else "ar"
            rebuildUi()
        }
        head.addView(uiBtn, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        add(body, head)

        // اللغتان + زر التبديل / the two languages + swap button
        myPicker = picker(t.getValue("i_speak"), { myIdx }, { myIdx = it })
        theirPicker = picker(t.getValue("they_speak"), { theirIdx }, { theirIdx = it })
        val col1 = LinearLayout(this); col1.orientation = LinearLayout.VERTICAL
        add(col1, label(t.getValue("i_speak"), 3.4f, cMuted))
        add(col1, myPicker, px(1f))
        val col2 = LinearLayout(this); col2.orientation = LinearLayout.VERTICAL
        add(col2, label(t.getValue("they_speak"), 3.4f, cMuted))
        add(col2, theirPicker, px(1f))
        val swap = smallButton(t.getValue("swap")) {
            val a = myIdx; myIdx = theirIdx; theirIdx = a
            updatePickers()
        }
        val langRow = LinearLayout(this)
        langRow.orientation = LinearLayout.HORIZONTAL
        langRow.gravity = Gravity.BOTTOM
        add(langRow, col1, weight = 1f, width = 0)
        val swapLp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        swapLp.marginStart = gap; swapLp.marginEnd = gap
        langRow.addView(swap, swapLp)
        add(langRow, col2, weight = 1f, width = 0)
        add(body, langRow, gap)

        // بطاقة النتيجة: الأصل صغير والترجمة كبيرة / result card: small original, big translation
        val card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        card.setPadding(m, m, m, m)
        card.background = rounded(cCard, cCardBorder)
        origLbl = label(lastOrig, 4.2f, cOrig)
        transLbl = label(lastTrans, 6f, cText, true)
        for (v in listOf(origLbl, transLbl)) {
            v.setTextIsSelectable(true)
            v.textDirection = View.TEXT_DIRECTION_FIRST_STRONG   // اتجاه حسب لغة النص / direction by text language
            v.textAlignment = View.TEXT_ALIGNMENT_TEXT_START
        }
        add(card, label(t.getValue("orig"), 3.4f, cMuted))
        add(card, origLbl, px(2f))
        add(card, label(t.getValue("trans"), 3.4f, cMuted), px(2f))
        add(card, transLbl, px(2f))
        add(body, card, gap)

        // حقل الإدخال والزران الكبيران / input field and the two big buttons
        input = EditText(this).apply {
            hint = t.getValue("hint")
            setHintTextColor(cMuted)
            setTextColor(cText)
            sizeU(this, 4.2f)
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setSingleLine(false); maxLines = 4
            minHeight = px(12f)
            setPadding(px(2.5f), px(2.5f), px(2.5f), px(2.5f))
            background = rounded(cField, cFieldBorder)
            textDirection = View.TEXT_DIRECTION_FIRST_STRONG
            imeOptions = EditorInfo.IME_ACTION_SEND
            setText(draft)
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEND) { go(true); true } else false
            }
        }
        add(body, input, gap)

        meBtn = bigButton(t.getValue("btn_me"), cPrimary, cPrimaryPressed) { go(true) }
        theyBtn = bigButton(t.getValue("btn_they"), cAccent, cAccentPressed) { go(false) }
        val btns = LinearLayout(this)
        btns.orientation = LinearLayout.HORIZONTAL
        add(btns, meBtn, weight = 1f, width = 0)
        val theyLp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        theyLp.marginStart = gap
        btns.addView(theyBtn, theyLp)
        add(body, btns, gap)
        meBtn.isEnabled = !busy; theyBtn.isEnabled = !busy

        statusLbl = label("", 3.4f, cMuted)
        add(body, statusLbl, gap)

        // السجل (آخر المحادثات) / history of the latest exchanges
        add(body, label(t.getValue("history"), 3.4f, cMuted), gap)
        histBox = LinearLayout(this)
        histBox.orientation = LinearLayout.VERTICAL
        for ((i, line) in history.withIndex()) {
            val v = label(line, 4.2f, cOrig)
            v.textDirection = View.TEXT_DIRECTION_FIRST_STRONG
            add(histBox, v, if (i == 0) 0 else px(1.5f))
        }
        add(body, histBox, gap)

        updatePickers()
        refreshStatus()
        setContentView(root)
    }

    // ── الحالة / Status ──
    private fun refreshStatus() {
        if (!::statusLbl.isInitialized) return
        var s = t.getValue(statusKey)
        if (statusKey == "ready" && !speaker.available) s += "  " + t.getValue("speak_off")
        statusLbl.text = s
    }

    private fun setStatus(key: String) {
        statusKey = key
        refreshStatus()
    }

    // ── الترجمة / Translation ──
    private fun go(meToThem: Boolean) {
        if (busy) return
        val text = input.text.toString().trim()
        if (text.isEmpty()) { setStatus("empty"); return }
        if (myIdx == theirIdx) { setStatus("same"); return }
        val my = Langs.CODES[myIdx]
        val their = Langs.CODES[theirIdx]
        val src = if (meToThem) my else their
        val dst = if (meToThem) their else my
        busy = true
        meBtn.isEnabled = false; theyBtn.isEnabled = false
        setStatus("working")
        io.execute {
            val out: String? = try {
                translator.translate(text, src, dst)
            } catch (e: Exception) {
                null
            }
            main.post { if (!isFinishing) onDone(text, out, src, dst) }
        }
    }

    private fun onDone(orig: String, out: String?, src: String, dst: String) {
        busy = false
        meBtn.isEnabled = true; theyBtn.isEnabled = true
        if (out == null) { setStatus("fail"); return }
        lastOrig = orig; lastTrans = out
        origLbl.text = orig; transLbl.text = out
        setStatus("ready")
        input.setText("")
        speaker.say(out, dst)
        // سطر في السجل (الأحدث أولًا، بحد أقصى 20) / history line (newest first, max 20)
        history.add(0, "${Langs.NAMES.getValue(src)} ➜ ${Langs.NAMES.getValue(dst)}:  $orig  →  $out")
        while (history.size > 20) history.removeAt(history.size - 1)
        rebuildUi()
    }
}
