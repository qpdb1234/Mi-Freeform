# Mi-Freeform 米窗（2.5.38 维护分支）

[English](README.md) | **简体中文**

![License](https://img.shields.io/badge/license-GPL--3.0-blue)

> **Fork 声明**
> 本仓库 fork 自 [sunshine0523/Mi-Freeform](https://github.com/sunshine0523/Mi-Freeform)（GPL-3.0），
> 基线是其 `main` 分支（2.5.18 / versionCode 2518，末次提交 2023-04-27）。
> 上游原作者 sunshine0523 拥有原始代码的全部著作权；本分支的修改同样以 **GPL-3.0** 发布。

Mi-Freeform（米窗）是一款通过 Shizuku / Sui 激活的 Android 第三方小窗应用，可以把大多数应用以小窗（freeform）形式显示，支持 Android 8.1 起。

## 系统兼容性

> **安装前请先看这里**
>
> 本分支的实测覆盖情况如下，**未按下列版本实测过的系统不保证可用**：
>
> | 系统版本 | 状态 |
> | --- | --- |
> | Android 10 | ✅ 正常（AOSP API 29 模拟器实测，2026-11） |
> | Android 12 | ✅ 正常（AOSP API 31 模拟器实测，2026-11） |
> | Android 14 | ⚠️ **部分正常**（Android 14 真机（小米 MIX 2S，类原生 LineageOS）+ AOSP 14 模拟器实测）：仅下面「已验证」清单内的功能可用，其余**未逐项验证** |
>
> **一个安装包同时覆盖这三个版本**。没有分版本构建，也没有 product flavour：同一个 debug 包
> （versionCode 2551 / 2.5.38）分别装到 API 29、API 31、API 34 三台模拟器上跑了同一套检查：
>
> | 检查项 | Android 10 | Android 12 | Android 14 |
> | --- | --- | --- | --- |
> | 缺权限时直接跳系统悬浮窗授权页 | ✅ 跳 | ✅ 跳 | ✅ **不跳**（设计如此） |
> | 侧边栏把手与面板可见 | ✅ | ✅ | ✅ |
> | 小窗（虚拟屏 + Activity） | ✅ | ✅ | ✅ |
> | 触摸注入小窗 | ✅ | ✅ | ✅ |
> | 重新授权后系统仍强制隐藏的悬浮窗 | n/a | 0 | 已修，见下 |
>
> **Android 14 上已验证可用**（2026-11，跑的是当前 `main` 构建出的包）：小窗的打开 / 关闭 / 拖拽改尺寸、
> 顶栏关闭按钮、返回键、侧边栏把手、侧边栏面板、常驻通知与快捷设置磁贴入口。
> Android 14 真机（类原生 LineageOS）回归为小窗 3 轮、把手 2 轮、面板 3 轮，均未再造出下面那个状态。
>
> **Android 14 上唯一确认的系统级缺陷**（自 commit `7629360` 起已在 `main` 上修掉，2026-11；
> 写在这里是为了让还在用旧包的人认出来）：
> 小窗、侧边栏把手、侧边栏面板会
> **同时**从屏幕上消失（应用还活着，只是什么都不显示）。原因不是 hidden API 被收紧，而是：
> Android 14 上，当悬浮窗权限未生效、用户点了一次开窗时，**旧版会在拒绝分支里自动跳一次
> 系统「显示在其他应用界面上层」设置页**——这一跳会让该进程之后创建的**所有**悬浮窗
> （包括换成无障碍窗口类型）被系统强制隐藏，且重新授予权限不会解除。
> 真机定量：保留这一跳 4/4 造出该状态，只去掉这一跳（其余照旧）0/4。
> 单独查权限、单独跳那个设置页（不伴随一次开窗尝试）都不会触发。
>
> **恢复方法**（按顺序试）：
> 1. **彻底重启米窗**：在最近任务里划掉再打开（不是退回后台）——实测有效；
> 2. 仍不行就**重启手机**；
> 3. 实测**无效**：重新授予悬浮窗权限、把窗口关掉重开、转屏、亮灭屏、以及进入该状态后再开无障碍保活。
>
> **预防方法**：先给悬浮窗权限再开第一扇小窗；或开启无障碍保活（窗口走不需要该权限的类型，
> 就不会发出被拒的尝试）。**Android 14 及以上已不再自动跳转**：缺权限时只写一条诊断记录 + 弹 Toast，
> 常驻通知正文会显示原因并多出一个「去授权」按钮，由用户自己点进权限页。若用户把米窗的通知
> 整体关掉了（通知上没有按钮可点），Toast 与记录会改成指向**不依赖通知**的那条路：从桌面打开
> 米窗，在权限申请页里开启悬浮窗权限。Android 8.1–13 **保留这一跳**（`Build.VERSION.SDK_INT < 34`），
> 因为它在这些版本上没有代价：API 31 模拟器实测，跳完再重新授权，系统里一条强制隐藏记录都没有——
> 这些版本照旧享受一键直达。
> 上表之外的版本（Android 8.1–9、11、13、15+）**未实测**，不代表可用。
> **Android 14 上仍然没做的适配**（所以是「部分正常」而不是「正常」）：
> - Xposed 路线在 Android 14 上**一个钩子都不装**——`HookFramework.handleLoadPackage` 里的
>   `when (Build.VERSION.SDK_INT)` 只写到 33，没有 34 分支。因此「勾选 Xposed 阻止应用跳出小窗
>   到全屏」这类依赖 Xposed 的功能在 14 上不生效（未验证，因为钩子根本没装）；
>   走 Shizuku / Sui 的对应功能不受影响。
> - 除该 Android 14 真机（小米 MIX 2S，类原生 LineageOS）与 AOSP 14 模拟器之外的 Android 14 设备**未实测**。
> - 输入法、剪贴板、多屏等专项在 14 上**未逐项验证**。
> Android 14 的适配现在**就在 `main` 上**（commit `7629360`）：权限闸门、只在该跳转真正有害时才关掉
> 的 SDK-34 守卫、常驻通知的「去授权」按钮、能报出「是哪个应用要求隐藏窗口」的看门狗，以及最近任务入口
> 背后那个静态声明的接收器。**从该 commit 之后构建的包都带着这些**；在此之前的产物没有 —— `v2.5.38`
> 这个 tag 落在本次合并的前一个 commit 上，所以从 Releases 下载的包可能版本号同样是 2.5.38 /
> versionCode 2551，行为却仍是上面描述的老样子；拿不准时请从源码构建，或与那个 commit 比对。

当前支持：
- 通过全局侧边栏把收藏应用以小窗打开
- 通过常驻通知打开收藏应用
- 通过快捷设置磁贴打开
- 让发出通知的应用直接以小窗打开
- 开放 API（见 `open_api_zh-Hans.md`）

---

## 这个分支改了什么

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

## 下载

见本仓库 [Releases](../../releases)。上游原版：[sunshine0523/Mi-Freeform releases](https://github.com/sunshine0523/Mi-Freeform/releases/)。

⚠️ 下载前请先看上面的[系统兼容性](#系统兼容性)：Android 14 属于**部分正常**，
已验证的功能与尚未适配的部分（含 Xposed 在 14 上不装钩子）都在同一节里列着。

激活需要 [Shizuku](https://shizuku.rikka.app/) 或 [Sui](https://github.com/topjohnwu/Sui)，侧边栏等功能还需要无障碍权限。

## 仓库内文档

| 文件 | 内容 |
| --- | --- |
| `README.md` | 本说明的英文版（仓库默认版本） |
| `README_zh-Hans.md` | 简体中文版（本文件） |
| `open_api.md` / `open_api_zh-Hans.md` | 开放 API（英 / 中） |
| `qa.md` / `qa_zh-Hans.md` | 2.5 版本常见问题（英 / 中） |
| `app/update.md` | 历史更新记录（上游） |
| `todo.md` | 待办 |

约定：本仓库**新增文档默认用英文撰写**，中文版放在 `*_zh-Hans.md` 文件里。

## 从源码构建

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

## 第三方库

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
