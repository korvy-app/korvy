package app.korvy.launcher.core

/** Which picture to show at [epochMillis]: the slideshow steps once per [minutes] on the clock. */
fun slideshowIndex(epochMillis: Long, minutes: Int, count: Int): Int {
    if (count <= 1 || minutes <= 0) return 0
    return ((epochMillis / (minutes * 60_000L)) % count).toInt()
}
