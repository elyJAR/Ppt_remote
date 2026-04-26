package com.antigravity.pptremote

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val client = BridgeClient()
    private val appContext = getApplication<Application>()
    private var lastNetworkType: NetworkType = NetworkType.UNKNOWN
    private var networkChangeCallbackRegistered = false

    private val _state = MutableStateFlow(
        RemoteState(
            bridgeUrl = RemotePrefs.getBridgeUrl(appContext),
            showOnboarding = !RemotePrefs.isOnboardingCompleted(appContext),
            bridgePort = RemotePrefs.getBridgePort(appContext),
            pollingIntervalSeconds = RemotePrefs.getPollingInterval(appContext),
            isDarkTheme = RemotePrefs.isDarkTheme(appContext),
            connectionHistory = RemotePrefs.getConnectionHistory(appContext),
            notificationText = RemotePrefs.getNotificationText(appContext)
        )
    )
    val state: StateFlow<RemoteState> = _state.asStateFlow()

    init {
        updateNetworkType()
        updateServiceStatus()
        registerNetworkChangeListener()
        startPolling()
    }

    private fun updateNetworkType() {
        val currentNetworkType = NetworkDetector.getNetworkType(appContext)

        // Only update if network type changed
        if (currentNetworkType != lastNetworkType) {
            lastNetworkType = currentNetworkType

            val warning = when (currentNetworkType) {
                NetworkType.HOTSPOT_USING -> {
                    "Using phone hotspot: Connection may be less stable. Using aggressive reconnection strategy."
                }
                NetworkType.HOTSPOT_PROVIDING -> {
                    "Providing hotspot to PC: Connection may be less stable. Using aggressive reconnection strategy."
                }
                NetworkType.CELLULAR -> {
                    "Using cellular data: Consider switching to WiFi for better stability."
                }
                else -> null
            }

            val current = _state.value
            _state.value = current.copy(
                networkType = currentNetworkType,
                networkWarning = warning
            )
        }
    }

    private fun registerNetworkChangeListener() {
        if (networkChangeCallbackRegistered) return

        try {
            val connectivityManager = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val networkCallback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: android.net.Network) {
                    updateNetworkType()
                }

                override fun onLost(network: android.net.Network) {
                    updateNetworkType()
                }

                override fun onCapabilitiesChanged(
                    network: android.net.Network,
                    networkCapabilities: android.net.NetworkCapabilities
                ) {
                    updateNetworkType()
                }
            }

            connectivityManager.registerDefaultNetworkCallback(networkCallback)
            networkChangeCallbackRegistered = true
        } catch (e: Exception) {
            // Fallback: Will detect network type during polling
        }
    }

    fun setBridgeUrl(url: String) {
        val trimmedUrl = url.trim()
        val urlWithPort = if (trimmedUrl.isNotBlank()) {
            buildBridgeUrl(trimmedUrl, _state.value.bridgePort)
        } else {
            trimmedUrl
        }
        
        RemotePrefs.setBridgeUrl(appContext, urlWithPort)
        if (urlWithPort.isNotBlank()) {
            RemotePrefs.addToConnectionHistory(appContext, urlWithPort)
        }
        _state.value = _state.value.copy(
            bridgeUrl = urlWithPort,
            connectionHistory = RemotePrefs.getConnectionHistory(appContext)
        )
    }

    fun selectPresentation(id: String) {
        RemotePrefs.setSelectedPresentationId(appContext, id)
        _state.value = _state.value.copy(selectedPresentationId = id)
    }

    fun startSelectedSlideshow() {
        val selected = _state.value.selectedPresentationId ?: return
        runBridgeAction("Slideshow started") { url -> client.startSlideshow(url, selected) }
    }

    fun stopSelectedSlideshow() {
        val selected = _state.value.selectedPresentationId ?: return
        runBridgeAction("Slideshow stopped") { url -> client.stopSlideshow(url, selected) }
    }

    fun nextSlide() {
        val selected = ensureSelectedPresentation() ?: return
        runBridgeAction("Next slide") { url -> client.next(url, selected) }
    }

    fun previousSlide() {
        val selected = ensureSelectedPresentation() ?: return
        runBridgeAction("Previous slide") { url -> client.previous(url, selected) }
    }

    fun toggleService() {
        if (_state.value.isServiceRunning) {
            RemoteControlService.stop(appContext)
        } else {
            RemoteControlService.start(appContext)
        }
        updateServiceStatus()
    }

    private fun updateServiceStatus() {
        val isRunning = RemoteControlService.isRunning(appContext)
        _state.value = _state.value.copy(isServiceRunning = isRunning)
    }

    fun completeOnboarding() {
        RemotePrefs.setOnboardingCompleted(appContext, true)
        _state.value = _state.value.copy(showOnboarding = false)
    }

    fun showSettings() {
        _state.value = _state.value.copy(showSettings = true)
    }

    fun hideSettings() {
        _state.value = _state.value.copy(showSettings = false)
    }

    fun updateBridgePort(port: Int) {
        if (port in 1024..65535) {
            RemotePrefs.setBridgePort(appContext, port)
            _state.value = _state.value.copy(bridgePort = port)
        }
    }

    fun updatePollingInterval(seconds: Int) {
        if (seconds in 1..30) {
            RemotePrefs.setPollingInterval(appContext, seconds)
            _state.value = _state.value.copy(pollingIntervalSeconds = seconds)
        }
    }

    fun updateTheme(isDark: Boolean) {
        RemotePrefs.setDarkTheme(appContext, isDark)
        _state.value = _state.value.copy(isDarkTheme = isDark)
    }

    fun updateNotificationText(text: String) {
        RemotePrefs.setNotificationText(appContext, text)
        _state.value = _state.value.copy(notificationText = text)
    }

    private fun buildBridgeUrl(baseUrl: String, port: Int): String {
        if (baseUrl.isBlank()) return ""
        
        // If URL already contains a port, use it as-is
        if (baseUrl.contains("://") && baseUrl.substringAfter("://").contains(":")) {
            return baseUrl
        }
        
        // If it's just an IP or hostname, add the configured port
        val cleanUrl = baseUrl.removePrefix("http://").removePrefix("https://")
        return "http://$cleanUrl:$port"
    }

    private fun ensureSelectedPresentation(): String? {
        val current = _state.value
        if (current.selectedPresentationId != null) {
            return current.selectedPresentationId
        }

        val autoPick = current.presentations.firstOrNull { it.inSlideshow }
            ?: current.presentations.firstOrNull()

        return autoPick?.id?.also { selectPresentation(it) }
    }

    private fun runBridgeAction(successMessage: String, action: (String) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val current = _state.value
            _state.value = current.copy(isBusy = true)

            // Use smart retry logic based on network type
            val maxRetries = when (current.networkType) {
                NetworkType.HOTSPOT_USING, NetworkType.HOTSPOT_PROVIDING -> 3  // More retries for any hotspot
                NetworkType.CELLULAR -> 1
                else -> 2
            }

            var lastException: Exception? = null

            for (attempt in 1..maxRetries) {
                try {
                    action(current.bridgeUrl)
                    _state.value = _state.value.copy(statusMessage = successMessage, isBusy = false)
                    refreshPresentations()
                    return@launch
                } catch (ex: Exception) {
                    lastException = ex
                    if (attempt < maxRetries) {
                        // Exponential backoff with network-type adjustment
                        val backoffMs = when (current.networkType) {
                            NetworkType.HOTSPOT_USING, NetworkType.HOTSPOT_PROVIDING -> 300L * (attempt - 1)
                            NetworkType.CELLULAR -> 500L * (attempt - 1)
                            else -> 200L * (attempt - 1)
                        }
                        delay(backoffMs)
                    }
                }
            }

            _state.value = _state.value.copy(statusMessage = lastException?.message ?: "Bridge call failed")
            _state.value = _state.value.copy(isBusy = false)
        }
    }

    private fun startPolling() {
        viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                // Adjust polling frequency based on network type
                // Hotspots benefit from more frequent checks for stability
                val delayMs = when (_state.value.networkType) {
                    NetworkType.HOTSPOT_USING, NetworkType.HOTSPOT_PROVIDING -> 1500L  // More aggressive for any hotspot
                    NetworkType.CELLULAR -> 3000L // Less frequent for cellular
                    else -> 2000L
                }

                refreshPresentations()
                delay(delayMs)
            }
        }
    }

    fun refreshPresentations() {
        updateNetworkType()

        val current = _state.value
        val effectiveUrl = if (current.bridgeUrl.isBlank()) {
            // Use smart discovery timeout based on network type
            val discoveryTimeoutMs = when (current.networkType) {
                NetworkType.HOTSPOT_USING, NetworkType.HOTSPOT_PROVIDING -> 2500  // Longer timeout for any hotspot
                NetworkType.CELLULAR -> 3000
                else -> 1500
            }

            val detectedUrl = client.discoverBridge(discoveryTimeoutMs, current.bridgePort + 1) // Discovery port is typically bridge port + 1
            if (detectedUrl == null) {
                _state.value = current.copy(
                    presentations = emptyList(),
                    statusMessage = "Searching for desktop bridge..."
                )
                return
            }

            RemotePrefs.setBridgeUrl(appContext, detectedUrl)
            _state.value = current.copy(
                bridgeUrl = detectedUrl,
                statusMessage = "Bridge detected at $detectedUrl"
            )
            detectedUrl
        } else {
            current.bridgeUrl
        }

        try {
            val latestState = _state.value
            val presentations = client.fetchPresentations(effectiveUrl)

            // Also fetch bridge's network status
            val bridgeNetworkWarning = client.getNetworkStatus(effectiveUrl)?.warning

            val selected = when {
                latestState.selectedPresentationId != null && presentations.any { it.id == latestState.selectedPresentationId } -> {
                    latestState.selectedPresentationId
                }
                else -> presentations.firstOrNull { it.inSlideshow }?.id ?: presentations.firstOrNull()?.id
            }

            RemotePrefs.setSelectedPresentationId(appContext, selected)
            _state.value = latestState.copy(
                presentations = presentations,
                selectedPresentationId = selected,
                bridgeNetworkWarning = bridgeNetworkWarning,
                statusMessage = if (presentations.isEmpty()) {
                    "No open PowerPoint files detected"
                } else {
                    "Connected"
                }
            )
        } catch (ex: Exception) {
            _state.value = _state.value.copy(
                presentations = emptyList(),
                statusMessage = ex.message ?: "Unable to reach bridge"
            )
        }
    }
}
