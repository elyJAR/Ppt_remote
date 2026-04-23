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

class BridgeClient {
    private val client = OkHttpClient()
    private val discoveryPort = 8788
    private val discoveryToken = "PPT_REMOTE_DISCOVER"

    private fun baseUrl(url: String): String = url.trimEnd('/')
    private fun encodedId(id: String): String =
        URLEncoder.encode(id, StandardCharsets.UTF_8.toString()).replace("+", "%20")

    fun fetchPresentations(url: String): List<Presentation> {
        val request = Request.Builder()
            .url("${baseUrl(url)}/api/presentations")
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("Bridge error: HTTP ${response.code}")
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

    fun startSlideshow(url: String, presentationId: String) {
        post(url, "/api/presentations/${encodedId(presentationId)}/start")
    }

    fun next(url: String, presentationId: String) {
        post(url, "/api/presentations/${encodedId(presentationId)}/next")
    }

    fun previous(url: String, presentationId: String) {
        post(url, "/api/presentations/${encodedId(presentationId)}/previous")
    }

    fun discoverBridge(timeoutMs: Int = 1500): String? {
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
        val request = Request.Builder()
            .url("${baseUrl(url)}$path")
            .post("{}".toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("Bridge error: HTTP ${response.code}")
            }
        }
    }
}
