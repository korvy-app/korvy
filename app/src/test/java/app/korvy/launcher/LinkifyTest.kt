package app.korvy.launcher

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.LinkAnnotation
import app.korvy.launcher.ui.linkify
import org.junit.Assert.assertEquals
import org.junit.Test

class LinkifyTest {
    @Test fun findsUrlsWithoutTrailingPunctuation() {
        val a = linkify("See https://example.com/a?b=1. Or (http://x.org) now", Color.Blue)
        val urls = a.getLinkAnnotations(0, a.length).map { (it.item as LinkAnnotation.Url).url }
        assertEquals(listOf("https://example.com/a?b=1", "http://x.org"), urls)
        assertEquals("See https://example.com/a?b=1. Or (http://x.org) now", a.text)
    }
}
