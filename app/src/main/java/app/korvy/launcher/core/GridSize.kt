package app.korvy.launcher.core

/**
 * How many columns and rows the home grid gets. "Auto" fits the screen: worked out from its portrait size (the
 * short side for width, the long side for height), so turning the phone never reshuffles the icons.
 * Small phones get 3 columns, most phones 4, XL phones 5, tablets up to 8.
 */
object GridSize {
    /** Room around the grid: side margins, and the clock, page dots, suggestions and search bar. */
    private const val SIDES_DP = 40
    private const val CHROME_DP = 300
    private const val CELL_WIDTH_DP = 80
    private const val CELL_HEIGHT_DP = 100

    fun auto(shortSideDp: Int, longSideDp: Int): Pair<Int, Int> {
        val cols = ((shortSideDp - SIDES_DP) / CELL_WIDTH_DP).coerceIn(3, 8)
        val rows = ((longSideDp - CHROME_DP) / CELL_HEIGHT_DP).coerceIn(4, 8)
        return cols to rows
    }

    /** The user's choice (0 = auto) or the automatic size. */
    fun choose(colsSetting: Int, rowsSetting: Int, shortSideDp: Int, longSideDp: Int): Pair<Int, Int> {
        val (c, r) = auto(shortSideDp, longSideDp)
        return (if (colsSetting in 3..8) colsSetting else c) to (if (rowsSetting in 4..8) rowsSetting else r)
    }
}
