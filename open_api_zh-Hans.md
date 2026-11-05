# 米窗开放API（持续更新）

[English](open_api.md)

米窗调用接口现已对外开放，你可以选择以下方式启动米窗提供的小窗：

## 1.打开米窗提供的应用选择界面
米窗提供的打开应用选择界面的活动为：`com.mifreeform.freeform/com.moonlight.freeform.ui.floating.FloatingActivity`。你可以通过其他应用调用该活动以打开应用选择界面。

<b>请注意：只有「无障碍服务」保活方式需要先让米窗跑起来；前台服务方式下调用该活动会把米窗直接拉起。</b>

## 2.直接打开米窗提供的小窗界面
除上述方式外，米窗还提供广播方式接收外部应用发送的打开小窗指令。具体例子如下：

```kotlin
//下面两个是「要在小窗里打开的那个应用」的包名与活动名，这里以时钟为例
val packageName: String = "com.google.android.deskclock"
val activityName: String = "com.android.deskclock.DeskClock"
val userId: Int = 0
val intent = Intent("com.mifreeform.freeform.start_freeform").apply {
                    //必须写显式组件，不能只写 setPackage：
                    //只有 action 的隐式广播在 Android 8 以后不会投给 manifest 里静态声明的接收器，
                    //米窗进程一旦被划掉/停止，这种发法就是彻底无反应（不报错、也不开小窗）。
                    component = ComponentName(
                        "com.mifreeform.freeform",
                        "com.moonlight.freeform.broadcast.StartFreeformReceiver"
                    )
                    //要启动小窗程序的包名：如com.mifreeform.freeform
                    putExtra("packageName", packageName)
                    //要启动小窗的活动名称，请注意，该活动可能需要对外暴露才可启动
                    putExtra("activityName", activityName)
                    //可选，默认为0。对于系统存在“应用分身”等情况，可以指定userId
                    putExtra("userId", userId)
                }
context.sendBroadcast(intent)
```

用 adb 验证同一条通路（注意 `setComponent` 的写法，只给 action 是发不出去的）：

```sh
am broadcast --user 0 \
  -n com.mifreeform.freeform/com.moonlight.freeform.broadcast.StartFreeformReceiver \
  -a com.mifreeform.freeform.start_freeform \
  --es packageName <目标包名> --es activityName <目标活动> --ei userId 0
```