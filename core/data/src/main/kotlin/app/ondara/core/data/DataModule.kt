package app.ondara.core.data

import app.ondara.core.domain.BackupRepository
import app.ondara.core.domain.CatalogRepository
import app.ondara.core.domain.ImportRepository
import app.ondara.core.domain.LibraryRepository
import app.ondara.core.domain.LyricsRepository
import app.ondara.core.domain.SettingsRepository
import app.ondara.core.model.UserSettings
import app.ondara.core.network.HttpClientFactory
import app.ondara.source.innertube.InnerTubeClient
import app.ondara.source.innertube.InnerTubeConfig
import app.ondara.source.lyrics.KuGouProvider
import app.ondara.source.lyrics.LrcLibProvider
import app.ondara.source.lyrics.LyricsAggregator
import app.ondara.source.lyrics.PlainTextProvider
import app.ondara.source.resolver.ExtensionStrategy
import app.ondara.source.resolver.InnerTubeClientStrategy
import app.ondara.source.resolver.NewPipeStrategy
import app.ondara.source.resolver.ResolvedStreamCache
import app.ondara.source.resolver.StreamResolver
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import okhttp3.OkHttpClient
import javax.inject.Inject
import javax.inject.Qualifier
import javax.inject.Singleton

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

/** Último valor conocido de los ajustes, legible de forma síncrona desde código no suspendido. */
@Singleton
class SettingsCache @Inject constructor(repository: SettingsRepository, @ApplicationScope scope: CoroutineScope) {
    val state: StateFlow<UserSettings> = repository.settings.stateIn(scope, SharingStarted.Eagerly, UserSettings())
    val value: UserSettings get() = state.value
}

@Module
@InstallIn(SingletonComponent::class)
abstract class DataModule {
    @Binds abstract fun catalog(impl: CatalogRepositoryImpl): CatalogRepository

    @Binds abstract fun library(impl: LibraryRepositoryImpl): LibraryRepository

    @Binds abstract fun lyrics(impl: LyricsRepositoryImpl): LyricsRepository

    @Binds abstract fun imports(impl: ImportRepositoryImpl): ImportRepository

    @Binds abstract fun backup(impl: BackupRepositoryImpl): BackupRepository

    companion object {
        @Provides
        @Singleton
        @ApplicationScope
        fun applicationScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        @Provides @Singleton fun okHttp(): OkHttpClient = HttpClientFactory.okHttp()

        @Provides @Singleton fun httpClient(okHttp: OkHttpClient): HttpClient = HttpClientFactory.create(okHttp)

        @Provides
        @Singleton
        fun innerTube(http: HttpClient, settings: SettingsCache): InnerTubeClient = InnerTubeClient(http) {
            val s = settings.value
            InnerTubeConfig(s.contentLanguage, s.contentCountry, s.youtubeCookie)
        }

        /** Orden = prioridad: extensión (si está configurada) → NewPipe → clientes InnerTube. */
        @Provides
        @Singleton
        fun streamResolver(okHttp: OkHttpClient, http: HttpClient, innerTube: InnerTubeClient, settings: SettingsCache): StreamResolver =
            StreamResolver(
                strategies = listOf(
                    ExtensionStrategy(http) { settings.value.extensionEndpoint },
                    NewPipeStrategy(okHttp),
                    InnerTubeClientStrategy(innerTube),
                ),
                cache = ResolvedStreamCache(capacity = 128),
            )

        @Provides
        @Singleton
        fun lyricsAggregator(http: HttpClient, innerTube: InnerTubeClient): LyricsAggregator = LyricsAggregator(
            listOf(
                KuGouProvider(http),
                LrcLibProvider(http),
                PlainTextProvider("YouTube Music") { videoId -> innerTube.lyrics(videoId) },
            ),
        )
    }
}
