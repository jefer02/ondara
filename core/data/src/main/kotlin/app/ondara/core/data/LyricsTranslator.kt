package app.ondara.core.data

import app.ondara.core.model.Lyrics
import app.ondara.core.network.array
import app.ondara.core.network.get
import app.ondara.core.network.string
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonElement
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Traduce una letra línea a línea al idioma del usuario (la vista de letras la muestra bajo cada
 * línea, en tamaño menor).
 *
 * Usa el endpoint público de Google Translate (`translate_a/single`, cliente `gtx`), sin clave. Las
 * líneas se envían en lotes separados por saltos de línea; si un lote vuelve con otro número de
 * líneas se descarta (mejor sin traducción que desalineada). Si el idioma detectado ya es el de
 * destino, la letra se devuelve intacta. El resultado se guarda con la letra en Room, así que solo
 * hay red la primera vez. Se puede desactivar en Ajustes.
 *
 * Complejidad: O(n) sobre las líneas, en ⌈caracteres / [BATCH_CHARS]⌉ peticiones.
 */
@Singleton
class LyricsTranslator @Inject constructor(private val http: HttpClient) {
    suspend fun translate(lyrics: Lyrics, target: String): Lyrics {
        val indices = lyrics.lines.indices.filter { lyrics.lines[it].text.isNotBlank() }
        if (indices.isEmpty()) return lyrics
        val translations = HashMap<Int, String>()
        for (batch in batches(indices.map { it to lyrics.lines[it].text.replace('\n', ' ') })) {
            val result = try {
                request(batch.joinToString("\n") { it.second }, target)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                return lyrics
            } ?: return lyrics // mismo idioma: no hay nada que traducir
            val lines = result.split('\n')
            if (lines.size != batch.size) continue
            batch.forEachIndexed { i, (index, original) ->
                val text = lines[i].trim()
                if (text.isNotEmpty() && !text.equals(original.trim(), ignoreCase = true)) translations[index] = text
            }
        }
        if (translations.isEmpty()) return lyrics
        return lyrics.copy(lines = lyrics.lines.mapIndexed { i, line -> translations[i]?.let { line.copy(translation = it) } ?: line })
    }

    /** Agrupa las líneas en lotes de como mucho [BATCH_CHARS] caracteres. O(n). */
    internal fun batches(lines: List<Pair<Int, String>>): List<List<Pair<Int, String>>> {
        val out = ArrayList<List<Pair<Int, String>>>()
        var current = ArrayList<Pair<Int, String>>()
        var size = 0
        for (line in lines) {
            if (size + line.second.length + 1 > BATCH_CHARS && current.isNotEmpty()) {
                out += current
                current = ArrayList()
                size = 0
            }
            current += line
            size += line.second.length + 1
        }
        if (current.isNotEmpty()) out += current
        return out
    }

    /** @return el texto traducido, o `null` si el idioma de origen coincide con [target]. */
    private suspend fun request(text: String, target: String): String? {
        val body: JsonElement = http.get("https://translate.googleapis.com/translate_a/single") {
            parameter("client", "gtx")
            parameter("sl", "auto")
            parameter("tl", target)
            parameter("dt", "t")
            parameter("q", text)
        }.body()
        if (body[2].string?.substringBefore('-').equals(target.substringBefore('-'), ignoreCase = true)) return null
        return body[0].array.joinToString("") { it[0].string.orEmpty() }
    }

    private companion object {
        const val BATCH_CHARS = 1_400
    }
}
