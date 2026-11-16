package app.ondara.core.datastore

import android.content.Context
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import app.ondara.core.domain.SettingsRepository
import app.ondara.core.model.UserSettings
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.protobuf.ProtoBuf
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * DataStore tipado con codificación Protocol Buffers.
 *
 * El esquema es la data class `@Serializable` [UserSettings] (kotlinx.serialization-protobuf) en vez
 * de un `.proto` + plugin de Gradle: mismo formato binario en disco, sin generación de código.
 */
@OptIn(ExperimentalSerializationApi::class)
object UserSettingsSerializer : Serializer<UserSettings> {
    override val defaultValue = UserSettings()

    override suspend fun readFrom(input: InputStream): UserSettings = try {
        ProtoBuf.decodeFromByteArray(UserSettings.serializer(), input.readBytes())
    } catch (e: SerializationException) {
        throw CorruptionException("settings.pb ilegible", e)
    }

    override suspend fun writeTo(t: UserSettings, output: OutputStream) {
        output.write(ProtoBuf.encodeToByteArray(UserSettings.serializer(), t))
    }
}

@Singleton
class DataStoreSettingsRepository @Inject constructor(private val store: DataStore<UserSettings>) : SettingsRepository {
    override val settings: Flow<UserSettings> = store.data

    override suspend fun update(transform: (UserSettings) -> UserSettings) {
        store.updateData { transform(it) }
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class DataStoreModule {
    @Binds abstract fun settingsRepository(impl: DataStoreSettingsRepository): SettingsRepository

    companion object {
        @Provides
        @Singleton
        fun dataStore(@ApplicationContext context: Context): DataStore<UserSettings> = DataStoreFactory.create(
            serializer = UserSettingsSerializer,
            corruptionHandler = ReplaceFileCorruptionHandler { UserSettings() },
            produceFile = { File(context.filesDir, "datastore/settings.pb") },
        )
    }
}
