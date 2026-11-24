package app.korvy.launcher.core

/** A folder on the home screen: a name and its apps (keys), in order. */
data class Folder(val name: String, val keys: List<String>)

/**
 * The home-screen grid: which app or folder sits in which cell. Cells are numbered row by row from the top left
 * (cell = row * cols + col). Apps are identified by their key (AppEntry.key); a folder's cell holds "folder:<id>".
 */
data class HomeGrid(
    val cols: Int = 4, val rows: Int = 5,
    val cells: Map<Int, String> = emptyMap(),
    val folders: Map<String, Folder> = emptyMap(),
) {
    val size get() = cols * rows

    /** The cell holding [key] directly (an app or "folder:<id>"), not counting apps inside folders. */
    fun cellOf(key: String): Int? = cells.entries.firstOrNull { it.value == key }?.key

    /** The folder an app is in, if any. */
    fun folderOf(key: String): String? = folders.entries.firstOrNull { key in it.value.keys }?.key

    /** True if the app is on the home screen, directly or in a folder. */
    fun contains(key: String) = cellOf(key) != null || folderOf(key) != null

    fun folder(cellValue: String?): Folder? = cellValue?.takeIf(::isFolder)?.let { folders[it.removePrefix(FOLDER)] }

    fun firstEmpty(): Int? = (0 until size).firstOrNull { it !in cells }

    /**
     * Drops [key] on [target]. Coming from another home cell ([from]), an app already in the target swaps
     * into the old spot. Coming from the app list, the occupant moves to the nearest empty cell instead
     * (or the drop is refused when the grid is full). An app already on the grid is moved, never duplicated.
     */
    fun place(key: String, target: Int, from: Int? = null, combine: Boolean = false): HomeGrid {
        if (target !in 0 until size) return this
        val occupant = cells[target]
        // Dropped onto the middle of another icon: make a folder (or add to the folder there).
        if (combine && occupant != null && occupant != key && !isFolder(key)) return combineInto(key, target)
        val base = withoutInFolders(key)
        val m = base.cells.toMutableMap()
        val source = from?.takeIf { m[it] == key } ?: base.cellOf(key)
        if (source != null) m.remove(source)
        val occupant2 = m[target]
        if (occupant2 != null && occupant2 != key) {
            val spot = source ?: nearestEmpty(target, m) ?: return this
            m[spot] = occupant2
        }
        m[target] = key
        return base.copy(cells = m)
    }

    private fun combineInto(key: String, target: Int): HomeGrid {
        // Take the app out of wherever it was (its cell or another folder) first.
        var g = withoutInFolders(key)
        g = g.copy(cells = g.cells.filterValues { it != key })
        val here = g.cells[target] ?: return g.place(key, target)
        return if (isFolder(here)) {
            val id = here.removePrefix(FOLDER); val f = g.folders[id] ?: return this
            g.copy(folders = g.folders + (id to f.copy(keys = f.keys + key)))
        } else {
            val id = newFolderId(g)
            g.copy(cells = g.cells + (target to FOLDER + id), folders = g.folders + (id to Folder(DEFAULT_NAME, listOf(here, key))))
        }
    }

    /** Takes [key] out of any folder; a folder left with one app becomes that app, an empty one disappears. */
    private fun withoutInFolders(key: String): HomeGrid {
        val id = folderOf(key) ?: return this
        val f = folders.getValue(id)
        val left = f.keys - key
        val cell = cellOf(FOLDER + id)
        return when {
            left.size >= 2 -> copy(folders = folders + (id to f.copy(keys = left)))
            left.size == 1 -> copy(folders = folders - id, cells = if (cell != null) cells + (cell to left[0]) else cells)
            else -> copy(folders = folders - id, cells = if (cell != null) cells - cell else cells)
        }
    }

    /** "Remove from folder": the app goes to the nearest free cell (stays put if the home screen is full). */
    fun takeOutOfFolder(key: String): HomeGrid {
        val id = folderOf(key) ?: return this
        val near = cellOf(FOLDER + id) ?: 0
        val g = withoutInFolders(key)
        val spot = g.nearestEmpty(near, g.cells) ?: return this
        return g.copy(cells = g.cells + (spot to key))
    }

    fun renameFolder(id: String, name: String): HomeGrid =
        folders[id]?.let { copy(folders = folders + (id to it.copy(name = name.replace(Regex("[|\\u001F\\n]"), " ").trim().ifEmpty { DEFAULT_NAME }.take(40)))) } ?: this

    fun add(key: String): HomeGrid = if (contains(key)) this else firstEmpty()?.let { place(key, it) } ?: this

    /** Removes an app (from its cell or its folder), or a whole folder given "folder:<id>". */
    fun remove(key: String): HomeGrid = if (isFolder(key)) copy(cells = cells.filterValues { it != key }, folders = folders - key.removePrefix(FOLDER))
        else withoutInFolders(key).let { it.copy(cells = it.cells.filterValues { v -> v != key }) }

    /** Drops apps that no longer exist (uninstalled) or shouldn't show (hidden), also inside folders. */
    fun keepOnly(keys: Set<String>): HomeGrid {
        var g = copy(cells = cells.filterValues { it in keys || isFolder(it) })
        for ((_, f) in folders) for (k in f.keys) if (k !in keys) g = g.withoutInFolders(k)
        return g.copy(folders = g.folders.filterKeys { g.cellOf(FOLDER + it) != null }) // folders whose cell is gone
    }

    /** Cell under a point in the grid's own coordinates, or null outside it. */
    fun cellAt(x: Float, y: Float, width: Float, height: Float): Int? {
        if (x < 0 || y < 0 || x >= width || y >= height) return null
        val col = (x / (width / cols)).toInt().coerceIn(0, cols - 1)
        val row = (y / (height / rows)).toInt().coerceIn(0, rows - 1)
        return row * cols + col
    }

    fun nearestEmpty(target: Int, m: Map<Int, String> = cells): Int? {
        val tr = target / cols; val tc = target % cols
        return (0 until size).filter { it !in m && it != target }
            .minByOrNull { val r = it / cols; val c = it % cols; (r - tr) * (r - tr) + (c - tc) * (c - tc) }
    }

    /** Stored as "cols x rows|cell=key|…|@id=name␟key␟key" (␟ = U+001F). Keys never contain '|' or ␟. */
    fun serialize(): String = buildString {
        append(cols).append('x').append(rows)
        cells.toSortedMap().forEach { (c, k) -> append('|').append(c).append('=').append(k) }
        folders.toSortedMap().forEach { (id, f) -> append("|@").append(id).append('=').append((listOf(f.name) + f.keys).joinToString(SEP)) }
    }

    private fun newFolderId(g: HomeGrid): String = generateSequence(1) { it + 1 }.map { "f$it" }.first { it !in g.folders }

    companion object {
        const val FOLDER = "folder:"
        const val DEFAULT_NAME = "Folder"
        private const val SEP = "\u001F"
        fun isFolder(cellValue: String) = cellValue.startsWith(FOLDER)

        fun parse(s: String?): HomeGrid {
            if (s.isNullOrBlank()) return HomeGrid()
            val parts = s.split('|')
            val (cols, rows) = parts[0].split('x').mapNotNull { it.toIntOrNull() }.let { if (it.size == 2) it[0] to it[1] else 4 to 5 }
            val cells = parts.drop(1).filterNot { it.startsWith("@") }.mapNotNull { p ->
                val i = p.indexOf('='); if (i <= 0) null else p.substring(0, i).toIntOrNull()?.let { it to p.substring(i + 1) }
            }.toMap()
            val folders = parts.drop(1).filter { it.startsWith("@") }.mapNotNull { p ->
                val i = p.indexOf('='); if (i <= 1) return@mapNotNull null
                val items = p.substring(i + 1).split(SEP)
                p.substring(1, i) to Folder(items.first(), items.drop(1).filter { it.isNotEmpty() })
            }.toMap()
            return HomeGrid(cols, rows, cells.filterKeys { it in 0 until cols * rows }, folders)
        }
    }
}
