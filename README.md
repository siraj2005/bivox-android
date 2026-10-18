# BiVox Android — الإصدار 0.4.2 (المرحلة ج: الترجمة على الشاشة / Phase C: on-screen translation)

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

## التحديث التلقائي / Auto-update
- كل رفع إلى `main` يبني APK موقّعًا وينشره في **Releases** (الوسم `b` + رقم البناء).
  Every push to `main` builds a signed APK and publishes it to **Releases** (tag `b` + build number).
- التطبيق يتحقق عند الفتح (وبزر «التحقق من التحديث») ثم يعرض نافذة «تحديث الآن».
  The app checks on launch (and via the "Check for updates" button) and offers "Update now".
- يلزم إضافة 4 قيم في **Settings ← Secrets and variables ← Actions**: `BIVOX_KEYSTORE_B64` و`BIVOX_STORE_PASS` و`BIVOX_KEY_ALIAS` و`BIVOX_KEY_PASS`.
  Add 4 secrets under **Settings → Secrets and variables → Actions**.
- دون المفتاح يُبنى APK للتجربة فقط ولا يُنشر تحديث. / Without the key only a test APK is built, no update is published.
- لا ترفع ملف المفتاح إلى المستودع أبدًا. / Never commit the keystore to the repo.

## الفقاعة العائمة / Floating bubble
- فقاعة قابلة للسحب فوق كل التطبيقات تفتح لوحة ترجمة مصغّرة؛ تعمل عبر خدمة أمامية بإشعار فيه زر «إيقاف».
  A draggable bubble over all apps opens a mini translator panel; runs as a foreground service with a Stop action.
- الأذونات: العرض فوق التطبيقات (مطلوب)، الإشعارات وتجاهل البطارية (موصى بهما) — شرحها في الشاشة الرئيسية.
  Permissions: display over apps (required), notifications and battery exemption (recommended) — guided on the main screen.

## الترجمة على الشاشة / On-screen translation
- «✂ اختر» يحدد نصًا أو عدة نصوص، «▤ الصفحة» تترجم كل نص ظاهر، و«🔍 عدسة» إطار يترجم ما يمرّ تحته أثناء التمرير (متحرك، أضلاعه قابلة للتغيير، وزر تثبيت).
  Select text, translate the whole page, or use a lens frame that translates whatever scrolls under it (movable, resizable sides, with a pin button).
- تُرسم الترجمة فوق النص الأصلي. تعمل عبر خدمة إمكانية الوصول (مفعّلة من الإعدادات) وتقرأ الشاشة عند الطلب فقط.
  Translations are drawn over the original text. Needs the accessibility service (enabled in Settings); it reads the screen only on request.

## الهوية / Branding
- ملفات الشعار الرسمية (SVG) في `branding/`؛ الأيقونة والشعار داخل التطبيق مولّدان منها.
  The official logo files (SVG) are in `branding/`; the app icon and in-app logo are generated from them.
