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
 */
class BridgeHttpException(val statusCode: Int, message: String) : IllegalStateException(message)

/**
 * HTTP client for communicating with the PPT Remote desktop bridge.
 */
class BridgeClient {
    private val discoveryToken = "PPT_REMOTE_DISCOVER"
    var apiKey: String = ""

    private val baseClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    private fun createClient(timeoutSeconds: Int = 10): OkHttpClient {
        return if (timeoutSeconds == 10) baseClient
        else baseClient.newBuilder()
            .connectTimeout(timeoutSeconds.toLong(), TimeUnit.SECONDS)
            .readTimeout(timeoutSeconds.toLong(), TimeUnit.SECONDS)
            .writeTimeout(timeoutSeconds.toLong(), TimeUnit.SECONDS)
            .build()
    }

    private fun baseUrl(url: String): String = url.trimEnd('/')
    private fun encodedId(id: String): String =
        URLEncoder.encode(id, StandardCharsets.UTF_8.toString()).replace("+", "%20")

    private fun Request.Builder.withApiKey(): Request.Builder =
        if (apiKey.isNotBlank()) header("X-Api-Key", apiKey) else this

    fun fetchPresentations(url: String): List<Presentation> {
        val client = createClient(timeoutSeconds = 10)
        val request = Request.Builder()
            .url("${baseUrl(url)}/api/presentations")
            .withApiKey()
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val detail = try {
                    val body = response.body?.string().orEmpty().trim()
                    JSONObject(body).optString("detail", body).take(200)
                } catch (_: Exception) { "" }
                val msg = if (detail.isNotBlank()) "Bridge error ${response.code}: $detail"
                          else "Bridge error: HTTP ${response.code}"
                throw BridgeHttpException(response.code, msg)
            }

            val body = response.body?.string().orEmpty().trim()
            val arr = JSONArray(body)
            val presentations = mutableListOf<Presentation>()

            for (i in 0 until arr.length()) {
                val item = arr.getJSONObject(i)
                presentations += Presentation(
                    id = item.optString("id"),
                    name = item.optString("name"),
                    path = item.optString("path"),
                    inSlideshow = item.optBoolean("in_slideshow"),
                    currentSlide = if (item.has("current_slide") && !item.isNull("current_slide")) {
                        item.optInt("current_slide")
                    } else null,
                    totalSlides = item.optInt("total_slides")
                )
            }
            return presentations
        }
    }

    fun getNetworkStatus(url: String): NetworkStatus? {
        return try {
            val client = createClient(timeoutSeconds = 5)
            val request = Request.Builder()
                .url("${baseUrl(url)}/api/network/status")
                .withApiKey()
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val json = JSONObject(response.body?.string().orEmpty().trim())
                NetworkStatus(
                    networkType = json.optString("network_type", "unknown"),
                    isHotspot = json.optBoolean("is_hotspot", false),
                    warning = if (json.isNull("warning")) null else json.getString("warning")
                )
            }
        } catch (ex: Exception) { null }
    }

    fun startSlideshow(url: String, presentationId: String) = post(url, "/api/presentations/${encodedId(presentationId)}/start")
    fun stopSlideshow(url: String, presentationId: String) = post(url, "/api/presentations/${encodedId(presentationId)}/stop")
    fun next(url: String, presentationId: String) = post(url, "/api/presentations/${encodedId(presentationId)}/next")
    fun previous(url: String, presentationId: String) = post(url, "/api/presentations/${encodedId(presentationId)}/previous")
    fun openFtpOnPc(url: String, clientIp: String? = null) {
        val path = if (clientIp != null) "/api/ftp/open?client_ip=$clientIp" else "/api/ftp/open"
        post(url, path)
    }

    fun registerClient(url: String, deviceId: String, deviceName: String, ftpPort: Int = 2121) {
        if (url.isBlank()) return
        val client = createClient(timeoutSeconds = 5)
        val json = JSONObject().apply {
            put("device_id", deviceId)
            put("device_name", deviceName)
            put("ftp_port", ftpPort)
        }
        val request = Request.Builder()
            .url("${baseUrl(url)}/api/clients/register")
            .withApiKey()
            .post(json.toString().toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(request).execute().use { if (!it.isSuccessful) throw BridgeHttpException(it.code, "Registration failed") }
    }

    fun fetchCurrentThumbnail(url: String, presentationId: String, width: Int = 720): ByteArray? {
        return try {
            val client = createClient(timeoutSeconds = 15)
            val request = Request.Builder()
                .url("${baseUrl(url)}/api/presentations/${encodedId(presentationId)}/current-thumbnail?width=$width")
                .withApiKey()
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) null else response.body?.bytes()
            }
        } catch (e: Exception) { null }
    }

    fun fetchSlideThumbnail(url: String, presentationId: String, slideIndex: Int, width: Int = 720): ByteArray? {
        return try {
            val client = createClient(timeoutSeconds = 15)
            val request = Request.Builder()
                .url("${baseUrl(url)}/api/presentations/${encodedId(presentationId)}/slides/$slideIndex/thumbnail?width=$width")
                .withApiKey()
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) null else response.body?.bytes()
            }
        } catch (e: Exception) { null }
    }

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
                SlideNote(slideIndex = obj.getInt("slide_index"), notes = obj.getString("notes"))
            }
        } catch (e: Exception) { null }
    }

    fun discoverBridge(
        timeoutMs: Int = 1500,
        discoveryPort: Int = 8788,
        bridgePort: Int = 8787,
        networkType: NetworkType = NetworkType.UNKNOWN
    ): List<BridgeInfo> {
        return try {
            val bridges = mutableListOf<BridgeInfo>()
            if (networkType == NetworkType.HOTSPOT_PROVIDING) {
                bridges += discoverViaHotspotSubnet(bridgePort)
            }
            bridges += discoverViaBroadcast(timeoutMs, discoveryPort)
            // Deduplicate by ID
            bridges.distinctBy { it.id }
        } catch (e: Exception) { emptyList() }
    }

    private fun discoverViaHotspotSubnet(bridgePort: Int): List<BridgeInfo> {
        val hotspotSubnets = listOf("192.168.43", "192.168.1", "192.168.0", "10.0.0")
        val scanRangeEnd = 20
        val probeClient = OkHttpClient.Builder()
            .connectTimeout(400, TimeUnit.MILLISECONDS)
            .readTimeout(400, TimeUnit.MILLISECONDS)
            .build()

        val executor = Executors.newFixedThreadPool(16)
        val futures = mutableListOf<Future<BridgeInfo?>>()

        for (subnet in hotspotSubnets) {
            for (host in 2..scanRangeEnd) {
                val candidate = "http://$subnet.$host:$bridgePort"
                futures += executor.submit<BridgeInfo?> {
                    try {
                        val req = Request.Builder().url("$candidate/api/health").get().build()
                        probeClient.newCall(req).execute().use { resp ->
                            if (resp.isSuccessful) {
                                // Since legacy health doesn't have ID, we use URL as ID fallback
                                // Real multi-device environment will mostly use Broadcast
                                BridgeInfo(id = candidate, name = "Bridge at $subnet.$host", url = candidate, isAutoDiscovered = true)
                            } else null
                        }
                    } catch (_: Exception) { null }
                }
            }
        }

        executor.shutdown()
        try { executor.awaitTermination(1500, TimeUnit.MILLISECONDS) } catch (_: Exception) {}

        val result = futures.mapNotNull { f ->
            try { if (f.isDone) f.get() else null } catch (_: Exception) { null }
        }
        executor.shutdownNow()
        return result
    }

    private fun discoverViaBroadcast(timeoutMs: Int, discoveryPort: Int): List<BridgeInfo> {
        val payload = discoveryToken.toByteArray(StandardCharsets.UTF_8)
        val receiveBuffer = ByteArray(1024)
        val bridges = mutableListOf<BridgeInfo>()

        try {
            DatagramSocket().use { socket ->
                socket.broadcast = true
                socket.soTimeout = timeoutMs
                val targets = mutableSetOf<InetAddress>()
                targets += InetAddress.getByName("255.255.255.255")
                try {
                    val interfaces = NetworkInterface.getNetworkInterfaces()
                    if (interfaces != null) {
                        while (interfaces.hasMoreElements()) {
                            val iface = interfaces.nextElement()
                            if (!iface.isUp || iface.isLoopback) continue
                            for (address in iface.interfaceAddresses) {
                                address.broadcast?.let { targets += it }
                            }
                        }
                    }
                } catch (_: Exception) {}

                for (target in targets) {
                    try {
                        socket.send(DatagramPacket(payload, payload.size, target, discoveryPort))
                    } catch (_: Exception) {}
                }

                val startTime = System.currentTimeMillis()
                while (System.currentTimeMillis() - startTime < timeoutMs) {
                    try {
                        val response = DatagramPacket(receiveBuffer, receiveBuffer.size)
                        socket.receive(response)
                        val body = String(response.data, 0, response.length, StandardCharsets.UTF_8)
                        if (!body.trim().startsWith("{")) continue
                        val json = JSONObject(body)
                        val url = json.optString("bridge_url", "")
                        if (url.isNotBlank()) {
                            bridges += BridgeInfo(
                                id = json.optString("bridge_id", url),
                                name = json.optString("bridge_name", "Bridge at ${response.address.hostAddress}"),
                                url = url,
                                version = json.optString("version", "unknown"),
                                isAutoDiscovered = true
                            )
                        }
                    } catch (_: SocketTimeoutException) { break }
                    catch (e: org.json.JSONException) { continue }
                    catch (e: Exception) { break }
                }
            }
        } catch (e: Exception) { }
        return bridges
    }

    private fun post(url: String, path: String) {
        if (url.isBlank()) return
        val client = createClient(timeoutSeconds = 10)
        val request = Request.Builder()
            .url("${baseUrl(url)}$path")
            .withApiKey()
            .post("{}".toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val detail = try {
                    val body = response.body?.string().orEmpty()
                    JSONObject(body).optString("detail", body).take(200)
                } catch (_: Exception) { "" }
                throw BridgeHttpException(response.code, detail.ifBlank { "Bridge error: HTTP ${response.code}" })
            }
        }
    }
}

data class NetworkStatus(val networkType: String, val isHotspot: Boolean, val warning: String?)
data class SlideNote(val slideIndex: Int, val notes: String)
