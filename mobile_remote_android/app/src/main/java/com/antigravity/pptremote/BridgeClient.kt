package com.antigravity.pptremote

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executors
import java.util.concurrent.Future

/**
 * Thrown when the bridge HTTP server responded but returned a non-2xx status.
 * Distinct from network errors (IOException/timeout) so callers can decide
 * whether to reset the bridge URL or just show a status message.
 */
class BridgeHttpException(val statusCode: Int, message: String) : Exception(message)

/**
 * HTTP client for communicating with the PPT Remote desktop bridge.
 *
 * All methods are blocking and must be called from a background thread (e.g. via
 * [kotlinx.coroutines.Dispatchers.IO]). Every request automatically includes the
 * [apiKey] header when one is configured.
 */
class BridgeClient {
    private val discoveryToken = "PPT_REMOTE_DISCOVER"

    /** Optional API key sent as `X-Api-Key` on every request. Set from [RemotePrefs.getApiKey]. */
    var apiKey: String = ""

    private fun createClient(timeoutSeconds: Int = 10): OkHttpClient {
        return OkHttpClient.Builder()
            .connectTimeout(timeoutSeconds.toLong(), TimeUnit.SECONDS)
            .readTimeout(timeoutSeconds.toLong(), TimeUnit.SECONDS)
            .writeTimeout(timeoutSeconds.toLong(), TimeUnit.SECONDS)
            .build()
    }

    private fun baseUrl(url: String): String = url.trimEnd('/')
    private fun encodedId(id: String): String =
        URLEncoder.encode(id, StandardCharsets.UTF_8.toString()).replace("+", "%20")

    /** Add X-Api-Key header when a key is configured. */
    private fun Request.Builder.withApiKey(): Request.Builder =
        if (apiKey.isNotBlank()) header("X-Api-Key", apiKey) else this

    /** Fetches the list of open PowerPoint presentations from the bridge. Throws on HTTP error. */
    fun fetchPresentations(url: String): List<Presentation> {
        val client = createClient(timeoutSeconds = 10)
        val request = Request.Builder()
            .url("${baseUrl(url)}/api/presentations")
            .withApiKey()
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw BridgeHttpException(response.code, "Bridge error: HTTP ${response.code}")
            }

            val body = response.body?.string().orEmpty()
            val arr = JSONArray(body)
            val presentations = mutableListOf<Presentation>()

            for (i in 0 until arr.length()) {
                val item = arr.getJSONObject(i)
                presentations += Presentation(
                    id = item.getString("id"),
                    name = item.getString("name"),
                    path = item.getString("path"),
                    inSlideshow = item.getBoolean("in_slideshow"),
                    currentSlide = if (item.isNull("current_slide")) null else item.getInt("current_slide"),
                    totalSlides = item.getInt("total_slides")
                )
            }

            return presentations
        }
    }

    /** Returns the bridge's current network type and any hotspot warning. Returns null on error. */
    fun getNetworkStatus(url: String): NetworkStatus? {
        return try {
            val client = createClient(timeoutSeconds = 5)
            val request = Request.Builder()
                .url("${baseUrl(url)}/api/network/status")
                .withApiKey()
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return null
                }

                val body = response.body?.string().orEmpty()
                val json = JSONObject(body)
                
                NetworkStatus(
                    networkType = json.optString("network_type", "unknown"),
                    isHotspot = json.optBoolean("is_hotspot", false),
                    warning = json.optString("warning", null)
                )
            }
        } catch (ex: Exception) {
            null
        }
    }

    /** Starts the slideshow for the given presentation. Throws on failure. */
    fun startSlideshow(url: String, presentationId: String) {
        post(url, "/api/presentations/${encodedId(presentationId)}/start")
    }

    /** Stops the active slideshow for the given presentation. Throws on failure. */
    fun stopSlideshow(url: String, presentationId: String) {
        post(url, "/api/presentations/${encodedId(presentationId)}/stop")
    }

    /** Advances to the next slide. Auto-starts slideshow if not already running. Throws on failure. */
    fun next(url: String, presentationId: String) {
        post(url, "/api/presentations/${encodedId(presentationId)}/next")
    }

    /** Goes back to the previous slide. Auto-starts slideshow if not already running. Throws on failure. */
    fun previous(url: String, presentationId: String) {
        post(url, "/api/presentations/${encodedId(presentationId)}/previous")
    }

    /** Fetch the current slide thumbnail as raw PNG bytes. Returns null on any error. */
    fun fetchCurrentThumbnail(url: String, presentationId: String, width: Int = 480): ByteArray? {
        return try {
            val client = createClient(timeoutSeconds = 15) // export can be slow
            val request = Request.Builder()
                .url("${baseUrl(url)}/api/presentations/${encodedId(presentationId)}/current-thumbnail?width=$width")
                .withApiKey()
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                response.body?.bytes()
            }
        } catch (e: Exception) {
            null
        }
    }

    /** Fetch a specific slide thumbnail by 1-based index. Returns null on any error. */
    fun fetchSlideThumbnail(url: String, presentationId: String, slideIndex: Int, width: Int = 480): ByteArray? {
        return try {
            val client = createClient(timeoutSeconds = 15)
            val request = Request.Builder()
                .url("${baseUrl(url)}/api/presentations/${encodedId(presentationId)}/slides/$slideIndex/thumbnail?width=$width")
                .withApiKey()
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                response.body?.bytes()
            }
        } catch (e: Exception) {
            null
        }
    }

    /** Fetch speaker notes for all slides. Returns empty list on error. */
    fun fetchAllNotes(url: String, presentationId: String): List<SlideNote> {
        return try {
            val client = createClient(timeoutSeconds = 10)
            val request = Request.Builder()
                .url("${baseUrl(url)}/api/presentations/${encodedId(presentationId)}/notes")
                .withApiKey()
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return emptyList()
                val arr = JSONArray(response.body?.string().orEmpty())
                (0 until arr.length()).map { i ->
                    val obj = arr.getJSONObject(i)
                    SlideNote(
                        slideIndex = obj.getInt("slide_index"),
                        notes = obj.getString("notes")
                    )
                }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Fetch speaker notes for the current slideshow slide. Returns null if not in slideshow or on error. */
    fun fetchCurrentNotes(url: String, presentationId: String): SlideNote? {
        return try {
            val client = createClient(timeoutSeconds = 10)
            val request = Request.Builder()
                .url("${baseUrl(url)}/api/presentations/${encodedId(presentationId)}/current-notes")
                .withApiKey()
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val obj = JSONObject(response.body?.string().orEmpty())
                SlideNote(
                    slideIndex = obj.getInt("slide_index"),
                    notes = obj.getString("notes")
                )
            }
        } catch (e: Exception) {
            null
        }
    }

    /** Check bridge health. Returns true if reachable. */
    fun checkHealth(url: String): Boolean {
        return try {
            val client = createClient(timeoutSeconds = 5)
            val request = Request.Builder()
                .url("${baseUrl(url)}/api/health")
                .withApiKey()
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                response.isSuccessful
            }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Discover the bridge.
     *
     * Strategy:
     *  1. If [networkType] is HOTSPOT_PROVIDING — the phone IS the gateway, so UDP
     *     broadcast may not loop back. We scan the hotspot subnet directly via HTTP
     *     health checks (parallel, fast timeout). Falls through to UDP as backup.
     *  2. Otherwise — standard UDP broadcast discovery on all interfaces.
     */
    fun discoverBridge(
        timeoutMs: Int = 1500,
        discoveryPort: Int = 8788,
        bridgePort: Int = 8787,
        networkType: NetworkType = NetworkType.UNKNOWN
    ): String? {
        // Strategy 1: when phone is hotspot gateway, probe subnet via HTTP directly
        if (networkType == NetworkType.HOTSPOT_PROVIDING) {
            val found = discoverViaHotspotSubnet(bridgePort)
            if (found != null) return found
        }

        // Strategy 2: UDP broadcast (works for WIFI / HOTSPOT_USING)
        return discoverViaBroadcast(timeoutMs, discoveryPort)
    }

    /**
     * When the phone provides a hotspot, the PC is a client on the hotspot subnet.
     * Android assigns itself 192.168.43.1 (standard), or occasionally 192.168.1.1
     * or 192.168.0.1. We scan .2–.20 on each candidate subnet in parallel with a
     * short HTTP timeout — whichever responds first wins.
     */
    private fun discoverViaHotspotSubnet(bridgePort: Int): String? {
        // Candidate hotspot gateway subnets Android commonly uses
        val hotspotSubnets = listOf("192.168.43", "192.168.1", "192.168.0", "10.0.0")
        val scanRangeEnd = 20 // scan .2–.20 (PC gets a low address from DHCP)

        val probeClient = OkHttpClient.Builder()
            .connectTimeout(400, TimeUnit.MILLISECONDS)
            .readTimeout(400, TimeUnit.MILLISECONDS)
            .build()

        val executor = Executors.newFixedThreadPool(16)
        val futures = mutableListOf<Future<String?>>()

        for (subnet in hotspotSubnets) {
            for (host in 2..scanRangeEnd) {
                val candidate = "http://$subnet.$host:$bridgePort"
                futures += executor.submit<String?> {
                    try {
                        val req = Request.Builder().url("$candidate/api/health").get().build()
                        probeClient.newCall(req).execute().use { resp ->
                            if (resp.isSuccessful) candidate else null
                        }
                    } catch (_: Exception) { null }
                }
            }
        }

        executor.shutdown()
        // Wait up to 1.5s for any hit
        executor.awaitTermination(1500, TimeUnit.MILLISECONDS)

        val result = futures.firstNotNullOfOrNull { f ->
            try { if (f.isDone) f.get() else null } catch (_: Exception) { null }
        }

        executor.shutdownNow()
        return result
    }

    private fun discoverViaBroadcast(timeoutMs: Int, discoveryPort: Int): String? {
        val payload = discoveryToken.toByteArray(StandardCharsets.UTF_8)
        val receiveBuffer = ByteArray(1024)

        DatagramSocket().use { socket ->
            socket.broadcast = true
            socket.soTimeout = timeoutMs

            val targets = mutableSetOf<InetAddress>()
            targets += InetAddress.getByName("255.255.255.255")
            targets += getBroadcastTargets()

            for (target in targets) {
                val packet = DatagramPacket(payload, payload.size, target, discoveryPort)
                socket.send(packet)
            }

            while (true) {
                try {
                    val response = DatagramPacket(receiveBuffer, receiveBuffer.size)
                    socket.receive(response)
                    val body = String(response.data, 0, response.length, StandardCharsets.UTF_8)
                    val json = JSONObject(body)
                    val url = json.optString("bridge_url", "")
                    if (url.isNotBlank()) {
                        return url
                    }
                } catch (_: SocketTimeoutException) {
                    return null
                }
            }
        }
    }

    private fun getBroadcastTargets(): List<InetAddress> {
        val targets = mutableListOf<InetAddress>()
        val interfaces = NetworkInterface.getNetworkInterfaces() ?: return targets

        while (interfaces.hasMoreElements()) {
            val iface = interfaces.nextElement()
            if (!iface.isUp || iface.isLoopback) {
                continue
            }

            for (address in iface.interfaceAddresses) {
                val broadcast = address.broadcast ?: continue
                targets += broadcast
            }
        }

        return targets
    }

    private fun post(url: String, path: String) {
        val client = createClient(timeoutSeconds = 10)
        val request = Request.Builder()
            .url("${baseUrl(url)}$path")
            .withApiKey()
            .post("{}".toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("Bridge error: HTTP ${response.code}")
            }
        }
    }
}

data class NetworkStatus(
    val networkType: String,
    val isHotspot: Boolean,
    val warning: String?
)

data class SlideNote(
    val slideIndex: Int,
    val notes: String
)
