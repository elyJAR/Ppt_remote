package com.antigravity.pptremote

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class MainViewModel : ViewModel() {
    private val client = BridgeClient()
    private val _state = MutableStateFlow(RemoteState())
    val state: StateFlow<RemoteState> = _state.asStateFlow()

    init {
        startPolling()
    }

    fun setBridgeUrl(url: String) {
        _state.value = _state.value.copy(bridgeUrl = url)
    }

    fun selectPresentation(id: String) {
        _state.value = _state.value.copy(selectedPresentationId = id)
    }

    fun startSelectedSlideshow() {
        val selected = _state.value.selectedPresentationId ?: return
        runBridgeAction("Slideshow started") { url -> client.startSlideshow(url, selected) }
    }

    fun nextSlide() {
        val selected = ensureSelectedPresentation() ?: return
        runBridgeAction("Next slide") { url -> client.next(url, selected) }
    }

    fun previousSlide() {
        val selected = ensureSelectedPresentation() ?: return
        runBridgeAction("Previous slide") { url -> client.previous(url, selected) }
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
            try {
                action(current.bridgeUrl)
                _state.value = _state.value.copy(statusMessage = successMessage)
                refreshPresentations()
            } catch (ex: Exception) {
                _state.value = _state.value.copy(statusMessage = ex.message ?: "Bridge call failed")
            } finally {
                _state.value = _state.value.copy(isBusy = false)
            }
        }
    }

    private fun startPolling() {
        viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                refreshPresentations()
                delay(2000)
            }
        }
    }

    private fun refreshPresentations() {
        val current = _state.value
        val effectiveUrl = if (current.bridgeUrl.isBlank()) {
            val detectedUrl = client.discoverBridge()
            if (detectedUrl == null) {
                _state.value = current.copy(
                    presentations = emptyList(),
                    statusMessage = "Searching for desktop bridge..."
                )
                return
            }

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
            val selected = when {
                latestState.selectedPresentationId != null && presentations.any { it.id == latestState.selectedPresentationId } -> {
                    latestState.selectedPresentationId
                }
                else -> presentations.firstOrNull { it.inSlideshow }?.id ?: presentations.firstOrNull()?.id
            }

            _state.value = latestState.copy(
                presentations = presentations,
                selectedPresentationId = selected,
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
