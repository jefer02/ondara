package app.ondara.core.designsystem.theme

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Catálogo de temas: los incluidos en la carpeta `assets/themes` más los creados/importados por el
 * usuario en la carpeta `files/themes`. Añadir un tema = añadir un JSON, sin tocar código.
 */
@Singleton
class ThemeStore @Inject constructor(@ApplicationContext private val context: Context) {
    private val customDir = File(context.filesDir, "themes")
    private val _themes = MutableStateFlow(loadAll())
    val themes: StateFlow<List<ThemeSpec>> = _themes.asStateFlow()

    val default: ThemeSpec get() = _themes.value.firstOrNull { it.id == DEFAULT_ID } ?: _themes.value.first()

    fun byId(id: String): ThemeSpec = _themes.value.firstOrNull { it.id == id } ?: default

    /** Guarda (o reemplaza) un tema del usuario y devuelve la versión persistida. */
    suspend fun save(spec: ThemeSpec): ThemeSpec = withContext(Dispatchers.IO) {
        val safeId = spec.id.lowercase().replace(Regex("[^a-z0-9_-]"), "-").ifBlank { "tema" }
        val builtIn = builtInIds()
        val id = if (safeId in builtIn) "$safeId-custom" else safeId
        val stored = spec.copy(id = id, custom = true)
        customDir.mkdirs()
        File(customDir, "$id.json").writeText(ThemeJson.encode(stored))
        _themes.value = loadAll()
        stored
    }

    /** Valida e importa un JSON externo. @throws IllegalArgumentException si no es un tema válido. */
    suspend fun import(json: String): ThemeSpec {
        val spec = try {
            ThemeJson.decode(json)
        } catch (e: Exception) {
            throw IllegalArgumentException("El archivo no es un tema de Ondara válido", e)
        }
        validate(spec)
        return save(spec)
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        File(customDir, "$id.json").delete()
        _themes.value = loadAll()
    }

    fun export(id: String): String = ThemeJson.encode(byId(id))

    private fun builtInIds(): Set<String> =
        context.assets.list(ASSET_DIR).orEmpty().map { it.removeSuffix(".json") }.toSet()

    private fun loadAll(): List<ThemeSpec> {
        val builtIn = context.assets.list(ASSET_DIR).orEmpty().filter { it.endsWith(".json") }.mapNotNull { name ->
            runCatching { ThemeJson.decode(context.assets.open("$ASSET_DIR/$name").bufferedReader().use { it.readText() }) }.getOrNull()
        }.sortedBy { ORDER.indexOf(it.id).let { i -> if (i < 0) Int.MAX_VALUE else i } }
        val custom = customDir.listFiles { f -> f.extension == "json" }.orEmpty().sortedBy { it.name }.mapNotNull { file ->
            runCatching { ThemeJson.decode(file.readText()).copy(custom = true) }.getOrNull()
        }
        return builtIn + custom
    }

    companion object {
        const val DEFAULT_ID = "ondara"
        private const val ASSET_DIR = "themes"
        private val ORDER = listOf("ondara", "cupertino", "mono", "vinilo", "neon", "dinamico", "tinta")

        /** Comprueba que los colores se puedan parsear; lanza `IllegalArgumentException` si no. */
        fun validate(spec: ThemeSpec) {
            require(spec.id.isNotBlank() && spec.name.isNotBlank()) { "El tema necesita id y nombre" }
            for (p in listOfNotNull(spec.colors.dark, spec.colors.light)) {
                listOf(p.background, p.surface, p.surfaceHigh, p.onBackground, p.onMuted, p.accent, p.onAccent, p.outline, p.error)
                    .forEach(ColorMath::parseHex)
            }
        }
    }
}
