<div align="center">

# 📌 OxyPin

**Закрепление приложений через плитку в шторке для OxygenOS / ColorOS.**

Xposed-модуль: тап по плитке закрепляет текущее приложение, повторный тап - открепляет.

<br>

![Android](https://img.shields.io/badge/ANDROID-7.0%2B%20(API%2024%2B)-3DDC84?style=for-the-badge&logo=android&logoColor=white)
![Java](https://img.shields.io/badge/JAVA-17-F89820?style=for-the-badge&logo=openjdk&logoColor=white)
![Gradle](https://img.shields.io/badge/GRADLE-8.10-02303A?style=for-the-badge&logo=gradle&logoColor=white)

![Xposed](https://img.shields.io/badge/XPOSED-API%2082-4EAA5A?style=for-the-badge&logo=android&logoColor=white)
![LSPosed](https://img.shields.io/badge/LSPOSED-МОДУЛЬ-2C3E50?style=for-the-badge&logo=android&logoColor=white)
![OxygenOS](https://img.shields.io/badge/OXYGENOS%20%7C%20COLOROS-ПОДДЕРЖКА-EB0028?style=for-the-badge)
![License](https://img.shields.io/badge/LICENSE-MIT-0091D5?style=for-the-badge)

<br>

🇬🇧 **[Read the documentation in English](README.md)**

</div>

---

## ⭐ Обзор

OxyPin добавляет плитку в шторку уведомлений, которая закрепляет текущее приложение. Чтобы открепить - нужно потянуть (вытащить) снизу вверх на середину экрана жест «Домой» (стандартный жест открепления в Android), после чего устройство перенаправит вас на экран блокировки. Пока приложение закреплено, им можно пользоваться, но нельзя выйти или открыть другие данные - это удобно, когда отдаёшь телефон другому человеку.

На стоковом Android закрепление доступно через *Обзор → иконка приложения → Закрепить*. В OxygenOS / ColorOS кнопка **Закрепить** скрывается при включенных жестах и до неё сложно добраться, поэтому модуль хукает `system_server` и вызывает системные API закрепления напрямую.

Начиная с v1.1 модуль также возвращает кнопку **Закрепить** в меню рецентс (*Обзор → тап по иконке/трем точкам → Закрепить*) даже при включенной навигации жестами и перехватывает её нажатие через тот же защищённый API, что и плитка — закрепление из рецентс работает с жестами.

У модуля нет активити и экрана настроек: установить, включить в LSPosed, перезагрузиться, добавить плитку.

---

## 💡 Как это работает

1. `MainHook` загружается в `system_server` (scope `android`) и ждёт старта main looper.
2. При инициализации генерирует случайный токен и записывает его в `Settings.Secure` под ключом `oxypin_token`.
3. `PinTile` (`TileService`) читает токен и рассылает broadcast `ru.oxypin.ACTION_PIN` или `ru.oxypin.ACTION_UNPIN`.
4. Хук находит целевой task (явный `taskId` из рецентс, если передан, иначе foreground-task) и вызывает `startScreenPinning` у `ActivityTaskManager`. Сигнатура метода отличается в разных версиях прошивки, поэтому метод ищется по имени и числу аргументов; если не нашёлся - модуль откатывается к `LockTaskController`. Открепление - через `stopScreenPinning` или `clearLockedTasks`.
5. Broadcast с неверным токеном игнорируется, так что сторонние приложения не могут ничего закрепить или открепить.
6. Для меню рецентс `MainHook` дополнительно загружается в `com.android.systemui` и `com.android.launcher` (System Launcher). Хукает `ActivityManagerWrapper.isScreenPinningEnabled` чтобы всегда возвращать `true` (кнопка **Закрепить** не скрывается) и перехватывает `PinSystemShortcut.onClick` / `SystemUiProxy.startScreenPinning`, отправляя тот же защищённый `ACTION_PIN` с выбранным `taskId` в `system_server`.

---

## ✨ Возможности

- Закрепление и открепление активного приложения с любого экрана через QS-плитку.
- **Кнопка Закрепить в рецентс всегда видна** — `Обзор → иконка/⋮ → Закрепить` возвращается даже при навигации жестами (OxygenOS скрывает её по умолчанию) и закрепляет через тот же защищённый API.
- Состояние плитки синхронизируется с состоянием lock-task.
- Поиск метода закрепления адаптируется под разные сборки OxygenOS / ColorOS.
- Тосты на русском и английском, выбираются по языку системы.
- APK весит ~16 КБ, рантайм-зависимостей нет.

---

## 📲 Установка

1. Скачайте APK со страницы [Releases](../../releases).
2. Установите и включите модуль в LSPosed со scopes **System Framework (`android`)**, **System UI (`com.android.systemui`)** и **System Launcher (`com.android.launcher`)** — последние два нужны, чтобы кнопка **Закрепить** в рецентс появлялась при жестах.
3. Перезагрузите устройство.
4. Добавьте плитку **Закрепить приложение** в шторку или назначьте её на жест. Кнопка **Закрепить** также появится в рецентс: `Обзор → тап по иконке или ⋮ → Закрепить`.

---

## 🔨 Сборка из исходников

Требования: JDK 17+ и Android SDK (платформа 35). Задайте `ANDROID_HOME` либо создайте `local.properties` со строкой `sdk.dir=...`.

```bash
git clone https://github.com/Nexuverse-Labs/OxyPin.git
cd OxyPin
./gradlew assembleRelease
```

Готовый APK: `app/build/outputs/apk/release/app-release.apk`. Debug-сборка: `./gradlew assembleDebug`.

> [!NOTE]
> Release-APK по умолчанию подписывается debug-ключом, поэтому сборка работает без дополнительных настроек.
> Чтобы подписывать своим ключом, создайте `keystore.properties` в корне репозитория (файл в `.gitignore`):
>
> ```properties
> storeFile=my-release.keystore
> storePassword=***
> keyAlias=my-alias
> keyPassword=***
> ```

---

## 📁 Структура проекта

```
app/src/main/
├── java/ru/oxypin/
│   ├── MainHook.java        # хук system_server: pin/unpin, приём команд
│   ├── PinTile.java         # QS TileService: шлёт команды хуку
│   └── OxyPin.java          # общие константы
├── assets/xposed_init       # точка входа Xposed
├── res/values*/strings.xml  # строки (en, ru)
└── AndroidManifest.xml
```

---

## 📄 Лицензия

[MIT](LICENSE)
