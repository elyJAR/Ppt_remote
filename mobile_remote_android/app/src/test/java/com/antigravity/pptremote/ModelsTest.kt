package com.antigravity.pptremote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelsTest {

    @Test
    fun `Presentation data class stores all fields correctly`() {
        val p = Presentation(
            id = "C:\\test.pptx",
            name = "test.pptx",
            path = "C:\\test.pptx",
            inSlideshow = true,
            currentSlide = 2,
            totalSlides = 15,
        )
        assertEquals("C:\\test.pptx", p.id)
        assertEquals("test.pptx", p.name)
        assertTrue(p.inSlideshow)
        assertEquals(2, p.currentSlide)
        assertEquals(15, p.totalSlides)
    }

    @Test
    fun `RemoteState has sensible defaults`() {
        val state = RemoteState()
        assertEquals("", state.bridgeUrl)
        assertTrue(state.presentations.isEmpty())
        assertNull(state.selectedPresentationId)
        assertFalse(state.isBusy)
        assertEquals(NetworkType.UNKNOWN, state.networkType)
        assertNull(state.networkWarning)
        assertNull(state.bridgeNetworkWarning)
    }

    @Test
    fun `RemoteState copy preserves unchanged fields`() {
        val state = RemoteState(bridgeUrl = "http://1.2.3.4:8787")
        val updated = state.copy(isBusy = true)
        assertEquals("http://1.2.3.4:8787", updated.bridgeUrl)
        assertTrue(updated.isBusy)
    }
}
