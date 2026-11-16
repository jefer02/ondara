package app.ondara.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Database(
    entities = [
        SongEntity::class, FavoriteEntity::class, PlaylistEntity::class, PlaylistSongEntity::class,
        HistoryEntity::class, SearchHistoryEntity::class, LyricsEntity::class, DownloadEntity::class, PageCacheEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class OndaraDatabase : RoomDatabase() {
    abstract fun libraryDao(): LibraryDao
    abstract fun cacheDao(): CacheDao
    abstract fun downloadDao(): DownloadDao
}

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): OndaraDatabase =
        Room.databaseBuilder(context, OndaraDatabase::class.java, "ondara.db").build()

    @Provides fun libraryDao(db: OndaraDatabase): LibraryDao = db.libraryDao()

    @Provides fun cacheDao(db: OndaraDatabase): CacheDao = db.cacheDao()

    @Provides fun downloadDao(db: OndaraDatabase): DownloadDao = db.downloadDao()
}
