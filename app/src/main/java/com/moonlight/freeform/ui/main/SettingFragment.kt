package com.moonlight.freeform.ui.main

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.preference.Preference
import androidx.preference.PreferenceManager
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SeekBarPreference
import androidx.preference.SwitchPreference
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.moonlight.freeform.R
import com.moonlight.freeform.app.MiFreeform
import com.moonlight.freeform.service.ForegroundService
import com.moonlight.freeform.service.KeepAliveService
import com.moonlight.freeform.ui.choose_apps.ChooseAppsActivity
import com.moonlight.freeform.ui.freeform.FreeformView
import com.moonlight.freeform.ui.permission.PermissionActivity
import com.moonlight.freeform.ui.view.IntegerSimpleMenuPreference
import com.moonlight.freeform.utils.PermissionUtils
import com.moonlight.freeform.utils.SettingsBackup
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class SettingFragment : PreferenceFragmentCompat(), Preference.OnPreferenceClickListener,
    Preference.OnPreferenceChangeListener {

    private lateinit var sp: SharedPreferences
    private lateinit var accessibilityRFAR: ActivityResultLauncher<Intent>

    // SAF「备份保存到哪」的系统回调。CreateDocument 只在用户确认后回调，取消选择不回调。
    private lateinit var createBackupLauncher: ActivityResultLauncher<String>

    // SAF「从哪个备份文件恢复」的系统回调。
    private lateinit var openBackupLauncher: ActivityResultLauncher<Array<String>>

    // 顶部三张状态卡片（Xposed / Shizuku / 无障碍），从原首页迁移而来。
    private var statusCards: StatusCards? = null

    // 拖动把手改位置后，服务会把 sidebar_pos_x_dp / sidebar_pos_y_dp 写回 SP。
    // Preference 框架对外部 SP 变更不会自动刷新 UI，这里自己监听并同步滑条与数字芯片，
    // 做到「拖完把手，设置里的数字和滑条即时变」。同进程共用同一份 SharedPreferences 实例，
    // 服务的写入会直接回调到这里。
    private val spSyncListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        when (key) {
            // 把手尺寸改了，两个位置滑条的上限要跟着重算。这里不能挂在 onPreferenceChange 上：
            // SeekBarPreference.setValue（点数字芯片输入那条路）内部直接 persistInt，
            // 只有拖动才走 callChangeListener，所以只有 SP 回调能同时覆盖两种改法。
            SIDEBAR_WIDTH_DP, SIDEBAR_LENGTH_DP -> updateSidebarRange()
            SIDEBAR_POS_X, SIDEBAR_POS_Y -> {
                val pref = findPreference<SeekBarPreference>(key) ?: return@OnSharedPreferenceChangeListener
                val v = sp.getInt(key, pref.value)
                // 同值不写回，避免「用户拖滑条 -> persist -> 本监听 -> 再 persist」无意义往返
                if (pref.value != v) pref.value = v
            }
        }
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceManager.sharedPreferencesName = MiFreeform.APP_SETTINGS_NAME
        preferenceManager.sharedPreferencesMode = Context.MODE_PRIVATE
        setPreferencesFromResource(R.xml.settings, null)

        sp = requireActivity().getSharedPreferences(MiFreeform.APP_SETTINGS_NAME, Context.MODE_PRIVATE)
        accessibilityRFAR = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (PermissionUtils.isAccessibilitySettingsOn(requireContext())) {
                findPreference<IntegerSimpleMenuPreference>(SERVICE_TYPE)!!.setValueIndex(0)
            } else {
                sp.edit().putInt("service_type", ForegroundService.SERVICE_TYPE).apply()
                findPreference<IntegerSimpleMenuPreference>(SERVICE_TYPE)!!.setValueIndex(1)
                requireContext().startForegroundService(Intent(requireContext(), ForegroundService::class.java))
            }
        }

        // 备份：弹出系统文件选择器让用户选保存位置，拿到 Uri 后写设置 JSON。
        // 必须显式传 MIME：无参构造会用 "*/*"，建出来的文件在 MediaStore 里
        // 类型不匹配 application/json，恢复时选择器会拒绝选中（报「无应用可执行」）q261013.3。
        createBackupLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument(BACKUP_MIME_TYPE)) { uri: Uri? ->
            if (uri == null) return@registerForActivityResult
            exportSettings(uri)
        }

        // 恢复：弹出系统文件选择器让用户选要导入的备份文件。
        openBackupLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri == null) return@registerForActivityResult
            importSettings(uri)
        }

        findPreference<Preference>(QUICK_FLOATING_APP)!!.onPreferenceClickListener = this
        findPreference<Preference>(NOTIFICATION_FREEFORM_APPS)!!.onPreferenceClickListener = this
        findPreference<Preference>(RESET_OVERLAY_SETTING)!!.onPreferenceClickListener = this
        findPreference<Preference>(BACKUP_SETTINGS)!!.onPreferenceClickListener = this
        findPreference<Preference>(RESTORE_SETTINGS)!!.onPreferenceClickListener = this
        findPreference<SwitchPreference>(SHOW_FLOATING)!!.onPreferenceChangeListener = this
        findPreference<SwitchPreference>(NOTIFY_FREEFORM)!!.onPreferenceChangeListener = this
        findPreference<SwitchPreference>(HIDE_FROM_RECENT)!!.onPreferenceChangeListener = this
        findPreference<IntegerSimpleMenuPreference>(SERVICE_TYPE)!!.onPreferenceChangeListener = this
        findPreference<IntegerSimpleMenuPreference>(APP_LANGUAGE)!!.onPreferenceChangeListener = this
        // 一键重置全部默认设置：点击后弹确认框，确认再执行。
        // （旧的小窗缩放数据输入界面已删除，数字输入改由 NumericSeekBarPreference 自己承担）
        findPreference<Preference>(RESET_ALL_SETTINGS)!!.onPreferenceClickListener = this

        // 侧边栏 X/Y 轴锁定：锁定后对应的位置滑条禁用（同时服务侧也禁止该轴拖动）。
        findPreference<SwitchPreference>(SIDEBAR_LOCK_X)!!.onPreferenceChangeListener = this
        findPreference<SwitchPreference>(SIDEBAR_LOCK_Y)!!.onPreferenceChangeListener = this
        findPreference<SeekBarPreference>(SIDEBAR_POS_X)?.isEnabled = !sp.getBoolean(SIDEBAR_LOCK_X, true)
        findPreference<SeekBarPreference>(SIDEBAR_POS_Y)?.isEnabled = !sp.getBoolean(SIDEBAR_LOCK_Y, false)
        updateSidebarRange()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // 三张状态卡片作为列表头部。用 ConcatAdapter 而不是 addView：
        // PreferenceFragmentCompat 的列表是 RecyclerView，直接 addView 非 adapter 创建的
        // 子 View 会在 layout 时崩溃（RecyclerView 只接受通过 adapter 添加的 item）。
        val headerAdapter = StatusCardsAdapter(this) { holder ->
            val existing = statusCards
            if (existing != null && existing.container === holder) {
                // 头部 item 滚出屏幕被回收、滚回来又重新绑定，拿到的是同一个容器：
                // 卡片已经在里面了，只刷状态。以前这里每次都重建，
                // 反复滚动后顶部就堆出一大堆重复卡片。
                existing.refresh()
            } else {
                // 容器是新建的（首次或 RecyclerView 换了个 Holder），重新建三张卡片。
                statusCards = StatusCards(this, holder).also { cards ->
                    // 复用 fragment 已注册的 launcher（registerForActivityResult 必须在 STARTED 之前调用，
                    // 不能放到 onBindViewHolder 之后再注册，否则会抛 IllegalStateException）。
                    cards.onGoToAccessibilitySettings = {
                        accessibilityRFAR.launch(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    }
                    cards.init()
                    cards.refresh()
                }
            }
        }
        listView.adapter = headerAdapter.wrap(listView.adapter)
    }

    /**
     * 供状态卡片「去切换保活方式」跳转用：滚动到保活方式条目。
     */
    fun scrollToServiceType() {
        findPreference<IntegerSimpleMenuPreference>(SERVICE_TYPE)?.let {
            listView.scrollToPosition(getPreferencePosition(it))
        }
    }

    private fun getPreferencePosition(preference: Preference): Int {
        val screen = preferenceScreen
        for (i in 0 until screen.preferenceCount) {
            if (screen.getPreference(i) == preference) return i
        }
        return 0
    }

    override fun onPreferenceClick(preference: Preference): Boolean {
        when(preference.key) {
            QUICK_FLOATING_APP -> {
                requireActivity().startActivity(Intent(requireActivity(), ChooseAppsActivity::class.java).putExtra("type", 1))
            }
            NOTIFICATION_FREEFORM_APPS -> {
                requireActivity().startActivity(Intent(requireActivity(), ChooseAppsActivity::class.java).putExtra("type", 2))
            }
            RESET_OVERLAY_SETTING -> {
                sp.edit().apply {
                    val screenWidth = min(requireContext().resources.displayMetrics.heightPixels, requireContext().resources.displayMetrics.widthPixels)

                    putInt("floating_position_portrait_y", 0)
                    putInt("floating_position_landscape_y", 0)
                    putInt("sidebar_pos_x_dp", 0)
                    putInt("sidebar_pos_y_dp", 0)
                    putInt(FreeformView.REMEMBER_X, 0)
                    putInt(FreeformView.REMEMBER_Y, 0)
                    putInt(FreeformView.REMEMBER_LAND_X, 0)
                    putInt(FreeformView.REMEMBER_LAND_Y, 0)
                    putInt(FreeformView.REMEMBER_HEIGHT, (screenWidth * 1.6 * 0.75).roundToInt())
                    putInt(FreeformView.REMEMBER_LAND_HEIGHT, (screenWidth * 0.8).roundToInt())
                    apply()
                }
                Snackbar.make(requireView(), getString(R.string.reset_success), Snackbar.LENGTH_SHORT).show()
            }
            BACKUP_SETTINGS -> {
                // 系统文件选择器，让用户选备份文件存到哪里（下载、文档、网盘都行）。
                createBackupLauncher.launch(BACKUP_FILE_NAME)
            }
            RESTORE_SETTINGS -> {
                // 系统文件选择器，让用户挑一个之前导出的备份文件。
                openBackupLauncher.launch(arrayOf(BACKUP_MIME_TYPE, "application/json", "application/octet-stream"))
            }
            RESET_ALL_SETTINGS -> {
                // 需点击确认后才恢复默认，避免误触把全部自定义参数清空
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(getString(R.string.reset_all_settings))
                    .setMessage(getString(R.string.reset_all_settings_confirm))
                    .setNegativeButton(getString(R.string.cancel)) { dialog, _ -> dialog.dismiss() }
                    .setPositiveButton(getString(R.string.done)) { _, _ -> resetAllSettings() }
                    .show()
            }
        }
        return true
    }

    override fun onPreferenceChange(preference: Preference, newValue: Any): Boolean {
        when(preference.key) {
            SIDEBAR_LOCK_X -> {
                // 锁上 X 轴就禁用 X 位置滑条；newValue 尚未落库，直接用它算
                findPreference<SeekBarPreference>(SIDEBAR_POS_X)?.isEnabled = !(newValue as Boolean)
            }
            SIDEBAR_LOCK_Y -> {
                findPreference<SeekBarPreference>(SIDEBAR_POS_Y)?.isEnabled = !(newValue as Boolean)
            }
            SHOW_FLOATING -> {
                if (newValue as Boolean) {
                    when(sp.getInt(SERVICE_TYPE, ForegroundService.SERVICE_TYPE)) {
                        KeepAliveService.SERVICE_TYPE -> {
                            if (!PermissionUtils.isAccessibilitySettingsOn(requireContext())) {
                                Toast.makeText(requireContext(), getString(R.string.require_accessibility), Toast.LENGTH_SHORT).show()
                                startActivity(Intent(requireActivity(), PermissionActivity::class.java))
                                requireActivity().finish()
                                return false
                            }
                        }
                        ForegroundService.SERVICE_TYPE -> {
                            requireContext().startForegroundService(Intent(requireContext(), ForegroundService::class.java))
                        }
                    }
                }
            }
            NOTIFY_FREEFORM -> {
                if (newValue as Boolean && !PermissionUtils.checkNotificationListenerPermission(requireContext())) {
                    Toast.makeText(requireContext(), getString(R.string.require_notification), Toast.LENGTH_SHORT).show()
                    startActivity(Intent(requireActivity(), PermissionActivity::class.java))
                    requireActivity().finish()
                    return false
                }
            }
            HIDE_FROM_RECENT -> {
                //该开关靠 task 属性生效，而 task 属性只在任务创建时读一次，
                //所以拨完立即用新标志重建任务，不用“划掉米窗再重新进入”。
                applyHideFromRecentImmediately(newValue as Boolean)
            }
            APP_LANGUAGE -> {
                //语言在 attachBaseContext 里包 Context 生效（见 LocaleHelper），需要重建上下文才能读到新值。
                //回调时框架尚未落库（listener 返回 true 后才 persistInt），这里先自行 commit，
                //再重启前台服务（重新走 attachBaseContext）并 recreate 设置页；
                //无障碍服务由系统绑定，其文案下次重绑时才更新。
                sp.edit().putInt(APP_LANGUAGE, newValue as Int).commit()
                if (sp.getInt(SERVICE_TYPE, ForegroundService.SERVICE_TYPE) == ForegroundService.SERVICE_TYPE) {
                    requireContext().stopService(Intent(requireContext(), ForegroundService::class.java))
                    requireContext().startForegroundService(Intent(requireContext(), ForegroundService::class.java))
                }
                requireActivity().recreate()
            }
            SERVICE_TYPE -> {
                when(newValue as Int) {
                    KeepAliveService.SERVICE_TYPE -> {
                        if (PermissionUtils.isAccessibilitySettingsOn(requireContext())) {
                            requireContext().stopService(Intent(requireContext(), ForegroundService::class.java))
                        } else {
                            accessibilityRFAR.launch(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                            Toast.makeText(requireContext(), getString(R.string.require_start_accessibility), Toast.LENGTH_SHORT).show()
                            return false
                        }
                    }
                    ForegroundService.SERVICE_TYPE -> {
                        if (PermissionUtils.isAccessibilitySettingsOn(requireContext())) {
                            accessibilityRFAR.launch(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                            Toast.makeText(requireContext(), getString(R.string.require_stop_accessibility), Toast.LENGTH_SHORT).show()
                            return false
                        } else {
                            requireContext().startForegroundService(Intent(requireContext(), ForegroundService::class.java))
                        }
                    }
                }
            }
        }
        return true
    }

    /**
     * 让「从最近任务隐藏」开关当场生效。
     *
     * excludeFromRecents 是任务（task）级别的属性，系统只在**新建任务**的那一刻
     * 从启动 intent 里读一次，已经存在的任务改不了。
     * 实测：光靠 FLAG_ACTIVITY_CLEAR_TASK 是不行的 —— CLEAR_TASK 是「原地复用同一个任务、
     * 把里面的 Activity 清空」，任务本身和它当初的 root intent 都还在，
     * 所以新加的 EXCLUDE_FROM_RECENTS 根本不会被读到（logcat 里 START 带了 0x10008000，
     * 但 dumpsys activity recents 里任务照旧）。
     * 这里先 finishAndRemoveTask 把本 app 的任务真正删除（空任务会被系统从最近任务里抹掉），
     * 再新建一个任务，新任务才会按当前开关状态决定是否出现在最近任务里。
     * 冷启动那条路径本来就是对的：SplashActivity 在清单里单独用了 taskAffinity=":splash"
     * 且 excludeFromRecents=true，所以它跳 MainActivity 时必然是一个新建任务，
     * 新任务的隐藏属性正好由启动 intent 的 flag 决定。
     * 设置值已经由 Preference 框架写进 SharedPreferences，重建后读到的就是新状态。
     */
    private fun applyHideFromRecentImmediately(hide: Boolean) {
        val appContext = requireContext().applicationContext
        var flags = Intent.FLAG_ACTIVITY_NEW_TASK
        if (hide) {
            flags = flags or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
        }
        // 等开关动画落完再重建，否则看不到拨动的反馈；
        // 用主线程 Handler 而不是 view.postDelayed：Activity 已经 finish，view 不可靠。
        Handler(Looper.getMainLooper()).postDelayed({
            runCatching {
                appContext.startActivity(
                    Intent(appContext, MainActivity::class.java).addFlags(flags)
                )
            }
        }, 400)
        runCatching { requireActivity().finishAndRemoveTask() }
    }

    /** 把全部设置导出到用户在系统文件选择器里选中的位置。 */
    private fun exportSettings(uri: Uri) {
        try {
            val out = requireContext().contentResolver.openOutputStream(uri, "wt")
            if (out == null) {
                Toast.makeText(requireContext(), getString(R.string.backup_failed), Toast.LENGTH_SHORT).show()
                return
            }
            out.use {
                if (SettingsBackup.export(requireContext(), it)) {
                    Toast.makeText(requireContext(), getString(R.string.backup_success), Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(requireContext(), getString(R.string.backup_failed), Toast.LENGTH_SHORT).show()
                }
            }
        } catch (e: Throwable) {
            // 用户选了只读位置、或被系统拒绝访问时，contentResolver 会抛
            // SecurityException / FileNotFoundException，不接住会直接崩溃。
            android.util.Log.w("SettingFragment", "export_failed", e)
            Toast.makeText(requireContext(), getString(R.string.backup_failed), Toast.LENGTH_SHORT).show()
        }
    }

    /** 从用户在系统文件选择器里选中的备份文件恢复设置。 */
    private fun importSettings(uri: Uri) {
        try {
            requireContext().contentResolver.openInputStream(uri)?.use { input ->
                if (SettingsBackup.import(requireContext(), input)) {
                    // 设置页是从 SharedPreferences 取值的，恢复后要重绘才能立刻看到新值。
                    view?.post {
                        setPreferencesFromResource(R.xml.settings, null)
                        onResume()
                    }
                    Toast.makeText(requireContext(), getString(R.string.restore_success), Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(requireContext(), getString(R.string.restore_failed), Toast.LENGTH_SHORT).show()
                }
                return
            }
            Toast.makeText(requireContext(), getString(R.string.restore_failed), Toast.LENGTH_SHORT).show()
        } catch (e: Throwable) {
            // 选了打不开的文件、或没有读权限时，contentResolver 会抛异常，不接住会崩溃。
            android.util.Log.w("SettingFragment", "import_failed", e)
            Toast.makeText(requireContext(), getString(R.string.restore_failed), Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 一键重置：清空 SharedPreferences 里全部设置并重新套用 xml 默认值。
     * 快捷应用 / 通知接管应用列表存在 Room 数据库里，不在 SP，所以不受影响、会被保留；
     * 另外保留 version / version_privacy，避免重置后重启又弹引导 / 隐私页。
     *
     * 注意：不能在点击回调里直接调 setPreferencesFromResource()，
     * 因为此时 RecyclerView 正在处理事件分发，内部 notifyDataSetChanged() 会抛
     * IllegalStateException（Cannot call this method while RecyclerView is computing
     * a layout or scrolling）。用 recreate() 让 Activity 干净重建，
     * Fragment 生命周期重新走一遍，listener 和 ConcatAdapter 都会正确恢复。
     */
    private fun resetAllSettings() {
        val keepVersion = sp.getInt("version", MiFreeform.VERSION)
        val keepPrivacy = sp.getInt("version_privacy", MiFreeform.VERSION_PRIVACY)
        sp.edit()
            .clear()
            .putInt("version", keepVersion)
            .putInt("version_privacy", keepPrivacy)
            .commit()
        // 重新按 android:defaultValue 落一遍默认值
        PreferenceManager.setDefaultValues(requireContext(), MiFreeform.APP_SETTINGS_NAME, Context.MODE_PRIVATE, R.xml.settings, true)
        Toast.makeText(requireContext(), R.string.reset_all_success, Toast.LENGTH_SHORT).show()
        // 把重建延后到下一帧：本调用来自确认弹窗的按钮回调，此刻弹窗正在 dismiss、
        // 设置列表的 RecyclerView 可能还在完成本次事件分发/布局。同步 recreate() 在部分
        // 机型上会与弹窗销毁或布局过程冲突抛 IllegalStateException 而闪退；post 之后
        // 点击事件与 dismiss 都已结束，再用 runCatching 兜底，确保不会因重建失败而崩。
        val root = view
        if (root != null) {
            root.post { runCatching { if (isAdded) requireActivity().recreate() } }
        } else {
            runCatching { requireActivity().recreate() }
        }
    }

    /**
     * 按当前屏幕和把手尺寸重算 X/Y 位置滑条的区间，把「拖了也没反应」的死档收掉。
     *
     * 服务侧的几何（ForegroundService / KeepAliveService 一致）是：
     *   x = positionX * max(0, (screenWidth - width) / 2 - offsetX)
     *   y = clamp(offsetY, -screenHeight / 2, screenHeight / 2)
     * 于是：
     * - offsetX 一旦超过 (screenWidth - width) / 2，max(0, …) 恒为 0，把手只会钉在屏幕正中，
     *   滑条再往右拉没有任何变化；
     * - offsetY 一旦超过 (screenHeight - length) / 2，窗口就被系统卡在应用区上下沿，同样白拉。
     * 这两个上限跟屏幕方向、把手宽高绑定，XML 里只能写一个固定兜底值，这里在进页面、
     * 转屏和改尺寸时重算；顺带把以前存越界的旧值（比如旧版本留下的）夹回区间内。
     */
    private fun updateSidebarRange() {
        val widthDp = sp.getInt(SIDEBAR_WIDTH_DP, DEFAULT_SIDEBAR_WIDTH_DP)
        val lengthDp = sp.getInt(SIDEBAR_LENGTH_DP, DEFAULT_SIDEBAR_LENGTH_DP)
        val metrics = resources.displayMetrics
        val density = metrics.density
        // 与两个服务里的取值方式保持一致：按当前方向把 displayMetrics 配成 screenWidth / screenHeight
        val portrait = resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
        val screenWidth = if (portrait) min(metrics.widthPixels, metrics.heightPixels)
        else max(metrics.widthPixels, metrics.heightPixels)
        val screenHeight = if (portrait) max(metrics.widthPixels, metrics.heightPixels)
        else min(metrics.widthPixels, metrics.heightPixels)

        val widthPx = (widthDp * density).roundToInt()
        val lengthPx = (lengthDp * density).roundToInt()
        val maxXDp = ((screenWidth - widthPx) / 2f / density).roundToInt().coerceAtLeast(0)
        val maxYDp = ((screenHeight - lengthPx) / 2f / density).roundToInt().coerceAtLeast(0)

        // 先定区间再夹值：区间没更新就写 value 会被旧区间夹错
        findPreference<SeekBarPreference>(SIDEBAR_POS_X)?.let { pref ->
            pref.min = 0
            pref.max = maxXDp
            val clamped = pref.value.coerceIn(0, maxXDp)
            if (clamped != pref.value) pref.value = clamped
        }
        findPreference<SeekBarPreference>(SIDEBAR_POS_Y)?.let { pref ->
            pref.min = -maxYDp
            pref.max = maxYDp
            val clamped = pref.value.coerceIn(-maxYDp, maxYDp)
            if (clamped != pref.value) pref.value = clamped
        }
    }

    override fun onStart() {
        super.onStart()
        sp.registerOnSharedPreferenceChangeListener(spSyncListener)
    }

    override fun onStop() {
        sp.unregisterOnSharedPreferenceChangeListener(spSyncListener)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        if (PermissionUtils.isAccessibilitySettingsOn(requireContext())) {
            findPreference<IntegerSimpleMenuPreference>(SERVICE_TYPE)!!.setValueIndex(0)
        }
        // 首页已删除，三张卡片的状态刷新挪到设置页
        statusCards?.refresh()
        // 转屏后屏幕宽高对调，位置滑条的区间跟着重算
        updateSidebarRange()
    }

    companion object {
        private const val QUICK_FLOATING_APP = "quick_floating_app"
        private const val NOTIFICATION_FREEFORM_APPS = "notification_freeform_apps"
        private const val SHOW_FLOATING = "show_floating"
        private const val NOTIFY_FREEFORM = "notify_freeform"
        // 从最近任务隐藏米窗自身卡片（settings.xml 里 key 为 hide_from_recent）
        private const val HIDE_FROM_RECENT = "hide_from_recent"
        private const val RESET_OVERLAY_SETTING = "reset_overlay_setting"
        // 一键重置全部默认设置条目（settings.xml 里 key 为 reset_all_settings）
        private const val RESET_ALL_SETTINGS = "reset_all_settings"
        // settings.xml 里 backup_settings / restore_settings 两个条目的 key，
        // 原来这里漏了定义，findPreference<Preference>(BACKUP_SETTINGS) 编不过。
        private const val BACKUP_SETTINGS = "backup_settings"
        private const val RESTORE_SETTINGS = "restore_settings"
        // SAF 弹出时默认的文件名 / 类型，用户仍可在选择器里改名、换位置。
        private const val BACKUP_FILE_NAME = "MiFreeform-settings.json"
        private const val BACKUP_MIME_TYPE = "application/json"
        private const val SERVICE_TYPE = "service_type"
        //应用内语言切换条目（settings.xml 里 key 为 app_language，值域见 LocaleHelper）
        private const val APP_LANGUAGE = "app_language"
        private const val FREEFORM_SCALE_PORTRAIT = "freeform_scale_portrait"
        private const val FREEFORM_SCALE_LANDSCAPE = "freeform_scale_landscape"
        // 侧边栏几何设置：X/Y 位置滑条与 X/Y 轴锁定开关
        private const val SIDEBAR_POS_X = "sidebar_pos_x_dp"
        private const val SIDEBAR_POS_Y = "sidebar_pos_y_dp"
        private const val SIDEBAR_LOCK_X = "sidebar_lock_x"
        private const val SIDEBAR_LOCK_Y = "sidebar_lock_y"
        // 把手尺寸条目，key 与默认值都要和 settings.xml、两个服务里的 getIntSp 保持一致
        private const val SIDEBAR_WIDTH_DP = "sidebar_width_dp"
        private const val SIDEBAR_LENGTH_DP = "sidebar_length_dp"
        private const val DEFAULT_SIDEBAR_WIDTH_DP = 8
        private const val DEFAULT_SIDEBAR_LENGTH_DP = 64
    }
}