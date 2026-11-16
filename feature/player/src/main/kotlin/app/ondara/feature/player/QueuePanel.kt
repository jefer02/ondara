package app.ondara.feature.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.ondara.core.designsystem.component.Label
import app.ondara.core.designsystem.component.OndaraIconButton
import app.ondara.core.designsystem.component.Text
import app.ondara.core.designsystem.component.rememberHaptics
import app.ondara.core.designsystem.icon.Icon
import app.ondara.core.designsystem.icon.OndaraIcons
import app.ondara.core.designsystem.theme.OndaraTheme
import app.ondara.core.model.PlayerState
import app.ondara.core.model.Song
import app.ondara.core.ui.SongRow
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

internal data class QueueEntry(val key: String, val song: Song)

/** Claves estables aunque una canción aparezca varias veces en la cola: `id#ocurrencia`. O(n). */
internal fun queueEntries(queue: List<Song>): List<QueueEntry> {
    val seen = HashMap<String, Int>()
    return queue.map { song ->
        val n = seen.merge(song.id, 1, Int::plus)!!
        QueueEntry("${song.id}#$n", song)
    }
}

/** Cola editable: arrastra el asa para reordenar, toca para saltar, ✕ para quitar. */
@Composable
internal fun QueuePanel(player: PlayerState, onEvent: (PlayerEvent) -> Unit, onClose: () -> Unit) {
    val colors = OndaraTheme.colors
    val haptics = rememberHaptics()
    var entries by remember { mutableStateOf(queueEntries(player.queue)) }
    var dragging by remember { mutableStateOf(false) }
    // Mientras se arrastra manda la lista local; al soltar se vuelve a sincronizar con el reproductor.
    LaunchedEffect(player.queue, dragging) { if (!dragging) entries = queueEntries(player.queue) }

    val listState = rememberLazyListState(initialFirstVisibleItemIndex = player.currentIndex.coerceAtLeast(0))
    val reorderState = rememberReorderableLazyListState(listState) { from, to ->
        entries = entries.toMutableList().apply { add(to.index, removeAt(from.index)) }
        onEvent(PlayerEvent.MoveQueueItem(from.index, to.index))
        haptics.tick()
    }
    Column(Modifier.fillMaxSize().background(colors.background).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(start = OndaraTheme.spacing.screen, end = OndaraTheme.spacing.sm, top = OndaraTheme.spacing.sm), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("A continuación", Modifier.semantics { heading() }, style = OndaraTheme.type.display, maxLines = 1)
                Label("${entries.size} canciones · arrastra para reordenar")
            }
            OndaraIconButton(OndaraIcons.Close, "Cerrar cola", onClose)
        }
        LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(vertical = OndaraTheme.spacing.md)) {
            itemsIndexed(entries, key = { _, e -> e.key }, contentType = { _, _ -> "queue" }) { index, entry ->
                ReorderableItem(reorderState, key = entry.key) { isDragging ->
                    Box(
                        Modifier
                            .graphicsLayer {
                                val s = if (isDragging) 1.03f else 1f
                                scaleX = s
                                scaleY = s
                            }
                            .background(if (isDragging) colors.surfaceHigh else colors.background),
                    ) {
                        SongRow(
                            entry.song,
                            onClick = { onEvent(PlayerEvent.SkipTo(index)) },
                            trailing = {
                                if (index != player.currentIndex) {
                                    OndaraIconButton(OndaraIcons.Close, "Quitar ${entry.song.title} de la cola", { onEvent(PlayerEvent.RemoveQueueItem(index)) }, tint = colors.onMuted, size = 16.dp)
                                }
                                Box(
                                    Modifier
                                        .draggableHandle(
                                            onDragStarted = {
                                                dragging = true
                                                haptics.confirm()
                                            },
                                            onDragStopped = { dragging = false },
                                        )
                                        .padding(OndaraTheme.spacing.md),
                                ) {
                                    Icon(OndaraIcons.Drag, "Reordenar ${entry.song.title}", tint = colors.onMuted, size = 20.dp)
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}
