package app.ondara.feature.player

import androidx.compose.runtime.Composable
import app.ondara.core.designsystem.component.OndaraSheet
import app.ondara.core.designsystem.icon.OndaraIcons
import app.ondara.core.model.PlayerState
import app.ondara.core.ui.MenuAction

@Composable
internal fun PlayerTimer(player: PlayerState, onEvent: (PlayerEvent) -> Unit, onClose: () -> Unit) {
    OndaraSheet(onClose, title = "Temporizador de sueño") {
        if (player.sleepTimerEndsAtMs != null) {
            MenuAction(OndaraIcons.Close, "Cancelar temporizador", { onEvent(PlayerEvent.SleepTimer(null)); onClose() })
        }
        listOf(5, 15, 30, 45, 60, 90).forEach { minutes ->
            MenuAction(OndaraIcons.Timer, "$minutes minutos", { onEvent(PlayerEvent.SleepTimer(minutes)); onClose() })
        }
    }
}
