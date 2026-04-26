package com.antigravity.pptremote

data class Presentation(
    val id: String,
    val name: String,
    val path: String,
    val inSlideshow: Boolean,
    val currentSlide: Int?,
    val totalSlides: Int
)

data class RemoteState(
    val bridgeUrl: String = "",
    val presentations: List<Presentation> = emptyList(),
    val selectedPresentationId: String? = null,
    val statusMessage: String = "Searching for desktop bridge...",
    val isBusy: Boolean = false,
    val networkType: NetworkType = NetworkType.UNKNOWN,
    val networkWarning: String? = null,
    val bridgeNetworkWarning: String? = null,
    val isServiceRunning: Boolean = false,
    val showOnboarding: Boolean = false,
    val showSettings: Boolean = false,
    val bridgePort: Int = 8787,
    val pollingIntervalSeconds: Int = 3,
    val isDarkTheme: Boolean = true,
    val connectionHistory: List<String> = emptyList(),
    val notificationText: String = "Tap ⏮ ⏭ to change slides — works with screen off"
)
