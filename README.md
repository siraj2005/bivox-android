# BiVox Android — الإصدار 0.1.0 (المرحلة أ / Phase A)

مترجم محادثة وجهًا لوجه: اكتب نصًا، اختر الاتجاه («أنا ➜ هو» أو «هو ➜ أنا»)، فتظهر الترجمة بخط كبير وتُنطق إن توفر محرك نطق.
Face-to-face conversation translator: type text, pick the direction, and the translation appears in large text and is spoken if a TTS engine exists.

## الحصول على APK / Getting the APK
1. ارفع كل محتويات هذا المجلد إلى جذر مستودع GitHub (بما فيها المجلد المخفي `.github`).
   Upload everything in this folder to the root of your GitHub repo (including the hidden `.github` folder).
2. افتح تبويب **Actions** ← **Build APK** ← **Run workflow** (يعمل أيضًا تلقائيًا عند كل رفع).
   Open **Actions** → **Build APK** → **Run workflow** (it also runs on every push).
3. بعد اكتمال البناء نزّل الملف `BiVox-debug-apk` من أسفل صفحة التشغيل، فك ضغطه، ثم ثبّت `app-debug.apk` على هاتفك.
   When the build finishes, download `BiVox-debug-apk` from the bottom of the run page, unzip, and install `app-debug.apk`.
4. إن فشل البناء: أرسل لي لقطة شاشة لسطر الخطأ الأحمر.
   If the build fails, send me a screenshot of the red error line.

## المواصفات / Specs
- Kotlin أصلي، بلا مكتبات خارجية / Native Kotlin, no external libraries.
- الحد الأدنى أندرويد 7.0 (API 24)، الهدف 14 (API 34) / min Android 7.0, target 14.
- العربية والإنجليزية للواجهة، و11 لغة للترجمة / Arabic + English UI, 11 translation languages.
- الإصدار يظهر في عنوان الشاشة ويُرفع من `app/build.gradle.kts` (`versionName`).
  The version shows in the screen title and is bumped in `app/build.gradle.kts`.

## بنية الملفات / Layout
- `app/src/main/java/app/bivox/android/MainActivity.kt` — الشاشة / the screen
- `Translator.kt` — Google ثم MyMemory / Google then MyMemory
- `Speaker.kt` — النطق الاختياري / optional speech
- `Langs.kt` — اللغات ونصوص الواجهة / languages and UI strings
