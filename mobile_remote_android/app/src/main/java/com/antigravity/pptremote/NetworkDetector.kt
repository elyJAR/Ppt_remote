package com.antigravity.pptremote

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build

enum class NetworkType {
    WIFI,
    HOTSPOT_USING,      // Using someone's hotspot
    HOTSPOT_PROVIDING,  // Providing hotspot to others
    CELLULAR,
    UNKNOWN
}

/**
 * Detects the current network type of the Android device.
 */
object NetworkDetector {
    /** Returns the current [NetworkType] based on active network capabilities and SSID heuristics. */
    fun getNetworkType(context: Context): NetworkType {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        
        // Check if this phone is providing hotspot
        if (isProvidingHotspot(context)) {
            return NetworkType.HOTSPOT_PROVIDING
        }
        
        // Check if using someone's hotspot
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val network = connectivityManager.activeNetwork ?: return NetworkType.UNKNOWN
            val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return NetworkType.UNKNOWN
            
            when {
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> {
                    if (isUsingHotspot(context)) {
                        NetworkType.HOTSPOT_USING
                    } else {
                        NetworkType.WIFI
                    }
                }
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkType.CELLULAR
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkType.WIFI
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI_AWARE) -> NetworkType.WIFI
                else -> NetworkType.UNKNOWN
            }
        } else {
            @Suppress("DEPRECATION")
            val activeNetwork = connectivityManager.activeNetworkInfo ?: return NetworkType.UNKNOWN
            when (activeNetwork.type) {
                ConnectivityManager.TYPE_WIFI -> {
                    if (isUsingHotspot(context)) {
                        NetworkType.HOTSPOT_USING
                    } else {
                        NetworkType.WIFI
                    }
                }
                ConnectivityManager.TYPE_MOBILE -> NetworkType.CELLULAR
                else -> NetworkType.UNKNOWN
            }
        }
    }
    
    private fun isProvidingHotspot(context: Context): Boolean {
        return try {
            val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            @Suppress("DEPRECATION")
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
            
            try {
                // Primary method: getTetheredIfaces (returns currently active tethering interfaces)
                val method = connectivityManager.javaClass.getMethod("getTetheredIfaces")
                val result = method.invoke(connectivityManager)
                val activeIfaces = when (result) {
                    is Array<*> -> result.filterIsInstance<String>()
                    is List<*> -> result.filterIsInstance<String>()
                    else -> emptyList()
                }

                // Check for common WiFi hotspot interface names to avoid false positives from USB/BT
                val hasWifiHotspot = activeIfaces.any { it.contains("wlan") || it.contains("ap") || it.contains("softap") }
                if (hasWifiHotspot) return true

                // Fallback to legacy reflection check
                val wifiMethod = wifiManager.javaClass.getDeclaredMethod("isWifiApEnabled")
                wifiMethod.isAccessible = true
                val res = wifiMethod.invoke(wifiManager)
                if (res is Boolean) return res
                
                false
            } catch (e: Exception) {
                // Secondary fallback: check via wifiManager reflection
                try {
                    val method = wifiManager.javaClass.getDeclaredMethod("isWifiApEnabled")
                    method.isAccessible = true
                    val res = method.invoke(wifiManager)
                    if (res is Boolean) res else false
                } catch (e2: Exception) {
                    false
                }
            }
        } catch (e: Exception) {
            false
        }
    }
    
    private fun isUsingHotspot(context: Context): Boolean {
        return try {
            @Suppress("DEPRECATION")
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
            @Suppress("DEPRECATION")
            val connectionInfo = wifiManager.connectionInfo ?: return false
            @Suppress("DEPRECATION")
            val ssid = connectionInfo.ssid?.replace("\"", "") ?: return false

            // Common patterns for personal hotspot names
            val hotspotIndicators = listOf(
                "iphone", "samsung", "hotspot", "personal", "pixel", "oneplus",
                "motorola", "nokia", "xiaomi", "huawei", "tethering", "moto hotspot",
                "android ap", "wifi direct", "wifidirect"
            )

            val ssidNorm = ssid.lowercase().replace("-", "").replace(" ", "")
            hotspotIndicators.any { indicator ->
                ssidNorm.contains(indicator.replace("-", "").replace(" ", ""))
            }
        } catch (e: Exception) {
            false
        }
    }
}
