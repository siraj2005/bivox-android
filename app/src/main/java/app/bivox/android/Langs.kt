package app.bivox.android

// اللغات الـ 11 (نفس لغات BiVox) ونصوص الواجهة بالعربية والإنجليزية
// The 11 languages (same as BiVox) and UI texts in Arabic and English
object Langs {
    val CODES = listOf("ar", "en", "fr", "es", "de", "tr", "ru", "zh", "hi", "pt", "it")

    val NAMES = mapOf(
        "ar" to "العربية", "en" to "English", "fr" to "Français",
        "es" to "Español", "de" to "Deutsch", "tr" to "Türkçe",
        "ru" to "Русский", "zh" to "中文", "hi" to "हिन्दी",
        "pt" to "Português", "it" to "Italiano",
    )
}

object Ui {
    val TEXTS: Map<String, Map<String, String>> = mapOf(
        "ar" to mapOf(
            "title" to "BiVox — مترجم المحادثة", "i_speak" to "لغتي",
            "they_speak" to "لغة الطرف الآخر", "hint" to "اكتب هنا...",
            "btn_me" to "أنا ➜ هو", "btn_they" to "هو ➜ أنا",
            "orig" to "النص الأصلي", "trans" to "الترجمة", "history" to "السجل",
            "ready" to "جاهز", "working" to "جاري الترجمة...",
            "fail" to "تعذّرت الترجمة — تحقق من الإنترنت",
            "empty" to "اكتب نصًا أولًا", "swap" to "⇄", "ui_lang" to "EN",
            "speak_off" to "(بلا نطق)", "same" to "اختر لغتين مختلفتين",
            "speak" to "🔊 نطق",
            "no_voice" to "لا يوجد صوت لهذه اللغة — اضغط «نطق» للحل",
            "no_voice_msg" to "جهازك لا يملك صوتًا للغة (%s).\n\nاضغط «تنزيل الصوت» لطلبه من محرك Google، ثم اختر هذه اللغة وانتظر اكتمال التنزيل.\n\nإن لم يكن المحرك مثبتًا سيفتح متجر Play لتثبيت «Speech Services by Google». وإن بقي الصوت غائبًا فاختر محرك Google من «إعدادات النطق».",
            "voice_install" to "تنزيل الصوت",
            "voice_settings" to "إعدادات النطق", "close" to "إغلاق",
            "update_check" to "التحقق من التحديث",
            "update_checking" to "جاري التحقق من التحديث...",
            "update_none" to "أنت على آخر إصدار",
            "update_fail" to "تعذّر التحديث — تحقق من الإنترنت",
            "update_title" to "تحديث جديد متاح",
            "update_found" to "الإصدار %s (بناء %d)",
            "update_now" to "تحديث الآن", "update_later" to "لاحقًا",
            "update_downloading" to "جاري التنزيل", "update_installing" to "جاري التثبيت...",
            "update_perm_title" to "السماح بالتثبيت",
            "update_perm_msg" to "للتحديث من داخل التطبيق اسمح لـ BiVox بتثبيت التطبيقات في الشاشة التالية، ثم ارجع واضغط «تحديث الآن» مجددًا.",
            "update_open" to "فتح الإعدادات",
        ),
        "en" to mapOf(
            "title" to "BiVox — Conversation Translator", "i_speak" to "My language",
            "they_speak" to "Other person", "hint" to "Type here...",
            "btn_me" to "Me ➜ Them", "btn_they" to "Them ➜ Me",
            "orig" to "Original", "trans" to "Translation", "history" to "History",
            "ready" to "Ready", "working" to "Translating...",
            "fail" to "Translation failed — check internet",
            "empty" to "Type some text first", "swap" to "⇄", "ui_lang" to "ع",
            "speak_off" to "(no speech)", "same" to "Choose two different languages",
            "speak" to "🔊 Speak",
            "no_voice" to "No voice for this language — tap Speak for help",
            "no_voice_msg" to "Your device has no voice for (%s).\n\nTap \"Download voice\" to request it from the Google engine, then pick this language and wait for the download to finish.\n\nIf the engine is missing, Play Store opens to install \"Speech Services by Google\". If the voice is still missing, choose the Google engine in \"Speech settings\".",
            "voice_install" to "Download voice",
            "voice_settings" to "Speech settings", "close" to "Close",
            "update_check" to "Check for updates",
            "update_checking" to "Checking for updates...",
            "update_none" to "You're up to date",
            "update_fail" to "Update failed — check internet",
            "update_title" to "Update available",
            "update_found" to "Version %s (build %d)",
            "update_now" to "Update now", "update_later" to "Later",
            "update_downloading" to "Downloading", "update_installing" to "Installing...",
            "update_perm_title" to "Allow installs",
            "update_perm_msg" to "To update from inside the app, allow BiVox to install apps on the next screen, then come back and tap \"Update now\" again.",
            "update_open" to "Open settings",
        ),
    )
}
