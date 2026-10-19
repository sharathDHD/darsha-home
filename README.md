# Darsha Home

A small, personal Android TV home screen: a clean app launcher with no permissions and no internet access. Built directly from source with the Android SDK tools (no Gradle) and signed locally.

The repo also includes an optional clock screensaver for TVs whose firmware force-suspends on idle.

Tested on a Xiaomi Mi TV running Android 9 (API 28). Min SDK 21, target SDK 34.

## Features

- Home row of apps, six per row; scrolls when there are more.
- Edit from the remote: hold OK (or press Menu) on an app to move, rename, replace, hide, or remove it.
- All apps grid (A-Z) with pin/unpin, rename, app info, and hide/unhide.
- Hold OK (or press Menu) on Settings for Home settings: Warm / Black (OLED) / Blue themes, hidden apps, reset, and About.
- HDMI inputs discovered from the TV's TvInputManager, with Live TV fallbacks.
- HDMI tiles show the port number and update live: Active (green), Standby (amber), or No signal (gray).
- Play Store shortcut.
- Optional companion clock screensaver for TVs that sleep before a normal screensaver can start.
- The home screen itself has no permissions, no INTERNET permission, and no analytics.

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

## Clock screensaver (optional)

`screensaver/` is a separate app (`com.sharath.tvscreensaver`) for TVs that force-suspend on idle, such as some Xiaomi models. An accessibility service shows a clock shortly before the screen timeout; any remote key dismisses it and returns to the previous app. It skips the clock while audio is playing. The app has no launcher activity, so it does not appear in the app grid.

Build it with the same key as the home screen (run the main build once so `key.jks` exists):

```powershell
.\screensaver\build.ps1
```

```sh
sh screensaver/build.sh
```

Install and enable the watcher:

```sh
adb install -r screensaver/out/tv-screensaver.apk
adb shell settings put secure enabled_accessibility_services com.sharath.tvscreensaver/com.sharath.tvscreensaver.IdleWatcherService
adb shell settings put secure accessibility_enabled 1
adb shell appops set com.sharath.tvscreensaver SYSTEM_ALERT_WINDOW allow
adb shell settings put system screen_off_timeout 300000
adb shell settings put secure tvscreensaver_force 0
```

The clock appears at `screen_off_timeout` minus 8 seconds. Set `tvscreensaver_force` to `1` to test it immediately even while audio is playing. The accessibility service only watches key events to reset its idle timer; it does not read screen content.

To turn the watcher off:

```sh
adb shell settings put secure enabled_accessibility_services ""
```

If the original debug-signed build from the TV setup session is still installed, uninstall it first (`adb uninstall com.sharath.tvscreensaver`); this build uses the repo's `tvhome` key.

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
- `screensaver/` - optional clock screensaver app with its own build scripts.

## Notes

- The launcher only sees apps that expose a TV or normal launcher activity.
- App banners and icons come from the installed apps; no assets are downloaded.
- No data leaves the TV.
