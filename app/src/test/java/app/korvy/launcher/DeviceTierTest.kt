package app.korvy.launcher

import app.korvy.launcher.core.DeviceSpecs
import app.korvy.launcher.core.DeviceTiers
import app.korvy.launcher.core.Tier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceTierTest {
    private fun tier(ram: Long, cores: Int, sdk: Int, low: Boolean = false, pc: Int = 0) =
        DeviceTiers.classify(DeviceSpecs(ram, cores, sdk, low, pc))

    @Test fun pixel4aIsMid() = assertEquals(Tier.MID, tier(5_600, 8, 33))
    @Test fun pixel9ProIsHigh() = assertEquals(Tier.HIGH, tier(15_300, 8, 36, pc = 34))
    @Test fun threeGigPhoneIsLow() = assertEquals(Tier.LOW, tier(2_800, 8, 33))
    @Test fun quadCoreIsLow() = assertEquals(Tier.LOW, tier(5_600, 4, 33))
    @Test fun oldAndroidIsLow() = assertEquals(Tier.LOW, tier(5_600, 8, 28))
    @Test fun lowRamFlagIsLow() = assertEquals(Tier.LOW, tier(5_600, 8, 33, low = true))

    @Test fun animationsOffByDefaultOnlyOnLowTier() {
        assertFalse(DeviceTiers.animationsDefault(Tier.LOW))
        assertTrue(DeviceTiers.animationsDefault(Tier.MID))
        assertTrue(DeviceTiers.animationsDefault(Tier.HIGH))
    }
}
