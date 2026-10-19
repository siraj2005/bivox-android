package app.bivox.android

import android.app.Activity
import android.app.StatusBarManager
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Icon
import android.graphics.drawable.StateListDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
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
    private var lastDst = "en"                   // لغة آخر ترجمة (للنطق) / language of the last translation (for speech)
    private val history = ArrayList<String>()   // الأحدث أولًا، حتى 20 / newest first, max 20
    private var statusKey = "ready"
    private var busy = false
    private var draft = ""
    private var checkingUpdate = false
    private var updating = false
    private var voiceRefreshNeeded = false
    private var notifAsked = false               // سبق طلب إذن الإشعارات / notification permission already requested
    private var lastSig = ""                     // بصمة حالة الأذونات عند بناء الواجهة / permission-state signature at build time

    private var u = 4f                           // وحدة القياس = 1% من الضلع القصير / unit = 1% of short side
    private lateinit var t: Map<String, String>

    private val translator = Translator()
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    // مستمعا النطق المشترك (الشاشة والفقاعة تتشاركان نسخة واحدة تُنشأ عند أول نطق)
    // Listeners for the shared speech (the screen and the bubble share one instance created on first speech)
    private val voiceReady: () -> Unit = { main.post { refreshStatus() } }
    private val voiceFail: (String) -> Unit = { _ -> main.post { if (!isFinishing) setStatus("speak_fail") } }

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
        // نقرأ اللغات المحفوظة (مشتركة مع الفقاعة) / read the saved languages (shared with the bubble)
        uiLang = Prefs.uiLang(this)
        myIdx = Prefs.my(this)
        theirIdx = Prefs.their(this)
        Speech.acquire()
        Speech.listen(voiceReady, voiceFail)
        buildUi()
        // تحقق صامت من التحديث عند فتح التطبيق / silent update check when the app opens
        if (savedInstanceState == null) checkUpdate(false)
        // نهيّئ النطق بعد لحظة (لا يبطئ الفتح) كي يكون جاهزًا عند أول ترجمة
        // Warm up speech a moment later (does not slow the launch) so it is ready by the first translation
        main.postDelayed({ if (!isFinishing && Prefs.autoSpeak(this)) Speech.get(this) }, 1500)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        rebuildUi()   // أحجام جديدة بعد التدوير / new sizes after rotation
    }

    // بعد العودة من تنزيل صوت أو من إعدادات النطق نعيد بناء المحركات ليرى التطبيق التغيير
    // After returning from a voice download or the speech settings, rebuild the engines so the app sees the change
    override fun onResume() {
        super.onResume()
        // اللغات قد تتغير من لوحة الفقاعة / the languages may change from the bubble panel
        myIdx = Prefs.my(this)
        theirIdx = Prefs.their(this)
        if (::myPicker.isInitialized) updatePickers()
        // تغيّرت الأذونات أو حالة الفقاعة؟ نعيد بناء الواجهة / permissions or bubble state changed? rebuild
        if (::statusLbl.isInitialized && permSig() != lastSig) rebuildUi()
        if (voiceRefreshNeeded) {
            voiceRefreshNeeded = false
            Speech.peek()?.refresh()
        }
    }

    override fun onDestroy() {
        Speech.unlisten(voiceReady, voiceFail)
        Speech.release()
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
        // اتجاه العنوان تابع للغة الواجهة فيُحاذى دائمًا مع بقية العناصر
        // The title direction follows the UI language so it always aligns with the rest
        val head = LinearLayout(this)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        // الشعار الرسمي بجانب العنوان / the official logo next to the title
        val logo = ImageView(this)
        logo.setImageResource(R.drawable.bivox_logo)
        logo.contentDescription = "BiVox"
        val logoLp = LinearLayout.LayoutParams(px(11f), px(11f))
        logoLp.marginEnd = gap
        head.addView(logo, logoLp)
        val titleLbl = label(t.getValue("title"), 5.2f, cText, true)
        titleLbl.textDirection = if (rtl) View.TEXT_DIRECTION_RTL else View.TEXT_DIRECTION_LTR
        titleLbl.textAlignment = View.TEXT_ALIGNMENT_VIEW_START
        add(head, titleLbl, weight = 1f, width = 0)
        val uiBtn = smallButton(t.getValue("ui_lang")) {
            uiLang = if (uiLang == "ar") "en" else "ar"
            Prefs.setUiLang(this, uiLang)
            rebuildUi()
        }
        head.addView(uiBtn, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        add(body, head)

        // رقم الإصدار تحت العنوان، دائمًا بالاتجاه اللاتيني / version under the title, always LTR
        val ver = label("v${BuildConfig.VERSION_NAME} · build ${BuildConfig.VERSION_CODE}", 3.4f, cMuted)
        ver.textDirection = View.TEXT_DIRECTION_LTR
        ver.textAlignment = View.TEXT_ALIGNMENT_VIEW_START
        add(body, ver, px(0.5f))

        // اللغتان + زر التبديل / the two languages + swap button
        myPicker = picker(t.getValue("i_speak"), { myIdx }, { myIdx = it; Prefs.setLangs(this, myIdx, theirIdx) })
        theirPicker = picker(t.getValue("they_speak"), { theirIdx }, { theirIdx = it; Prefs.setLangs(this, myIdx, theirIdx) })
        val col1 = LinearLayout(this); col1.orientation = LinearLayout.VERTICAL
        add(col1, label(t.getValue("i_speak"), 3.4f, cMuted))
        add(col1, myPicker, px(1f))
        val col2 = LinearLayout(this); col2.orientation = LinearLayout.VERTICAL
        add(col2, label(t.getValue("they_speak"), 3.4f, cMuted))
        add(col2, theirPicker, px(1f))
        val swap = smallButton(t.getValue("swap")) {
            val a = myIdx; myIdx = theirIdx; theirIdx = a
            Prefs.setLangs(this, myIdx, theirIdx)
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
        // زر النطق + زر حالة الأصوات (التشخيص متاح دائمًا) / Speak button + voice status button (diagnosis always reachable)
        val speakRow = LinearLayout(this)
        speakRow.orientation = LinearLayout.HORIZONTAL
        speakRow.addView(smallButton(t.getValue("speak")) { speakLast() }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val diagLp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        diagLp.marginStart = gap
        speakRow.addView(smallButton(t.getValue("voice_diag")) {
            voiceRefreshNeeded = true
            showVoiceDiag()
        }, diagLp)
        add(card, speakRow, px(2f), width = ViewGroup.LayoutParams.WRAP_CONTENT)
        add(body, card, gap)

        // حقل الإدخال والزران الكبيران / input field and the two big buttons
        input = EditText(this).apply {
            hint = t.getValue("hint")
            setHintTextColor(cMuted)
            setTextColor(cText)
            sizeU(this, 4.2f)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
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

        // قسم الفقاعة العائمة / floating bubble section
        buildBubbleSection(body, m, gap)

        // زر التحقق من التحديث / check-for-update button
        add(body, smallButton(t.getValue("update_check")) { checkUpdate(true) }, gap,
            width = ViewGroup.LayoutParams.WRAP_CONTENT)

        // السجل (آخر المحادثات) / history of the latest exchanges
        add(body, label(t.getValue("history"), 3.4f, cMuted), gap)
        histBox = LinearLayout(this)
        histBox.orientation = LinearLayout.VERTICAL
        fillHistory()
        add(body, histBox, gap)

        updatePickers()
        refreshStatus()
        setContentView(root)
    }

    // ── الحالة / Status ──
    private fun refreshStatus() {
        if (!::statusLbl.isInitialized) return
        var s = t.getValue(statusKey)
        if (statusKey == "ready" && Speech.peek()?.available == false) s += "  " + t.getValue("speak_off")
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
        if (!Net.online(this)) { setStatus("offline"); return }
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
        lastOrig = orig; lastTrans = out; lastDst = dst
        origLbl.text = orig; transLbl.text = out
        input.setText("")
        statusKey = "ready"
        if (Prefs.autoSpeak(this)) {
            Speech.say(this, out, dst, main) { r ->
                if (r == SpeakResult.NO_VOICE && !isFinishing) setStatus("no_voice")
            }
        }
        // سطر في السجل (الأحدث أولًا، بحد أقصى 20) / history line (newest first, max 20)
        history.add(0, "${Langs.NAMES.getValue(src)} ➜ ${Langs.NAMES.getValue(dst)}:  $orig  →  $out")
        while (history.size > 20) history.removeAt(history.size - 1)
        // نحدّث ما تغيّر فقط: لا إعادة بناء للشاشة فلا يتحرك شيء ولا يضيع التمرير
        // Update only what changed: no full rebuild, so nothing jumps and the scroll position is kept
        fillHistory()
        refreshStatus()
    }

    private fun fillHistory() {
        histBox.removeAllViews()
        for ((i, line) in history.withIndex()) {
            val v = label(line, 4.2f, cOrig)
            v.textDirection = View.TEXT_DIRECTION_FIRST_STRONG
            add(histBox, v, if (i == 0) 0 else px(1.5f))
        }
    }

    // ── النطق اليدوي / Manual speech ──
    private fun speakLast() {
        if (lastTrans.isEmpty()) return
        Speech.say(this, lastTrans, lastDst, main) { r ->
            if (r == SpeakResult.NO_VOICE || r == SpeakResult.NO_ENGINE) showVoiceHelp(lastDst)
        }
    }

    // يشرح كيف يُضاف صوت اللغة ويفتح إعدادات النطق
    // Explains how to add the language voice and opens the speech settings
    private fun showVoiceHelp(lang: String) {
        if (isFinishing) return
        voiceRefreshNeeded = true   // سنعيد بناء المحركات عند العودة / rebuild the engines on return
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setMessage(t.getValue("no_voice_msg").format(Langs.NAMES.getValue(lang)))
            .setPositiveButton(t.getValue("voice_install")) { _, _ -> installVoiceData() }
            .setNeutralButton(t.getValue("voice_diag")) { _, _ -> showVoiceDiag() }
            .setNegativeButton(t.getValue("close"), null)
            .show()
    }

    // يعرض المحركات المثبتة وحالة صوت كل لغة، ويفتح إعدادات النطق عند الحاجة
    // Shows the installed engines and each language's voice state, and opens speech settings if needed
    private fun showVoiceDiag() {
        if (isFinishing) return
        // أول استعمال: ننشئ المحركات وننتظر لحظة ليكون التقرير صحيحًا / first use: create the engines and wait a moment so the report is accurate
        if (Speech.peek() == null) {
            Speech.get(this)
            main.postDelayed({ showVoiceDiag() }, 900)
            return
        }
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle(t.getValue("diag_title"))
            .setMessage(t.getValue("diag_legend") + "\n\n" + Speech.get(this).report())
            .setPositiveButton(t.getValue("voice_settings")) { _, _ -> openVoiceSettings() }
            .setNegativeButton(t.getValue("close"), null)
            .show()
    }

    // يطلب من محرك Google شاشة تنزيل بيانات الصوت؛ وإن غاب المحرك يفتح صفحته في Play
    // Asks the Google engine for its voice-data download screen; if missing, opens its Play page
    private fun installVoiceData() {
        val gtts = "com.google.android.tts"
        try {
            val i = Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA)
            i.setPackage(gtts)
            startActivity(i)
            return
        } catch (e: Exception) {
            // محرك Google غير مثبّت / Google engine not installed
        }
        try {
            startActivity(Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA))
            return
        } catch (e: Exception) {
            // لا محرك يدعم الطلب / no engine handles the request
        }
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$gtts")))
        } catch (e: Exception) {
            try {
                startActivity(Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://play.google.com/store/apps/details?id=$gtts")))
            } catch (e2: Exception) {
                // لا شيء نفعله / nothing else to do
            }
        }
    }

    private fun openVoiceSettings() {
        try {
            startActivity(Intent("com.android.settings.TTS_SETTINGS"))
        } catch (e: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            } catch (e2: Exception) {
                // لا شيء نفعله / nothing else to do
            }
        }
    }

    // ── الفقاعة العائمة / Floating bubble ──
    private fun overlayOk(): Boolean = BubbleService.canOverlay(this)

    private fun notifOk(): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            checkSelfPermission("android.permission.POST_NOTIFICATIONS") == PackageManager.PERMISSION_GRANTED

    // هل خدمة إمكانية الوصول مفعّلة؟ / is the accessibility service enabled?
    private fun a11yOk(): Boolean {
        if (BivoxAccessibilityService.instance != null) return true
        return try {
            val en = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
            en.contains("$packageName/")
        } catch (e: Exception) {
            false
        }
    }

    private fun openA11ySettings() {
        try {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        } catch (e: Exception) {
            openAppInfo()
        }
    }

    private fun battOk(): Boolean = try {
        (getSystemService(POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(packageName)
    } catch (e: Exception) {
        true   // لا نزعج المستخدم إن تعذر الفحص / don't bother the user if the check fails
    }

    // بصمة تتغير عند تغير أي إذن أو حالة الخدمة / signature that changes when any permission or the service state changes
    private fun permSig(): String = "${overlayOk()}${notifOk()}${battOk()}${a11yOk()}${BubbleService.running}"

    // سطر إذن: علامة ✓/✗ + نص + زر «السماح» إن لم يُمنح
    // Permission row: ✓/✗ mark + text + an "Allow" button if not granted
    private fun permRow(parent: LinearLayout, textKey: String, ok: Boolean, gap: Int, onAllow: () -> Unit) {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        val lbl = label((if (ok) "✓  " else "✗  ") + t.getValue(textKey), 3.8f, if (ok) cAccent else cOrig)
        lbl.textAlignment = View.TEXT_ALIGNMENT_VIEW_START
        add(row, lbl, weight = 1f, width = 0)
        if (!ok) {
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.marginStart = gap
            row.addView(smallButton(t.getValue("perm_allow")) { onAllow() }, lp)
        }
        add(parent, row, gap)
    }

    private fun buildBubbleSection(body: LinearLayout, m: Int, gap: Int) {
        val card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        card.setPadding(m, m, m, m)
        card.background = rounded(cCard, cCardBorder)
        add(card, label(t.getValue("bubble_title"), 4.6f, cText, true))
        val desc = label(t.getValue("bubble_desc"), 3.6f, cMuted)
        desc.textAlignment = View.TEXT_ALIGNMENT_VIEW_START
        add(card, desc, px(1f))

        permRow(card, "perm_overlay", overlayOk(), gap) { openOverlaySettings() }
        if (Build.VERSION.SDK_INT >= 33) {
            permRow(card, "perm_notif", notifOk(), gap) { askNotifications() }
        }
        permRow(card, "perm_batt", battOk(), gap) { askBatteryExemption() }
        permRow(card, "perm_a11y", a11yOk(), gap) { openA11ySettings() }
        if (!a11yOk()) {
            val an = label(t.getValue("a11y_note"), 3.2f, cMuted)
            an.textAlignment = View.TEXT_ALIGNMENT_VIEW_START
            add(card, an, gap)
        }

        val note = label(t.getValue("bubble_samsung"), 3.2f, cMuted)
        note.textAlignment = View.TEXT_ALIGNMENT_VIEW_START
        add(card, note, gap)
        add(card, smallButton(t.getValue("app_info")) { openAppInfo() }, gap,
            width = ViewGroup.LayoutParams.WRAP_CONTENT)

        // زر القائمة السريعة / Quick Settings tile
        val tileNote = label(t.getValue("tile_desc"), 3.2f, cMuted)
        tileNote.textAlignment = View.TEXT_ALIGNMENT_VIEW_START
        add(card, tileNote, gap)
        if (Build.VERSION.SDK_INT >= 33) {
            add(card, smallButton(t.getValue("tile_add")) { requestAddTile() }, gap,
                width = ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        val on = BubbleService.running
        val toggle = bigButton(t.getValue(if (on) "bubble_stop" else "bubble_start"),
            if (on) cBtn else cAccent, if (on) cBtnPressed else cAccentPressed) { toggleBubble() }
        add(card, toggle, gap)
        add(body, card, gap)
        buildSettingsSection(body, m, gap)
        lastSig = permSig()
    }

    // أندرويد 13+: يطلب من النظام إضافة الزر إلى القائمة السريعة مباشرة
    // Android 13+: asks the system to add the tile to Quick Settings directly
    private fun requestAddTile() {
        if (Build.VERSION.SDK_INT < 33) return
        try {
            val sbm = getSystemService(StatusBarManager::class.java)
            sbm.requestAddTileService(ComponentName(this, BubbleTile::class.java), "BiVox",
                Icon.createWithResource(this, R.drawable.ic_stat_bivox), mainExecutor) { _ -> }
        } catch (e: Exception) {
            // يبقى الشرح اليدوي / the manual instructions remain
        }
    }

    // صف إعداد: النص + زر يدور بين القيم / a settings row: the label + a button that cycles through the values
    private fun cycleRow(parent: LinearLayout, labelKey: String, gap: Int, current: () -> String, onTap: () -> Unit) {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        val lbl = label(t.getValue(labelKey), 3.8f, cOrig)
        lbl.textAlignment = View.TEXT_ALIGNMENT_VIEW_START
        add(row, lbl, weight = 1f, width = 0)
        val btn = smallButton(current()) { }
        btn.setOnClickListener {
            onTap()
            btn.text = current()
        }
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.marginStart = gap
        row.addView(btn, lp)
        add(parent, row, gap)
    }

    // الإعدادات: حجم الفقاعة وشفافيتها والنطق التلقائي / settings: bubble size, opacity and automatic speech
    private fun buildSettingsSection(body: LinearLayout, m: Int, gap: Int) {
        val card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        card.setPadding(m, m, m, m)
        card.background = rounded(cCard, cCardBorder)
        add(card, label(t.getValue("set_title"), 4.6f, cText, true))
        cycleRow(card, "set_size", gap, { t.getValue("size_" + Prefs.bubbleScale(this)) }) {
            Prefs.setBubbleLook(this, (Prefs.bubbleScale(this) + 1) % 3, Prefs.bubbleAlpha(this))
            BubbleService.reload(this)
        }
        cycleRow(card, "set_alpha", gap, { t.getValue("alpha_" + Prefs.bubbleAlpha(this)) }) {
            Prefs.setBubbleLook(this, Prefs.bubbleScale(this), (Prefs.bubbleAlpha(this) + 1) % 3)
            BubbleService.reload(this)
        }
        cycleRow(card, "set_speak", gap, { t.getValue(if (Prefs.autoSpeak(this)) "on" else "off") }) {
            Prefs.setAutoSpeak(this, !Prefs.autoSpeak(this))
        }
        add(body, card, gap)
    }

    private fun toggleBubble() {
        if (BubbleService.running) {
            Prefs.setBubbleOn(this, false)
            BubbleService.stop(this)
            setStatus("bubble_stopped")
        } else {
            if (!overlayOk()) { setStatus("bubble_need_overlay"); return }
            try {
                Prefs.setBubbleOn(this, true)
                BubbleService.start(this)
                setStatus("bubble_started")
            } catch (e: Exception) {
                Prefs.setBubbleOn(this, false)
                setStatus("bubble_unsupported")
                return
            }
        }
        // الخدمة تبدأ/تتوقف بعد لحظة؛ نحدّث الزر بعدها / the service starts/stops a moment later; refresh the button then
        main.postDelayed({ if (!isFinishing) rebuildUi() }, 700)
    }

    private fun openOverlaySettings() {
        try {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        } catch (e: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            } catch (e2: Exception) {
                setStatus("bubble_unsupported")
            }
        }
    }

    private fun askNotifications() {
        if (Build.VERSION.SDK_INT < 33) return
        if (!notifAsked) {
            notifAsked = true
            requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), REQ_NOTIF)
        } else {
            // سبق الرفض: النظام لن يسأل ثانية، نفتح إعدادات الإشعارات / denied before: the system won't ask again, open notification settings
            try {
                startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
            } catch (e: Exception) {
                openAppInfo()
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_NOTIF && !isFinishing) rebuildUi()
    }

    private fun askBatteryExemption() {
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        } catch (e: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (e2: Exception) {
                openAppInfo()
            }
        }
    }

    private fun openAppInfo() {
        try {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        } catch (e: Exception) {
            // لا شيء نفعله / nothing else to do
        }
    }

    companion object {
        private const val REQ_NOTIF = 7
    }

    // ── التحديث التلقائي / Auto-update ──
    private fun checkUpdate(manual: Boolean) {
        if (checkingUpdate || updating) return
        if (!manual) {
            // فحص صامت مرة كل 6 ساعات فقط: أخف على البطارية والشبكة / silent check only every 6 hours: lighter on battery and network
            val now = System.currentTimeMillis()
            if (now - Prefs.lastUpdateCheck(this) < 6 * 3600 * 1000L) return
            Prefs.setLastUpdateCheck(this, now)
        }
        checkingUpdate = true
        if (manual) setStatus("update_checking")
        io.execute {
            val r: Pair<UpdateInfo?, Boolean> = try {
                Pair(Updater.check(BuildConfig.VERSION_CODE), false)
            } catch (e: Exception) {
                Pair(null, true)
            }
            main.post {
                if (isFinishing) return@post
                checkingUpdate = false
                val info = r.first
                if (info != null) {
                    if (statusKey == "update_checking") setStatus("ready")
                    showUpdateDialog(info)
                } else if (manual) {
                    setStatus(if (r.second) "update_fail" else "update_none")
                } else if (statusKey == "update_checking") {
                    setStatus("ready")
                }
            }
        }
    }

    private fun showUpdateDialog(info: UpdateInfo) {
        val notes = info.notes.trim().take(400)
        val msg = t.getValue("update_found").format(info.name, info.code) +
            (if (notes.isEmpty()) "" else "\n\n$notes")
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle(t.getValue("update_title"))
            .setMessage(msg)
            .setPositiveButton(t.getValue("update_now")) { _, _ -> startUpdate(info) }
            .setNegativeButton(t.getValue("update_later"), null)
            .show()
    }

    private fun startUpdate(info: UpdateInfo) {
        if (updating) return
        // أندرويد 8+: يلزم إذن «تثبيت تطبيقات غير معروفة» لهذا التطبيق مرة واحدة
        // Android 8+: this app needs the "install unknown apps" permission once
        if (Build.VERSION.SDK_INT >= 26 && !packageManager.canRequestPackageInstalls()) {
            AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle(t.getValue("update_perm_title"))
                .setMessage(t.getValue("update_perm_msg"))
                .setPositiveButton(t.getValue("update_open")) { _, _ ->
                    try {
                        startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:$packageName")))
                    } catch (e: Exception) {
                        // نتجاهل / ignore
                    }
                }
                .setNegativeButton(t.getValue("close"), null)
                .show()
            return
        }
        updating = true
        setStatus("update_downloading")
        io.execute {
            try {
                val file = Updater.download(this, info) { p ->
                    main.post {
                        if (::statusLbl.isInitialized) {
                            statusLbl.text = t.getValue("update_downloading") + " " + p + "%"
                        }
                    }
                }
                main.post { setStatus("update_installing") }
                Updater.install(this, file)
            } catch (e: Exception) {
                main.post { setStatus("update_fail") }
            }
            main.post { updating = false }
        }
    }
}
