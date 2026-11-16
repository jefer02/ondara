package app.ondara.playback

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import androidx.core.net.toUri
import android.os.Build
import androidx.annotation.OptIn
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.ondara.core.database.DownloadDao
import app.ondara.core.database.DownloadEntity
import app.ondara.core.database.DownloadWithSong
import app.ondara.core.database.LibraryDao
import app.ondara.core.database.toEntity
import app.ondara.core.domain.DownloadRepository
import app.ondara.core.domain.PlayerController
import app.ondara.core.model.AudioQuality
import app.ondara.core.model.DownloadInfo
import app.ondara.core.model.DownloadStatus
import app.ondara.core.model.Song
import app.ondara.source.resolver.StreamResolver
import dagger.Binds
import dagger.Module
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DownloadRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: DownloadDao,
    private val libraryDao: LibraryDao,
    private val caches: PlaybackCaches,
) : DownloadRepository {
    override fun downloads(): Flow<List<DownloadInfo>> = dao.downloads().map { list -> list.map { it.toModel() } }

    override fun download(songId: String): Flow<DownloadInfo?> = dao.download(songId).map { it?.toModel() }

    override suspend fun enqueue(songs: List<Song>, quality: AudioQuality) {
        libraryDao.upsertSongs(songs.map { it.toEntity() })
        val manager = WorkManager.getInstance(context)
        for (song in songs) {
            if (dao.get(song.id)?.status == DownloadStatus.COMPLETED.name) continue
            dao.put(DownloadEntity(song.id, DownloadStatus.QUEUED.name, 0f, quality.name, 0, System.currentTimeMillis()))
            val request = OneTimeWorkRequestBuilder<DownloadWorker>()
                .setInputData(workDataOf(DownloadWorker.KEY_ID to song.id, DownloadWorker.KEY_QUALITY to quality.name))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .addTag(TAG)
                .build()
            manager.enqueueUniqueWork(workName(song.id), ExistingWorkPolicy.REPLACE, request)
        }
    }

    @OptIn(UnstableApi::class)
    override suspend fun remove(songId: String) = withContext(Dispatchers.IO) {
        WorkManager.getInstance(context).cancelUniqueWork(workName(songId))
        caches.downloads.removeResource(songId)
        dao.delete(songId)
    }

    private fun DownloadWithSong.toModel() = DownloadInfo(
        song.toModel(),
        runCatching { DownloadStatus.valueOf(status) }.getOrDefault(DownloadStatus.FAILED),
        progress,
        runCatching { AudioQuality.valueOf(quality) }.getOrDefault(AudioQuality.HIGH),
        bytes,
    )

    private fun workName(id: String) = "download_$id"

    private companion object {
        const val TAG = "ondara_download"
    }
}

/**
 * Descarga una canción a la caché permanente de Media3 con la clave = id de la canción; el
 * reproductor la encuentra ahí antes de ir a la red, así que suena sin conexión.
 */
@OptIn(UnstableApi::class)
@HiltWorker
class DownloadWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val resolver: StreamResolver,
    private val caches: PlaybackCaches,
    private val dao: DownloadDao,
    private val okHttp: OkHttpClient,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val id = inputData.getString(KEY_ID) ?: return Result.failure()
        val quality = runCatching { AudioQuality.valueOf(inputData.getString(KEY_QUALITY).orEmpty()) }.getOrDefault(AudioQuality.HIGH)
        runCatching { setForeground(foregroundInfo()) }
        return try {
            dao.update(id, DownloadStatus.DOWNLOADING.name, 0f, 0, System.currentTimeMillis())
            val stream = resolver.resolve(id, quality)
            val source = CacheDataSource.Factory().setCache(caches.downloads)
                .setUpstreamDataSourceFactory(OkHttpDataSource.Factory(okHttp)).createDataSource()
            val spec = DataSpec.Builder().setUri(stream.url.toUri()).setKey(id).build()
            var lastPercent = -1
            var total = 0L
            val writer = CacheWriter(source, spec, null) { length, cached, _ ->
                total = cached
                val percent = if (length > 0) (cached * 100 / length).toInt() else 0
                if (percent >= lastPercent + 4) {
                    lastPercent = percent
                    runBlocking { dao.update(id, DownloadStatus.DOWNLOADING.name, percent / 100f, cached, System.currentTimeMillis()) }
                }
            }
            try {
                runInterruptible(Dispatchers.IO) { writer.cache() }
            } catch (e: CancellationException) {
                writer.cancel()
                throw e
            }
            dao.update(id, DownloadStatus.COMPLETED.name, 1f, total, System.currentTimeMillis())
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (runAttemptCount < 2) {
                Result.retry()
            } else {
                dao.update(id, DownloadStatus.FAILED.name, 0f, 0, System.currentTimeMillis())
                Result.failure()
            }
        }
    }

    private fun foregroundInfo(): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, applicationContext.getString(R.string.download_channel), NotificationManager.IMPORTANCE_LOW),
        )
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(R.drawable.ic_download)
            .setContentTitle(applicationContext.getString(R.string.download_title))
            .setOngoing(true)
            .setProgress(0, 0, true)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        const val KEY_ID = "id"
        const val KEY_QUALITY = "quality"
        private const val CHANNEL = "downloads"
        private const val NOTIFICATION_ID = 4201
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class PlaybackModule {
    @Binds abstract fun controller(impl: PlayerControllerImpl): PlayerController

    @Binds abstract fun downloads(impl: DownloadRepositoryImpl): DownloadRepository
}
