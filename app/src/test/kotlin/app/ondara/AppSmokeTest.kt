package app.ondara

import android.graphics.Bitmap
import android.os.Looper
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performSemanticsAction
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.rules.ExternalResource
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.nio.ByteBuffer
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Prueba de humo de la app real sobre Robolectric: arranca `MainActivity` con el grafo Hilt de
 * producción (Room, DataStore, navegación, temas) y recorre las pantallas principales en cada tema.
 * No sustituye a un dispositivo, pero detecta fallos de arranque, de inyección y de composición.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w393dp-h851dp-xhdpi")
class AppSmokeTest {
    /**
     * Sin red: todo el tráfico va a un proxy inexistente. Así la prueba es determinista (también en CI)
     * y evita decodificar imágenes en hilos de fondo, algo que el runtime nativo de Robolectric no soporta bien.
     */
    @get:Rule(order = 0)
    val offline = object : ExternalResource() {
        private var previous: ProxySelector? = null

        override fun before() {
            // El runtime nativo de Robolectric inicializa su caché JNI de clases java.nio con el primer uso;
            // si ese primer uso ocurre en un hilo de fondo (p. ej. medición de texto) aborta el proceso.
            // Se fuerza aquí, en el hilo del test, antes de lanzar la Activity.
            Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).copyPixelsToBuffer(ByteBuffer.allocate(16))
            previous = ProxySelector.getDefault()
            ProxySelector.setDefault(object : ProxySelector() {
                override fun select(uri: URI?) = listOf(Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved("127.0.0.1", 9)))

                override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) = Unit
            })
        }

        override fun after() = ProxySelector.setDefault(previous)
    }

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<MainActivity>()

    /** Avanza el reloj de Compose y deja trabajar a los hilos de fondo (DataStore, Room). */
    private fun settle(ms: Long = 1_200) {
        repeat((ms / 100).toInt()) {
            compose.mainClock.advanceTimeBy(100)
            Thread.sleep(15)
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    private fun shot(name: String) = compose.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")

    @Test
    fun appStartsNavigatesAndSwitchesEveryTheme() {
        compose.mainClock.autoAdvance = false
        settle(2_500)
        compose.onNodeWithText("Tu música, en otra frecuencia.").assertIsDisplayed()
        shot("home_ondara")

        compose.onNodeWithContentDescription("Buscar").performClick()
        settle()
        compose.onNodeWithText("Canciones, artistas, álbumes").assertIsDisplayed()

        compose.onNodeWithContentDescription("Biblioteca").performClick()
        settle()
        compose.onNodeWithText("Aún no tienes playlists").assertIsDisplayed()
        shot("library_ondara")

        compose.onNodeWithContentDescription("Ajustes").performClick()
        settle()
        compose.onNodeWithText("Calidad de streaming").assertIsDisplayed()
        shot("settings_ondara")

        compose.onNodeWithText("Temas").performClick()
        settle()
        shot("themes_gallery")
        for (theme in listOf("Cupertino", "Mono", "Vinilo", "Neón", "Dinámico", "Ondara")) {
            compose.onNodeWithContentDescription("Tema $theme", substring = true).performSemanticsAction(SemanticsActions.OnClick)
            settle(1_500)
            shot("themes_" + theme.lowercase())
        }

        // Vuelve al inicio con el último tema aplicado: la navegación sigue viva tras seis cambios.
        compose.onAllNodesWithContentDescription("Atrás").onFirst().performClick()
        settle()
        compose.onAllNodesWithContentDescription("Atrás").onFirst().performClick()
        settle()
        compose.onNodeWithText("Aún no tienes playlists").assertIsDisplayed()
        compose.onAllNodesWithText("Biblioteca").onFirst().assertIsDisplayed()
    }
}
