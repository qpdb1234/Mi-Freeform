# Mi-Freeform open API (continuously updated)

**English** | [简体中文](open_api_zh-Hans.md)

The Mi-Freeform call interface is now open to external apps. Choose one of the ways below to start a
freeform window provided by Mi-Freeform:

## 1. Open the app picker panel provided by Mi-Freeform
The activity that shows Mi-Freeform's app picker panel is
`com.mifreeform.freeform/com.moonlight.freeform.ui.floating.FloatingActivity`. You can launch that activity
from another app to open the picker panel.

<b>Note: only the "accessibility service" keep-alive mode requires Mi-Freeform to be running already; with the
foreground-service mode, launching this activity starts Mi-Freeform itself.</b>

## 2. Open a freeform window directly
Besides the way above, Mi-Freeform also receives "open a freeform window" commands from external apps
through a broadcast. A complete example:

```kotlin
//These two are the package name and activity name of "the app you want inside the freeform window".
//The clock app is used as the example here.
val packageName: String = "com.google.android.deskclock"
val activityName: String = "com.android.deskclock.DeskClock"
val userId: Int = 0
val intent = Intent("com.mifreeform.freeform.start_freeform").apply {
                    //You must name the explicit component, setPackage alone is not enough:
                    //since Android 8 an implicit broadcast (action only) is not delivered to a receiver
                    //declared statically in the manifest. Once the Mi-Freeform process has been swiped
                    //away or stopped, that form of sending does nothing at all — no error, no window.
                    component = ComponentName(
                        "com.mifreeform.freeform",
                        "com.moonlight.freeform.broadcast.StartFreeformReceiver"
                    )
                    //Package name of the app to start in the window, e.g. com.google.android.deskclock
                    putExtra("packageName", packageName)
                    //Activity name to start in the window. Note that this activity may have to be
                    //exported before it can be started.
                    putExtra("activityName", activityName)
                    //Optional, defaults to 0. For cases such as the system's "app clone", a userId
                    //can be given.
                    putExtra("userId", userId)
                }
context.sendBroadcast(intent)
```

The same path verified with adb (note how `-n` has to be written — an action alone does not get through):

```sh
am broadcast --user 0 \
  -n com.mifreeform.freeform/com.moonlight.freeform.broadcast.StartFreeformReceiver \
  -a com.mifreeform.freeform.start_freeform \
  --es packageName <package name> --es activityName <activity name> --ei userId 0
```
