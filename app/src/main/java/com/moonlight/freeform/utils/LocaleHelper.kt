package com.moonlight.freeform.utils

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import com.moonlight.freeform.app.MiFreeform
import java.util.Locale

/**
 * 应用内语言切换（不修改系统语言）。
 *
 * 原理：项目排除 androidx.appcompat、用 rikka 的 1.5.0 分叉，没有
 * AppCompatDelegate.setApplicationLocales；compileSdk 32 也够不到 Android 13
 * 的 per-app locale API。所以采用经典方案：在 Application / 各 Activity / Service
 * 的 attachBaseContext 里按设置值包一层 createConfigurationContext，
 * 之后该上下文里的 getString / inflate 都取对应语言的资源目录
 * （values / values-zh-rCN）。
 *
 * 语言值存在 SharedPreferences（key = app_language），改完只需重建上下文：
 * 设置页切换后由 SettingFragment 触发 recreate + 重启前台服务来生效。
 */
object LocaleHelper {

    /** 与 settings.xml 里 app_language 条目、arrays.xml 的 app_language_value 一一对应 */
    const val KEY = "app_language"
    const val LANGUAGE_SYSTEM = 0
    const val LANGUAGE_ZH_CN = 1
    const val LANGUAGE_EN = 2

    fun getLanguage(context: Context): Int {
        return context.getSharedPreferences(MiFreeform.APP_SETTINGS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY, LANGUAGE_SYSTEM)
    }

    /**
     * 按用户选择的语言包裹 base。选「跟随系统」时原样返回，
     * 行为与未实现该功能前完全一致。
     */
    fun wrap(base: Context): Context {
        val locale = when (getLanguage(base)) {
            LANGUAGE_ZH_CN -> Locale.SIMPLIFIED_CHINESE
            LANGUAGE_EN -> Locale.ENGLISH
            else -> return base
        }
        val config = Configuration(base.resources.configuration)
        config.setLocales(LocaleList(locale))
        config.setLayoutDirection(locale)
        return base.createConfigurationContext(config)
    }
}
