package app.korvy.launcher.core

/**
 * The home-screen grid: which app sits in which cell. Cells are numbered row by row from the top left
 * (cell = row * cols + col). Apps are identified by their key (AppEntry.key).
 */
data class HomeGrid(val cols: Int = 4, val rows: Int = 5, val cells: Map<Int, String> = emptyMap()) {
    val size get() = cols * rows

    fun cellOf(key: String): Int? = cells.entries.firstOrNull { it.value == key }?.key

    fun firstEmpty(): Int? = (0 until size).firstOrNull { it !in cells }

    /**
     * Drops [key] on [target]. Coming from another home cell ([from]), an app already in the target swaps
     * into the old spot. Coming from the app list, the occupant moves to the nearest empty cell instead
     * (or the drop is refused when the grid is full). An app already on the grid is moved, never duplicated.
     */
    fun place(key: String, target: Int, from: Int? = null): HomeGrid {
        if (target !in 0 until size) return this
        val m = cells.toMutableMap()
        val source = from ?: cellOf(key)
        if (source != null) m.remove(source)
        val occupant = m[target]
        if (occupant != null && occupant != key) {
            val spot = source ?: nearestEmpty(target, m) ?: return this
            m[spot] = occupant
        }
        m[target] = key
        return copy(cells = m)
    }

    fun add(key: String): HomeGrid = if (cellOf(key) != null) this else firstEmpty()?.let { place(key, it) } ?: this

    fun remove(key: String): HomeGrid = copy(cells = cells.filterValues { it != key })

    /** Drops apps that no longer exist (uninstalled) or shouldn't show (hidden). */
    fun keepOnly(keys: Set<String>): HomeGrid = copy(cells = cells.filterValues { it in keys })

    /** Cell under a point in the grid's own coordinates, or null outside it. */
    fun cellAt(x: Float, y: Float, width: Float, height: Float): Int? {
        if (x < 0 || y < 0 || x >= width || y >= height) return null
        val col = (x / (width / cols)).toInt().coerceIn(0, cols - 1)
        val row = (y / (height / rows)).toInt().coerceIn(0, rows - 1)
        return row * cols + col
    }

    private fun nearestEmpty(target: Int, m: Map<Int, String>): Int? {
        val tr = target / cols; val tc = target % cols
        return (0 until size).filter { it !in m && it != target }
            .minByOrNull { val r = it / cols; val c = it % cols; (r - tr) * (r - tr) + (c - tc) * (c - tc) }
    }

    /** Stored as "cols x rows|cell=key|cell=key". Keys never contain '|' or '='-prefixed numbers. */
    fun serialize(): String = buildString {
        append(cols).append('x').append(rows)
        cells.toSortedMap().forEach { (c, k) -> append('|').append(c).append('=').append(k) }
    }

    companion object {
        fun parse(s: String?): HomeGrid {
            if (s.isNullOrBlank()) return HomeGrid()
            val parts = s.split('|')
            val (cols, rows) = parts[0].split('x').mapNotNull { it.toIntOrNull() }.let { if (it.size == 2) it[0] to it[1] else 4 to 5 }
            val cells = parts.drop(1).mapNotNull { p ->
                val i = p.indexOf('='); if (i <= 0) null else p.substring(0, i).toIntOrNull()?.let { it to p.substring(i + 1) }
            }.toMap()
            return HomeGrid(cols, rows, cells.filterKeys { it in 0 until cols * rows })
        }
    }
}
