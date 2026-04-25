package com.antigravity.pptremote

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    private val viewModel by viewModels<MainViewModel>()
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            RemoteControlService.start(this)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Request battery optimization exemption for better background performance
        requestBatteryOptimizationExemption()

        ensureNotificationPermissionAndStartService()

        setContent {
            MaterialTheme {
                val state by viewModel.state.collectAsState()
                RemoteScreen(
                    state = state,
                    onBridgeUrlChange = viewModel::setBridgeUrl,
                    onPresentationSelect = viewModel::selectPresentation,
                    onStartSlideshow = viewModel::startSelectedSlideshow,
                    onStopSlideshow = viewModel::stopSelectedSlideshow,
                    onNext = viewModel::nextSlide,
                    onPrevious = viewModel::previousSlide
                )
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> {
                viewModel.previousSlide()
                true
            }

            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                viewModel.nextSlide()
                true
            }

            else -> super.onKeyDown(keyCode, event)
        }
    }

    private fun requestBatteryOptimizationExemption() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val powerManager = getSystemService(POWER_SERVICE) as PowerManager
                val packageName = packageName

                if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                    android.util.Log.d("MainActivity", "Requesting battery optimization exemption")
                }
            } catch (e: Exception) {
                android.util.Log.e("MainActivity", "Failed to request battery optimization exemption", e)
            }
        }
    }

    private fun ensureNotificationPermissionAndStartService() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED

            if (!granted) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                return
            }
        }

        try {
            RemoteControlService.start(this)
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "Failed to start foreground service", e)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Note: Service continues running even after activity is destroyed
        // User can stop it from notification or by force-stopping the app
    }
}

@Composable
private fun RemoteScreen(
    state: RemoteState,
    onBridgeUrlChange: (String) -> Unit,
    onPresentationSelect: (String) -> Unit,
    onStartSlideshow: () -> Unit,
    onStopSlideshow: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("PowerPoint Remote", style = MaterialTheme.typography.headlineSmall)

        OutlinedTextField(
            value = state.bridgeUrl,
            onValueChange = onBridgeUrlChange,
            label = { Text("Desktop Bridge URL") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Text(
            text = "Status: ${state.statusMessage}",
            style = MaterialTheme.typography.bodyMedium
        )

        // Improvement 1 — Network Warning Banners
        if (!state.networkWarning.isNullOrBlank()) {
            WarningBanner(message = state.networkWarning)
        }
        if (!state.bridgeNetworkWarning.isNullOrBlank()) {
            WarningBanner(message = "Desktop: ${state.bridgeNetworkWarning}")
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(onClick = onStartSlideshow, modifier = Modifier.weight(1f)) {
                Text("Start Slideshow")
            }
            Button(onClick = onStopSlideshow, modifier = Modifier.weight(1f), enabled = !state.isBusy) {
                Text("Stop")
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(onClick = onPrevious, modifier = Modifier.weight(1f), enabled = !state.isBusy) {
                Text("Previous")
            }
            Button(onClick = onNext, modifier = Modifier.weight(1f), enabled = !state.isBusy) {
                Text("Next")
            }
        }

        // Improvement 2 — Loading Indicator When isBusy
        if (state.isBusy) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        Text(
            "Foreground: Volume Up/Down control slides. Background or screen-off: use notification actions (Previous/Next/Start/Stop).",
            style = MaterialTheme.typography.bodySmall
        )

        // Improvement 3 — Empty State Message
        if (state.presentations.isEmpty()) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .padding(24.dp)
                        .fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("📂", style = MaterialTheme.typography.displaySmall)
                    Text(
                        "No open presentations found",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "Open a .pptx file in PowerPoint on your PC, then wait a moment.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.presentations, key = { it.id }) { presentation ->
                    val selected = presentation.id == state.selectedPresentationId
                    PresentationItem(
                        presentation = presentation,
                        selected = selected,
                        onClick = { onPresentationSelect(presentation.id) }
                    )
                }
            }
        }
    }
}

@Composable
private fun PresentationItem(presentation: Presentation, selected: Boolean, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = presentation.name,
                style = MaterialTheme.typography.titleMedium,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = if (presentation.inSlideshow) {
                    "Slideshow: slide ${presentation.currentSlide ?: 1}/${presentation.totalSlides}"
                } else {
                    "Editing mode"
                },
                style = MaterialTheme.typography.bodyMedium
            )
            // Improvement 4 — Slide Progress Bar
            if (presentation.inSlideshow && presentation.currentSlide != null && presentation.totalSlides > 0) {
                LinearProgressIndicator(
                    progress = { presentation.currentSlide.toFloat() / presentation.totalSlides.toFloat() },
                    modifier = Modifier.fillMaxWidth()
                )
            }
            Text(presentation.path, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun WarningBanner(message: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFFFFF3CD)
        )
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("⚠", style = MaterialTheme.typography.bodyMedium)
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF856404)
            )
        }
    }
}
