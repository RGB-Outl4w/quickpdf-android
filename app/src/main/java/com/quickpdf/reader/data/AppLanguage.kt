package com.quickpdf.reader.data

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import java.util.Locale

/**
 * In-app language. Android 13+ has per-app languages built in (also settable from system settings);
 * older versions get the choice applied to the activity's context.
 */
object AppLanguage {
    /** Language tags offered in settings; "" follows the system. */
    val options = listOf("", "en", "ru")

    fun get(context: Context): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.getSystemService(LocaleManager::class.java).applicationLocales.get(0)?.language.orEmpty()
        } else {
            prefs(context).getString(KEY, "").orEmpty()
        }

    fun set(activity: Activity, tag: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // The system recreates the activity.
            activity.getSystemService(LocaleManager::class.java).applicationLocales =
                if (tag.isEmpty()) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(tag)
        } else {
            prefs(activity).edit().putString(KEY, tag).commit()
            activity.recreate()
        }
    }

    /** For Activity.attachBaseContext before Android 13. */
    fun wrap(base: Context): Context {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return base
        val tag = prefs(base).getString(KEY, "").orEmpty()
        if (tag.isEmpty()) return base
        val config = Configuration(base.resources.configuration).apply { setLocale(Locale.forLanguageTag(tag)) }
        return base.createConfigurationContext(config)
    }

    private fun prefs(context: Context) = context.getSharedPreferences("language", Context.MODE_PRIVATE)

    private const val KEY = "tag"
}
