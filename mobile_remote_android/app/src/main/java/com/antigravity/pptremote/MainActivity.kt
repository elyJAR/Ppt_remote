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
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat

// ─── Colour palette ──────────────────────────────────────────────────────────
private val Navy900  = Color(0xFF0D1117)
private val Navy800  = Color(0xFF161B22)
private val Navy700  = Color(0xFF21262D)
private val Navy600  = Color(0xFF30363D)
private val Accent   = Color(0xFF2F81F7)   // GitHub-blue accent
private val AccentDim = Color(0xFF1F6FEB)
private val Green    = Color(0xFF3FB950)
private val Amber    = Color(0xFFD29922)
private val Red      = Color(0xFFF85149)
private val TextPrimary   = Color(0xFFE6EDF3)
private val TextSecondary = Color(0xFF8B949E)
private val TextMuted     = Color(0xFF484F58)

private val AppColorScheme = darkColorScheme(
    primary          = Accent,
    onPrimary        = Color.White,
    primaryContainer = AccentDim,
    background       = Navy900,
    surface          = Navy800,
    surfaceVariant   = Navy700,
    onBackground     = TextPrimary,
    onSurface        = TextPrimary,
    onSurfaceVariant = TextSecondary,
    outline          = Navy600,
)

class MainActivity : ComponentActivity() {
    private val viewModel by viewModels<MainViewModel>()
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) RemoteControlService.start(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestBatteryOptimizationExemption()
        ensureNotificationPermissionAndStartService()
        setContent {
            MaterialTheme(colorScheme = AppColorScheme) {
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

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_VOLUME_UP   -> { viewModel.previousSlide(); true }
        KeyEvent.KEYCODE_VOLUME_DOWN -> { viewModel.nextSlide();     true }
        else -> super.onKeyDown(keyCode, event)
    }

    private fun requestBatteryOptimizationExemption() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val pm = getSystemService(POWER_SERVICE) as PowerManager
                if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                    startActivity(
                        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                            .apply { data = Uri.parse("package:$packageName") }
                    )
                }
            } catch (_: Exception) {}
        }
    }

    private fun ensureNotificationPermissionAndStartService() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) { notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS); return }
        }
        try { RemoteControlService.start(this) } catch (_: Exception) {}
    }

    override fun onDestroy() { super.onDestroy() }
}

// ─── Root screen ─────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RemoteScreen(
    state: RemoteState,
    onBridgeUrlChange: (String) -> Unit,
    onPresentationSelect: (String) -> Unit,
    onStartSlideshow: () -> Unit,
    onStopSlideshow: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
) {
    val connected = state.statusMessage == "Connected"

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "PPT Remote",
                            fontWeight = FontWeight.SemiBold,
                            color = TextPrimary,
                            fontSize = 18.sp
                        )
                        Spacer(Modifier.width(8.dp))
                        StatusDot(connected = connected)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Navy800,
                    titleContentColor = TextPrimary,
                )
            )
        },
        containerColor = Navy900,
    ) { innerPadding ->

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {

            // ── Connection card ──────────────────────────────────────────────
            item {
                ConnectionCard(
                    bridgeUrl = state.bridgeUrl,
                    statusMessage = state.statusMessage,
                    connected = connected,
                    onBridgeUrlChange = onBridgeUrlChange,
                )
            }

            // ── Warning banners ──────────────────────────────────────────────
            if (!state.networkWarning.isNullOrBlank()) {
                item { WarningBanner(message = state.networkWarning) }
            }
            if (!state.bridgeNetworkWarning.isNullOrBlank()) {
                item { WarningBanner(message = "Desktop: ${state.bridgeNetworkWarning}") }
            }

            // ── Slide controls ───────────────────────────────────────────────
            item {
                SlideControlsCard(
                    isBusy = state.isBusy,
                    hasPresentation = state.selectedPresentationId != null,
                    onPrevious = onPrevious,
                    onNext = onNext,
                    onStart = onStartSlideshow,
                    onStop = onStopSlideshow,
                )
            }

            // ── Presentations header ─────────────────────────────────────────
            item {
                Text(
                    "Presentations",
                    style = MaterialTheme.typography.labelLarge,
                    color = TextSecondary,
                    modifier = Modifier.padding(top = 4.dp, bottom = 2.dp)
                )
            }

            // ── Empty state ──────────────────────────────────────────────────
            if (state.presentations.isEmpty()) {
                item { EmptyStateCard(connected = connected) }
            } else {
                items(state.presentations, key = { it.id }) { presentation ->
                    PresentationCard(
                        presentation = presentation,
                        selected = presentation.id == state.selectedPresentationId,
                        onClick = { onPresentationSelect(presentation.id) }
                    )
                }
            }

            // ── Bottom hint ──────────────────────────────────────────────────
            item {
                Text(
                    "Volume ▲ = Previous  •  Volume ▼ = Next  •  Works with screen off via notification",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMuted,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp)
                )
            }
        }
    }
}

// ─── Connection card ──────────────────────────────────────────────────────────

@Composable
private fun ConnectionCard(
    bridgeUrl: String,
    statusMessage: String,
    connected: Boolean,
    onBridgeUrlChange: (String) -> Unit,
) {
    AppCard {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = if (connected) Icons.Default.CheckCircle else Icons.Default.Search,
                    contentDescription = null,
                    tint = if (connected) Green else TextSecondary,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = statusMessage,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (connected) Green else TextSecondary,
                    fontWeight = FontWeight.Medium,
                )
            }

            OutlinedTextField(
                value = bridgeUrl,
                onValueChange = onBridgeUrlChange,
                label = { Text("Bridge URL", color = TextSecondary) },
                placeholder = { Text("Auto-detecting…", color = TextMuted) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
            )
        }
    }
}

// ─── Slide controls card ──────────────────────────────────────────────────────

@Composable
private fun SlideControlsCard(
    isBusy: Boolean,
    hasPresentation: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    AppCard {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {

            // Large Prev / Next buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                SlideNavButton(
                    label = "◀  Prev",
                    enabled = !isBusy && hasPresentation,
                    modifier = Modifier.weight(1f),
                    onClick = onPrevious,
                )
                SlideNavButton(
                    label = "Next  ▶",
                    enabled = !isBusy && hasPresentation,
                    modifier = Modifier.weight(1f),
                    onClick = onNext,
                )
            }

            // Start / Stop row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                FilledTonalButton(
                    onClick = onStart,
                    enabled = !isBusy && hasPresentation,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = Green.copy(alpha = 0.15f),
                        contentColor = Green,
                    )
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Start", fontWeight = FontWeight.SemiBold)
                }
                FilledTonalButton(
                    onClick = onStop,
                    enabled = !isBusy && hasPresentation,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = Red.copy(alpha = 0.15f),
                        contentColor = Red,
                    )
                ) {
                    Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Stop", fontWeight = FontWeight.SemiBold)
                }
            }

            // Busy indicator
            AnimatedVisibility(visible = isBusy, enter = fadeIn(), exit = fadeOut()) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)),
                    color = Accent,
                    trackColor = Navy700,
                )
            }
        }
    }
}

@Composable
private fun SlideNavButton(
    label: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(56.dp),
        shape = RoundedCornerShape(8.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = Accent,
            contentColor = Color.White,
            disabledContainerColor = Navy700,
            disabledContentColor = TextMuted,
        )
    ) {
        Text(label, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
    }
}

// ─── Presentation card ────────────────────────────────────────────────────────

@Composable
private fun PresentationCard(
    presentation: Presentation,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val progress by animateFloatAsState(
        targetValue = if (presentation.inSlideshow && presentation.currentSlide != null && presentation.totalSlides > 0)
            presentation.currentSlide.toFloat() / presentation.totalSlides.toFloat()
        else 0f,
        label = "slideProgress"
    )

    val borderColor = when {
        selected && presentation.inSlideshow -> Accent
        selected -> Accent.copy(alpha = 0.5f)
        else -> Color.Transparent
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, borderColor, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) Navy700 else Navy800
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // File icon
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (presentation.inSlideshow) Accent.copy(alpha = 0.15f) else Navy600),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (presentation.inSlideshow) Icons.Default.PlayArrow else Icons.Default.Folder,
                        contentDescription = null,
                        tint = if (presentation.inSlideshow) Accent else TextSecondary,
                        modifier = Modifier.size(18.dp)
                    )
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = presentation.name,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = if (selected) TextPrimary else TextPrimary.copy(alpha = 0.85f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = if (presentation.inSlideshow)
                            "Slide ${presentation.currentSlide ?: 1} of ${presentation.totalSlides}"
                        else
                            "${presentation.totalSlides} slides",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (presentation.inSlideshow) Accent else TextSecondary,
                    )
                }

                // Live badge
                if (presentation.inSlideshow) {
                    LiveBadge()
                }
            }

            // Slide progress bar
            if (presentation.inSlideshow && presentation.totalSlides > 0) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .clip(RoundedCornerShape(2.dp)),
                    color = Accent,
                    trackColor = Navy600,
                )
            }
        }
    }
}

// ─── Empty state ──────────────────────────────────────────────────────────────

@Composable
private fun EmptyStateCard(connected: Boolean) {
    AppCard {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("📂", fontSize = 40.sp)
            Text(
                if (connected) "No open presentations" else "Not connected",
                style = MaterialTheme.typography.titleMedium,
                color = TextPrimary,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                if (connected)
                    "Open a .pptx file in PowerPoint on your PC"
                else
                    "Make sure the desktop bridge is running on your PC",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
                textAlign = TextAlign.Center,
            )
        }
    }
}

// ─── Warning banner ───────────────────────────────────────────────────────────

@Composable
private fun WarningBanner(message: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Amber.copy(alpha = 0.12f))
            .border(1.dp, Amber.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.Warning, contentDescription = null, tint = Amber, modifier = Modifier.size(16.dp))
        Text(message, style = MaterialTheme.typography.bodySmall, color = Amber)
    }
}

// ─── Status dot ───────────────────────────────────────────────────────────────

@Composable
private fun StatusDot(connected: Boolean) {
    Box(
        modifier = Modifier
            .size(8.dp)
            .clip(CircleShape)
            .background(if (connected) Green else TextMuted)
    )
}

// ─── Live badge ───────────────────────────────────────────────────────────────

@Composable
private fun LiveBadge() {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(Red.copy(alpha = 0.15f))
            .border(1.dp, Red.copy(alpha = 0.4f), RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(
            "LIVE",
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            color = Red,
            letterSpacing = 0.5.sp,
        )
    }
}

// ─── Shared card surface ──────────────────────────────────────────────────────

@Composable
private fun AppCard(content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = Navy800,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        border = androidx.compose.foundation.BorderStroke(1.dp, Navy600),
    ) {
        Box(modifier = Modifier.padding(16.dp)) {
            content()
        }
    }
}
