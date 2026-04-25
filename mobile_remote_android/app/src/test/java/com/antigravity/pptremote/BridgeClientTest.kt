package com.antigravity.pptremote

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class BridgeClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: BridgeClient
    private lateinit var baseUrl: String

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        baseUrl = server.url("/").toString().trimEnd('/')
        client = BridgeClient()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    // -------------------------------------------------------------------------
    // fetchPresentations
    // -------------------------------------------------------------------------

    @Test
    fun `fetchPresentations returns empty list on empty JSON array`() {
        server.enqueue(MockResponse().setBody("[]").setResponseCode(200))
        val result = client.fetchPresentations(baseUrl)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `fetchPresentations parses single presentation correctly`() {
        val json = """
            [{
                "id": "C:\\\\Slides\\\\demo.pptx",
                "name": "demo.pptx",
                "path": "C:\\\\Slides\\\\demo.pptx",
                "in_slideshow": true,
                "current_slide": 3,
                "total_slides": 10
            }]
        """.trimIndent()
        server.enqueue(MockResponse().setBody(json).setResponseCode(200))

        val result = client.fetchPresentations(baseUrl)

        assertEquals(1, result.size)
        val p = result[0]
        assertEquals("C:\\Slides\\demo.pptx", p.id)
        assertEquals("demo.pptx", p.name)
        assertEquals(true, p.inSlideshow)
        assertEquals(3, p.currentSlide)
        assertEquals(10, p.totalSlides)
    }

    @Test
    fun `fetchPresentations handles null current_slide`() {
        val json = """
            [{
                "id": "C:\\\\test.pptx",
                "name": "test.pptx",
                "path": "C:\\\\test.pptx",
                "in_slideshow": false,
                "current_slide": null,
                "total_slides": 5
            }]
        """.trimIndent()
        server.enqueue(MockResponse().setBody(json).setResponseCode(200))

        val result = client.fetchPresentations(baseUrl)
        assertNull(result[0].currentSlide)
    }

    @Test(expected = IllegalStateException::class)
    fun `fetchPresentations throws on non-200 response`() {
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"detail":"error"}"""))
        client.fetchPresentations(baseUrl)
    }

    // -------------------------------------------------------------------------
    // startSlideshow / stopSlideshow / next / previous
    // -------------------------------------------------------------------------

    @Test
    fun `startSlideshow sends POST to correct path`() {
        server.enqueue(MockResponse().setBody("""{"ok":true}""").setResponseCode(200))
        client.startSlideshow(baseUrl, "C:\\Slides\\demo.pptx")

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertTrue(request.path!!.endsWith("/start"))
    }

    @Test
    fun `stopSlideshow sends POST to correct path`() {
        server.enqueue(MockResponse().setBody("""{"ok":true}""").setResponseCode(200))
        client.stopSlideshow(baseUrl, "C:\\Slides\\demo.pptx")

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertTrue(request.path!!.endsWith("/stop"))
    }

    @Test
    fun `next sends POST to correct path`() {
        server.enqueue(MockResponse().setBody("""{"ok":true}""").setResponseCode(200))
        client.next(baseUrl, "C:\\Slides\\demo.pptx")

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertTrue(request.path!!.endsWith("/next"))
    }

    @Test
    fun `previous sends POST to correct path`() {
        server.enqueue(MockResponse().setBody("""{"ok":true}""").setResponseCode(200))
        client.previous(baseUrl, "C:\\Slides\\demo.pptx")

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertTrue(request.path!!.endsWith("/previous"))
    }

    @Test
    fun `presentation id is URL-encoded in path`() {
        server.enqueue(MockResponse().setBody("""{"ok":true}""").setResponseCode(200))
        client.next(baseUrl, "C:\\My Slides\\demo.pptx")

        val request = server.takeRequest()
        // Space should be encoded as %20 in the path
        assertTrue(request.path!!.contains("%20") || request.path!!.contains("+"))
    }

    @Test(expected = IllegalStateException::class)
    fun `next throws on error response`() {
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"detail":"not found"}"""))
        client.next(baseUrl, "C:\\Slides\\demo.pptx")
    }

    // -------------------------------------------------------------------------
    // getNetworkStatus
    // -------------------------------------------------------------------------

    @Test
    fun `getNetworkStatus parses response correctly`() {
        val json = """{"network_type":"wifi","is_hotspot":false,"warning":null}"""
        server.enqueue(MockResponse().setBody(json).setResponseCode(200))

        val status = client.getNetworkStatus(baseUrl)
        assertNotNull(status)
        assertEquals("wifi", status!!.networkType)
        assertEquals(false, status.isHotspot)
        assertNull(status.warning)
    }

    @Test
    fun `getNetworkStatus returns null on error`() {
        server.enqueue(MockResponse().setResponseCode(500))
        val status = client.getNetworkStatus(baseUrl)
        assertNull(status)
    }

    @Test
    fun `getNetworkStatus returns null on network failure`() {
        // Shut down server to simulate network failure
        server.shutdown()
        val status = client.getNetworkStatus("http://127.0.0.1:1")
        assertNull(status)
    }
}
