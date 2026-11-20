package org.southtyrol.transit.di

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.southtyrol.transit.data.LanguageProvider
import org.southtyrol.transit.data.NameLanguage
import org.southtyrol.transit.data.SettingsRepository
import org.southtyrol.transit.data.resolve
import javax.inject.Qualifier

/** The [LanguageProvider] for stop, place and line names: the "Stop names" setting. Alerts and notices use the unqualified one. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class NamesLanguage

/**
 * Resolves the "Stop names" setting against [AppLanguage]. The choice is kept in memory and follows the
 * setting; the first call reads it once, blocking, so a cold widget or follow update never gets the app
 * language by mistake.
 */
class NamesLanguageProvider(private val settings: SettingsRepository) : LanguageProvider {
    @Volatile private var choice: NameLanguage? = null

    init {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch { settings.settings.collect { choice = it.nameLanguage } }
    }

    override fun current(): String {
        val names = choice ?: runBlocking { settings.current().nameLanguage }.also { choice = it }
        return names.resolve(AppLanguage.current())
    }
}
