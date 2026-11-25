package app.korvy.launcher.core

/** A folder on the home screen: a name and its apps (keys), in order. */
data class Folder(val name: String, val keys: List<String>)

/**
 * The home-screen grid: which app or folder sits in which cell, over one or more pages the user swipes between.
 * Cells are numbered row by row from the top left of page 0, then on through page 1 and so on
 * (cell = page * pageSize + row * cols + col). Apps are identified by their key (AppEntry.key); a folder's cell
 * holds "folder:<id>". Empty pages are dropped by [compact].
 */
data class HomeGrid(
    val cols: Int = 4, val rows: Int = 5,
    val cells: Map<Int, String> = emptyMap(),
    val folders: Map<String, Folder> = emptyMap(),
    val pages: Int = 1,
) {
    val pageSize get() = cols * rows
    /** All cells on all pages. */
    val size get() = pageSize * pages

    fun pageOf(cell: Int) = cell / pageSize

    /** The cell holding [key] directly (an app or "folder:<id>"), not counting apps inside folders. */
    fun cellOf(key: String): Int? = cells.entries.firstOrNull { it.value == key }?.key

    /** The folder an app is in, if any. */
    fun folderOf(key: String): String? = folders.entries.firstOrNull { key in it.value.keys }?.key

    /** True if the app is on the home screen, directly or in a folder. */
    fun contains(key: String) = cellOf(key) != null || folderOf(key) != null

    fun folder(cellValue: String?): Folder? = cellValue?.takeIf(::isFolder)?.let { folders[it.removePrefix(FOLDER)] }

    fun firstEmpty(): Int? = (0 until size).firstOrNull { it !in cells }

    /** A grid with an extra empty page at the end (up to [MAX_PAGES]). */
    fun withNewPage(): HomeGrid = if (pages < MAX_PAGES) copy(pages = pages + 1) else this

    /** Drops pages with nothing on them (keeping at least one); later pages move up. */
    fun compact(): HomeGrid {
        val used = cells.keys.mapTo(HashSet()) { it / pageSize }
        val keep = (0 until pages).filter { it in used }.ifEmpty { listOf(0) }
        if (keep.size == pages) return this
        val newIndex = keep.withIndex().associate { (i, p) -> p to i }
        return copy(pages = keep.size, cells = cells.mapKeys { (c, _) -> newIndex.getValue(c / pageSize) * pageSize + c % pageSize })
    }

    /**
     * Drops [key] on [target]. Coming from another home cell ([from]), an app already in the target swaps
     * into the old spot. Coming from the app list, the occupant moves to the nearest empty cell instead
     * (or the drop is refused when the grid is full). An app already on the grid is moved, never duplicated.
     */
    fun place(key: String, target: Int, from: Int? = null, combine: Boolean = false): HomeGrid {
        // A drop on the page after the last one (shown while dragging) adds that page.
        if (target >= size && target < size + pageSize && pages < MAX_PAGES) return copy(pages = pages + 1).place(key, target, from, combine)
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
        var g = withoutInFolders(key)
        val spot = g.nearestEmpty(near, g.cells) ?: g.withNewPage().let { if (it.pages == g.pages) return this; g = it; g.size - g.pageSize }
        return g.copy(cells = g.cells + (spot to key))
    }

    fun renameFolder(id: String, name: String): HomeGrid =
        folders[id]?.let { copy(folders = folders + (id to it.copy(name = name.replace(Regex("[|\\u001F\\n]"), " ").trim().ifEmpty { DEFAULT_NAME }.take(40)))) } ?: this

    /** Adds an app at the first free spot, on a new page if every page is full. */
    fun add(key: String): HomeGrid = when {
        contains(key) -> this
        firstEmpty() != null -> place(key, firstEmpty()!!)
        pages < MAX_PAGES -> withNewPage().let { it.place(key, it.firstEmpty()!!) }
        else -> this
    }

    /** Removes an app (from its cell or its folder), or a whole folder given "folder:<id>". */
    fun remove(key: String): HomeGrid = if (isFolder(key)) copy(cells = cells.filterValues { it != key }, folders = folders - key.removePrefix(FOLDER))
        else withoutInFolders(key).let { it.copy(cells = it.cells.filterValues { v -> v != key }) }

    /** Drops apps that no longer exist (uninstalled) or shouldn't show (hidden), also inside folders. */
    fun keepOnly(keys: Set<String>): HomeGrid {
        var g = copy(cells = cells.filterValues { it in keys || isFolder(it) })
        for ((_, f) in folders) for (k in f.keys) if (k !in keys) g = g.withoutInFolders(k)
        return g.copy(folders = g.folders.filterKeys { g.cellOf(FOLDER + it) != null }) // folders whose cell is gone
    }

    /** Cell (in-page index, 0 until [pageSize]) under a point in one page's coordinates, or null outside it. */
    fun cellAt(x: Float, y: Float, width: Float, height: Float): Int? {
        if (x < 0 || y < 0 || x >= width || y >= height) return null
        val col = (x / (width / cols)).toInt().coerceIn(0, cols - 1)
        val row = (y / (height / rows)).toInt().coerceIn(0, rows - 1)
        return row * cols + col
    }

    /** The free cell closest to [target], preferring its own page. */
    fun nearestEmpty(target: Int, m: Map<Int, String> = cells): Int? {
        val tp = target / pageSize; val tr = target % pageSize / cols; val tc = target % cols
        return (0 until size).filter { it !in m && it != target }
            .minByOrNull {
                val p = it / pageSize; val r = it % pageSize / cols; val c = it % cols
                kotlin.math.abs(p - tp) * 10_000 + (r - tr) * (r - tr) + (c - tc) * (c - tc)
            }
    }

    /** Stored as "cols x rows[x pages]|cell=key|…|@id=name␟key␟key" (␟ = U+001F). Keys never contain '|' or ␟. */
    fun serialize(): String = buildString {
        append(cols).append('x').append(rows)
        if (pages > 1) append('x').append(pages)
        cells.toSortedMap().forEach { (c, k) -> append('|').append(c).append('=').append(k) }
        folders.toSortedMap().forEach { (id, f) -> append("|@").append(id).append('=').append((listOf(f.name) + f.keys).joinToString(SEP)) }
    }

    private fun newFolderId(g: HomeGrid): String = generateSequence(1) { it + 1 }.map { "f$it" }.first { it !in g.folders }

    companion object {
        const val FOLDER = "folder:"
        const val DEFAULT_NAME = "Folder"
        const val MAX_PAGES = 10
        private const val SEP = "\u001F"
        fun isFolder(cellValue: String) = cellValue.startsWith(FOLDER)

        fun parse(s: String?): HomeGrid {
            if (s.isNullOrBlank()) return HomeGrid()
            val parts = s.split('|')
            val dims = parts[0].split('x').mapNotNull { it.toIntOrNull() }
            val (cols, rows) = if (dims.size >= 2) dims[0] to dims[1] else 4 to 5
            val pages = dims.getOrNull(2)?.coerceIn(1, MAX_PAGES) ?: 1
            val cells = parts.drop(1).filterNot { it.startsWith("@") }.mapNotNull { p ->
                val i = p.indexOf('='); if (i <= 0) null else p.substring(0, i).toIntOrNull()?.let { it to p.substring(i + 1) }
            }.toMap()
            val folders = parts.drop(1).filter { it.startsWith("@") }.mapNotNull { p ->
                val i = p.indexOf('='); if (i <= 1) return@mapNotNull null
                val items = p.substring(i + 1).split(SEP)
                p.substring(1, i) to Folder(items.first(), items.drop(1).filter { it.isNotEmpty() })
            }.toMap()
            return HomeGrid(cols, rows, cells.filterKeys { it in 0 until cols * rows * pages }, folders, pages)
        }
    }
}
