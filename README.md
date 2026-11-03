# PDF Просмотр (Android)

Простой просмотрщик PDF на встроенном PdfRenderer (без сторонних библиотек для PDF).
Работает на Android 7.0+ (API 24).

## Как получить APK

### Вариант 1 — Android Studio
1. File → Open → выберите папку проекта, дождитесь Gradle Sync.
2. Build → Build Bundle(s) / APK(s) → Build APK(s).
3. Файл: app/build/outputs/apk/debug/app-debug.apk

### Вариант 2 — GitHub Actions (без установки чего-либо)
1. Создайте репозиторий на github.com и загрузите в него содержимое этой папки.
2. Вкладка Actions → Build APK → дождитесь зелёной галочки (2–4 минуты).
3. В запуске внизу, в разделе Artifacts, скачайте PdfViewer-apk (внутри app-debug.apk).

## Установка на телефон
Скопируйте app-debug.apk на устройство, откройте его и разрешите
установку из неизвестных источников.
