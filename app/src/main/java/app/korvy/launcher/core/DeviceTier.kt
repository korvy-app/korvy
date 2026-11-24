package app.korvy.launcher.core

/** What the phone can comfortably do. Decides the defaults for animations, effects and the slideshow. */
enum class Tier { LOW, MID, HIGH }

/**
 * @param totalRamMb as Android reports it (a little under the advertised size: a "4 GB" phone reports ~3.6 GB)
 * @param performanceClass Android's media performance class (0 when the phone doesn't declare one)
 */
data class DeviceSpecs(
    val totalRamMb: Long,
    val cores: Int,
    val sdk: Int,
    val lowRamDevice: Boolean,
    val performanceClass: Int,
)

object DeviceTiers {
    fun classify(d: DeviceSpecs): Tier = when {
        // Under ~4 GB, fewer than 6 cores, or older than Android 10: keep everything light.
        d.lowRamDevice || d.totalRamMb < 3_300 || d.cores < 6 || d.sdk < 29 -> Tier.LOW
        d.totalRamMb >= 7_000 && d.sdk >= 31 && (d.performanceClass >= 31 || d.cores >= 8) -> Tier.HIGH
        else -> Tier.MID
    }

    /** Defaults; every one is a user toggle in Settings. */
    fun animationsDefault(t: Tier) = t != Tier.LOW
    fun blurDefault(t: Tier) = t == Tier.HIGH
    fun slideshowAllowedByDefault(t: Tier) = t != Tier.LOW
}
