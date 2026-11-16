package app.ondara.playback

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import app.ondara.core.model.PlayerState
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/** Widget de pantalla de inicio: título, artista y controles anterior / play-pausa / siguiente. */
@AndroidEntryPoint
class OndaraWidget : AppWidgetProvider() {
    @Inject lateinit var controller: PlayerControllerImpl

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        render(context, manager, ids, controller.state.value)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent) // Hilt inyecta aquí
        when (intent.action) {
            ACTION_TOGGLE -> controller.togglePlayPause()
            ACTION_NEXT -> controller.next()
            ACTION_PREVIOUS -> controller.previous()
        }
    }

    companion object {
        private const val ACTION_TOGGLE = "app.ondara.widget.TOGGLE"
        private const val ACTION_NEXT = "app.ondara.widget.NEXT"
        private const val ACTION_PREVIOUS = "app.ondara.widget.PREVIOUS"
        private var lastKey: String? = null

        /** Repinta todos los widgets si cambió algo visible (canción o estado de reproducción). */
        fun update(context: Context, state: PlayerState) {
            val key = "${state.current?.id}/${state.isPlaying}"
            if (key == lastKey) return
            lastKey = key
            val manager = AppWidgetManager.getInstance(context) ?: return
            val ids = manager.getAppWidgetIds(ComponentName(context, OndaraWidget::class.java))
            if (ids.isNotEmpty()) render(context, manager, ids, state)
        }

        private fun render(context: Context, manager: AppWidgetManager, ids: IntArray, state: PlayerState) {
            val views = RemoteViews(context.packageName, R.layout.widget_ondara)
            val song = state.current
            views.setTextViewText(R.id.widget_title, song?.title ?: context.getString(R.string.widget_idle))
            views.setTextViewText(R.id.widget_artist, song?.artistLine.orEmpty())
            views.setImageViewResource(R.id.widget_toggle, if (state.isPlaying) R.drawable.ic_widget_pause else R.drawable.ic_widget_play)
            views.setOnClickPendingIntent(R.id.widget_toggle, action(context, ACTION_TOGGLE))
            views.setOnClickPendingIntent(R.id.widget_next, action(context, ACTION_NEXT))
            views.setOnClickPendingIntent(R.id.widget_previous, action(context, ACTION_PREVIOUS))
            context.packageManager.getLaunchIntentForPackage(context.packageName)?.let { launch ->
                views.setOnClickPendingIntent(R.id.widget_root, PendingIntent.getActivity(context, 0, launch, PendingIntent.FLAG_IMMUTABLE))
            }
            manager.updateAppWidget(ids, views)
        }

        private fun action(context: Context, action: String): PendingIntent = PendingIntent.getBroadcast(
            context, action.hashCode(), Intent(context, OndaraWidget::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
