<div align="center">

# 📌 OxyPin

**App pinning with a Quick Settings tile for OxygenOS / ColorOS.**

An Xposed module: tap the tile to pin the current app, tap again to unpin.

<br>

![Android](https://img.shields.io/badge/ANDROID-7.0%2B%20(API%2024%2B)-3DDC84?style=for-the-badge&logo=android&logoColor=white)
![Java](https://img.shields.io/badge/JAVA-17-F89820?style=for-the-badge&logo=openjdk&logoColor=white)
![Gradle](https://img.shields.io/badge/GRADLE-8.10-02303A?style=for-the-badge&logo=gradle&logoColor=white)

![Xposed](https://img.shields.io/badge/XPOSED-API%2082-4EAA5A?style=for-the-badge&logo=android&logoColor=white)
![LSPosed](https://img.shields.io/badge/LSPOSED-MODULE-2C3E50?style=for-the-badge&logo=android&logoColor=white)
![OxygenOS](https://img.shields.io/badge/OXYGENOS%20%7C%20COLOROS-SUPPORTED-EB0028?style=for-the-badge)
![License](https://img.shields.io/badge/LICENSE-MIT-0091D5?style=for-the-badge)

<br>

🇷🇺 **[Читать документацию на русском языке](README.ru.md)**

</div>

---

## ⭐ Overview

OxyPin adds a Quick Settings tile that pins the current app. To unpin, swipe up from the bottom to the middle of the screen with the Home gesture (the standard Android unpin gesture), and the device will take you to the lock screen. While an app is pinned, you can keep using it, but you cannot exit it or open anything else - handy when you hand the phone to someone else.

On stock Android pinning is reached through *Overview → app icon → Pin*. OxygenOS / ColorOS hide the **Pin** button when gesture navigation is enabled and make this entry point hard to reach, so the module hooks `system_server` and calls the pinning APIs directly.

Since v1.1 the module also restores the **Pin** button in the Recents task menu (*Overview → tap the app icon/dots → Pin*) even with gesture navigation enabled, and routes its click through the same secure pinning API as the tile, so pinning from Recents works with gestures as well.

There is no launcher activity and no settings screen: install, enable in LSPosed, reboot, add the tile.

---

## 💡 How It Works

1. `MainHook` is loaded into `system_server` (scope `android`) and waits for the main looper to start.
2. On init it generates a random token and writes it to `Settings.Secure` under the `oxypin_token` key.
3. `PinTile` (a `TileService`) reads the token and broadcasts `ru.oxypin.ACTION_PIN` or `ru.oxypin.ACTION_UNPIN`.
4. The hook finds the target task (explicit `taskId` from Recents if provided, otherwise the foreground task) and invokes `startScreenPinning` on `ActivityTaskManager`. The method signature differs between firmware versions, so it is resolved by name and parameter count; if that fails, the module falls back to `LockTaskController`. Unpinning uses `stopScreenPinning` or `clearLockedTasks`.
5. Broadcasts with a wrong token are ignored, so other apps cannot pin or unpin anything.
6. For the Recents menu, `MainHook` is additionally loaded into `com.android.systemui` and `com.android.launcher` (System Launcher). It hooks `ActivityManagerWrapper.isScreenPinningEnabled` to always return `true` so the **Pin** option is never hidden, and intercepts `PinSystemShortcut.onClick` / `SystemUiProxy.startScreenPinning` to send the same secure `ACTION_PIN` broadcast with the selected `taskId` to `system_server`.

---

## ✨ Features

- Pin / unpin the foreground app from any screen via the QS tile.
- **Pin button in Recents always visible** — `Overview → app icon/dots → Pin` is restored even with gesture navigation (OxygenOS hides it by default) and pins via the same secure API.
- Tile state follows the lock-task state.
- Pin method resolution adapts to different OxygenOS / ColorOS builds.
- Toasts in English and Russian, selected by system language.
- APK is ~16 KB and has no runtime dependencies.

---

## 📲 Installation

1. Download an APK from [Releases](../../releases).
2. Install it and enable the module in LSPosed with scopes **System Framework (`android`)**, **System UI (`com.android.systemui`)** and **System Launcher (`com.android.launcher`)** — the latter two are required for the Recents **Pin** button to appear with gesture navigation.
3. Reboot the device.
4. Add the **Pin app** tile to the Quick Settings, or bind the tile to a gesture. The **Pin** option will also appear in Recents: `Overview → tap the app icon or ⋮ → Pin`.

---

## 🔨 Building from Source

Requirements: JDK 17+ and the Android SDK (platform 35). Set `ANDROID_HOME`, or create `local.properties` with `sdk.dir=...`.

```bash
git clone https://github.com/Nexuverse-Labs/OxyPin.git
cd OxyPin
./gradlew assembleRelease
```

The APK is written to `app/build/outputs/apk/release/app-release.apk`. Debug build: `./gradlew assembleDebug`.

> [!NOTE]
> The release APK is signed with the debug key by default, so the build works without any setup.
> To sign with your own key, create `keystore.properties` in the repo root (it is gitignored):
>
> ```properties
> storeFile=my-release.keystore
> storePassword=***
> keyAlias=my-alias
> keyPassword=***
> ```

---

## 📁 Project Structure

```
app/src/main/
├── java/ru/oxypin/
│   ├── MainHook.java        # system_server hook: pin/unpin, command receiver
│   ├── PinTile.java         # QS TileService: sends commands to the hook
│   └── OxyPin.java          # shared constants
├── assets/xposed_init       # Xposed entry point
├── res/values*/strings.xml  # strings (en, ru)
└── AndroidManifest.xml
```

---

## 📄 License

[MIT](LICENSE)
