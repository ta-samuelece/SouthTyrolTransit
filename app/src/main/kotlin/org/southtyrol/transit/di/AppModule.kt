package org.southtyrol.transit.di

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import org.southtyrol.transit.data.AlertRepository
import org.southtyrol.transit.data.DepartureRepository
import org.southtyrol.transit.data.EfaClient
import org.southtyrol.transit.data.GtfsSchedule
import org.southtyrol.transit.data.JourneyRepository
import org.southtyrol.transit.data.LanguageProvider
import org.southtyrol.transit.data.LineRepository
import org.southtyrol.transit.data.MapRepository
import org.southtyrol.transit.data.MobilityRepository
import org.southtyrol.transit.data.OdhMobilitySource
import org.southtyrol.transit.data.OdhRealtime
import org.southtyrol.transit.data.PlacesRepository
import org.southtyrol.transit.data.RealtimeRepository
import org.southtyrol.transit.data.SavedRepository
import org.southtyrol.transit.data.ScheduleStore
import org.southtyrol.transit.data.SettingsRepository
import org.southtyrol.transit.data.TransitHttp
import org.southtyrol.transit.data.TripRepository
import org.southtyrol.transit.data.UserDao
import org.southtyrol.transit.data.UserDatabase
import org.southtyrol.transit.location.AndroidLocationProvider
import org.southtyrol.transit.location.LocationProvider
import org.southtyrol.transit.model.MobilityKind
import org.southtyrol.transit.model.NoPushBackend
import org.southtyrol.transit.model.OfficialTicketingLink
import org.southtyrol.transit.model.PushBackend
import org.southtyrol.transit.model.TicketingProvider
import org.southtyrol.transit.model.TransitScheduleDataSource
import java.io.File
import javax.inject.Singleton

/**
 * Wires concrete data sources to the source-independent repositories. Replacing EFA, the GTFS
 * store or the map provider only requires changes here.
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides @Singleton
    fun okHttp(@ApplicationContext context: Context): OkHttpClient = TransitHttp.client(File(context.cacheDir, "http"))

    @Provides @Singleton
    fun http(client: OkHttpClient): TransitHttp = TransitHttp(client)

    @Provides @Singleton
    fun userDatabase(@ApplicationContext context: Context): UserDatabase =
        Room.databaseBuilder(context, UserDatabase::class.java, "user.db").build()

    @Provides
    fun userDao(db: UserDatabase): UserDao = db.user()

    @Provides @Singleton
    fun settings(@ApplicationContext context: Context): SettingsRepository = SettingsRepository(context)

    @Provides @Singleton
    fun scheduleStore(@ApplicationContext context: Context, http: TransitHttp): ScheduleStore = ScheduleStore(context, http)

    @Provides @Singleton
    fun schedule(store: ScheduleStore): TransitScheduleDataSource = GtfsSchedule(store)

    @Provides @Singleton
    fun efa(http: TransitHttp): EfaClient = EfaClient(http)

    @Provides @Singleton
    fun realtime(http: TransitHttp, user: UserDao): RealtimeRepository = RealtimeRepository(OdhRealtime(http), user)

    @Provides @Singleton
    fun alerts(realtime: RealtimeRepository, efa: EfaClient, user: UserDao): AlertRepository = AlertRepository(realtime, efa, user)

    @Provides @Singleton
    fun departures(schedule: TransitScheduleDataSource, efa: EfaClient, realtime: RealtimeRepository): DepartureRepository = DepartureRepository(schedule, efa, realtime)

    @Provides @Singleton
    fun saved(user: UserDao): SavedRepository = SavedRepository(user)

    @Provides @Singleton
    fun journeys(efa: EfaClient, saved: SavedRepository): JourneyRepository = JourneyRepository(efa, saved)

    @Provides @Singleton
    fun places(efa: EfaClient, schedule: TransitScheduleDataSource): PlacesRepository = PlacesRepository(efa, schedule)

    @Provides @Singleton
    fun trips(schedule: TransitScheduleDataSource, realtime: RealtimeRepository): TripRepository = TripRepository(schedule, realtime)

    @Provides @Singleton
    fun lines(schedule: TransitScheduleDataSource): LineRepository = LineRepository(schedule)

    @Provides @Singleton
    fun map(schedule: TransitScheduleDataSource, realtime: RealtimeRepository): MapRepository = MapRepository(schedule, realtime)

    @Provides @Singleton
    fun mobility(http: TransitHttp): MobilityRepository = MobilityRepository(MobilityKind.entries.map { OdhMobilitySource(http, it) })

    @Provides @Singleton
    fun language(): LanguageProvider = AppLanguage

    @Provides @Singleton
    fun ticketing(): TicketingProvider = OfficialTicketingLink()

    @Provides @Singleton
    fun push(): PushBackend = NoPushBackend

    @Provides @Singleton
    fun location(@ApplicationContext context: Context): LocationProvider = AndroidLocationProvider(context)
}
