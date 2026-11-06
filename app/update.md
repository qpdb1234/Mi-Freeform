# 2.5.7 beta
- Still haven't tracked down the black-border issue. If you run into it, please send me a screenshot of the MiFreeform settings screen and tell me which app shows the problem
- Fixed: MiFreeform crashing when locking the screen while programs are still running q220902.1
- Fixed (untested): some portrait-only apps also going landscape q220902.2
- Fixed (untested): attempted fix for the sidebar ending up in the middle of the screen q220902.3
- Added: open the sidebar via an activity

# 2.5.8 beta
Special note: MiFreeform is built close to stock Android, so running it on any customized ROM may cause unpredictable issues
Special note: MiFreeform is built close to stock Android, so running it on any customized ROM may cause unpredictable issues
Special note: MiFreeform is built close to stock Android, so running it on any customized ROM may cause unpredictable issues
- Optimized: the position where a landscape freeform window is suspended q220903.1
- Removed the compatibility-mode option
- Re-enabled XP blocking of "leaving the freeform window" on Android 10 devices
- Fixed: screen rotation inside the freeform window not working on Android 10 devices q220903.2

# 2.5.9 beta
- Fixed: attempted fix for the recorded freeform-window size being abnormal q220904.1
- Optimized: a wider allowed range for freeform-window scaling
- Fixed: MiFreeform crashing due to a negative dpi after adjusting dpi q220904.2
- Optimized: when rotating inside the freeform window, adjust if the rotated size exceeds the screen size q220904.3
- Fixed: abnormal freeform-window scaling after suspend-then-restore q220904.5
- Optimized: attempt more finger-following scaling q220904.6
- Optimized: a smaller bottom bar
- Added: manually adjust the freeform window's orientation q220904.7
- Optimized: attempt smoother scaling animation q220904.8

# 2.5.10
- Added: foreground-service keep-alive

# 2.5.12
- Optimized: multi-touch support

# 2.5.13
- Added: support for opening a freeform window from the recents screen
- Optimized: UI improvements
- Fixed: the app-choose screen possibly becoming unclosable when opened via an activity
- Fixed: resolution errors caused by screen rotation on some devices
- Fixed: screen rotation not being detected in some cases (e.g. rotating while on the recents screen)

# 2.5.14
- Optimized: some UI improvements
- Optimized: if the freeform window moves off-screen and becomes uncontrollable, tap the app in the sidebar again to move it back to the screen center q220917.4
- Fixed: incorrect QQ scaling after screen rotation q220910.1
- Optimized: QQ and WeChat now support scaling too q220917.1
- Removed the guide screen, though you can still view it manually q220917.2
- Optimized: the freeform window's state after locking the screen q220917.3

# 2.5.15
- Added: (possibly) support for Android 8-9
- Added: (test) freely adjust the freeform-window aspect ratio
- Added: adjustable sidebar transparency. The sidebar will be optimized further, stay tuned

# 2.5.16
- Fixed: incorrect aspect ratio after suspending the freeform window
- Fixed: the freeform-window content ratio sometimes not refreshing when the aspect ratio is freely adjusted

# 2.5.17
- Fixed: touch misalignment when the "remember position" option is enabled q220925.3

# 2.5.18
- Fixed: the sidebar not hugging the edge after screen rotation q221208.1
- Optimized: changed how MiFreeform opens from recents to tapping the app icon, after issue#7

## Investigation
- If an app crashes, onConfigurationChanged won't be called and the configuration doesn't change at all
- Treat touch as a first-class citizen to support multi-touch; also look into why multi-touch wasn't supported that way... q220906.1
