package app.ondara.core.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.ondara.core.designsystem.component.OndaraDialog
import app.ondara.core.designsystem.component.OndaraSheet
import app.ondara.core.designsystem.component.OndaraTextField
import app.ondara.core.designsystem.component.Text
import app.ondara.core.designsystem.component.pressable
import app.ondara.core.designsystem.icon.Icon
import app.ondara.core.designsystem.icon.OndaraIcons
import app.ondara.core.designsystem.theme.OndaraTheme
import app.ondara.core.domain.DownloadRepository
import app.ondara.core.domain.LibraryRepository
import app.ondara.core.domain.PlayerController
import app.ondara.core.domain.SettingsRepository
import app.ondara.core.model.DownloadInfo
import app.ondara.core.model.DownloadStatus
import app.ondara.core.model.Playlist
import app.ondara.core.model.Song
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Acciones comunes sobre una canción, compartidas por todas las pantallas. */
@HiltViewModel
class SongActionsViewModel @Inject constructor(
    private val library: LibraryRepository,
    private val downloads: DownloadRepository,
    private val player: PlayerController,
    private val settings: SettingsRepository,
) : ViewModel() {
    val playlists = library.playlists().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun isFavorite(songId: String): Flow<Boolean> = library.isFavorite(songId)

    fun download(songId: String): Flow<DownloadInfo?> = downloads.download(songId)

    fun toggleFavorite(song: Song) = viewModelScope.launch { library.toggleFavorite(song) }

    fun playNext(song: Song) = player.playNext(song)

    fun addToQueue(songs: List<Song>) = player.addToQueue(songs)

    fun startRadio(song: Song) = player.startRadio(song)

    fun toggleDownload(song: Song, current: DownloadInfo?) = viewModelScope.launch {
        if (current == null) downloads.enqueue(listOf(song), settings.settings.first().downloadQuality) else downloads.remove(song.id)
    }

    fun downloadAll(songs: List<Song>) = viewModelScope.launch { downloads.enqueue(songs, settings.settings.first().downloadQuality) }

    fun addToPlaylist(playlistId: String, song: Song) = viewModelScope.launch { library.addToPlaylist(playlistId, listOf(song)) }

    fun createPlaylist(name: String, songs: List<Song>) = viewModelScope.launch { library.createPlaylist(name, songs) }
}

@Composable
fun MenuAction(icon: ImageVector, text: String, onClick: () -> Unit, modifier: Modifier = Modifier, active: Boolean = false) {
    Row(
        modifier.fillMaxWidth().pressable(haptic = true, onClick = onClick)
            .heightIn(min = OndaraTheme.spacing.touch + 4.dp).padding(horizontal = OndaraTheme.spacing.screen),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = if (active) OndaraTheme.colors.accent else OndaraTheme.colors.onBackground, size = 22.dp)
        Spacer(Modifier.width(OndaraTheme.spacing.lg))
        Text(text, style = OndaraTheme.type.headline, maxLines = 1)
    }
}

/**
 * Hoja de acciones de una canción: favorito, cola, radio, descarga, playlists y navegación.
 * Uso: `var menu by remember { mutableStateOf<Song?>(null) }` y `menu?.let { SongMenuSheet(it, { menu = null }, …) }`.
 */
@Composable
fun SongMenuSheet(
    song: Song,
    onDismiss: () -> Unit,
    onArtist: (String) -> Unit = {},
    onAlbum: (String) -> Unit = {},
    extra: @Composable () -> Unit = {},
    viewModel: SongActionsViewModel = hiltViewModel(),
) {
    val favorite by remember(song.id) { viewModel.isFavorite(song.id) }.collectAsStateWithLifecycle(false)
    val download by remember(song.id) { viewModel.download(song.id) }.collectAsStateWithLifecycle(null)
    var choosingPlaylist by remember { mutableStateOf(false) }
    if (choosingPlaylist) {
        PlaylistPickerSheet(listOf(song), onDismiss = onDismiss, viewModel = viewModel)
        return
    }
    OndaraSheet(onDismiss) {
        Row(Modifier.padding(horizontal = OndaraTheme.spacing.screen, vertical = OndaraTheme.spacing.md), verticalAlignment = Alignment.CenterVertically) {
            Artwork(song.thumbnailUrl, Modifier.size(56.dp))
            Spacer(Modifier.width(OndaraTheme.spacing.md))
            Column {
                Text(song.title, style = OndaraTheme.type.title, maxLines = 1)
                Text(song.artistLine, style = OndaraTheme.type.caption, color = OndaraTheme.colors.onMuted, maxLines = 1)
            }
        }
        Column(Modifier.verticalScroll(rememberScrollState())) {
            MenuAction(if (favorite) OndaraIcons.HeartFilled else OndaraIcons.Heart, if (favorite) "Quitar de favoritos" else "Añadir a favoritos", { viewModel.toggleFavorite(song) }, active = favorite)
            MenuAction(OndaraIcons.Queue, "Reproducir a continuación", { viewModel.playNext(song); onDismiss() })
            MenuAction(OndaraIcons.Add, "Añadir a la cola", { viewModel.addToQueue(listOf(song)); onDismiss() })
            MenuAction(OndaraIcons.Radio, "Iniciar radio", { viewModel.startRadio(song); onDismiss() })
            MenuAction(OndaraIcons.Playlist, "Añadir a playlist", { choosingPlaylist = true })
            val status = download?.status
            MenuAction(
                if (status == DownloadStatus.COMPLETED) OndaraIcons.Downloaded else OndaraIcons.Download,
                when (status) {
                    null -> "Descargar"
                    DownloadStatus.COMPLETED -> "Eliminar descarga"
                    DownloadStatus.FAILED -> "Descarga fallida · quitar"
                    else -> "Descargando ${((download?.progress ?: 0f) * 100).toInt()} % · cancelar"
                },
                { viewModel.toggleDownload(song, download) },
                active = status == DownloadStatus.COMPLETED,
            )
            song.artists.firstOrNull { it.id != null }?.let { artist ->
                MenuAction(OndaraIcons.Person, "Ir a ${artist.name}", { onDismiss(); onArtist(artist.id!!) })
            }
            song.album?.let { album -> MenuAction(OndaraIcons.Album, "Ir al álbum", { onDismiss(); onAlbum(album.id) }) }
            extra()
        }
    }
}

/** Elige una playlist local (o crea una) para añadir [songs]. */
@Composable
fun PlaylistPickerSheet(songs: List<Song>, onDismiss: () -> Unit, viewModel: SongActionsViewModel = hiltViewModel()) {
    val playlists by viewModel.playlists.collectAsStateWithLifecycle()
    var creating by remember { mutableStateOf(false) }
    if (creating) {
        NameDialog("Nueva playlist", "Crear", onDismiss = onDismiss) { name ->
            viewModel.createPlaylist(name, songs)
            onDismiss()
        }
        return
    }
    OndaraSheet(onDismiss, title = "Añadir a playlist") {
        Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
            MenuAction(OndaraIcons.Add, "Nueva playlist", { creating = true }, active = true)
            playlists.forEach { playlist: Playlist ->
                MenuAction(OndaraIcons.Playlist, "${playlist.title} · ${playlist.songCount ?: 0}", {
                    songs.forEach { viewModel.addToPlaylist(playlist.id, it) }
                    onDismiss()
                })
            }
        }
    }
}

/** Diálogo con un campo de texto (crear/renombrar playlist, contraseñas…). */
@Composable
fun NameDialog(
    title: String,
    confirm: String,
    onDismiss: () -> Unit,
    initial: String = "",
    placeholder: String = "Nombre",
    password: Boolean = false,
    allowEmpty: Boolean = false,
    text: String? = null,
    onConfirm: (String) -> Unit,
) {
    var value by remember { mutableStateOf(initial) }
    OndaraDialog(title, onDismiss, confirm, { onConfirm(value.trim()) }, text = text, confirmEnabled = allowEmpty || value.isNotBlank()) {
        OndaraTextField(value, { value = it }, Modifier.fillMaxWidth(), placeholder = placeholder, password = password)
    }
}
