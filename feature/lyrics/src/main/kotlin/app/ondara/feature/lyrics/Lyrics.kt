package app.ondara.feature.lyrics

import android.os.Build
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.ondara.core.designsystem.component.EmptyState
import app.ondara.core.designsystem.component.Label
import app.ondara.core.designsystem.component.Skeleton
import app.ondara.core.designsystem.component.Text
import app.ondara.core.designsystem.component.pressable
import app.ondara.core.designsystem.icon.OndaraIcons
import app.ondara.core.designsystem.theme.OndaraTheme
import app.ondara.core.domain.LyricsRepository
import app.ondara.core.domain.PlayerController
import app.ondara.core.model.LyricLine
import app.ondara.core.model.Lyrics
import app.ondara.core.model.Song
import app.ondara.core.ui.MviViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.abs

data class LyricsUiState(val song: Song? = null, val loading: Boolean = false, val lyrics: Lyrics? = null)

sealed interface LyricsEvent {
    data class Seek(val positionMs: Long) : LyricsEvent
    data object Retry : LyricsEvent
}

@HiltViewModel
class LyricsViewModel @Inject constructor(
    private val repository: LyricsRepository,
    private val player: PlayerController,
) : MviViewModel<LyricsUiState, LyricsEvent, Nothing>(LyricsUiState()) {
    init {
        viewModelScope.launch {
            player.state.map { it.current }.distinctUntilChanged { a, b -> a?.id == b?.id }.collectLatest { load(it, false) }
        }
    }

    fun positionMs(): Long = player.positionMs()

    override fun onEvent(event: LyricsEvent) {
        when (event) {
            is LyricsEvent.Seek -> player.seekTo(event.positionMs)
            LyricsEvent.Retry -> viewModelScope.launch { load(state.value.song, true) }
        }
    }

    private suspend fun load(song: Song?, force: Boolean) {
        setState { LyricsUiState(song, loading = song != null) }
        if (song == null) return
        val lyrics = runCatching { repository.lyrics(song, force) }.getOrNull()
        setState { if (this.song?.id == song.id) copy(loading = false, lyrics = lyrics) else this }
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
    viewModel: LyricsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val lyrics = state.lyrics
    when {
        state.loading -> Column(modifier.fillMaxSize().padding(OndaraTheme.spacing.xxl), verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center) {
            repeat(5) { Skeleton(Modifier.padding(vertical = 10.dp).size((260 - it * 28).dp, 26.dp)) }
        }
        lyrics == null -> Box(modifier.fillMaxSize(), Alignment.Center) {
            EmptyState(OndaraIcons.Lyrics, "Sin letra", message = "Ningún proveedor tiene la letra de esta canción.", action = "Buscar de nuevo", onAction = { viewModel.onEvent(LyricsEvent.Retry) })
        }
        !lyrics.synced -> PlainLyrics(lyrics, modifier, contentPadding)
        else -> SyncedLyrics(lyrics, viewModel::positionMs, { viewModel.onEvent(LyricsEvent.Seek(it)) }, modifier, contentPadding)
    }
}

@Composable
private fun PlainLyrics(lyrics: Lyrics, modifier: Modifier, contentPadding: PaddingValues) {
    LazyColumn(modifier.fillMaxSize(), contentPadding = contentPadding) {
        itemsIndexed(lyrics.lines, key = { i, _ -> i }, contentType = { _, _ -> "line" }) { _, line ->
            Text(line.text, Modifier.fillMaxWidth().padding(horizontal = OndaraTheme.spacing.xxl, vertical = 6.dp), style = OndaraTheme.type.title, maxLines = 6)
        }
        item { Label("Letra sin sincronizar · ${lyrics.provider}", Modifier.padding(OndaraTheme.spacing.xxl)) }
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
        item(key = "provider") { Label("Letra: ${lyrics.provider}", Modifier.padding(OndaraTheme.spacing.xxl)) }
    }
}

@Composable
private fun LyricLineRow(line: LyricLine, distance: Int, isActive: Boolean, position: () -> Long, onClick: () -> Unit) {
    val colors = OndaraTheme.colors
    val motion = OndaraTheme.motion
    val style = OndaraTheme.type.lyric
    val emphasis by animateFloatAsState(if (isActive) 1f else 0f, motion.spring(), label = "lyricEmphasis")
    val blurRadius = if (isActive || motion.reduced || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) 0.dp else (distance.coerceAtMost(3) * 1.1f).dp
    val text = line.text.ifEmpty { "♪" }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    Box(
        Modifier
            .fillMaxWidth()
            .pressable(onClickLabel = "Saltar a esta línea", onClick = onClick)
            .padding(horizontal = OndaraTheme.spacing.xxl, vertical = 9.dp)
            .graphicsLayer {
                val s = 0.94f + 0.06f * emphasis
                scaleX = s
                scaleY = s
                transformOrigin = TransformOrigin(0f, 0.5f)
                alpha = 0.34f + 0.66f * emphasis
            }
            .then(if (blurRadius > 0.dp) Modifier.blur(blurRadius) else Modifier)
            .semantics(mergeDescendants = true) { contentDescription = text },
    ) {
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
}
