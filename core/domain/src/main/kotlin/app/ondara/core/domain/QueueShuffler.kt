package app.ondara.core.domain

import kotlin.random.Random

/** Barajado de la cola de reproducción. */
object QueueShuffler {
    /**
     * Fisher–Yates que conserva el elemento [currentIndex] en la posición 0.
     *
     * Devuelve una permutación de índices `0 until size`: `result[k]` es el índice original que
     * queda en la posición `k`. Todas las permutaciones del resto son equiprobables.
     *
     * Complejidad: **O(n)** tiempo; **O(1)** espacio extra además del arreglo de salida.
     */
    fun shuffledOrder(size: Int, currentIndex: Int, random: Random = Random.Default): IntArray {
        require(size >= 0) { "size negativo" }
        val order = IntArray(size) { it }
        if (size <= 1) return order
        val start = if (currentIndex in 0 until size) {
            order[0] = currentIndex
            order[currentIndex] = 0
            1
        } else {
            0
        }
        for (i in size - 1 downTo start + 1) {
            val j = random.nextInt(start, i + 1)
            val tmp = order[i]
            order[i] = order[j]
            order[j] = tmp
        }
        return order
    }

    /** Aplica [shuffledOrder] a una lista. O(n). */
    fun <T> shuffle(items: List<T>, currentIndex: Int, random: Random = Random.Default): List<T> =
        shuffledOrder(items.size, currentIndex, random).map { items[it] }
}
