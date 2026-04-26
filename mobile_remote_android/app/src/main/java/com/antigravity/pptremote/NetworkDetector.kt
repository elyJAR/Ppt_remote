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

object NetworkDetector {
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
            // Check if WiFi hotspot is enabled on this device
            @Suppress("DEPRECATION")
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
            
            // Try to check if hotspot is active (requires reflection on some Android versions)
            try {
                val method = wifiManager.javaClass.getDeclaredMethod("isWifiApEnabled")
                method.invoke(wifiManager) as Boolean
            } catch (e: Exception) {
                // Fallback: check via connectivity manager if device is a local AP
                val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    // In Android 10+, check for local network sharing
                    try {
                        val tetheredIfaces = connectivityManager.javaClass
                            .getMethod("getTetheredIfaces")
                            .invoke(connectivityManager) as Array<*>
                        tetheredIfaces.isNotEmpty()
                    } catch (e: Exception) {
                        false
                    }
                } else {
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

            // Common patterns for personal hotspot names — checked case-insensitively
            val hotspotIndicators = listOf(
                "iphone",
                "samsung",
                "hotspot",
                "personal",
                "pixel",
                "oneplus",
                "motorola",
                "nokia",
                "xiaomi",
                "huawei",
                "tethering",
                "moto hotspot",
                "android ap",
                "wifi direct",   // normalised: no hyphen, lowercase
                "wifidirect"
            )

            val ssidNorm = ssid.lowercase().replace("-", "").replace(" ", "")
            hotspotIndicators.any { indicator ->
                ssidNorm.contains(indicator.replace("-", "").replace(" ", ""))
            }
            // Removed unreliable link-speed heuristic (< 54 Mbps is not a reliable
            // hotspot indicator — many routers and 5 GHz bands report lower speeds)
        } catch (e: Exception) {
            false
        }
    }
}

