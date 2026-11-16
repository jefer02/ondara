package app.ondara.core.designsystem.theme

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/** Matemática de color pura (ARGB en `Int`), compartida por el motor de temas y sus tests. */
object ColorMath {
    const val AA_TEXT = 4.5
    const val AA_LARGE = 3.0

    fun parseHex(hex: String): Int {
        val h = hex.trim().removePrefix("#")
        return when (h.length) {
            6 -> (0xFF000000 or h.toLong(16)).toInt()
            8 -> h.toLong(16).toInt()
            3 -> parseHex("#" + h.map { "$it$it" }.joinToString(""))
            else -> throw IllegalArgumentException("color inválido: $hex")
        }
    }

    fun toHex(argb: Int): String = "#%06X".format(argb and 0xFFFFFF)

    private fun channel(c: Int): Double {
        val s = c / 255.0
        return if (s <= 0.03928) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
    }

    /** Luminancia relativa WCAG 2.1 en [0, 1]. */
    fun luminance(argb: Int): Double =
        0.2126 * channel(argb shr 16 and 0xFF) + 0.7152 * channel(argb shr 8 and 0xFF) + 0.0722 * channel(argb and 0xFF)

    /** Ratio de contraste WCAG entre dos colores opacos, en [1, 21]. */
    fun contrast(a: Int, b: Int): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    /** Mezcla lineal en sRGB: `t = 0` → [a], `t = 1` → [b]. */
    fun mix(a: Int, b: Int, t: Float): Int {
        fun ch(shift: Int) = ((a shr shift and 0xFF) + ((b shr shift and 0xFF) - (a shr shift and 0xFF)) * t).roundToInt().coerceIn(0, 255)
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    /** Compone [fg] con alfa sobre [bg] opaco. */
    fun composite(fg: Int, bg: Int): Int = mix(bg, fg or (0xFF shl 24), (fg ushr 24) / 255f)

    /**
     * Devuelve [fg] ajustado hacia blanco o negro hasta alcanzar [ratio] de contraste contra [bg].
     * Búsqueda binaria sobre el factor de mezcla: O(log(1/ε)) ≈ 12 iteraciones.
     */
    fun ensureContrast(fg: Int, bg: Int, ratio: Double = AA_TEXT): Int {
        if (contrast(fg, bg) >= ratio) return fg
        val white = 0xFFFFFFFF.toInt()
        val black = 0xFF000000.toInt()
        val target = if (contrast(white, bg) >= contrast(black, bg)) white else black
        var lo = 0f
        var hi = 1f
        repeat(12) {
            val mid = (lo + hi) / 2
            if (contrast(mix(fg, target, mid), bg) >= ratio) hi = mid else lo = mid
        }
        return mix(fg, target, hi)
    }

    /** Blanco o negro, el que más contraste dé sobre [bg]. */
    fun onColor(bg: Int): Int = if (luminance(bg) > 0.4) 0xFF101010.toInt() else 0xFFFFFFFF.toInt()

    fun saturation(argb: Int): Float {
        val r = (argb shr 16 and 0xFF) / 255f
        val g = (argb shr 8 and 0xFF) / 255f
        val b = (argb and 0xFF) / 255f
        val mx = max(r, max(g, b))
        val mn = min(r, min(g, b))
        return if (mx == 0f) 0f else (mx - mn) / mx
    }

    /**
     * Deriva una paleta completa y accesible (AA) a partir de dos colores de portada.
     * Así el tema "Dinámico" nunca produce texto ilegible, sea cual sea la carátula.
     */
    fun paletteFrom(dominant: Int, vibrant: Int, dark: Boolean = true): Palette {
        val base = if (dark) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        val bg = mix(dominant, base, if (dark) 0.82f else 0.9f)
        val surface = mix(dominant, base, if (dark) 0.7f else 0.82f)
        val surfaceHigh = mix(dominant, base, if (dark) 0.58f else 0.72f)
        val onBg = if (dark) 0xFFF4F4F4.toInt() else 0xFF141414.toInt()
        val accentSeed = if (abs(luminance(vibrant) - luminance(bg)) < 0.02 && saturation(vibrant) < 0.1f) onBg else vibrant
        val accent = ensureContrast(accentSeed, bg, AA_TEXT)
        return Palette(
            background = toHex(bg),
            surface = toHex(surface),
            surfaceHigh = toHex(surfaceHigh),
            onBackground = toHex(ensureContrast(onBg, surfaceHigh, AA_TEXT)),
            onMuted = toHex(ensureContrast(mix(onBg, bg, 0.38f), surfaceHigh, AA_TEXT)),
            accent = toHex(accent),
            onAccent = toHex(ensureContrast(onColor(accent), accent, AA_TEXT)),
            outline = toHex(mix(onBg, bg, 0.82f)),
        )
    }

    /** Problemas de contraste AA de una paleta; lista vacía = accesible. */
    fun contrastIssues(p: Palette): List<String> {
        val bg = parseHex(p.background)
        val surface = composite(parseHex(p.surface), bg)
        val high = composite(parseHex(p.surfaceHigh), bg)
        val accent = parseHex(p.accent)
        val checks = listOf(
            Triple("onBackground/background", contrast(parseHex(p.onBackground), bg), AA_TEXT),
            Triple("onBackground/surface", contrast(parseHex(p.onBackground), surface), AA_TEXT),
            Triple("onBackground/surfaceHigh", contrast(parseHex(p.onBackground), high), AA_TEXT),
            Triple("onMuted/background", contrast(parseHex(p.onMuted), bg), AA_TEXT),
            Triple("onMuted/surface", contrast(parseHex(p.onMuted), surface), AA_TEXT),
            Triple("accent/background", contrast(accent, bg), AA_TEXT),
            Triple("onAccent/accent", contrast(parseHex(p.onAccent), accent), AA_TEXT),
        )
        return checks.filter { it.second < it.third }.map { "${it.first} = %.2f (< %.1f)".format(it.second, it.third) }
    }
}
