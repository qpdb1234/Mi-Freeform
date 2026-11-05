# Mi-Freeform (2.5.38 maintenance fork)

**English** | [简体中文](README_zh-Hans.md)

![License](https://img.shields.io/badge/license-GPL--3.0-blue)

> **Fork notice**
> This repository is a fork of [sunshine0523/Mi-Freeform](https://github.com/sunshine0523/Mi-Freeform) (GPL-3.0).
> The baseline is upstream's `main` branch (2.5.18 / versionCode 2518, last commit 2023-04-27).
> All copyright of the original code stays with the upstream author sunshine0523; the modifications on this
> branch are distributed under the same **GPL-3.0** license.

Mi-Freeform is a third-party freeform-window app for Android, activated through Shizuku / Sui. It can display
most apps as a freeform window, and supports Android 8.1 and above.

## Compatibility

> **Read this before installing**
>
> Below is what has actually been tested. In other words: **systems not listed here are not guaranteed to work**.
>
> | System version | Status |
> | --- | --- |
> | Android 10 | ✅ working |
> | Android 12 | ✅ working |
> | Android 14 | ⚠️ **partially working** (tested on a HyperOS 14 phone and an AOSP 14 emulator, on the **adaptation build** described at the end of this section): only the features in the verified list below work, everything else has **not been checked item by item** |
>
> **Verified working on Android 14** (2026-11, on the `a14-android14` adaptation build — **not** the
> 2.5.38 APK released from `main`): opening, closing and resizing
> freeform windows, the top-bar close button, the back key, the sidebar handle, the sidebar app panel, and the
> persistent-notification and QS-tile entries. Regression: 3 window rounds, 2 handle rounds, 3 panel rounds —
> the state described below was not reproduced once.
>
> **The one system-level Android 14 defect we confirmed** (fixed **only on the `a14-android14` adaptation
> branch, which is not published and not merged — the builds released from `main` still contain the trigger**;
> kept here so that people who hit it can recognise it): the freeform window, the sidebar handle and the sidebar
> panel disappear
> from the screen **at the same time**; the app is still alive, it just shows nothing. The cause is not the
> hidden-API policy being tightened. On HyperOS 14, when a window is *attempted* while the overlay permission
> is not in effect, **the old build auto-opened the system "display over other apps" settings page from the
> rejection branch** — and that single jump makes the system force-hide **every** overlay window that the
> process creates afterwards (the accessibility window type included), and re-granting the permission does not
> undo it. Measured on device: with the jump kept 4/4 poisoned, with only the jump removed (everything else
> unchanged) 0/4. Querying the permission alone, or opening that settings page alone (with no rejected window
> attempt), does **not** trigger it.
>
> **Recovery** (try in this order):
>
> 1. **Fully restart Mi-Freeform**: swipe it away in Recents and open it again (just backgrounding it is not
>    enough) — this works in practice;
> 2. if that still fails, **reboot the phone**;
> 3. measured **ineffective**: re-granting the overlay permission, closing and reopening the window, rotating
>    the screen, a screen off/on cycle, and enabling the accessibility keep-alive only after the state is
>    already there.
>
> **Prevention**: grant the overlay permission before opening the first window, or enable the accessibility
> keep-alive service (its windows use a type that does not need that permission, so no rejected attempt is ever
> made). **The `a14-android14` adaptation branch no longer jumps anywhere automatically** — the code on `main`
> still does: on that branch a rejected request only writes one
> diagnostic record and shows a toast, and the persistent notification explains the reason and carries a
> "grant permission" action that the user taps to enter the permission page. If the user has switched
> Mi-Freeform's notifications off entirely (so there is no button to tap), the toast and the record instead
> point at the path that does not depend on notifications: open Mi-Freeform from the launcher and turn the
> overlay permission on in its permission page.
> Versions outside the table above (Android 8.1–9, 11, 13, 15+) have **not been tested**, which does not mean
> they work.
>
> **What is still NOT adapted for Android 14** (which is why the table says *partially*, not *working*):
> - the Xposed route installs **no hook at all** on Android 14 — the `when (Build.VERSION.SDK_INT)` in
>   `HookFramework.handleLoadPackage` stops at 33 and has no 34 branch. So Xposed-dependent options such as
>   "stop apps from jumping out of the freeform window into fullscreen" are inert on 14 (unverified, because
>   nothing gets hooked in the first place); the Shizuku / Sui equivalents are unaffected.
> - No Android 14 device other than that HyperOS 14 phone (Xiaomi MIX 2S) and the AOSP 14 emulator has been tested.
> - Input method, clipboard and multi-display behaviour on 14 have **not been checked item by item**.
>
> Android 14 adaptation happens in a **separate, unpublished copy of the source tree** (local branch
> `a14-android14`, not pushed to GitHub): the permission
> gate, the removal of automatic jumps, the "grant permission" action on the persistent notification and the
> watchdog for hidden windows have all landed there and been regression-tested on a HyperOS 14 device (3 window
> rounds, 2 handle rounds, 3 panel rounds, none of them re-creating that state). **None of that is in `main`,
> and none of it is in the released APKs of this repository yet.**

Current support:
- open favorited apps as a freeform window from the global sidebar
- open favorited apps from a persistent notification
- open favorited apps from a QS tile
- make apps that post notifications open in freeform directly
- an open API for external control (see [`open_api.md`](open_api.md))

---

## What this fork changes

Baseline 2.5.18 → current **2.5.38 / versionCode 2551**. The main changes, grouped by theme:

**Identity and coexistence**
- `applicationId` is `com.mifreeform.freeform` while the source namespace stays `com.moonlight.freeform`, so
  this build **can be installed next to the official one** and neither overwrites the other.
- Places that match by package name (such as `packageNames` in `res/xml/accessibility_config.xml`) are kept in sync.

**Freeform window behaviour**
- **Portrait and landscape sizes are remembered separately**, and every app's window keeps its own memory; the
  memory is cleared as soon as the window is closed (`userPortraitHeight` / `userLandscapeHeight` in
  `FreeformView`). Rotating back and forth no longer changes the size.
- Tapping a notification opens the sidebar **immediately**; the delay the original had is gone.
- Touch mapping and window geometry were extracted out of `FreeformView` into `FreeformTouchMapper` /
  `FreeformWindowGeometry`, with unit tests alongside.
- Added freeform screenshots (`ScreenshotHelper`) and a mini floating ball (`MiniBallView`).

**UI**
- The three top-bar buttons have configurable actions: close / fullscreen / minimize / back / screenshot /
  rotate / center (`view_top_bar.xml`, `TopBarAction`).
- The double-tap action on the middle of the bottom bar is configurable too (defaults to rotating the window).
- The home screen is now a set of status cards (`StatusCards`), and the settings layout is unified.

**Splash and loading screens**
- **All splash / loading screens removed**: the Lottie dependency, `res/raw/loading.json` and the loading views
  in the layouts are gone, and `SplashActivity` only routes. After entering entries such as "quick apps" in
  settings, the list is rendered directly. The APK shrank from roughly 12.4 MB to roughly 7.5 MB.

**Settings**
- Numeric settings all use `NumericSeekBarPreference` (tap to type a value + grey background + range validation).
- Some preference keys were renamed once, so the first launch migrates the old values to the new keys
  (`SettingsKeyMigration`). Restoring a backup file exported by an older version migrates them the same way, so
  settings you had tuned are not thrown back to their defaults.
- One-tap reset to defaults; fixed the crash on some vendor ROMs (where the `clear()` callback reports key=null).
- Settings import / export (`SettingsBackup`).
- In-app language switching (`LocaleHelper`), independent of the system setting.
- The sidebar handle geometry is configurable and can be locked.

**Build**
- compileSdk 32 / targetSdk 32 / minSdk 27, AGP 7.4.2 + Gradle 7.5 + Kotlin 1.7.10.
- The debug build additionally packages x86 / x86_64 so it can be verified on emulators; release still ships arm only.
- Signing material is read from `keystore.properties` at the repository root, and **neither that file nor any
  `*.jks` private key is committed**, so the repository contains no passwords and no keys
  (template: `keystore.properties.example`):
  - with no keystore configured, the debug build uses the standard AGP debug certificate (`CN=Android Debug`) —
    **a fresh clone builds an installable debug APK without configuring any key at all**;
  - with no keystore configured, release gets no `signingConfig` and produces `app-release-unsigned.apk`
    (unsigned, **not installable directly**);
  - an earlier version hardcoded the keystore from the upstream author's machine, which amounted to signing
    this fork's builds in the name of `CN=sunshine`; that has been avoided.
- The unit tests under `app/src/test/` can be run with `./gradlew testDebugUnitTest`.

---

## Download

See this repository's [Releases](../../releases). The upstream originals:
[sunshine0523/Mi-Freeform releases](https://github.com/sunshine0523/Mi-Freeform/releases/).

⚠️ Before downloading, read the [Compatibility](#compatibility) section above: Android 14 is **partially
working**, and both the verified features and the parts that are still unadapted (Xposed installing no hook on
14 included) are listed in that same section.

Activation needs [Shizuku](https://shizuku.rikka.app/) or [Sui](https://github.com/topjohnwu/Sui); the sidebar
and similar features also need the accessibility permission.

## Documentation

| File | Contents |
| --- | --- |
| `README.md` | this file — the default version, in English |
| `README_zh-Hans.md` | Chinese translation of this file |
| `open_api.md` / `open_api_zh-Hans.md` | open API (English / Chinese) |
| `qa_zh-Hans.md` | FAQ for the 2.5 series (Chinese only) |
| `app/update.md` | upstream changelog (Chinese only) |
| `todo.md` | to-do list (Chinese only) |

Convention: **new documentation in this repository is written in English by default**; Chinese versions live in
files named `*_zh-Hans.md`.

## Build from source

1. JDK 17 and the Android SDK (platform 32). The SDK path in `local.properties` is not committed, so generate
   it yourself on the first build (Android Studio writes it automatically).
2. `app/libs/XposedBridgeAPI-89.jar` is already in the repository (Xposed API stub, `compileOnly`).
3. Build the debug package:

   ```bash
   ./gradlew assembleDebug
   # output: app/build/outputs/apk/debug/app-debug.apk
   ```

4. If you want to sign a release (the same key applies to your debug builds as well), first prepare **your own**
   key and an uncommitted configuration — **do not** reuse the upstream author's key and passwords:

   ```bash
   keytool -genkeypair -keystore sunshinekey.jks -alias demo \
     -keyalg RSA -keysize 2048 -validity 10000
   cp keystore.properties.example keystore.properties   # then fill in your own passwords
   ```

   `keystore.properties` and `*.jks` are excluded by `.gitignore` and are never committed, and
   `app/build.gradle` contains no hardcoded password. Note: once the key changes, packages already on the device
   cannot be upgraded in place because their signature differs — uninstall them first.

## Libraries

[AppIconLoader](https://github.com/zhanghai/AppIconLoader) ·
[Glide](https://github.com/bumptech/glide) ·
[RikkaX](https://github.com/RikkaApps/RikkaX) ·
[Shizuku](https://github.com/RikkaApps/Shizuku) ·
[TinyPinyin](https://github.com/promeG/TinyPinyin) ·
[Xposed](https://github.com/rovo89/Xposed) ·
[Room](https://developer.android.com/training/data-storage/room) ·
[AndroidX Preference](https://developer.android.com/jetpack/androidx/releases/preference) ·
[StringFog](https://github.com/megatronking/stringfog) ·
[Bugly](https://bugly.qq.com/) ·
[HiddenApiBypass](https://github.com/LSPosed/HiddenApiBypass)

## License

GPL-3.0, full text in [`LICENSE`](LICENSE).

```
Copyright (C) 2021-2023  sunshine0523  (upstream original work)
Copyright (C) 2026       qpdb1234      (modifications in this fork)

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.
```

This repository contains modifications relative to upstream; they are described in "What this fork changes"
above, and the exact dates and content can be found in the commit history.
