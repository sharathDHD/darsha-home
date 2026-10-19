# Darsha Home

A small, personal Android TV home screen: a clean app launcher with no permissions and no internet access. Built directly from source with the Android SDK tools (no Gradle) and signed locally.

Tested on a Xiaomi Mi TV running Android 9 (API 28). Min SDK 21, target SDK 34.

## Features

- Home row of apps, six per row; scrolls when there are more.
- Edit from the remote: hold OK (or press Menu) on an app to move, rename, replace, hide, or remove it.
- All apps grid (A-Z) with pin/unpin, rename, app info, and hide/unhide.
- Hold OK (or press Menu) on Settings for Home settings: Warm / Black (OLED) / Blue themes, hidden apps, reset, and About.
- HDMI inputs discovered from the TV's TvInputManager, with Live TV fallbacks.
- Play Store shortcut.
- No permissions, no INTERNET permission, no analytics.

## Build

Requirements:

- JDK 17
- Android SDK platform 34
- Android SDK build-tools 36.0.0
- `JAVA_HOME` and `ANDROID_HOME` (or `ANDROID_SDK_ROOT`) set

Windows (PowerShell):

```powershell
$env:TVHOME_KEY_PASS = "choose-a-password"
.\build.ps1
```

macOS/Linux:

```sh
KEYSTORE_PASS="choose-a-password" sh build.sh
```

Output: `out/darsha-home.apk`.

The first build creates `key.jks` (alias `tvhome`) and signs the APK. Keep `key.jks`; updates must use the same key. The key and password files are git-ignored.

Instead of an environment variable, you can put the password in an ignored local file:

- Windows: `keystore.env.ps1` with `$env:TVHOME_KEY_PASS = "..."`.
- macOS/Linux: `keystore.env` with `KEYSTORE_PASS=...`.

If the key is lost, the app must be uninstalled before installing a new build signed with a different key.

## Install

Enable network or USB debugging on the TV and connect with ADB.

Windows:

```powershell
.\install-local.ps1 -Tv 192.168.1.50:5555 -Launch -SetHome
```

macOS/Linux:

```sh
sh install.sh 192.168.1.50
```

`install.sh` can disable the TV's stock home screen (with confirmation); `undo.sh` re-enables it. On Windows, `-SetHome` saves the previous home activity to `home-before.txt` and sets Darsha Home as the default. If the TV keeps opening its stock launcher, disable that launcher once:

```sh
adb shell pm disable-user --user 0 <stock-launcher-package>
```

Re-enable it with `adb shell pm enable <stock-launcher-package>`.

## Configuration

The default home row comes from `apps.txt` (one package name per line). Missing apps are skipped. You can also pass a list when starting the app:

```sh
adb shell am start -n com.darsha.tvhome/.MainActivity --es apps "pkg.one,pkg.two"
```

After you edit the row on the TV, the saved list takes over. `Home settings -> Reset home layout` returns to the `apps.txt` defaults.

## Project layout

- `src/com/darsha/tvhome/MainActivity.java` - home screen, All apps grid, settings, and HDMI handling.
- `src/com/darsha/tvhome/LineIcon.java` - built-in line icons.
- `AndroidManifest.xml` - single HOME activity, no permissions.
- `build.ps1` / `build.sh` - build and sign without Gradle.
- `install-local.ps1` / `install.sh` - install and set as the default home screen.
- `undo.sh` - restore the previous home screen (Unix).
- `apps.txt` - default app list.

## Notes

- The launcher only sees apps that expose a TV or normal launcher activity.
- App banners and icons come from the installed apps; no assets are downloaded.
- No data leaves the TV.
