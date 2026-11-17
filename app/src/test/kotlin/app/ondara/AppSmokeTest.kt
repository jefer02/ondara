package app.ondara

import android.graphics.Bitmap
import android.os.Looper
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
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

    /**
     * Desplaza la lista vertical de la pantalla hasta que [matcher] esté compuesto: primero hacia abajo y luego
     * hacia arriba. No usa `performScrollToNode`, que necesita fotogramas y se bloquea con el reloj en pausa.
     */
    private fun scrollUntilComposed(matcher: SemanticsMatcher) {
        val list = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange)).onFirst()
        for (direction in listOf(1f, -1f)) {
            repeat(SCROLL_STEPS) {
                if (compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()) return
                list.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, direction * SCROLL_PX) }
                settle(400)
            }
        }
    }

    @Test
    fun appStartsNavigatesAndSwitchesEveryTheme() {
        compose.mainClock.autoAdvance = false
        settle(2_500)
        // Primer arranque: onboarding a pantalla completa, sin barra de pestañas.
        compose.onNodeWithText("Omitir").assertIsDisplayed()
        shot("onboarding")
        compose.onNodeWithText("Omitir").performClick()
        settle(2_000)
        compose.onNodeWithText("Tu música, en otra frecuencia.", substring = true).assertIsDisplayed()
        compose.onAllNodesWithText("Inicio").onFirst().assertIsDisplayed()
        shot("home_ondara")

        // Cuatro pestañas en la píldora y Buscar en su círculo aparte.
        compose.onNodeWithContentDescription("Novedades").performClick()
        settle()
        compose.onAllNodesWithText("Novedades").onFirst().assertIsDisplayed()
        compose.onNodeWithContentDescription("Radio").performClick()
        settle()
        compose.onNodeWithText("Ondara Flow").assertIsDisplayed()
        shot("radio_ondara")

        compose.onNodeWithContentDescription("Buscar").performClick()
        settle()
        compose.onNodeWithText("Artistas, canciones, letras y más").assertIsDisplayed()
        shot("search_ondara")

        compose.onNodeWithContentDescription("Biblioteca").performClick()
        settle()
        compose.onNodeWithText("Tu biblioteca empieza aquí").assertIsDisplayed()
        shot("library_ondara")

        // El avatar (con punto: sin cuentas) abre la hoja de Cuenta; de ahí se llega al perfil y a Ajustes.
        compose.onNodeWithContentDescription("Cuenta, sin cuentas conectadas").performClick()
        settle()
        compose.onNodeWithText("Ver perfil").assertIsDisplayed()
        shot("account_sheet")
        compose.onNodeWithText("Ver perfil").performClick()
        settle(1_800)
        shot("profile_ondara")
        compose.onAllNodesWithContentDescription("Atrás").onFirst().performClick()
        settle()

        compose.onNodeWithContentDescription("Cuenta, sin cuentas conectadas").performClick()
        settle()
        compose.onNodeWithText("Ajustes").performClick()
        settle()
        compose.onNodeWithText("Calidad de streaming").assertIsDisplayed()
        shot("settings_ondara")

        compose.onNodeWithText("Temas").performClick()
        settle()
        shot("themes_gallery")
        for (theme in listOf("Cupertino", "Mono", "Vinilo", "Neón", "Dinámico", "Tinta", "Ondara")) {
            // Con siete temas la galería no cabe en pantalla: se desplaza hasta la tarjeta antes de pulsarla.
            val card = hasContentDescription("Tema $theme", substring = true)
            scrollUntilComposed(card)
            compose.onNode(card).performSemanticsAction(SemanticsActions.OnClick)
            settle(1_500)
            shot("themes_" + theme.lowercase())
        }

        // Vuelve a la Biblioteca con el último tema aplicado: la navegación sigue viva tras siete cambios.
        compose.onAllNodesWithContentDescription("Atrás").onFirst().performClick()
        settle()
        compose.onAllNodesWithContentDescription("Atrás").onFirst().performClick()
        settle()
        compose.onNodeWithText("Tu biblioteca empieza aquí").assertIsDisplayed()
        compose.onAllNodesWithText("Biblioteca").onFirst().assertIsDisplayed()
    }

    private companion object {
        const val SCROLL_STEPS = 8
        const val SCROLL_PX = 700f
    }
}
