package app.ondara

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import app.ondara.core.designsystem.component.OndaraDialog
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

private const val RELEASES_API = "https://api.github.com/repos/jefer02/ondara/releases/latest"
private const val DOWNLOAD_PREFIX = "https://github.com/jefer02/ondara/releases/download/"
private val updateJson = Json { ignoreUnknownKeys = true }

@Serializable
internal data class ReleaseAsset(val name: String, @SerialName("browser_download_url") val downloadUrl: String)

@Serializable
internal data class GithubRelease(
    @SerialName("tag_name") val tag: String,
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    val assets: List<ReleaseAsset> = emptyList(),
)

internal data class AppUpdate(val version: String, val downloadUrl: String)

/** O(n) en el tamaño del JSON. Solo acepta versiones estables y APK del repositorio oficial. */
internal fun parseAppUpdate(body: String, installedCode: Int): AppUpdate? {
    val release = updateJson.decodeFromString<GithubRelease>(body)
    if (release.draft || release.prerelease) return null
    val match = Regex("v(0|[1-9][0-9]{0,2})\\.(0|[1-9][0-9]{0,2})\\.(0|[1-9][0-9]{0,2})")
        .matchEntire(release.tag) ?: return null
    val (major, minor, patch) = match.destructured
    val code = major.toInt() * 1_000_000 + minor.toInt() * 1_000 + patch.toInt()
    if (code <= installedCode) return null
    val version = release.tag.removePrefix("v")
    val name = "Ondara-$version.apk"
    val url = "$DOWNLOAD_PREFIX${release.tag}/$name"
    return release.assets.firstOrNull { it.name == name && it.downloadUrl == url }
        ?.let { AppUpdate(version, url) }
}

private suspend fun checkAppUpdate(): AppUpdate? = withContext(Dispatchers.IO) {
    val client = OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS).build()
    val request = Request.Builder().url(RELEASES_API)
        .header("Accept", "application/vnd.github+json")
        .header("User-Agent", "Ondara/${BuildConfig.VERSION_NAME}").build()
    try {
        client.newCall(request).execute().use { response ->
            if (response.isSuccessful) response.body?.string()?.let { parseAppUpdate(it, BuildConfig.VERSION_CODE) } else null
        }
    } catch (_: IOException) {
        null // Sin conexión o límite de GitHub: volverá a comprobarse al abrir la app.
    } catch (_: IllegalArgumentException) {
        null // Una respuesta incompatible no debe impedir usar el reproductor.
    }
}

@Composable
internal fun AppUpdatePrompt() {
    var update by remember { mutableStateOf<AppUpdate?>(null) }
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        if (!BuildConfig.DEBUG) update = checkAppUpdate()
    }
    update?.let { available ->
        OndaraDialog(
            title = "Nueva versión disponible",
            text = "Ondara ${available.version} está lista. Descarga el APK y ábrelo para confirmar la actualización en Android.",
            confirmText = "Descargar",
            dismissText = "Más tarde",
            onDismiss = { update = null },
            onConfirm = {
                try {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(available.downloadUrl)))
                    update = null
                } catch (_: ActivityNotFoundException) {
                    Toast.makeText(context, "Instala un navegador para descargar la actualización.", Toast.LENGTH_LONG).show()
                }
            },
        )
    }
}
