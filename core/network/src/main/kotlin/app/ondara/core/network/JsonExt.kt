package app.ondara.core.network

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/*
 * Navegación tolerante sobre JSON sin esquema (InnerTube, KuGou…): cualquier paso inválido da null
 * en vez de lanzar, de modo que los parsers degradan con elegancia cuando YouTube cambia algo.
 */

operator fun JsonElement?.get(key: String): JsonElement? = (this as? JsonObject)?.get(key)

operator fun JsonElement?.get(index: Int): JsonElement? =
    (this as? JsonArray)?.let { if (index < 0) it.getOrNull(it.size + index) else it.getOrNull(index) }

fun JsonElement?.path(vararg keys: String): JsonElement? = keys.fold(this) { acc, k -> acc[k] }

val JsonElement?.array: List<JsonElement> get() = (this as? JsonArray) ?: emptyList()

val JsonElement?.string: String? get() = (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content

val JsonElement?.int: Int? get() = (this as? JsonPrimitive)?.let { it.intOrNull ?: it.content.toIntOrNull() }

val JsonElement?.long: Long? get() = (this as? JsonPrimitive)?.let { it.longOrNull ?: it.content.toLongOrNull() }

val JsonElement?.double: Double? get() = (this as? JsonPrimitive)?.let { it.doubleOrNull ?: it.content.toDoubleOrNull() }

val JsonElement?.bool: Boolean? get() = (this as? JsonPrimitive)?.booleanOrNull

/**
 * Recorre el árbol en profundidad y devuelve todos los valores bajo la clave [key], en orden de documento.
 * No desciende dentro de una coincidencia. Complejidad: O(n) sobre el número de nodos.
 */
fun JsonElement?.findAll(key: String): List<JsonElement> {
    val out = ArrayList<JsonElement>()
    fun walk(e: JsonElement?) {
        when (e) {
            is JsonObject -> for ((k, v) in e) if (k == key) out += v else walk(v)
            is JsonArray -> e.forEach(::walk)
            else -> Unit
        }
    }
    walk(this)
    return out
}

/** Primera coincidencia de [key] en profundidad, o null. O(n) en el peor caso. */
fun JsonElement?.findFirst(key: String): JsonElement? {
    when (this) {
        is JsonObject -> {
            this[key]?.let { return it }
            for (v in values) v.findFirst(key)?.let { return it }
        }
        is JsonArray -> for (v in this) v.findFirst(key)?.let { return it }
        else -> Unit
    }
    return null
}
