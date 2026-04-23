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
    val bridgeUrl: String = "http://192.168.1.10:8787",
    val presentations: List<Presentation> = emptyList(),
    val selectedPresentationId: String? = null,
    val statusMessage: String = "Waiting for bridge",
    val isBusy: Boolean = false
)
