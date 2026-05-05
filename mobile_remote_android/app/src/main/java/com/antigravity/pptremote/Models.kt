package com.antigravity.pptremote

/** A saved bridge entry with a user-friendly display name and connection URL. */
data class SavedBridge(
    val name: String,
    val url: String
)

/**
 * Represents a single open PowerPoint presentation on the desktop.
 *
 * @property id         Full file path or cloud URL — used as the unique identifier in all API calls.
 * @property name       Display name (filename, possibly annotated with status hints).
 * @property path       Same as [id]; kept for API symmetry.
 * @property inSlideshow Whether the presentation is currently running as a slideshow.
 * @property currentSlide 1-based current slide index, or null if not in slideshow.
 * @property totalSlides Total number of slides in the presentation.
 * @property currentThumbnail PNG bytes of the current slide, fetched on slide change. Null when not in slideshow.
 */
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

/**
 * Immutable UI state for the entire app, held in [MainViewModel] as a [kotlinx.coroutines.flow.StateFlow].
 *
 * All fields have sensible defaults so the initial state is valid without any prefs loaded.
 */
data class RemoteState(
    val bridgeUrl: String = "",
    val presentations: List<Presentation> = emptyList(),
    val searchQuery: String = "",
    val selectedPresentationId: String? = null,
    val statusMessage: String = "Searching for desktop bridge...",
    val isBusy: Boolean = false,
    val isRefreshing: Boolean = false,
    val networkType: NetworkType = NetworkType.UNKNOWN,
    val networkWarning: String? = null,
    val bridgeNetworkWarning: String? = null,
    val isServiceRunning: Boolean = false,
    val showOnboarding: Boolean = false,
    val showSettings: Boolean = false,
    val showNotes: Boolean = false,
    val bridgePort: Int = 8787,
    val pollingIntervalSeconds: Int = 2,
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
    val speakerNotes: List<String>? = null,
    // Track last thumbnail slide to avoid re-fetching the same slide
    val lastThumbnailSlide: Int? = null,
    // Multi-bridge
    val bridges: List<SavedBridge> = emptyList(),
    val activeBridgeIndex: Int = 0,
    // Connection tracking
    val failureCount: Int = 0,
    // FTP Feature
    val isFtpEnabled: Boolean = false,
    val isFtpAutoStart: Boolean = false
)
