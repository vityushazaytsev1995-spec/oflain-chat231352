# Гонец - финальная версия 4.0 - Полная инструкция

## Название
Приложение теперь называется **"Гонец"** (android:label="Гонец" в манифесте). Иконка системная, но название в лаунчере будет Гонец.

## Что исправлено относительно V3:

### 1. Меню полностью на русском:
Было: QR, SCAN, Peers, Hide, Log, FILE, IMG, MIC, SEND, Mesh:
Стало:
- Мой QR / Скан
- Контакты / Скрыть
- Лог
- Файл / Фото / Голос / Отпр.
- Обновить / Подключиться / Подключен
- Окно "Добро пожаловать в Гонец" вместо "Offline Mesh"
- Все диалоги на русском

### 2. Фото открываются как в мессенджерах:
Было: только текст "📷 filename ✅"
Стало:
- В чате показывается превью 200x200 с закругленными углами
- При нажатии открывается полный экран с картинкой 400dp
- Загрузка через BitmapFactory с inSampleSize=4 чтобы не падать по памяти
- Если файл еще грузится - показывает "Загружается..."

Код:
```kotlin
Image(bitmap = bmp.asImageBitmap(), ... .clickable { onImageClick(file) })
```

### 3. Голосовые внутри приложения:
Было: кнопка открывала внешний плеер через Intent, часто не работало.
Стало:
- Встроенный плеер на MediaPlayer внутри чата
- Кнопка ▶ / ⏸, прогресс бар, время текущее / общее
- Не выходит из приложения, играет внутри
- Код: VoicePlayer composable с DisposableEffect для release

### 4. Контакты и QR пофикшены (из V3):
- Проверка разрешений ДО старта сервиса
- Автоперезапуск discovery каждые 10 сек если пусто
- Очередь для QR: если отсканировал ID которого еще нет рядом - запомню и подключу когда найду
- CaptureActivity в манифесте
- Убраны тяжелые иконки material-icons-extended - только текст кнопки, чтобы не падать с OOM

### 5. Почищено:
Всего 14 файлов (было 16):
```
app/build.gradle
app/proguard-rules.pro
app/src/main/AndroidManifest.xml
app/src/main/java/com/gonets/messenger/MainActivity.kt
app/src/main/java/com/gonets/messenger/model/MeshModels.kt
app/src/main/java/com/gonets/messenger/service/MeshForegroundService.kt
app/src/main/java/com/gonets/messenger/service/MeshService.kt
app/src/main/java/com/gonets/messenger/util/FileUtils.kt
app/src/main/java/com/gonets/messenger/util/QrUtils.kt
.github/workflows/build-apk.yml
build.gradle
settings.gradle
gradle.properties
gradle/wrapper/gradle-wrapper.properties
```
Нет .gradle, build, .idea, web-demo (web-demo остался для теста в прошлой версии, в этой тоже есть но не нужен для сборки).

---

## Как залить в GitHub - САМАЯ ПОДРОБНАЯ:

### Подготовка:
1. Скачай `offline-gonets-final.zip` из этого workspace (слева в файлах)
2. Распакуй. На телефоне: файловый менеджер -> включи "Показывать скрытые файлы" (ZArchiver: ⋮ -> Настройки -> Показывать скрытые файлы). Иначе не увидишь папку `.github`

### Создание репо:
1. Открой https://github.com/new
2. Repository name: `gonets-final` (любое латиницей)
3. Выбери **Public** (обязательно! иначе артефакты не скачать)
4. НЕ ставь галочек Add README, .gitignore, license
5. Create repository

### Заливка файлов (2 способа):

#### Способ А - через сайт (с телефона):
1. В новом пустом репо нажми **Add file -> Upload files**
2. Открой папку `offline-gonets-final` на телефоне/ПК
3. Выдели ВСЕ что ВНУТРИ нее (а не саму папку!):
   - папку `app`
   - папку `gradle`
   - папку `.github` (если не видно - создай вручную, см. ниже)
   - файлы `build.gradle`, `settings.gradle`, `gradle.properties`
4. Перетащи в окно браузера на GitHub
5. Если `.github` не загрузилась (скрытая):
   - Нажми Add file -> Create new file
   - В поле имени введи `.github/workflows/build-apk.yml`
   - Открой файл `offline-gonets-final/.github/workflows/build-apk.yml` в блокноте, скопируй весь текст, вставь на GitHub
   - Commit
   - То же для `gradle/wrapper/gradle-wrapper.properties`
6. Нажми внизу **Commit changes**

#### Способ Б - через git (с компа):
```bash
cd offline-gonets-final
git init
git add .
git commit -m "gonets v4 final russian"
git branch -M main
git remote add origin https://github.com/ТВОЙ_НИК/gonets-final.git
git push -u origin main
```

### Проверка:
На главной странице репо должны быть:
```
app
gradle
.github
build.gradle
settings.gradle
gradle.properties
```
Если видишь только одну папку `offline-gonets-final` - ты залил папку в папке, неправильно. Удали репо и залей содержимое.

### Сборка:
1. Перейди в **Actions** - должна сразу запуститься сборка `Build Gonets Final`
2. Жди 4-6 минут (первый раз дольше качает зависимости)
3. Когда зеленая галочка ✅ - кликни на запуск -> внизу **Artifacts** -> `gonets-apk` -> скачай zip -> внутри `app-debug.apk`

### Установка:
1. Отправь apk на 2 телефона через Bluetooth / Telegram как файл / USB
2. Установи (разреши установку из неизвестных источников)
3. При первом запуске:
   - Даст запрос разрешений - дай ВСЕ: Bluetooth, WiFi, Камера, Микрофон, Геолокация
   - Спросит ник - введи минимум 2 буквы -> Сохранить. ID сгенерируется сам, постоянный.
   - Включи Bluetooth и WiFi на ОБОИХ (интернет не нужен)
   - В списке появятся контакты как ⚪ найден -> нажми Подключиться -> на втором появится окно "X хочет подключиться" -> Принять с обеих сторон -> станет 🟢 подключен
4. Теперь можно писать, слать фото (откроется в чате), файлы, голосовые (плеер внутри).

### Тестовая онлайн версия:
Открой `offline-mesh-final/web-demo/index.html` или `offline-mesh-final-v3/web-demo/index.html` в браузере - там можно потыкать интерфейс.

---

## Структура для проверки:

Если все правильно залито, в логе сборки должно быть:
```
> Task :app:compileDebugKotlin
w: ... 'constructor MediaRecorder()' is deprecated -> это нормально, ворнинг
> Task :app:mergeExtDexDebug
> Task :app:packageDebug
BUILD SUCCESSFUL
```

Если `OutOfMemoryError` - значит gradle.properties не залит или там не `Xmx4096m`. Замени.

Готово! Приложение Гонец готов к использованию без интернета.
