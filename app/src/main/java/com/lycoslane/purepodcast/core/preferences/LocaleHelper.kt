package com.lycoslane.purepodcast.core.preferences

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

object LocaleHelper {

    fun getSavedLanguage(context: Context): String {
        val prefs = context.getSharedPreferences(AppPreferences.PREFS_NAME, Context.MODE_PRIVATE)
        val lang = prefs.getString(AppPreferences.KEY_APP_LANGUAGE, "en")
        return if (lang == "de") "de" else "en"
    }

    fun wrapContext(base: Context): Context {
        val languageTag = getSavedLanguage(base)
        val locale = Locale.forLanguageTag(languageTag)
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration).apply {
            setLocale(locale)
            setLayoutDirection(locale)
        }
        return base.createConfigurationContext(config)
    }
}
