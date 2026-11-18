package app.ondara.feature.themes

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.ondara.core.designsystem.component.ButtonStyle
import app.ondara.core.designsystem.component.Label
import app.ondara.core.designsystem.component.LocalThemeTransition
import app.ondara.core.designsystem.component.MeshGradientBackground
import app.ondara.core.designsystem.component.OndaraButton
import app.ondara.core.designsystem.component.OndaraSheet
import app.ondara.core.designsystem.component.ScreenScaffold
import app.ondara.core.designsystem.component.Text
import app.ondara.core.designsystem.component.grain
import app.ondara.core.designsystem.component.rememberHaptics
import app.ondara.core.designsystem.icon.Icon
import app.ondara.core.designsystem.icon.OndaraIcons
import app.ondara.core.designsystem.theme.BackgroundStyle
import app.ondara.core.designsystem.theme.NavStyle
import app.ondara.core.designsystem.theme.OndaraTheme
import app.ondara.core.designsystem.theme.PlayerLayout
import app.ondara.core.designsystem.theme.ThemeSpec
import app.ondara.core.designsystem.theme.ThemeStore
import app.ondara.core.domain.SettingsRepository
import app.ondara.core.model.UserSettings
import app.ondara.core.ui.CollectEffects
import app.ondara.core.ui.MenuAction
import app.ondara.core.ui.MviViewModel
import app.ondara.core.ui.Nav
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ThemesUiState(val themes: List<ThemeSpec> = emptyList(), val activeId: String = ThemeStore.DEFAULT_ID)

sealed interface ThemesEvent {
    data class Select(val id: String) : ThemesEvent
    data class Delete(val id: String) : ThemesEvent
    data class Import(val json: String) : ThemesEvent

    /**
     * Guarda y aplica un tema. [applyPlayerLayout] = el usuario cambió el layout del reproductor en el editor:
     * entonces Ajustes > Reproductor pasa a "Según el tema"; si no, se respeta la elección previa (Cine por defecto).
     */
    data class Save(val spec: ThemeSpec, val applyPlayerLayout: Boolean = false) : ThemesEvent
}

sealed interface ThemesEffect {
    data class Message(val text: String) : ThemesEffect
    data object Saved : ThemesEffect
}

@HiltViewModel
class ThemesViewModel @Inject constructor(
    private val store: ThemeStore,
    private val settings: SettingsRepository,
) : MviViewModel<ThemesUiState, ThemesEvent, ThemesEffect>(ThemesUiState(store.themes.value)) {
    init {
        viewModelScope.launch { store.themes.collect { setState { copy(themes = it) } } }
        viewModelScope.launch { settings.settings.map { it.themeId }.collect { setState { copy(activeId = it) } } }
    }

    fun export(id: String): String = store.export(id)

    fun active(): ThemeSpec = store.byId(state.value.activeId)

    override fun onEvent(event: ThemesEvent) {
        when (event) {
            is ThemesEvent.Select -> {
                // Estado optimista: el reveal necesita el tema nuevo en el mismo frame.
                setState { copy(activeId = event.id) }
                viewModelScope.launch { settings.update { it.copy(themeId = event.id) } }
            }
            is ThemesEvent.Delete -> viewModelScope.launch {
                if (state.value.activeId == event.id) settings.update { it.copy(themeId = ThemeStore.DEFAULT_ID) }
                store.delete(event.id)
            }
            is ThemesEvent.Import -> viewModelScope.launch {
                runCatching { store.import(event.json) }
                    .onSuccess { emit(ThemesEffect.Message("Tema «${it.name}» importado")) }
                    .onFailure { emit(ThemesEffect.Message(it.message ?: "No se pudo importar el tema")) }
            }
            is ThemesEvent.Save -> viewModelScope.launch {
                val saved = store.save(event.spec)
                settings.update { settingsAfterThemeSave(it, saved.id, event.applyPlayerLayout) }
                emit(ThemesEffect.Saved)
            }
        }
    }
}

/**
 * Galería de temas. Cada tarjeta es un **mini-render real** del tema (un `OndaraTheme` anidado), no
 * una imagen. Al tocar una, el cambio se revela con un círculo que nace en el punto del toque.
 */
@Composable
fun ThemesScreen(onNavigate: (Nav) -> Unit, modifier: Modifier = Modifier, viewModel: ThemesViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val transition = LocalThemeTransition.current
    val haptics = rememberHaptics()
    var menuFor by remember { mutableStateOf<ThemeSpec?>(null) }
    var exporting by remember { mutableStateOf<String?>(null) }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() } }.getOrNull()
                ?.let { viewModel.onEvent(ThemesEvent.Import(it)) }
        }
    }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val json = exporting
        if (uri != null && json != null) runCatching { context.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) } }
        exporting = null
    }
    CollectEffects(viewModel.effects) { if (it is ThemesEffect.Message) Toast.makeText(context, it.text, Toast.LENGTH_SHORT).show() }

    ScreenScaffold("Temas", modifier, onBack = { onNavigate(Nav.Back) }, subtitle = "Cada tema cambia colores, formas, tipografía, navegación y reproductor.") {
        val rows = state.themes.chunked(2)
        items(rows.size, key = { i -> rows[i].joinToString("+") { it.id } }, contentType = { "row" }) { i ->
            Row(
                Modifier.padding(horizontal = OndaraTheme.spacing.screen, vertical = OndaraTheme.spacing.sm),
                horizontalArrangement = Arrangement.spacedBy(OndaraTheme.spacing.md),
            ) {
                rows[i].forEach { spec ->
                    ThemeCard(
                        spec, selected = spec.id == state.activeId, modifier = Modifier.weight(1f),
                        onLongPress = { menuFor = spec },
                    ) { origin ->
                        if (spec.id != state.activeId) {
                            haptics.tick()
                            val apply = { viewModel.onEvent(ThemesEvent.Select(spec.id)) }
                            if (transition != null) transition.change(origin, apply) else apply()
                        }
                    }
                }
                if (rows[i].size == 1) Spacer(Modifier.weight(1f))
            }
        }
        item(key = "actions") {
            Row(Modifier.padding(OndaraTheme.spacing.screen), horizontalArrangement = Arrangement.spacedBy(OndaraTheme.spacing.sm)) {
                OndaraButton("Crear tema", { onNavigate(Nav.ThemeEditor) }, icon = OndaraIcons.Edit)
                OndaraButton("Importar", { importer.launch(arrayOf("application/json", "text/*", "*/*")) }, style = ButtonStyle.TONAL, icon = OndaraIcons.Import)
            }
        }
        item(key = "hint") { Label("Mantén pulsado un tema para exportarlo o compartirlo.", Modifier.padding(horizontal = OndaraTheme.spacing.screen), maxLines = 2) }
    }

    menuFor?.let { spec ->
        OndaraSheet({ menuFor = null }, title = spec.name) {
            MenuAction(OndaraIcons.Download, "Exportar como JSON", {
                exporting = viewModel.export(spec.id)
                exporter.launch("ondara-tema-${spec.id}.json")
                menuFor = null
            })
            MenuAction(OndaraIcons.Share, "Compartir", {
                val send = Intent(Intent.ACTION_SEND).setType("application/json").putExtra(Intent.EXTRA_TEXT, viewModel.export(spec.id)).putExtra(Intent.EXTRA_SUBJECT, "Tema de Ondara: ${spec.name}")
                context.startActivity(Intent.createChooser(send, "Compartir tema"))
                menuFor = null
            })
            if (spec.custom) MenuAction(OndaraIcons.Delete, "Eliminar tema", { viewModel.onEvent(ThemesEvent.Delete(spec.id)); menuFor = null })
        }
    }
}

@Composable
private fun ThemeCard(spec: ThemeSpec, selected: Boolean, modifier: Modifier, onLongPress: () -> Unit, onTap: (Offset) -> Unit) {
    var position by remember { mutableStateOf(Offset.Zero) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    val colors = OndaraTheme.colors
    Column(modifier) {
        Box(
            Modifier
                .onGloballyPositioned {
                    position = it.positionInRoot()
                    size = it.size
                }
                .pointerInput(spec.id, selected) { detectTapGestures(onLongPress = { onLongPress() }) { offset -> onTap(position + offset) } }
                .semantics {
                    role = Role.RadioButton
                    this.selected = selected
                    contentDescription = "Tema ${spec.name}. ${spec.description}"
                    // TalkBack y teclados activan la tarjeta sin gesto táctil: el reveal nace en su centro.
                    onClick("Aplicar tema") {
                        onTap(position + Offset(size.width / 2f, size.height / 2f))
                        true
                    }
                }
                .aspectRatio(0.7f)
                .clip(OndaraTheme.shapes.large)
                .border(2.dp, if (selected) colors.accent else colors.outline, OndaraTheme.shapes.large),
        ) {
            ThemePreview(spec, Modifier.fillMaxSize())
            if (selected) {
                Box(Modifier.align(Alignment.TopEnd).padding(10.dp).size(26.dp).clip(CircleShape).background(colors.accent), Alignment.Center) {
                    Icon(OndaraIcons.Check, null, tint = colors.onAccent, size = 16.dp)
                }
            }
        }
        Text(spec.name, Modifier.padding(top = OndaraTheme.spacing.sm), style = OndaraTheme.type.bodyStrong, maxLines = 1)
        Label(spec.description, maxLines = 2)
    }
}

/** Mini-render en vivo de un tema: su fondo, tipografía, formas, layout de reproductor y navegación. */
@Composable
fun ThemePreview(spec: ThemeSpec, modifier: Modifier = Modifier) {
    OndaraTheme(spec, animateColors = false) {
        val colors = OndaraTheme.colors
        val type = OndaraTheme.type
        val shapes = OndaraTheme.shapes
        Box(modifier.background(colors.background).grain(spec.grain)) {
            if (spec.background != BackgroundStyle.SOLID) MeshGradientBackground(OndaraTheme.artwork, Modifier.fillMaxSize(), dim = 0.5f)
            if (spec.navigation == NavStyle.SIDE_RAIL) {
                Column(Modifier.fillMaxHeight().width(18.dp).background(colors.surface), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    repeat(3) { Box(Modifier.padding(vertical = 5.dp).size(7.dp).clip(shapes.small).background(if (it == 0) colors.accent else colors.onMuted)) }
                }
            }
            Column(Modifier.fillMaxSize().padding(start = if (spec.navigation == NavStyle.SIDE_RAIL) 28.dp else 12.dp, end = 12.dp, top = 14.dp, bottom = 10.dp)) {
                Text(if (type.uppercaseLabels) "AHORA" else "Ahora", style = type.title.copy(fontSize = type.title.fontSize * 0.8f), maxLines = 1)
                Spacer(Modifier.height(8.dp))
                // Portada según el layout del reproductor.
                val art = Modifier.clip(if (spec.playerLayout == PlayerLayout.VINYL) CircleShape else shapes.artwork)
                    .background(androidx.compose.ui.graphics.Brush.linearGradient(listOf(colors.accent, colors.surfaceHigh)))
                when (spec.playerLayout) {
                    PlayerLayout.MINIMAL -> {
                        Box(art.size(34.dp))
                        Text("03:42", style = type.monoLarge.copy(fontSize = type.monoLarge.fontSize * 0.9f), maxLines = 1)
                    }
                    else -> Box(art.fillMaxWidth(if (spec.playerLayout == PlayerLayout.IMMERSIVE) 1f else 0.82f).aspectRatio(1f).align(Alignment.CenterHorizontally))
                }
                Spacer(Modifier.height(8.dp))
                Box(Modifier.fillMaxWidth(0.7f).height(7.dp).clip(shapes.small).background(colors.onBackground))
                Spacer(Modifier.height(5.dp))
                Box(Modifier.fillMaxWidth(0.4f).height(5.dp).clip(shapes.small).background(colors.accent))
                Spacer(Modifier.weight(1f))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(22.dp).clip(shapes.control).background(colors.accent), Alignment.Center) { Icon(OndaraIcons.Play, null, tint = colors.onAccent, size = 12.dp) }
                }
                Spacer(Modifier.height(8.dp))
                when (spec.navigation) {
                    NavStyle.FLOATING_GLASS_PILL -> Row(
                        Modifier.align(Alignment.CenterHorizontally).clip(CircleShape).background(colors.surfaceHigh).padding(horizontal = 8.dp, vertical = 5.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) { repeat(3) { Box(Modifier.size(8.dp).clip(CircleShape).background(if (it == 0) colors.accent else colors.onMuted)) } }
                    NavStyle.BOTTOM_BAR -> Row(Modifier.fillMaxWidth().background(colors.surface).padding(vertical = 5.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                        repeat(3) { Box(Modifier.size(8.dp).clip(shapes.small).background(if (it == 0) colors.accent else colors.onMuted)) }
                    }
                    NavStyle.SIDE_RAIL -> Unit
                }
            }
        }
    }
}

/**
 * Ajustes tras "Guardar y aplicar" un tema. Si el usuario cambió el layout del reproductor en el editor, pasa a
 * "Según el tema": sin esto el layout elegido nunca se veía, porque un `playerLayout` explícito ("CINEMATIC" por
 * defecto) tiene prioridad sobre el del tema en `resolvePlayerLayout` (causa raíz del P7 de v3). O(1).
 */
internal fun settingsAfterThemeSave(current: UserSettings, themeId: String, applyPlayerLayout: Boolean): UserSettings =
    current.copy(themeId = themeId, playerLayout = if (applyPlayerLayout) UserSettings.PLAYER_LAYOUT_THEME else current.playerLayout)
