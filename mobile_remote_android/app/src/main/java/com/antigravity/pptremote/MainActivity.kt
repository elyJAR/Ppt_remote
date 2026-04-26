package com.antigravity.pptremote

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
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
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.google.accompanist.swiperefresh.SwipeRefresh
import com.google.accompanist.swiperefresh.rememberSwipeRefreshState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
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
private val Blue     = Color(0xFF2F81F7)
private val Gray     = Color(0xFF6E7681)
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
        // Install splash screen before calling super.onCreate()
        installSplashScreen()
        
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestBatteryOptimizationExemption()
        ensureNotificationPermissionAndStartService()
        setContent {
            MaterialTheme(colorScheme = AppColorScheme) {
                val state by viewModel.state.collectAsState()
                
                when {
                    state.showOnboarding -> {
                        OnboardingScreen(
                            onComplete = viewModel::completeOnboarding
                        )
                    }
                    state.showSettings -> {
                        SettingsScreen(
                            state = state,
                            onBack = viewModel::hideSettings,
                            onUpdateBridgePort = viewModel::updateBridgePort,
                            onUpdatePollingInterval = viewModel::updatePollingInterval,
                            onUpdateTheme = viewModel::updateTheme,
                            onUpdateNotificationText = viewModel::updateNotificationText
                        )
                    }
                    else -> {
                        RemoteScreen(
                            state = state,
                            onBridgeUrlChange = viewModel::setBridgeUrl,
                            onPresentationSelect = viewModel::selectPresentation,
                            onStartSlideshow = viewModel::startSelectedSlideshow,
                            onStopSlideshow = viewModel::stopSelectedSlideshow,
                            onNext = viewModel::nextSlide,
                            onPrevious = viewModel::previousSlide,
                            onRefresh = viewModel::refreshPresentations,
                            onToggleService = viewModel::toggleService,
                            onShowSettings = viewModel::showSettings
                        )
                    }
                }
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_VOLUME_UP   -> { 
            performHapticFeedback()
            viewModel.previousSlide()
            true 
        }
        KeyEvent.KEYCODE_VOLUME_DOWN -> { 
            performHapticFeedback()
            viewModel.nextSlide()
            true 
        }
        else -> super.onKeyDown(keyCode, event)
    }

    private fun performHapticFeedback() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // Android 12+ - Use VibratorManager
                val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                val vibrator = vibratorManager?.defaultVibrator
                vibrator?.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // Android 8+ - Use Vibrator with VibrationEffect
                val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                vibrator?.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                // Legacy - Use deprecated vibrate method
                @Suppress("DEPRECATION")
                val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                @Suppress("DEPRECATION")
                vibrator?.vibrate(50)
            }
        } catch (e: Exception) {
            // Haptic feedback is not critical, silently ignore errors
        }
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
    onRefresh: () -> Unit,
    onToggleService: () -> Unit,
    onShowSettings: () -> Unit,
) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val connected = state.statusMessage == "Connected"
    
    // Determine if we're on a tablet or in landscape
    val isTablet = configuration.screenWidthDp >= 600
    val isLandscape = configuration.screenWidthDp > configuration.screenHeightDp
    val useWideLayout = isTablet || isLandscape

    // Helper function for haptic feedback in gestures
    fun performGestureHapticFeedback() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                val vibrator = vibratorManager?.defaultVibrator
                vibrator?.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                vibrator?.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                @Suppress("DEPRECATION")
                vibrator?.vibrate(50)
            }
        } catch (e: Exception) {
            // Haptic feedback is not critical, silently ignore errors
        }
    }

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
                        StatusDot(
                            connected = connected,
                            serviceRunning = state.isServiceRunning
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = onToggleService,
                        modifier = Modifier.semantics {
                            role = Role.Button
                            contentDescription = if (state.isServiceRunning) "Stop background service" else "Start background service"
                        }
                    ) {
                        Icon(
                            imageVector = if (state.isServiceRunning) Icons.Default.Stop else Icons.Default.PlayArrow,
                            contentDescription = if (state.isServiceRunning) "Stop background service" else "Start background service",
                            tint = if (state.isServiceRunning) TextPrimary else TextMuted
                        )
                    }
                    IconButton(
                        onClick = onRefresh,
                        enabled = !state.isBusy,
                        modifier = Modifier.semantics {
                            role = Role.Button
                            contentDescription = "Refresh presentations"
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Refresh presentations",
                            tint = if (state.isBusy) TextMuted else TextPrimary
                        )
                    }
                    IconButton(
                        onClick = onShowSettings,
                        modifier = Modifier.semantics {
                            role = Role.Button
                            contentDescription = "Settings"
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Settings",
                            tint = TextPrimary
                        )
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

        val swipeRefreshState = rememberSwipeRefreshState(isRefreshing = state.isBusy)

        SwipeRefresh(
            state = swipeRefreshState,
            onRefresh = onRefresh,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectHorizontalDragGestures(
                            onDragEnd = { 
                                // Haptic feedback is handled in the drag detection
                            }
                    ) { _, dragAmount ->
                        val threshold = 100f // Minimum drag distance in pixels
                        if (dragAmount > threshold) {
                            // Swipe right - Previous slide
                            performGestureHapticFeedback()
                            onPrevious()
                        } else if (dragAmount < -threshold) {
                            // Swipe left - Next slide  
                            performGestureHapticFeedback()
                            onNext()
                        }
                    }
                },
            contentPadding = PaddingValues(
                horizontal = if (useWideLayout) 32.dp else 16.dp, 
                vertical = 12.dp
            ),
            verticalArrangement = Arrangement.spacedBy(if (useWideLayout) 16.dp else 12.dp)
        ) {

            // ── Connection card ──────────────────────────────────────────────
            item {
                ConnectionCard(
                    bridgeUrl = state.bridgeUrl,
                    statusMessage = state.statusMessage,
                    connected = connected,
                    connectionHistory = state.connectionHistory,
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
                    useWideLayout = useWideLayout,
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
}

// ─── Connection card ──────────────────────────────────────────────────────────

@Composable
private fun ConnectionCard(
    bridgeUrl: String,
    statusMessage: String,
    connected: Boolean,
    connectionHistory: List<String>,
    onBridgeUrlChange: (String) -> Unit,
) {
    var showHistory by remember { mutableStateOf(false) }
    
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

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = bridgeUrl,
                    onValueChange = onBridgeUrlChange,
                    label = { Text("Bridge URL", color = TextSecondary) },
                    placeholder = { Text("Auto-detecting…", color = TextMuted) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Accent,
                        unfocusedBorderColor = Navy600,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary
                    )
                )
                
                if (connectionHistory.isNotEmpty()) {
                    IconButton(
                        onClick = { showHistory = !showHistory }
                    ) {
                        Icon(
                            imageVector = if (showHistory) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = if (showHistory) "Hide history" else "Show history",
                            tint = TextSecondary
                        )
                    }
                }
            }
            
            // Connection History
            if (showHistory && connectionHistory.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "Recent Connections",
                        style = MaterialTheme.typography.labelMedium,
                        color = TextSecondary,
                        fontWeight = FontWeight.Medium
                    )
                    
                    connectionHistory.takeLast(5).forEach { historyUrl ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onBridgeUrlChange(historyUrl) }
                                .padding(vertical = 4.dp, horizontal = 8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.History,
                                contentDescription = null,
                                tint = TextMuted,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                historyUrl,
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }
        }
    }
}

// ─── Slide controls card ──────────────────────────────────────────────────────

@Composable
private fun SlideControlsCard(
    isBusy: Boolean,
    hasPresentation: Boolean,
    useWideLayout: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    AppCard {
        if (useWideLayout) {
            // Wide layout: all controls in a single row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
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
                FilledTonalButton(
                    onClick = onStart,
                    enabled = !isBusy && hasPresentation,
                    modifier = Modifier.weight(0.8f),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = Green.copy(alpha = 0.2f),
                        contentColor = Green
                    )
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Start", fontWeight = FontWeight.SemiBold)
                }
                FilledTonalButton(
                    onClick = onStop,
                    enabled = !isBusy && hasPresentation,
                    modifier = Modifier.weight(0.8f),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = Red.copy(alpha = 0.2f),
                        contentColor = Red
                    )
                ) {
                    Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Stop", fontWeight = FontWeight.SemiBold)
                }
            }
        } else {
            // Compact layout: original two-row design
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
            .clickable(onClick = onClick)
            .semantics {
                role = Role.Button
                contentDescription = buildString {
                    append("Presentation: ${presentation.name}")
                    if (presentation.inSlideshow) {
                        append(", currently in slideshow")
                        if (presentation.currentSlide != null) {
                            append(", slide ${presentation.currentSlide} of ${presentation.totalSlides}")
                        }
                    } else {
                        append(", ${presentation.totalSlides} slides total")
                    }
                    if (selected) append(", selected")
                }
            },
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
private fun StatusDot(connected: Boolean, serviceRunning: Boolean) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Connection status dot
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(if (connected) Green else TextMuted)
        )
        
        // Service status dot
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(if (serviceRunning) Blue else Gray)
        )
    }
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

// ─── Onboarding Screen ────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OnboardingScreen(onComplete: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Welcome to PPT Remote",
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimary
                    )
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
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            
            item {
                AppCard {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = null,
                            tint = Accent,
                            modifier = Modifier.size(48.dp)
                        )
                        
                        Text(
                            "Control PowerPoint from your phone",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = TextPrimary,
                            textAlign = TextAlign.Center
                        )
                        
                        Text(
                            "Use volume buttons or swipe gestures to navigate slides, even with your screen off.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextSecondary,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
            
            item {
                AppCard {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            "Setup Instructions",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = TextPrimary
                        )
                        
                        OnboardingStep(
                            number = "1",
                            title = "Download Desktop Bridge",
                            description = "Install the PPT Remote Bridge on your Windows PC from the GitHub releases page."
                        )
                        
                        OnboardingStep(
                            number = "2", 
                            title = "Run the Bridge",
                            description = "Start the bridge application on your PC. It will run in the system tray."
                        )
                        
                        OnboardingStep(
                            number = "3",
                            title = "Connect to Same Network", 
                            description = "Ensure both your phone and PC are on the same WiFi network."
                        )
                        
                        OnboardingStep(
                            number = "4",
                            title = "Open PowerPoint",
                            description = "Open a PowerPoint presentation on your PC. The app will auto-discover it."
                        )
                    }
                }
            }
            
            item {
                AppCard {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            "Controls",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = TextPrimary
                        )
                        
                        ControlItem("Volume ▲", "Previous slide")
                        ControlItem("Volume ▼", "Next slide") 
                        ControlItem("Swipe right", "Previous slide")
                        ControlItem("Swipe left", "Next slide")
                        ControlItem("Notification", "Control with screen off")
                    }
                }
            }
            
            item {
                Button(
                    onClick = onComplete,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Accent,
                        contentColor = Color.White
                    )
                ) {
                    Text(
                        "Get Started",
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun OnboardingStep(
    number: String,
    title: String,
    description: String
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(CircleShape)
                .background(Accent),
            contentAlignment = Alignment.Center
        ) {
            Text(
                number,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
        }
        
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = TextPrimary
            )
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )
        }
    }
}

@Composable
private fun ControlItem(action: String, result: String) {
    Row(
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            action,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = TextPrimary
        )
        Text(
            result,
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary
        )
    }
}

// ─── Settings Screen ──────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen(
    state: RemoteState,
    onBack: () -> Unit,
    onUpdateBridgePort: (Int) -> Unit,
    onUpdatePollingInterval: (Int) -> Unit,
    onUpdateTheme: (Boolean) -> Unit,
    onUpdateNotificationText: (String) -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Settings",
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimary
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = "Back",
                            tint = TextPrimary
                        )
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
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            
            item {
                SettingsSection(title = "Connection") {
                    SettingsNumberItem(
                        title = "Bridge Port",
                        description = "Port number for desktop bridge connection (affects new connections)",
                        value = state.bridgePort,
                        range = 1024..65535,
                        onValueChange = onUpdateBridgePort
                    )
                }
            }
            
            item {
                SettingsSection(title = "Performance") {
                    SettingsNumberItem(
                        title = "Polling Interval",
                        description = "How often to check for presentation updates (seconds)",
                        value = state.pollingIntervalSeconds,
                        range = 1..30,
                        onValueChange = onUpdatePollingInterval
                    )
                }
            }
            
            item {
                SettingsSection(title = "Appearance") {
                    SettingsSwitchItem(
                        title = "Dark Theme",
                        description = "Use dark color scheme throughout the app",
                        checked = state.isDarkTheme,
                        onCheckedChange = onUpdateTheme
                    )
                }
            }
            
            item {
                SettingsSection(title = "Notifications") {
                    SettingsTextItem(
                        title = "Default Notification Text",
                        description = "Text shown when no presentation is active",
                        value = state.notificationText,
                        onValueChange = onUpdateNotificationText
                    )
                }
            }
            
            item {
                SettingsSection(title = "About") {
                    SettingsInfoItem(
                        title = "Version",
                        value = "1.0.0"
                    )
                    SettingsInfoItem(
                        title = "Current Bridge URL",
                        value = state.bridgeUrl.ifBlank { "Auto-discovery enabled" }
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsSection(
    title: String,
    content: @Composable () -> Unit
) {
    AppCard {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = TextPrimary
            )
            content()
        }
    }
}

@Composable
private fun SettingsNumberItem(
    title: String,
    description: String,
    value: Int,
    range: IntRange,
    onValueChange: (Int) -> Unit
) {
    var textValue by remember(value) { mutableStateOf(value.toString()) }
    var isError by remember { mutableStateOf(false) }
    
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = TextPrimary
                )
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
            }
            
            OutlinedTextField(
                value = textValue,
                onValueChange = { newValue ->
                    textValue = newValue
                    val intValue = newValue.toIntOrNull()
                    if (intValue != null && intValue in range) {
                        isError = false
                        onValueChange(intValue)
                    } else {
                        isError = true
                    }
                },
                isError = isError,
                singleLine = true,
                modifier = Modifier.width(100.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Accent,
                    unfocusedBorderColor = Navy600,
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary
                )
            )
        }
        
        if (isError) {
            Text(
                "Value must be between ${range.first} and ${range.last}",
                style = MaterialTheme.typography.bodySmall,
                color = Red
            )
        }
    }
}

@Composable
private fun SettingsSwitchItem(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = TextPrimary
            )
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )
        }
        
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = Accent,
                uncheckedThumbColor = TextMuted,
                uncheckedTrackColor = Navy600
            )
        )
    }
}

@Composable
private fun SettingsInfoItem(
    title: String,
    value: String
) {
    Row(
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = TextPrimary
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun SettingsTextItem(
    title: String,
    description: String,
    value: String,
    onValueChange: (String) -> Unit
) {
    var textValue by remember(value) { mutableStateOf(value) }
    
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = TextPrimary
        )
        Text(
            description,
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary
        )
        
        OutlinedTextField(
            value = textValue,
            onValueChange = { newValue ->
                textValue = newValue
                onValueChange(newValue)
            },
            placeholder = { Text("Enter notification text", color = TextMuted) },
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Accent,
                unfocusedBorderColor = Navy600,
                focusedTextColor = TextPrimary,
                unfocusedTextColor = TextPrimary
            )
        )
    }
}