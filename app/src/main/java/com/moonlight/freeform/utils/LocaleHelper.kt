package com.moonlight.freeform.utils

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import com.moonlight.freeform.app.MiFreeform
import java.util.Locale

/**
 * In-app language switching (does not change the system language).
 *
 * How it works: the project excludes androidx.appcompat and uses rikka's 1.5.0 fork, so there is
 * no AppCompatDelegate.setApplicationLocales; compileSdk 32 also cannot reach the Android 13
 * per-app locale API. So we use the classic approach: in the attachBaseContext of the Application /
 * each Activity / Service, wrap one layer of createConfigurationContext based on the saved value,
 * after which getString / inflate in that context resolve resources from the matching language
 * directory (values / values-zh-rCN).
 *
 * The language value is stored in SharedPreferences (key = app_language); changing it only needs a
 * context rebuild: after switching on the settings page, SettingFragment triggers recreate + a
 * restart of the foreground service to take effect.
 */
object LocaleHelper {

    /** One-to-one with the app_language entry in settings.xml and app_language_value in arrays.xml */
    const val KEY = "app_language"
    const val LANGUAGE_SYSTEM = 0
    const val LANGUAGE_ZH_CN = 1
    const val LANGUAGE_EN = 2

    fun getLanguage(context: Context): Int {
        return context.getSharedPreferences(MiFreeform.APP_SETTINGS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY, LANGUAGE_SYSTEM)
    }

    /**
     * Wrap base with the user-selected language. When "Follow system" is chosen it returns unchanged,
     * behaving exactly as before this feature was implemented.
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
