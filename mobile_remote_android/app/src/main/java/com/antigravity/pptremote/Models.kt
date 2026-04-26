package com.antigravity.pptremote

data class Presentation(
    val id: String,
    val name: String,
    val path: String,
    val inSlideshow: Boolean,
    val currentSlide: Int?,
    val totalSlides: Int,
    val currentThumbnail: ByteArray? = null
) {
    // ByteArray needs custom equals/hashCode to avoid identity comparison
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Presentation) return false
        return id == other.id &&
            name == other.name &&
            inSlideshow == other.inSlideshow &&
            currentSlide == other.currentSlide &&
            totalSlides == other.totalSlides &&
            currentThumbnail.contentEquals(other.currentThumbnail)
    }
    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + inSlideshow.hashCode()
        result = 31 * result + (currentSlide ?: 0)
        result = 31 * result + (currentThumbnail?.contentHashCode() ?: 0)
        return result
    }
}

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
    val showNotes: Boolean = false,
    val bridgePort: Int = 8787,
    val pollingIntervalSeconds: Int = 3,
    val isDarkTheme: Boolean = true,
    val connectionHistory: List<String> = emptyList(),
    val notificationText: String = "Tap ⏮ ⏭ to change slides — works with screen off",
    // API key for bridge authentication (empty = open mode)
    val apiKey: String = "",
    // Whether the bridge is currently reachable (health check)
    val bridgeReachable: Boolean = false,
    // Speaker notes for the current slide
    val currentSlideNotes: String? = null,
    val currentSlideNotesIndex: Int? = null,
    // Track last thumbnail slide to avoid re-fetching the same slide
    val lastThumbnailSlide: Int? = null
)
