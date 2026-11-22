package org.southtyrol.transit.di

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.runBlocking
import org.southtyrol.transit.data.LanguageProvider
import org.southtyrol.transit.data.NameLanguage
import org.southtyrol.transit.data.SettingsRepository
import org.southtyrol.transit.data.resolve
import javax.inject.Inject
import javax.inject.Qualifier
import javax.inject.Singleton

/** The [LanguageProvider] for stop, place and line names: the "Stop names" setting. Alerts and notices use the unqualified one. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class NamesLanguage

/**
 * Resolves the "Stop names" setting against [AppLanguage]. The choice is kept in memory and follows the
 * setting; a call before the first value arrives reads it, blocking, so a cold widget or follow update never
 * gets the app language by mistake. Screens that are open observe [changes] to reload their names.
 */
@Singleton
class NamesLanguageProvider @Inject constructor(private val settings: SettingsRepository) : LanguageProvider {
    private val choice: StateFlow<NameLanguage?> = settings.settings.map { it.nameLanguage }
        .stateIn(CoroutineScope(SupervisorJob() + Dispatchers.IO), SharingStarted.Eagerly, null)

    /** The stored choice, then each change; [current] already returns the value collectors receive. */
    val changes: Flow<NameLanguage> = choice.filterNotNull()

    /** The language tag for [choice]. */
    fun language(choice: NameLanguage): String = choice.resolve(AppLanguage.current())

    override fun current(): String = language(choice.value ?: runBlocking { settings.current().nameLanguage })
}
