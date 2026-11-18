package app.ondara.feature.account

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.ondara.core.designsystem.theme.OndaraTheme
import app.ondara.core.designsystem.theme.ThemeJson
import app.ondara.core.model.UserSettings
import java.io.File
import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w393dp-h851dp-xhdpi")
class ProfilePhotoTest {
    @get:Rule val compose = createComposeRule()

    @get:Rule val directory = TemporaryFolder()

    @Test fun recortaGuardaYVuelveACargarElPng() {
        val source = Bitmap.createBitmap(1600, 1200, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        val path = saveProfilePhoto(directory.root, source, PhotoCrop())
        assertEquals(File(directory.root, "profile/avatar.png").absolutePath, path)
        val reopened = BitmapFactory.decodeFile(path)
        assertEquals(1024, reopened.width)
        assertEquals(1024, reopened.height)
        assertEquals(Color.RED, reopened.getPixel(40, 40))
        assertEquals(150, PhotoCrop(x = 1f, zoom = 2f).rect(200, 100).left)
    }

    @Test fun usaInicialesYPermiteDesactivarElEfecto() {
        Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).copyPixelsToBuffer(ByteBuffer.allocate(16))
        val spec = ThemeJson.decode(File("../../core/designsystem/src/main/assets/themes/ondara.json").readText())
        var state by mutableStateOf(ProfileUiState(
            settings = UserSettings(profileName = "Luna Sol"), name = "Luna Sol", loading = false,
        ))
        compose.setContent {
            OndaraTheme(spec, reduceMotion = true, animateColors = false) {
                ProfileContent(state, { event ->
                    when (event) {
                        ProfileEvent.Edit -> state = state.copy(editing = true)
                        is ProfileEvent.PixelEffect -> state = state.copy(settings = state.settings.copy(avatarPixelEffect = event.enabled))
                        else -> Unit
                    }
                })
            }
        }
        compose.onNodeWithText("LS").assertIsDisplayed()
        compose.onNodeWithContentDescription("Editar perfil").performClick()
        val effect = compose.onNodeWithContentDescription("Efecto píxel")
        effect.assertIsOn().performClick()
        effect.assertIsOff()
    }
}
