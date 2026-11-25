package app.korvy.launcher.core

/**
 * How many threads the on-device model uses. Measured on a Pixel 4a (2 fast + 6 slow cores, Qwen 2.5 1.5B):
 * writing a reply is fastest on the fast cores only (2 threads: 10.6 tokens/s; 4: 8.7; 8: 8.4), because every
 * token waits for the slowest thread; reading the prompt is fastest on every core (8: 57 tokens/s; 4: 44).
 */
object Threads {
    /** (threads for writing, threads for reading the prompt) from each core's relative speed. */
    fun forModel(capacities: List<Int>, cores: Int): Pair<Int, Int> {
        val batch = cores.coerceIn(1, 8)
        if (capacities.isEmpty()) return (cores / 2).coerceIn(1, 4) to batch
        val top = capacities.max()
        // "Fast" = within 70% of the fastest core: the prime and big cores, not the efficiency ones.
        val fast = capacities.count { it >= top * 0.7 }
        return fast.coerceIn(2, 4).coerceAtMost(cores) to batch
    }
}
