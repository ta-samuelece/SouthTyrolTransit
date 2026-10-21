package org.southtyrol.transit.di

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import org.southtyrol.transit.data.LanguageProvider
import java.util.Locale

/** App language selection backed by Android per-app locales. */
object AppLanguage : LanguageProvider {
    enum class Option(val tags: String) {
        SYSTEM(""),
        ENGLISH("en"),
        GERMAN("de"),
        ITALIAN("it"),
    }

    override fun current(): String {
        val locales = AppCompatDelegate.getApplicationLocales()
        val locale = if (locales.isEmpty) Locale.getDefault() else locales[0] ?: Locale.getDefault()
        return locale.toLanguageTag()
    }

    /**
     * Languages for operator texts (service notices), most preferred first: the app language, then
     * the phone's system languages. Notices often exist only in German and Italian, so someone using
     * the app in English on a German phone gets German rather than an arbitrary fallback.
     */
    fun textLanguages(): String {
        val system = android.content.res.Resources.getSystem().configuration.locales
        val tags = listOf(current()) + (0 until system.size()).map { system[it].toLanguageTag() }
        return tags.distinct().joinToString(",")
    }

    fun selected(): Option {
        val locales = AppCompatDelegate.getApplicationLocales()
        if (locales.isEmpty) return Option.SYSTEM
        return when (locales[0]?.language) {
            "en" -> Option.ENGLISH
            "de" -> Option.GERMAN
            "it" -> Option.ITALIAN
            else -> Option.SYSTEM
        }
    }

    /**
     * Ladin was a UI language until 2026-10-21 (locale list `lld,de,it`, shown in German): whoever still
     * has it gets German, what they saw. Called at app start.
     */
    fun dropLadin() {
        if (AppCompatDelegate.getApplicationLocales()[0]?.language == "lld") select(Option.GERMAN)
    }

    fun select(option: Option) {
        AppCompatDelegate.setApplicationLocales(
            if (option == Option.SYSTEM) LocaleListCompat.getEmptyLocaleList() else LocaleListCompat.forLanguageTags(option.tags),
        )
    }
}
