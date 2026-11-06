# Mi-Freeform 2.5 FAQ (continuously updated)

[**English**](qa.md) | [简体中文](qa_zh-Hans.md)

## 1. Some pages flash and disappear, and after that the sidebar and the floating windows never open again

If you use **foreground-service keep-alive**, and the page you opened is one of the important system
screens — **system settings, an authorization screen, the second-space picker on some devices** and the
like — this happens. Fix: switch to the accessibility keep-alive service.

> **Android 14 note (measured 2026-10)**: on Android 14 this has a well-defined mechanism;
> the recovery steps are item **8** below. Note that once the "never opens again" state is already there,
> switching to accessibility keep-alive does **not** bring the windows back (the accessibility window type
> is hidden as well) — recover with item 8 first. There its role is **prevention**, not cure.
> The action that triggers it has been removed from `main` **per Android version** since commit
> `7629360` (2026-11): Android 14 and above no longer jumps anywhere automatically, Android 8.1–13 still
> opens the permission page directly (measured: after that jump, re-granting leaves no force-hidden record
> there). Eight regression rounds after the change failed to reproduce the state.

## 2. Tapping a page sends the app fullscreen

Try enabling the module for the system framework in Xposed and rebooting. On Android 12+ you can instead
turn on the "Use Shizuku / Sui to stop apps jumping out of the freeform into fullscreen" option.

## 3. Cannot open a freeform / stuck on the guide / crashes when opening a freeform

The system most likely has Vulkan forced on. See the wording on the in-app guide page.

## 4. After opening a freeform the app does not fill it and looks like a Windows window

Go to system Settings → Developer options and turn off "Force desktop mode".

## 5. Mi-Freeform gets backgrounded (killed)

Some custom ROMs do this; lock the app in Recents. Mi-Freeform stays alive through the accessibility
service or a foreground service — if both get killed, suspect the ROM, or try the other keep-alive mode.

## 6. The UI scale is too large / too small

Freeform scaling is configured for portrait and landscape separately; adjust both.

## 7. The layout looks wrong after rotating the screen

This shows up mainly on low-performance devices. Turn off the "Change freeform scaling on rotation"
setting.

## 8. Android 14 (older builds): the freeform, the handle and the panel all vanish at once, and re-granting does nothing

This only appears on **Android 14 (measured on a Xiaomi MIX 2S running near-stock LineageOS)** and only on **builds
that still auto-jump to the system settings page from the rejection branch**. The code on `main` dropped
that jump for Android 14+ starting with commit **`7629360`** (2026-11), but **the 2.5.38 installers
released before that merge still belong to this class** — the item is kept here so people who hit it can
recognise it. The freeform window, the sidebar handle and the sidebar panel disappear from the screen
**at the same time**; the app is still alive, it just shows nothing, and no error is reported anywhere.

**Cause**: on those builds, when the overlay permission was not in effect and the user tapped to open a
window, the rejection branch **auto-opened the system "display over other apps" settings page once**.
That single jump is what Android 14 records: every overlay window the process creates afterwards is
marked force-hidden. Measured on device: keeping the jump 4/4 poisoned, removing only the jump (everything
else unchanged) 0/4. Querying the permission alone, or opening that page alone (with no rejected window
attempt), does not trigger it. The marker is kept per process, so **re-granting the permission does not
undo it**; neither does closing and reopening the window, rotating, or a screen off/on cycle, and switching
to the accessibility keep-alive does not help either (that window type is hidden too). **Builds made after
`7629360`** write "the freeform was hidden by the system" into the notification and name the app that asked
for it; **older releases do not — the windows still vanish silently**.

**Recovery** (try in this order):
1. **Fully restart Mi-Freeform**: swipe it away in Recents and open it again (just backgrounding it is not
   enough) — works in most cases;
2. if that still fails, **reboot the phone**;
3. measured ineffective (do not keep trying): re-granting the overlay permission, closing and reopening the
   window, rotating, screen off/on, and enabling the accessibility keep-alive only after the state is
   already there.

**Prevention**: grant the overlay permission before opening the first window, or use the **accessibility
keep-alive**. The current `main` (since commit **`7629360`**) adds three safety nets (**builds released
before that merge have none of them**):
(i) the rejection branch **no longer jumps anywhere** — it writes one diagnostic record and shows a toast,
and the persistent notification explains the reason and carries a **"grant permission"** action that you
tap yourself (both taps are yours); if you switched Mi-Freeform notifications off entirely (so there is no
action to tap), the toast and the record instead point at the path that **does not depend on
notifications**: open Mi-Freeform from the launcher and turn the overlay permission on in its permission
page; (ii) nothing is created while the permission is missing — not even the virtual display;
(iii) if the windows are already hidden, the window is closed automatically and the persistent notification
says so (no swarm of invisible windows left behind).
The wording of the diagnostic records also goes through resource files now, so a Chinese device logs
Chinese and an English device logs English instead of an English title over a Chinese body. And the jump was
only removed where it actually costs something: Android 8.1–13 keep it, because on the API 31 emulator a
re-grant after that jump left **not a single** force-hidden overlay record — those versions should not pay
for an Android 14 problem.

> **One installer covers Android 10 / 12 / 14**, with no per-version build: the same debug APK
> (2.5.38 / versionCode 2551) was checked on the API 29, API 31 and API 34 emulators for the sidebar handle
> and panel, the freeform window (virtual display + activity) and touch injection — all fine. The behaviour
> on a missing permission forks by version (10 / 12 jump to the permission page, 14 does not). Android 14 is
> still listed as **partially working** (Android 14 phone + AOSP 14 emulator): verified are opening, closing
> and resizing freeform windows, the top-bar close button, the back key, the sidebar handle and panel, and
> the notification and quick-settings-tile entries; **not yet adapted** is the Xposed route — the version
> branch in `HookFramework` stops at 33 and installs no hook at all on 14, so Xposed-dependent options are
> inert there (the Shizuku / Sui equivalents are unaffected). No Android 14 device besides those two has
> been tested. The adaptation is merged into `main` (commit `7629360`); see the Compatibility section of the
> README for details.
