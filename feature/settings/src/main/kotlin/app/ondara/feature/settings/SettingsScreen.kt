package app.ondara.feature.settings

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.ondara.core.designsystem.component.Label
import app.ondara.core.designsystem.component.OndaraSheet
import app.ondara.core.designsystem.component.OndaraSwitch
import app.ondara.core.designsystem.component.OndaraValueSlider
import app.ondara.core.designsystem.component.ScreenScaffold
import app.ondara.core.designsystem.component.SettingRow
import app.ondara.core.designsystem.component.Skeleton
import app.ondara.core.designsystem.component.Text
import app.ondara.core.designsystem.icon.OndaraIcons
import app.ondara.core.designsystem.theme.OndaraTheme
import app.ondara.core.model.AudioQuality
import app.ondara.core.ui.CollectEffects
import app.ondara.core.ui.MenuAction
import app.ondara.core.ui.NameDialog
import app.ondara.core.ui.Nav

private enum class Dialog { NONE, YT_URL, CSV_NAME, EXPORT_PASSWORD, IMPORT_PASSWORD, EXTENSION, STREAM_QUALITY, DOWNLOAD_QUALITY, COUNTRY }

@Composable
fun SettingsScreen(onNavigate: (Nav) -> Unit, modifier: Modifier = Modifier, viewModel: SettingsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val settings = state.settings
    val supportsDither = OndaraTheme.spec.ditherArtwork
    val context = LocalContext.current
    var dialog by remember { mutableStateOf(Dialog.NONE) }
    var pendingCsv by remember { mutableStateOf<String?>(null) }
    var pendingBackup by remember { mutableStateOf<ByteArray?>(null) }
    var exportBytes by remember { mutableStateOf<ByteArray?>(null) }
    fun update(transform: (app.ondara.core.model.UserSettings) -> app.ondara.core.model.UserSettings) = viewModel.onEvent(SettingsEvent.Update(transform))

    val saveBackup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val bytes = exportBytes
        if (uri != null && bytes != null) {
            runCatching { context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) } }
                .onSuccess { Toast.makeText(context, "Backup guardado", Toast.LENGTH_SHORT).show() }
                .onFailure { Toast.makeText(context, "No se pudo guardar el backup", Toast.LENGTH_SHORT).show() }
        }
        exportBytes = null
    }
    val openBackup = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            pendingBackup = runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
            // "ONDARA1" al inicio = cifrado: hay que pedir la contraseña.
            val bytes = pendingBackup
            if (bytes != null && String(bytes, 0, minOf(7, bytes.size)) == "ONDARA1") dialog = Dialog.IMPORT_PASSWORD
            else if (bytes != null) viewModel.onEvent(SettingsEvent.ImportBackup(bytes, ""))
        }
    }
    val openCsv = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            pendingCsv = runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() } }.getOrNull()
            if (pendingCsv != null) dialog = Dialog.CSV_NAME
        }
    }
    CollectEffects(viewModel.effects) { effect ->
        when (effect) {
            is SettingsEffect.Message -> Toast.makeText(context, effect.text, Toast.LENGTH_LONG).show()
            is SettingsEffect.Navigate -> onNavigate(effect.nav)
            is SettingsEffect.BackupReady -> {
                exportBytes = effect.bytes
                saveBackup.launch("ondara-backup.json")
            }
        }
    }
    LaunchedEffect(settings.equalizer.enabled) { if (settings.equalizer.enabled) viewModel.onEvent(SettingsEvent.LoadBands) }

    ScreenScaffold("Ajustes", modifier, onBack = { onNavigate(Nav.Back) }) {
        state.busy?.let { busy ->
            item(key = "busy", contentType = "progress") {
                Column(Modifier.padding(horizontal = OndaraTheme.spacing.screen, vertical = OndaraTheme.spacing.sm)) {
                    Label(busy, color = OndaraTheme.colors.accent)
                    Skeleton(Modifier.padding(top = 6.dp).fillMaxWidth().height(4.dp))
                }
            }
        }
        section("Apariencia")
        item(key = "setting-1", contentType = "setting") {
            SettingRow("Temas", subtitle = "Galería, editor y temas compartidos",
                icon = OndaraIcons.Palette,
                onClick = { onNavigate(Nav.Themes) }) }
        item(key = "setting-2", contentType = "setting") {
            SettingRow("Showcase de componentes", subtitle = "Todo el sistema de diseño con el tema activo",
                icon = OndaraIcons.Wave,
                onClick = { onNavigate(Nav.Showcase) }) }
        item(key = "setting-3", contentType = "setting") {
            SettingRow("Vibración sutil", subtitle = "Hápticos al reproducir, saltar y deslizar",
                icon = OndaraIcons.Bolt) { OndaraSwitch(settings.haptics, { v -> update { it.copy(haptics = v) } }) } }

        if (supportsDither) {
            item(key = "dither", contentType = "setting") {
                SettingRow("Portadas tramadas",
                icon = OndaraIcons.Palette) {
                    OndaraSwitch(settings.ditherArtwork, { v -> update { it.copy(ditherArtwork = v) } })
                }
            }
        }
        item(key = "player", contentType = "setting") {
            SettingRow("Reproductor", subtitle = "Layout, vídeo de fondo",
                icon = OndaraIcons.Play,
                onClick = { onNavigate(Nav.PlayerSettings) })
        }
        item(key = "translation", contentType = "setting") {
            SettingRow("Traducir letras", subtitle = "Usa el traductor de Google; el texto de la letra se envía a sus servidores",
                icon = OndaraIcons.Translate) {
                OndaraSwitch(settings.translateLyrics, { v -> update { it.copy(translateLyrics = v) } })
            }
        }

        section("Reproducción")
        item(key = "setting-4", contentType = "setting") {
            SettingRow("Calidad de streaming", subtitle = qualityLabel(settings.streamQuality),
                icon = OndaraIcons.Wave,
                onClick = { dialog = Dialog.STREAM_QUALITY }) }
        item(key = "setting-5", contentType = "setting") {
            SettingRow("Crossfade", subtitle = if (settings.crossfadeSeconds == 0) "Desactivado · reproducción sin pausas (gapless)" else "${settings.crossfadeSeconds} s · curva de igual potencia",
                icon = OndaraIcons.Shuffle)
            OndaraValueSlider(
                settings.crossfadeSeconds.toFloat(), { v -> update { it.copy(crossfadeSeconds = v.toInt()) } }, 0f..12f,
                Modifier.padding(horizontal = OndaraTheme.spacing.screen), steps = 12, description = "Segundos de crossfade",
            )
        }
        item(key = "setting-6", contentType = "setting") {
            SettingRow("Normalizar volumen", subtitle = "Iguala la sonoridad entre canciones",
                icon = OndaraIcons.Equalizer) { OndaraSwitch(settings.normalizeVolume, { v -> update { it.copy(normalizeVolume = v) } }) } }
        item(key = "setting-7", contentType = "setting") {
            SettingRow("Saltar silencios",
                icon = OndaraIcons.Next) { OndaraSwitch(settings.skipSilence, { v -> update { it.copy(skipSilence = v) } }) } }
        item(key = "setting-8", contentType = "setting") {
            SettingRow("Ondara Flow", subtitle = "Radio infinita cuando se acaba la cola",
                icon = OndaraIcons.Radio) { OndaraSwitch(settings.ondaraFlow, { v -> update { it.copy(ondaraFlow = v) } }) } }

        section("Ecualizador")
        item(key = "setting-9", contentType = "setting") {
            SettingRow("Ecualizador", subtitle = "Ajusta cada banda de frecuencia",
                icon = OndaraIcons.Equalizer) { OndaraSwitch(settings.equalizer.enabled, { v -> update { it.copy(equalizer = it.equalizer.copy(enabled = v)) } }) } }
        if (settings.equalizer.enabled) {
            if (state.bands.isEmpty()) {
                item(key = "setting-10", contentType = "setting") { Label("Empieza a reproducir algo para ver las bandas del dispositivo.", Modifier.padding(horizontal = OndaraTheme.spacing.screen), maxLines = 2) }
            }
            items(state.bands.size, key = { "band$it" }, contentType = { "band" }) { i ->
                val band = state.bands[i]
                val level = settings.equalizer.bandLevelsMb.getOrElse(i) { 0 }
                Row(Modifier.padding(horizontal = OndaraTheme.spacing.screen), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (band.centerHz >= 1000) "${band.centerHz / 1000} kHz" else "${band.centerHz} Hz", Modifier.width(64.dp), style = OndaraTheme.type.mono)
                    OndaraValueSlider(
                        level.toFloat(), { viewModel.onEvent(SettingsEvent.SetBand(i, it.toInt())) }, band.minMb.toFloat()..band.maxMb.toFloat(),
                        Modifier.weight(1f), description = "Banda de ${band.centerHz} hercios",
                    )
                    Text("%+d dB".format(level / 100), Modifier.width(56.dp).padding(start = 8.dp), style = OndaraTheme.type.mono, color = OndaraTheme.colors.onMuted)
                }
            }
        }

        section("Descargas")
        item(key = "setting-11", contentType = "setting") {
            SettingRow("Calidad de descarga", subtitle = qualityLabel(settings.downloadQuality),
                icon = OndaraIcons.Download,
                onClick = { dialog = Dialog.DOWNLOAD_QUALITY }) }

        section("Cuentas")
        item(key = "accounts", contentType = "setting") {
            val youtube = state.accounts.youtube.joinToString(", ") { it.name }.ifBlank { "sin conectar" }
            val spotify = state.accounts.spotify?.name ?: "sin conectar"
            SettingRow("Cuentas conectadas", subtitle = "YouTube Music: $youtube · Spotify: $spotify",
                icon = OndaraIcons.Person,
                onClick = { onNavigate(Nav.Account) })
        }
        item(key = "profile", contentType = "setting") {
            SettingRow("Perfil",
                icon = OndaraIcons.Person,
                onClick = { onNavigate(Nav.Profile) })
        }
        if (state.accounts.activeYouTube != null) {
            item(key = "syncLikes", contentType = "setting") {
                SettingRow("Sincronizar Me gusta con YouTube Music",
                icon = OndaraIcons.Star) {
                    OndaraSwitch(settings.syncLikes, { v -> update { it.copy(syncLikes = v) } })
                }
            }
        }
        if (state.accounts.spotify != null) {
            item(key = "unmatched", contentType = "setting") {
                SettingRow("Canciones de Spotify sin coincidencia",
                icon = OndaraIcons.Spotify,
                    onClick = { onNavigate(Nav.Unmatched) })
            }
        }
        section("Importación")
        item(key = "setting-12", contentType = "setting") {
            SettingRow("Importar playlist de YouTube Music", subtitle = "Pega el enlace de la playlist",
                icon = OndaraIcons.Import,
                onClick = { dialog = Dialog.YT_URL }) }
        item(key = "setting-13", contentType = "setting") {
            SettingRow("Importar CSV (Spotify / Exportify)", subtitle = "Columnas de título y artista",
                icon = OndaraIcons.Import,
                onClick = { openCsv.launch(arrayOf("text/*", "application/csv", "application/octet-stream")) }) }
        item(key = "setting-14", contentType = "setting") {
            SettingRow("País del contenido", subtitle = "${settings.contentCountry} · idioma ${settings.contentLanguage}",
                icon = OndaraIcons.Home,
                onClick = { dialog = Dialog.COUNTRY }) }
        item(key = "setting-15", contentType = "setting") {
            SettingRow("Servidor de extensión de audio", subtitle = settings.extensionEndpoint.ifBlank { "Desactivado · se usa YouTube" },
                icon = OndaraIcons.Bolt,
                onClick = { dialog = Dialog.EXTENSION }) }

        section("Copia de seguridad")
        item(key = "setting-16", contentType = "setting") {
            SettingRow("Exportar backup", subtitle = "Biblioteca y ajustes en JSON, con cifrado opcional",
                icon = OndaraIcons.Share,
                onClick = { dialog = Dialog.EXPORT_PASSWORD }) }
        item(key = "setting-17", contentType = "setting") {
            SettingRow("Restaurar backup",
                icon = OndaraIcons.Import,
                onClick = { openBackup.launch(arrayOf("application/json", "application/octet-stream", "*/*")) }) }

        item(key = "about", contentType = "setting") {
            SettingRow("Acerca de Ondara",
                icon = OndaraIcons.Info,
                onClick = { onNavigate(Nav.About) })
        }
        section("Privacidad")
        item(key = "setting-18", contentType = "setting") {
            SettingRow("Informes de fallos", subtitle = "Desactivado por defecto. Si lo activas, los fallos se guardan como JSON solo en tu dispositivo (carpeta crash de la app); nada se envía. Se aplica al reiniciar.",
                icon = OndaraIcons.Check) {
                OndaraSwitch(settings.crashReports, { v -> update { it.copy(crashReports = v) } })
            }
        }
        item(key = "setting-19", contentType = "setting") {
            Text(
                "Ondara no incluye telemetría ni anuncios. No aloja contenido: reproduce enlaces públicos de YouTube Music, que pertenecen a sus dueños. " +
                    "Apoya a los artistas. Software libre bajo GPL-3.0.",
                Modifier.padding(OndaraTheme.spacing.screen), style = OndaraTheme.type.caption, color = OndaraTheme.colors.onMuted, maxLines = 8,
            )
        }
    }

    when (dialog) {
        Dialog.NONE -> Unit
        Dialog.YT_URL -> NameDialog("Importar de YouTube Music", "Importar", { dialog = Dialog.NONE }, placeholder = "https://music.youtube.com/playlist?list=…") {
            dialog = Dialog.NONE
            viewModel.onEvent(SettingsEvent.ImportYouTube(it))
        }
        Dialog.CSV_NAME -> NameDialog("Nombre de la playlist", "Importar", { dialog = Dialog.NONE }, initial = "Importada de Spotify") { name ->
            dialog = Dialog.NONE
            pendingCsv?.let { viewModel.onEvent(SettingsEvent.ImportCsv(name, it)) }
            pendingCsv = null
        }
        Dialog.EXPORT_PASSWORD -> NameDialog("Exportar backup", "Exportar", { dialog = Dialog.NONE }, placeholder = "Contraseña (opcional)", password = true, allowEmpty = true, text = "Déjala vacía para un JSON sin cifrar.") {
            dialog = Dialog.NONE
            viewModel.onEvent(SettingsEvent.ExportBackup(it))
        }
        Dialog.IMPORT_PASSWORD -> NameDialog("Backup cifrado", "Restaurar", { dialog = Dialog.NONE }, placeholder = "Contraseña", password = true) { password ->
            dialog = Dialog.NONE
            pendingBackup?.let { viewModel.onEvent(SettingsEvent.ImportBackup(it, password)) }
            pendingBackup = null
        }
        Dialog.EXTENSION -> NameDialog("Servidor de extensión", "Guardar", { dialog = Dialog.NONE }, initial = settings.extensionEndpoint,
            placeholder = "https://… (vacío = desactivar)", allowEmpty = true, text = "Debe responder a GET /stream?videoId=…&quality=…") { url ->
            dialog = Dialog.NONE
            update { it.copy(extensionEndpoint = url) }
        }
        Dialog.COUNTRY -> NameDialog("País del contenido", "Guardar", { dialog = Dialog.NONE }, initial = settings.contentCountry, placeholder = "Código ISO, p. ej. CO, MX, ES, US") { code ->
            dialog = Dialog.NONE
            update { it.copy(contentCountry = code.uppercase().take(2)) }
        }
        Dialog.STREAM_QUALITY, Dialog.DOWNLOAD_QUALITY -> {
            val stream = dialog == Dialog.STREAM_QUALITY
            OndaraSheet({ dialog = Dialog.NONE }, title = if (stream) "Calidad de streaming" else "Calidad de descarga") {
                SettingsViewModel.QUALITIES.forEach { (quality, label) ->
                    MenuAction(
                        OndaraIcons.Check, label,
                        {
                            update { if (stream) it.copy(streamQuality = quality) else it.copy(downloadQuality = quality) }
                            dialog = Dialog.NONE
                        },
                        active = (if (stream) settings.streamQuality else settings.downloadQuality) == quality,
                    )
                }
            }
        }
    }
}

private fun qualityLabel(quality: AudioQuality) = SettingsViewModel.QUALITIES.first { it.first == quality }.second

private fun LazyListScope.section(title: String) {
    item(key = "section-$title", contentType = "section") {
        Spacer(Modifier.height(OndaraTheme.spacing.lg))
        Label(title, Modifier.padding(horizontal = OndaraTheme.spacing.screen, vertical = OndaraTheme.spacing.sm), color = OndaraTheme.colors.accent)
    }
}
