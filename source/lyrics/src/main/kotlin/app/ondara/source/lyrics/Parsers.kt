package app.ondara.source.lyrics

import app.ondara.core.model.LyricLine
import app.ondara.core.model.LyricWord
import app.ondara.core.model.Lyrics
import java.util.Base64
import java.util.zip.Inflater

/** Parser de LRC: estándar (`[mm:ss.xx]`), con varias marcas por línea, `[offset:]` y *enhanced* (`<mm:ss.xx>` por palabra). */
object LrcParser {
    private val LINE_TAG = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")
    private val WORD_TAG = Regex("""<(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?>""")
    private val OFFSET = Regex("""\[offset:\s*([+-]?\d+)]""", RegexOption.IGNORE_CASE)
    private const val LAST_LINE_MS = 5_000L

    /**
     * Complejidad: **O(n log n)** tiempo por la ordenación final (n = líneas) y O(n) espacio.
     * @return `null` si el texto no contiene ninguna marca de tiempo.
     */
    fun parse(text: String, provider: String, durationMs: Long = 0): Lyrics? {
        val offset = OFFSET.find(text)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        val raw = ArrayList<Pair<Long, String>>()
        for (line in text.lineSequence()) {
            val tags = LINE_TAG.findAll(line).toList()
            if (tags.isEmpty()) continue
            // Las marcas deben ir al principio; lo que sigue a la última es el contenido.
            val content = line.substring(tags.last().range.last + 1).trim()
            for (tag in tags) raw += (tag.toMs() - offset).coerceAtLeast(0) to content
        }
        if (raw.isEmpty()) return null
        raw.sortBy { it.first }
        val lines = raw.mapIndexed { i, (start, content) ->
            val end = raw.getOrNull(i + 1)?.first ?: maxOf(start + LAST_LINE_MS, durationMs)
            val words = words(content, start, end)
            LyricLine(start, end, WORD_TAG.replace(content, "").replace(Regex("\\s+"), " ").trim(), words)
        }.filter { it.text.isNotEmpty() || it.endMs - it.startMs > 4_000 }
        return Lyrics(lines, synced = true, provider = provider)
    }

    private fun words(content: String, lineStart: Long, lineEnd: Long): List<LyricWord> {
        val tags = WORD_TAG.findAll(content).toList()
        if (tags.isEmpty()) return emptyList()
        val out = ArrayList<LyricWord>(tags.size)
        for ((i, tag) in tags.withIndex()) {
            val textEnd = tags.getOrNull(i + 1)?.range?.first ?: content.length
            val word = content.substring(tag.range.last + 1, textEnd)
            if (word.isBlank()) continue
            val start = tag.toMs().coerceAtLeast(lineStart)
            val end = tags.getOrNull(i + 1)?.toMs() ?: lineEnd
            out += LyricWord(start, end.coerceAtLeast(start), word)
        }
        return out
    }

    private fun MatchResult.toMs(): Long {
        val (m, s, f) = destructured
        val frac = when (f.length) {
            0 -> 0L
            1 -> f.toLong() * 100
            2 -> f.toLong() * 10
            else -> f.take(3).toLong()
        }
        return m.toLong() * 60_000 + s.toLong() * 1000 + frac
    }

    fun plain(text: String, provider: String): Lyrics? {
        val lines = text.lines().map { it.trim() }.dropWhile { it.isEmpty() }.dropLastWhile { it.isEmpty() }
        if (lines.isEmpty()) return null
        return Lyrics(lines.map { LyricLine(0, 0, it) }, synced = false, provider = provider)
    }
}

/**
 * Formato KRC de KuGou: letra sincronizada **palabra por palabra**.
 *
 * El contenido llega en base64 → cabecera `krc1` → XOR con una clave fija de 16 bytes → zlib.
 * Cada línea es `[inicioMs,duraciónMs]<offset,duración,0>palabra<offset,duración,0>palabra…`.
 */
object KrcParser {
    private val KEY = intArrayOf(64, 71, 97, 119, 94, 50, 116, 71, 81, 54, 49, 45, 206, 210, 110, 105)
    private val LINE = Regex("""^\[(\d+),(\d+)](.*)$""")
    private val WORD = Regex("""<(\d+),(\d+),\d+>([^<]*)""")

    /** O(b) sobre los bytes del archivo. */
    fun decode(base64: String): String {
        val raw = Base64.getDecoder().decode(base64)
        require(raw.size > 4 && String(raw, 0, 4) == "krc1") { "no es un archivo KRC" }
        val data = ByteArray(raw.size - 4) { i -> (raw[i + 4].toInt() xor KEY[i % KEY.size]).toByte() }
        val inflater = Inflater()
        inflater.setInput(data)
        val out = java.io.ByteArrayOutputStream(data.size * 3)
        val buffer = ByteArray(8192)
        while (!inflater.finished()) {
            val n = inflater.inflate(buffer)
            if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
            out.write(buffer, 0, n)
        }
        inflater.end()
        return out.toString(Charsets.UTF_8)
    }

    /** O(n) sobre el texto; las líneas KRC ya vienen ordenadas, se reordenan por seguridad (O(l log l)). */
    fun parse(text: String, provider: String): Lyrics? {
        val lines = text.lineSequence().mapNotNull { rawLine ->
            val m = LINE.find(rawLine.trim()) ?: return@mapNotNull null
            val start = m.groupValues[1].toLong()
            val end = start + m.groupValues[2].toLong()
            val words = WORD.findAll(m.groupValues[3]).mapNotNull { w ->
                val text = w.groupValues[3]
                if (text.isEmpty()) return@mapNotNull null
                val ws = start + w.groupValues[1].toLong()
                LyricWord(ws, ws + w.groupValues[2].toLong(), text)
            }.toList()
            val content = if (words.isEmpty()) m.groupValues[3] else words.joinToString("") { it.text }
            LyricLine(start, end, content.trim(), words).takeIf { it.text.isNotEmpty() }
        }.sortedBy { it.startMs }.toList()
        // Las primeras líneas de KuGou suelen ser créditos ("作词", "Written by") pegados en 0 ms.
        val cleaned = lines.dropWhile { l -> CREDIT.containsMatchIn(l.text) }
        return if (cleaned.isEmpty()) null else Lyrics(cleaned, synced = true, provider = provider)
    }

    private val CREDIT = Regex("""^(作词|作曲|编曲|制作人|词|曲|Lyrics by|Written by|Composed by|Produced by)\s*[:：]""", RegexOption.IGNORE_CASE)
}
