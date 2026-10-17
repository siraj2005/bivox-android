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
        ),
    )
}
