package app.korvy.launcher.platform

import app.korvy.launcher.core.Threads
import java.io.File

/** Reads the phone's core layout for [Threads]. */
object CpuCores {
    /** Each core's relative speed as the kernel reports it (cpu_capacity, 1024 = fastest); empty if unreadable. */
    fun capacities(): List<Int> {
        val n = Runtime.getRuntime().availableProcessors()
        val caps = (0 until n).map { i ->
            runCatching { File("/sys/devices/system/cpu/cpu$i/cpu_capacity").readText().trim().toInt() }.getOrNull()
                ?: runCatching { File("/sys/devices/system/cpu/cpu$i/cpufreq/cpuinfo_max_freq").readText().trim().toInt() }.getOrNull()
        }
        return if (caps.any { it == null }) emptyList() else caps.filterNotNull()
    }

    fun inferenceThreads(): Pair<Int, Int> = Threads.forModel(capacities(), Runtime.getRuntime().availableProcessors())
}
