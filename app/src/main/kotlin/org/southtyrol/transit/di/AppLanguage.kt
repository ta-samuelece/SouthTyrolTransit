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
        // Ladin UI strings fall back to German (then Italian) where no Ladin translation exists.
        LADIN("lld,de,it"),
    }

    override fun current(): String {
        val locales = AppCompatDelegate.getApplicationLocales()
        val locale = if (locales.isEmpty) Locale.getDefault() else locales[0] ?: Locale.getDefault()
        return locale.toLanguageTag()
    }

    fun selected(): Option {
        val locales = AppCompatDelegate.getApplicationLocales()
        if (locales.isEmpty) return Option.SYSTEM
        return when (locales[0]?.language) {
            "en" -> Option.ENGLISH
            "de" -> Option.GERMAN
            "it" -> Option.ITALIAN
            "lld" -> Option.LADIN
            else -> Option.SYSTEM
        }
    }

    fun select(option: Option) {
        AppCompatDelegate.setApplicationLocales(
            if (option == Option.SYSTEM) LocaleListCompat.getEmptyLocaleList() else LocaleListCompat.forLanguageTags(option.tags),
        )
    }
}
