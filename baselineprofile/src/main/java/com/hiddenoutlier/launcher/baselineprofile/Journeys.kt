package com.hiddenoutlier.launcher.baselineprofile

import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until

const val PACKAGE = "com.hiddenoutlier.launcher"

/** First launch shows the tour; get past it to the home screen. */
fun MacrobenchmarkScope.skipTourIfShown() {
    device.wait(Until.hasObject(By.text("Skip")), 1_500)
    device.findObject(By.text("Skip"))?.click()
    device.waitForIdle()
}

/** What people do most: look at the home screen, swipe up for the app list, search, go back home. */
fun MacrobenchmarkScope.homeAndSearch() {
    skipTourIfShown()
    val w = device.displayWidth; val h = device.displayHeight
    device.swipe(w / 2, h * 3 / 4, w / 2, h / 4, 12) // open the app list
    device.wait(Until.hasObject(By.text("Search apps and the web")), 2_000)
    device.findObject(By.text("Search apps and the web"))?.click()
    device.waitForIdle()
    device.findObject(By.focused(true))?.text = "ca"
    device.waitForIdle()
    device.pressBack(); device.pressBack()
    device.waitForIdle()
}
