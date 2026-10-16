# Mi-Freeform 米窗（2.5.38 维护分支）

![License](https://img.shields.io/badge/license-GPL--3.0-blue)

> **Fork 声明 / Fork notice**
> 本仓库 fork 自 [sunshine0523/Mi-Freeform](https://github.com/sunshine0523/Mi-Freeform)（GPL-3.0），
> 基线是其 `main` 分支（2.5.18 / versionCode 2518，末次提交 2023-04-27）。
> 上游原作者 sunshine0523 拥有原始代码的全部著作权；本分支的修改同样以 **GPL-3.0** 发布。
> This repository is a fork of [sunshine0523/Mi-Freeform](https://github.com/sunshine0523/Mi-Freeform) under GPL-3.0.
> The baseline is upstream's `main` branch; all upstream copyright is retained, and these modifications are distributed under the same license.

Mi-Freeform（米窗）是一款通过 Shizuku / Sui 激活的 Android 第三方小窗应用，可以把大多数应用以小窗（freeform）形式显示，支持 Android 8.1 起。

## 系统兼容性 Compatibility

> **安装前请先看这里 / read before installing**
>
> 本分支的实测覆盖情况如下，**未按下列版本实测过的系统不保证可用**：
>
> | 系统版本 | 状态 |
> | --- | --- |
> | Android 10 | ✅ 正常 |
> | Android 12 | ✅ 正常 |
> | Android 14 | ❌ **多项功能异常**，暂不建议使用 |
>
> 在 Android 14 上已知会有多项功能失效（小窗无法打开、侧边栏与保持小窗等行为异常）。
> 原因是本项目依赖 freeform 窗口模式、隐藏 API 与 Shizuku 提权，而 Android 14 收紧了后台启动
> Activity、前台服务类型与非接口（hidden API）限制，2.5.x 的实现尚未跟进。
>
> Android 8.1–9、11、13 及 15 以上**未实测**，不代表可用。
> Android 14 的适配在单独的代码副本中进行，尚未完成；完成后会在本 README 与 Releases 里说明。
>
> English: verified working on **Android 10 and 12**; on **Android 14 many features are broken**
> because API 34 tightened background activity launches, foreground service types and
> hidden-API restrictions, which this 2.5.x codebase has not caught up with yet.
> Other versions are untested. Android 14 adaptation is being worked on in a separate copy of
> the source tree.


Current support:
- 通过全局侧边栏把收藏应用以小窗打开 / open favorited apps as a freeform window from the global sidebar
- 通过常驻通知打开收藏应用 / open them from a persistent notification
- 通过快捷设置磁贴打开 / open them from a QS tile
- 让发出通知的应用直接以小窗打开 / make apps that post notifications open in freeform
- 开放 API（见 `open_api_zh-Hans.md`）/ an open API for external control

---

## 这个分支改了什么 What this fork changes

基线 2.5.18 → 当前 **2.5.38 / versionCode 2551**。主要改动按主题归类：

**标识与共存**
- `applicationId` 改为 `com.mifreeform.freeform`，源码命名空间为 `com.moonlight.freeform`，因此**可以与官方版并存安装**、互不覆盖。
- 无障碍等按包名匹配的地方（`res/xml/accessibility_config.xml` 的 `packageNames`）已同步。

**小窗行为**
- **横屏 / 竖屏分别记忆尺寸**，并且每个应用的小窗各记各的；关闭小窗后该记忆立即清除（`FreeformView` 的 `userPortraitHeight` / `userLandscapeHeight`）。旋转往返不再改变大小。
- 通知点击**立即**打开侧边栏，去掉原先的延迟。
- 触控映射与窗口几何从 `FreeformView` 中拆出为 `FreeformTouchMapper` / `FreeformWindowGeometry`，并配套单元测试。
- 新增小窗截图（`ScreenshotHelper`）与迷你悬浮球（`MiniBallView`）。

**界面**
- 小窗顶栏三个按钮的动作可自定义：关闭 / 全屏 / 收起 / 返回 / 截屏 / 旋转 / 居中（`view_top_bar.xml`、`TopBarAction`）。
- 双击底栏中部按钮的动作同样可配置（默认旋转小窗）。
- 主页改为状态卡片（`StatusCards`），设置项布局统一。

**开屏与加载画面**
- **移除所有开屏 / loading 动画**：删掉 Lottie 依赖、`res/raw/loading.json` 及各布局里的 loading 视图，`SplashActivity` 只做路由。设置里的「快捷应用」等入口进入后直接渲染列表。APK 体积随之从约 12.4 MB 降到约 7.5 MB。

**设置页**
- 数值型设置统一为 `NumericSeekBarPreference`（点击直接输入 + 灰色底 + 范围校验）。
- 部分设置项的键名改过一次，首次启动会自动把旧键的值搬迁到新键（`SettingsKeyMigration`），
  恢复旧版导出的备份文件时也会同样搬迁，不会把已调好的设置退回默认值。
- 一键重置默认设置；修复其在部分厂商 ROM（`clear()` 回调 key=null）上的闪退。
- 设置导入 / 导出（`SettingsBackup`）。
- 应用内语言切换（`LocaleHelper`），不依赖系统设置。
- 侧边栏把手几何可配置，并支持锁定。

**构建**
- compileSdk 32 / targetSdk 32 / minSdk 27，AGP 7.4.2 + Gradle 7.5 + Kotlin 1.7.10。
- debug 包额外打包 x86 / x86_64，方便在模拟器上验证；release 仍只打 arm。
- 签名材料从仓库根的 `keystore.properties` 读取，**该文件与 `*.jks` 私钥都不入库**，所以仓库里没有任何口令与私钥（模板见 `keystore.properties.example`）：
  - 没配 keystore 时，debug 包自动使用 AGP 标准调试证书（`CN=Android Debug`），**克隆下来不配任何密钥就能构建出可安装的调试包**；
  - 没配 keystore 时 release 不设 `signingConfig`，产出 `app-release-unsigned.apk`（未签名，**不可直接安装**）；
  - 上一版把签名写死成上游本机的 keystore，等于用 `CN=sunshine` 的名义给本分支的包签名，现已避开。
- `app/src/test/` 下的单测可用 `./gradlew testDebugUnitTest` 运行。

---

## 下载 Download

见本仓库 [Releases](../../releases)。上游原版：[sunshine0523/Mi-Freeform releases](https://github.com/sunshine0523/Mi-Freeform/releases/)。

⚠️ 下载前请先看上面的[系统兼容性](#系统兼容性-compatibility)：Android 14 上多项功能异常。

激活需要 [Shizuku](https://shizuku.rikka.app/) 或 [Sui](https://github.com/topjohnwu/Sui)，侧边栏等功能还需要无障碍权限。

## 仓库内文档 Documentation

| 文件 | 内容 |
| --- | --- |
| `qa_zh-Hans.md` | 2.5 版本常见问题 |
| `open_api_zh-Hans.md` / `open_api.md` | 开放 API（中 / 英） |
| `app/update.md` | 历史更新记录（上游） |
| `todo.md` | 待办 |

## 从源码构建 Build from source

1. JDK 17 与 Android SDK（platform 32）。`local.properties` 里的 SDK 路径不入库，首次构建请自行生成（Android Studio 会自动写）。
2. `app/libs/XposedBridgeAPI-89.jar` 已在仓库中（Xposed API stub，`compileOnly`）。
3. 构建调试包：

   ```bash
   ./gradlew assembleDebug
   # 产物：app/build/outputs/apk/debug/app-debug.apk
   ```

4. 若要签 release（产出的 debug 包同样适用），先备一份**自己的**密钥与不入库的配置（**不要**沿用上游作者的密钥与口令）：

   ```bash
   keytool -genkeypair -keystore sunshinekey.jks -alias demo \
     -keyalg RSA -keysize 2048 -validity 10000
   cp keystore.properties.example keystore.properties   # 然后填上你自己的口令
   ```

   `keystore.properties` 与 `*.jks` 已被 `.gitignore` 排除，不会提交；`app/build.gradle`
   里没有写死的口令。注意：密钥一旦更换，设备上已有的旧包因签名不一致无法覆盖安装，需先卸载。

## 第三方库 Libraries

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

GPL-3.0，全文见 [`LICENSE`](LICENSE)。

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

本仓库包含相对上游的修改，修改说明见上文「这个分支改了什么」，具体日期与内容可在提交历史中查看。
