package app.ondara.playback

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import app.ondara.core.domain.LibraryRepository
import app.ondara.core.model.Song
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.guava.future
import javax.inject.Inject

/**
 * Servicio de reproducción en segundo plano. Publica una `MediaLibrarySession` sobre el reproductor
 * compartido: de ahí salen la notificación, los controles de la pantalla de bloqueo, los botones de
 * auriculares y el árbol de navegación de Android Auto (favoritos, historial y playlists).
 */
@OptIn(UnstableApi::class)
@AndroidEntryPoint
class PlaybackService : MediaLibraryService() {
    @Inject lateinit var controller: PlayerControllerImpl

    @Inject lateinit var library: LibraryRepository

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var session: MediaLibrarySession? = null

    override fun onCreate() {
        super.onCreate()
        val builder = MediaLibrarySession.Builder(this, controller.player, LibraryCallback())
        packageManager.getLaunchIntentForPackage(packageName)?.let { launch ->
            builder.setSessionActivity(PendingIntent.getActivity(this, 0, launch, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        }
        session = builder.build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = controller.player
        if (!player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        // El reproductor es del proceso (lo comparte la UI): solo se libera la sesión.
        session?.release()
        session = null
        scope.cancel()
        super.onDestroy()
    }

    private inner class LibraryCallback : MediaLibrarySession.Callback {
        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> = Futures.immediateFuture(LibraryResult.ofItem(folder(ROOT, "Ondara"), params))

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = scope.future {
            val items: List<MediaItem> = when {
                parentId == ROOT -> listOf(folder(FAVORITES, "Favoritos"), folder(HISTORY, "Historial"), folder(PLAYLISTS, "Playlists"))
                parentId == FAVORITES -> playable(library.favorites().first())
                parentId == HISTORY -> playable(library.history(100).first())
                parentId == PLAYLISTS -> library.playlists().first().map { folder(PLAYLIST_PREFIX + it.id, it.title) }
                parentId.startsWith(PLAYLIST_PREFIX) -> playable(library.playlistSongs(parentId.removePrefix(PLAYLIST_PREFIX)).first())
                else -> emptyList()
            }
            LibraryResult.ofItemList(ImmutableList.copyOf(items.drop(page * pageSize).take(pageSize)), params)
        }

        /** Los controladores externos solo envían ids: se completan con la URI y los metadatos reales. */
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> = Futures.immediateFuture(
            mediaItems.map { item -> this@PlaybackService.controller.knownSong(item.mediaId)?.toMediaItem() ?: item.toSong().toMediaItem() }.toMutableList(),
        )

        private fun playable(songs: List<Song>): List<MediaItem> {
            controller.register(songs)
            return songs.map { it.toMediaItem() }
        }

        private fun folder(id: String, title: String): MediaItem = MediaItem.Builder()
            .setMediaId(id)
            .setMediaMetadata(
                MediaMetadata.Builder().setTitle(title).setIsBrowsable(true).setIsPlayable(false)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED).setExtras(Bundle.EMPTY).build(),
            )
            .build()
    }

    private companion object {
        const val ROOT = "root"
        const val FAVORITES = "favorites"
        const val HISTORY = "history"
        const val PLAYLISTS = "playlists"
        const val PLAYLIST_PREFIX = "pl:"
    }
}
