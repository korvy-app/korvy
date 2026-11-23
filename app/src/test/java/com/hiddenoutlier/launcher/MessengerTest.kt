package com.hiddenoutlier.launcher

import com.hiddenoutlier.launcher.platform.Messenger
import org.junit.Assert.assertEquals
import org.junit.Test

class MessengerTest {
    @Test fun picksTheAppNamedInTheRequest() {
        assertEquals(Messenger.WHATSAPP to "Jen running late", Messenger.strip("Jen on WhatsApp running late"))
        assertEquals(Messenger.WHATSAPP to "Jen running late", Messenger.strip("Jen on whats app running late"))
        assertEquals(Messenger.SIGNAL to "Alex: see you soon", Messenger.strip("Alex via Signal: see you soon"))
        assertEquals(Messenger.TELEGRAM to "mom hi", Messenger.strip("mom hi on telegram"))
        assertEquals(null to "Jen I'm on my way", Messenger.strip("Jen I'm on my way"))
    }
}
