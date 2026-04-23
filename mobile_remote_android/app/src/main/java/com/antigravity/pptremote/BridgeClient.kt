package com.antigravity.pptremote

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

class BridgeClient {
    private val client = OkHttpClient()

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
