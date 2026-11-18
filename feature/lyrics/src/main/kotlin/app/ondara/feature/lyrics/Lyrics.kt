package app.ondara.feature.lyrics

import android.os.Build
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.ondara.core.designsystem.component.EmptyState
import app.ondara.core.designsystem.component.Label
import app.ondara.core.designsystem.component.LocalBottomInset
import app.ondara.core.designsystem.component.MeshGradientBackground
import app.ondara.core.designsystem.component.OndaraIconButton
import app.ondara.core.designsystem.component.OndaraSheet
import app.ondara.core.designsystem.component.Skeleton
import app.ondara.core.designsystem.component.Text
import app.ondara.core.designsystem.component.pressable
import app.ondara.core.designsystem.icon.OndaraIcons
import app.ondara.core.designsystem.theme.OndaraTheme
import app.ondara.core.domain.LibraryRepository
import app.ondara.core.domain.LyricsRepository
import app.ondara.core.domain.PlayerController
import app.ondara.core.domain.SettingsRepository
import app.ondara.core.model.LyricLine
import app.ondara.core.model.Lyrics
import app.ondara.core.model.Song
import app.ondara.core.ui.Artwork
import app.ondara.core.ui.MenuAction
import app.ondara.core.ui.MviViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.math.abs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

data class LyricsUiState(
    val song: Song? = null,
    val loading: Boolean = false,
    val lyrics: Lyrics? = null,
    val favorite: Boolean = false,
    val translate: Boolean = false,
)

sealed interface LyricsEvent {
    data class Seek(val positionMs: Long) : LyricsEvent
    data object Retry : LyricsEvent
    data object ToggleFavorite : LyricsEvent
    data object ToggleTranslation : LyricsEvent
}

@HiltViewModel
class LyricsViewModel @Inject constructor(
    private val repository: LyricsRepository,
    private val player: PlayerController,
    private val library: LibraryRepository,
    private val settings: SettingsRepository,
) : MviViewModel<LyricsUiState, LyricsEvent, Nothing>(LyricsUiState()) {
    private var loadJob: Job? = null

    init {
        viewModelScope.launch {
            combine(
                player.state.map { it.current }.distinctUntilChanged { a, b -> a?.id == b?.id },
                settings.settings.map { it.translateLyrics }.distinctUntilChanged(),
            ) { song, translate -> song to translate }.collect { (song, translate) ->
                val translationChanged = state.value.translate != translate
                setState { copy(translate = translate) }
                reload(song, translationChanged)
            }
        }
        viewModelScope.launch {
            player.state.map { it.current?.id }.distinctUntilChanged().collectLatest { id ->
                setState { copy(favorite = false) }
                if (id != null) library.isFavorite(id).collect { setState { copy(favorite = it) } }
            }
        }
    }

    fun positionMs(): Long = player.positionMs()

    override fun onEvent(event: LyricsEvent) {
        when (event) {
            is LyricsEvent.Seek -> player.seekTo(event.positionMs)
            LyricsEvent.Retry -> reload(state.value.song, true)
            LyricsEvent.ToggleFavorite -> state.value.song?.let { song ->
                viewModelScope.launch { library.toggleFavorite(song) }
            }
            LyricsEvent.ToggleTranslation -> viewModelScope.launch {
                settings.update { it.copy(translateLyrics = !it.translateLyrics) }
            }
        }
    }

    private fun reload(song: Song?, force: Boolean) {
        loadJob?.cancel()
        setState { copy(song = song, loading = song != null, lyrics = null) }
        if (song == null) return
        loadJob = viewModelScope.launch {
            val lyrics = try {
                repository.lyrics(song, force)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            setState { copy(loading = false, lyrics = lyrics) }
        }
    }
}

/**
 * Posición dentro de una línea como "caracteres cantados" (fraccionario).
 * Con sincronía por palabra interpola dentro de la palabra en curso; sin ella, toda la línea está activa.
 * O(w) con w palabras de la línea (≈ 10).
 */
internal fun sungChars(line: LyricLine, positionMs: Long): Float {
    if (line.words.isEmpty()) return Float.MAX_VALUE
    var chars = 0f
    for (word in line.words) {
        if (positionMs >= word.endMs) {
            chars += word.text.length
        } else {
            if (positionMs > word.startMs) {
                chars += word.text.length * (positionMs - word.startMs).toFloat() / (word.endMs - word.startMs).coerceAtLeast(1)
            }
            break
        }
    }
    return chars
}

/**
 * Letra sincronizada: la línea activa viaja al centro con un spring y se ilumina palabra por palabra
 * con un gradiente que avanza con el tiempo; el resto queda atenuado y desenfocado.
 *
 * Rendimiento: el índice activo se busca por búsqueda binaria (O(log n)) una vez por frame y solo
 * recompone cuando cambia de línea; el barrido de la palabra se pinta en la fase de dibujo.
 */
@Composable
fun LyricsView(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(vertical = 120.dp),
    showHeader: Boolean = true,
    viewModel: LyricsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LyricsContent(state, viewModel::onEvent, viewModel::positionMs, modifier, contentPadding, showHeader)
}

@Composable
internal fun LyricsContent(
    state: LyricsUiState, onEvent: (LyricsEvent) -> Unit, positionMs: () -> Long,
    modifier: Modifier = Modifier, contentPadding: PaddingValues = PaddingValues(vertical = 120.dp), showHeader: Boolean = true,
) {
    val lyrics = state.lyrics
    val padding = PaddingValues(
        start = contentPadding.calculateLeftPadding(androidx.compose.ui.unit.LayoutDirection.Ltr),
        end = contentPadding.calculateRightPadding(androidx.compose.ui.unit.LayoutDirection.Ltr),
        top = contentPadding.calculateTopPadding(),
        bottom = contentPadding.calculateBottomPadding() + LocalBottomInset.current,
    )
    Box(modifier.fillMaxSize().background(OndaraTheme.colors.background)) {
        LyricsBackground(state.song)
        Column(Modifier.fillMaxSize()) {
            if (showHeader) LyricsHeader(state) { onEvent(it) }
            when {
                state.loading -> Column(Modifier.fillMaxSize().padding(OndaraTheme.spacing.xxl), verticalArrangement = Arrangement.Center) {
                    repeat(5) { Skeleton(Modifier.padding(vertical = OndaraTheme.spacing.md).size((260 - it * 28).dp, 26.dp)) }
                }
                lyrics == null || lyrics.lines.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    EmptyState(OndaraIcons.Lyrics, "Sin letra", message = "Ningún proveedor tiene la letra de esta canción.",
                        action = "Buscar de nuevo", onAction = { onEvent(LyricsEvent.Retry) })
                }
                !lyrics.synced -> PlainLyrics(lyrics, Modifier, padding)
                else -> SyncedLyrics(lyrics, positionMs, { onEvent(LyricsEvent.Seek(it)) }, Modifier, padding)
            }
        }
    }
}

@Composable
private fun LyricsBackground(song: Song?) {
    if (OndaraTheme.materials.blur == 0.dp) return
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        Artwork(song?.thumbnailUrl, Modifier.fillMaxSize().scale(1.25f).blur(80.dp), sizePx = 480, playing = true)
    }
    MeshGradientBackground(OndaraTheme.artwork, Modifier.fillMaxSize().alpha(0.6f))
    // El velo conserva el contraste del texto sobre las zonas claras de la portada.
    Box(Modifier.fillMaxSize().background(OndaraTheme.colors.background.copy(alpha = 0.86f)))
}

@Composable
private fun LyricsHeader(state: LyricsUiState, onEvent: (LyricsEvent) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(OndaraTheme.spacing.screen), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(OndaraTheme.spacing.md)) {
        Artwork(state.song?.thumbnailUrl, Modifier.size(OndaraTheme.spacing.touch), shape = OndaraTheme.shapes.artwork)
        Column(Modifier.weight(1f)) {
            Text(state.song?.title.orEmpty(), style = OndaraTheme.type.headline, maxLines = 1)
            Text(state.song?.artistLine.orEmpty(), style = OndaraTheme.type.caption, color = OndaraTheme.colors.onMuted, maxLines = 1)
        }
        OndaraIconButton(if (state.favorite) OndaraIcons.StarFilled else OndaraIcons.Star,
            if (state.favorite) "Quitar de favoritas" else "Añadir a favoritas", { onEvent(LyricsEvent.ToggleFavorite) })
        OndaraIconButton(OndaraIcons.More, "Opciones de letra", { menu = true })
    }
    if (menu) OndaraSheet({ menu = false }, title = "Letra") {
        MenuAction(OndaraIcons.Search, "Buscar letra de nuevo", { menu = false; onEvent(LyricsEvent.Retry) })
        MenuAction(OndaraIcons.Translate, "Traducción: ${if (state.translate) "desactivar" else "activar"}",
            { menu = false; onEvent(LyricsEvent.ToggleTranslation) })
        Text("Proveedor: ${state.lyrics?.provider ?: "Sin letra"}", Modifier.padding(OndaraTheme.spacing.screen), style = OndaraTheme.type.body)
    }
}

/** Observa la línea activa en O(log n) por frame y solo recompone al cambiar el índice. */
@Composable
private fun activeLine(lyrics: Lyrics?, viewModel: LyricsViewModel): Int {
    var active by remember(lyrics) { mutableIntStateOf(-1) }
    LaunchedEffect(lyrics, viewModel) {
        if (lyrics?.synced != true) return@LaunchedEffect
        while (true) withFrameMillis { active = lyrics.activeLineIndex(viewModel.positionMs()) }
    }
    return active
}

/** Línea sincronizada actual, con fundido y desplazamiento corto al cambiar. */
@Composable
fun CurrentLyricLine(modifier: Modifier = Modifier, color: Color = Color.Unspecified, viewModel: LyricsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val index = activeLine(state.lyrics, viewModel)
    val line = state.lyrics?.takeIf { it.synced }?.lines?.getOrNull(index)
    val motion = OndaraTheme.motion
    val offset = with(LocalDensity.current) { OndaraTheme.spacing.md.roundToPx() }
    AnimatedContent(line, modifier, transitionSpec = {
        (fadeIn(motion.spring()) + slideInVertically(motion.spring()) { if (motion.reduced) 0 else offset }) togetherWith
            (fadeOut(motion.spring()) + slideOutVertically(motion.spring()) { if (motion.reduced) 0 else -offset })
    }, label = "línea actual") { current ->
        if (current != null) Text(current.text, style = OndaraTheme.type.lyric.copy(fontWeight = FontWeight.Bold),
            color = color, textAlign = TextAlign.Start)
    }
}

/** Ventana de O(maxLines) líneas alrededor de la activa, con estado vacío compacto. */
@Composable
fun LyricsPreview(modifier: Modifier = Modifier, maxLines: Int = 5, viewModel: LyricsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val index = activeLine(state.lyrics, viewModel)
    val lines = state.lyrics?.lines.orEmpty()
    val count = maxLines.coerceAtLeast(1)
    val start = (index - count / 2).coerceAtLeast(0).coerceAtMost((lines.size - count).coerceAtLeast(0))
    Column(modifier, verticalArrangement = Arrangement.spacedBy(OndaraTheme.spacing.sm)) {
        when {
            state.loading -> Skeleton(Modifier.fillMaxWidth().size(OndaraTheme.spacing.touch))
            lines.isEmpty() -> Text("Sin letra disponible", style = OndaraTheme.type.body, color = OndaraTheme.colors.onMuted)
            else -> lines.subList(start, (start + count).coerceAtMost(lines.size)).forEachIndexed { i, line ->
                Column {
                    Text(line.text, style = OndaraTheme.type.headline, color = if (start + i == index) {
                        OndaraTheme.colors.onBackground
                    } else { OndaraTheme.colors.onMuted })
                    line.translation?.let { Text(it, style = OndaraTheme.type.body, color = OndaraTheme.colors.onMuted) }
                }
            }
        }
    }
}

@Composable
private fun PlainLyrics(lyrics: Lyrics, modifier: Modifier, contentPadding: PaddingValues) {
    LazyColumn(modifier.fillMaxSize(), contentPadding = contentPadding) {
        itemsIndexed(lyrics.lines, key = { i, _ -> i }, contentType = { _, _ -> "line" }) { _, line ->
            Column(Modifier.fillMaxWidth().padding(horizontal = OndaraTheme.spacing.xxl, vertical = OndaraTheme.spacing.sm)) {
                Text(line.text, style = OndaraTheme.type.lyric.copy(fontWeight = FontWeight.Bold), textAlign = TextAlign.Start)
                line.translation?.let {
                    Text(it, Modifier.padding(top = OndaraTheme.spacing.sm), style = OndaraTheme.type.headline,
                        color = OndaraTheme.colors.onBackground.copy(alpha = 0.75f), textAlign = TextAlign.Start)
                }
            }
        }
        item(key = "provider", contentType = "provider") { Label("Letra sin sincronizar · ${lyrics.provider}", Modifier.padding(OndaraTheme.spacing.xxl)) }
    }
}

@Composable
private fun SyncedLyrics(lyrics: Lyrics, positionMs: () -> Long, onSeek: (Long) -> Unit, modifier: Modifier, contentPadding: PaddingValues) {
    val listState = rememberLazyListState()
    val motion = OndaraTheme.motion
    var active by remember(lyrics) { mutableIntStateOf(-1) }
    var position by remember { mutableLongStateOf(0L) }
    var userScrolledAt by remember { mutableLongStateOf(0L) }

    LaunchedEffect(lyrics) {
        while (true) {
            withFrameMillis {
                val now = positionMs()
                position = now // solo se lee en la fase de dibujo
                val index = lyrics.activeLineIndex(now)
                if (index != active) active = index
            }
        }
    }
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { if (it is DragInteraction.Start) userScrolledAt = System.currentTimeMillis() }
    }
    // La línea activa viaja al tercio superior con el spring del tema (salvo que el usuario esté explorando).
    LaunchedEffect(active) {
        if (active < 0 || System.currentTimeMillis() - userScrolledAt < 3_000) return@LaunchedEffect
        val info = listState.layoutInfo
        val item = info.visibleItemsInfo.firstOrNull { it.index == active }
        val target = info.viewportStartOffset + (info.viewportEndOffset - info.viewportStartOffset) * 0.32f
        if (item == null) {
            listState.scrollToItem(active, -(target - info.viewportStartOffset).toInt() + info.beforeContentPadding)
        } else {
            listState.animateScrollBy(item.offset + item.size / 2f - target, motion.spring())
        }
    }

    LazyColumn(modifier.fillMaxSize(), state = listState, contentPadding = contentPadding) {
        itemsIndexed(lyrics.lines, key = { i, _ -> i }, contentType = { _, _ -> "line" }) { index, line ->
            LyricLineRow(line, distance = if (active < 0) 2 else abs(index - active), isActive = index == active, position = { position }) {
                onSeek(line.startMs)
            }
        }
        item(key = "provider", contentType = "provider") { Label("Letra: ${lyrics.provider}", Modifier.padding(OndaraTheme.spacing.xxl)) }
    }
}

@Composable
private fun LyricLineRow(line: LyricLine, distance: Int, isActive: Boolean, position: () -> Long, onClick: () -> Unit) {
    val colors = OndaraTheme.colors
    val motion = OndaraTheme.motion
    val style = OndaraTheme.type.lyric.copy(fontWeight = FontWeight.Bold)
    val emphasis by animateFloatAsState(if (isActive) 1f else 0f, motion.spring(), label = "lyricEmphasis")
    val blurRadius = if (isActive || motion.reduced || OndaraTheme.materials.blur == 0.dp || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
        0.dp
    } else {
        (distance * 1.2f).coerceAtMost(5f).dp
    }
    val inactiveAlpha = (0.45f - distance * 0.07f).coerceAtLeast(0.17f)
    val text = line.text.ifEmpty { "♪" }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    Column(
        Modifier
            .fillMaxWidth()
            .pressable(onClickLabel = "Saltar a esta línea", onClick = onClick)
            .heightIn(min = OndaraTheme.spacing.touch)
            .padding(horizontal = OndaraTheme.spacing.xxl, vertical = OndaraTheme.spacing.md)
            .graphicsLayer {
                val s = 0.94f + 0.06f * emphasis
                scaleX = s
                scaleY = s
                transformOrigin = TransformOrigin(0f, 0.5f)
                alpha = inactiveAlpha + (1f - inactiveAlpha) * emphasis
            }
            .then(if (blurRadius > 0.dp) Modifier.blur(blurRadius) else Modifier)
            .semantics(mergeDescendants = true) { contentDescription = listOfNotNull(text, line.translation).joinToString(". ") },
    ) {
        Box {
            // Capa base: la línea completa en tono atenuado.
            BasicText(
                text,
                Modifier.clearAndSetSemantics { },
                style = style.copy(color = if (isActive && line.words.isNotEmpty()) colors.onBackground.copy(alpha = 0.38f) else colors.onBackground, textAlign = TextAlign.Start),
                onTextLayout = { layout = it },
            )
            if (isActive && line.words.isNotEmpty()) {
                // Capa brillante: se borra (DstOut) lo que aún no se ha cantado, con un borde difuminado.
                BasicText(
                    text,
                    Modifier
                        .clearAndSetSemantics { }
                        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                        .drawWithContent {
                            drawContent()
                            val result = layout ?: return@drawWithContent
                            val sung = sungChars(line, position()).coerceIn(0f, text.length.toFloat())
                            val whole = sung.toInt().coerceAtMost(text.length - 1).coerceAtLeast(0)
                            val fraction = sung - sung.toInt()
                            val currentLine = result.getLineForOffset(whole)
                            val box = result.getBoundingBox(whole)
                            val x = if (sung >= text.length) result.getLineRight(currentLine) + 40f else box.left + box.width * fraction
                            val feather = 28.dp.toPx()
                            for (l in 0 until result.lineCount) {
                                val top = result.getLineTop(l)
                                val height = result.getLineBottom(l) - top
                                when {
                                    l < currentLine -> Unit
                                    l > currentLine -> drawRect(Color.Black, Offset(0f, top), Size(size.width, height), blendMode = BlendMode.DstOut)
                                    else -> drawRect(
                                        Brush.horizontalGradient(listOf(Color.Transparent, Color.Black), x, x + feather),
                                        Offset(x, top), Size((size.width - x).coerceAtLeast(0f) + feather, height), blendMode = BlendMode.DstOut,
                                    )
                                }
                            }
                        },
                    style = style.copy(color = colors.onBackground, textAlign = TextAlign.Start),
                )
            }
        }
        line.translation?.let {
            Text(it, Modifier.padding(top = OndaraTheme.spacing.sm).clearAndSetSemantics { },
                style = OndaraTheme.type.headline, color = colors.onBackground.copy(alpha = 0.75f), textAlign = TextAlign.Start)
        }
    }
}
