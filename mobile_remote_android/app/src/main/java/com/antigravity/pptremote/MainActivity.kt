package com.antigravity.pptremote

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
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
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.google.accompanist.swiperefresh.SwipeRefresh
import com.google.accompanist.swiperefresh.rememberSwipeRefreshState

// ─── Colour palette — dark ───────────────────────────────────────────────────
private val Navy900   = Color(0xFF0D1117)
private val Navy800   = Color(0xFF161B22)
private val Navy700   = Color(0xFF21262D)
private val Navy600   = Color(0xFF30363D)
private val Accent    = Color(0xFF2F81F7)
private val AccentDim = Color(0xFF1F6FEB)
private val Green     = Color(0xFF3FB950)
private val Blue      = Color(0xFF2F81F7)
private val Gray      = Color(0xFF6E7681)
private val Amber     = Color(0xFFD29922)
private val Red       = Color(0xFFF85149)

private val DarkTextPrimary   = Color(0xFFE6EDF3)
private val DarkTextSecondary = Color(0xFF8B949E)
private val DarkTextMuted     = Color(0xFF484F58)

// ─── Colour palette — light ──────────────────────────────────────────────────
private val LightTextPrimary   = Color(0xFF1F2328)
private val LightTextSecondary = Color(0xFF656D76)
private val LightTextMuted     = Color(0xFFAFB8C1)

private val DarkColorScheme = darkColorScheme(
    primary          = Accent,
    onPrimary        = Color.White,
    primaryContainer = AccentDim,
    background       = Navy900,
    surface          = Navy800,
    surfaceVariant   = Navy700,
    onBackground     = DarkTextPrimary,
    onSurface        = DarkTextPrimary,
    onSurfaceVariant = DarkTextSecondary,
    outline          = Navy600,
)

private val LightColorScheme = lightColorScheme(
    primary          = Accent,
    onPrimary        = Color.White,
    primaryContainer = Color(0xFFDBEAFE),
    background       = Color(0xFFF6F8FA),
    surface          = Color(0xFFFFFFFF),
    surfaceVariant   = Color(0xFFF0F2F5),
    onBackground     = LightTextPrimary,
    onSurface        = LightTextPrimary,
    onSurfaceVariant = LightTextSecondary,
    outline          = Color(0xFFD0D7DE),
)

// ─── Theme-aware color shorthand (extension properties on ColorScheme) ────────
private val androidx.compose.material3.ColorScheme.textPrimary    inline get() = onBackground
private val androidx.compose.material3.ColorScheme.textSecondary  inline get() = onSurfaceVariant
private val androidx.compose.material3.ColorScheme.textMuted      inline get() = outline.copy(alpha = 0.7f)
private val androidx.compose.material3.ColorScheme.cardBg         inline get() = surface
private val androidx.compose.material3.ColorScheme.cardBgSelected inline get() = surfaceVariant
private val androidx.compose.material3.ColorScheme.screenBg       inline get() = background
private val androidx.compose.material3.ColorScheme.divider        inline get() = outline

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
            val state by viewModel.state.collectAsState()
            val colorScheme = if (state.isDarkTheme) DarkColorScheme else LightColorScheme

            MaterialTheme(colorScheme = colorScheme) {
                
                when {
                    state.showOnboarding -> {
                        OnboardingScreen(onComplete = viewModel::completeOnboarding)
                    }
                    state.showSettings -> {
                        SettingsScreen(
                            state = state,
                            onBack = viewModel::hideSettings,
                            onUpdateBridgePort = viewModel::updateBridgePort,
                            onUpdatePollingInterval = viewModel::updatePollingInterval,
                            onUpdateTheme = viewModel::updateTheme,
                            onUpdateNotificationText = viewModel::updateNotificationText,
                            onUpdateApiKey = viewModel::updateApiKey,
                            onUpdateFtpAutoStart = viewModel::updateFtpAutoStart,
                        )
                    }
                    state.showNotes -> {
                        NotesScreen(
                            state = state,
                            onBack = viewModel::hideNotes
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
                            onShowSettings = viewModel::showSettings,
                            onShowNotes = viewModel::showNotes,
                            onAddBridge = viewModel::addBridge,
                            onRemoveBridge = viewModel::removeBridge,
                            onSelectBridge = viewModel::selectBridge,
                            onSearchQueryChange = viewModel::updateSearchQuery,
                            onToggleFtp = viewModel::toggleFtp,
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
        
        // Request Manage External Storage for FTP on Android 11+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                try {
                    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                    intent.data = Uri.parse("package:$packageName")
                    startActivity(intent)
                } catch (e: Exception) {
                    val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                    startActivity(intent)
                }
            }
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
    onShowNotes: () -> Unit,
    onAddBridge: (String, String) -> Unit,
    onRemoveBridge: (Int) -> Unit,
    onSelectBridge: (Int) -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onToggleFtp: () -> Unit,
) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val connected = state.statusMessage == "Connected"
    
    // Determine if we're on a tablet or in landscape
    val isTablet = configuration.screenWidthDp >= 600
    val isLandscape = configuration.screenWidthDp > configuration.screenHeightDp
    val useWideLayout = isTablet || isLandscape

    val filteredPresentations = remember(state.presentations, state.searchQuery) {
        val base = if (state.searchQuery.isBlank()) state.presentations
        else state.presentations.filter { 
            it.name.contains(state.searchQuery, ignoreCase = true) || 
            it.path.contains(state.searchQuery, ignoreCase = true)
        }
        // Move in-slideshow presentations to the top
        base.sortedByDescending { it.inSlideshow }
    }

    // Helper function for haptic feedback in gestures — fires only once per swipe
    var swipeHapticFired = false
    fun performGestureHapticFeedback() {
        if (swipeHapticFired) return
        swipeHapticFired = true
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
        } catch (_: Exception) {}
    }

    Box(modifier = Modifier
        .fillMaxSize()
        .background(MaterialTheme.colorScheme.screenBg)
        .statusBarsPadding()
    ) {
        SwipeRefresh(
            state = rememberSwipeRefreshState(state.isRefreshing),
            onRefresh = onRefresh,
            modifier = Modifier.fillMaxSize()
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectHorizontalDragGestures(
                            onDragStart = { swipeHapticFired = false },
                            onDragEnd = { swipeHapticFired = false }
                        ) { _, dragAmount ->
                            val threshold = 80f
                            if (dragAmount > threshold) {
                                performGestureHapticFeedback()
                                onPrevious()
                            } else if (dragAmount < -threshold) {
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
                    bridges = state.bridges,
                    activeBridgeIndex = state.activeBridgeIndex,
                    onBridgeUrlChange = onBridgeUrlChange,
                    onAddBridge = onAddBridge,
                    onRemoveBridge = onRemoveBridge,
                    onSelectBridge = onSelectBridge,
                )
            }

            // ── FTP Server card ──────────────────────────────────────────────
            item {
                AppCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (state.isFtpEnabled) Green.copy(alpha = 0.15f) else MaterialTheme.colorScheme.cardBgSelected),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Folder,
                                    contentDescription = null,
                                    tint = if (state.isFtpEnabled) Green else MaterialTheme.colorScheme.textSecondary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Column {
                                Text(
                                    "FTP Server",
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.textPrimary
                                )
                                Text(
                                    if (state.isFtpEnabled) "Running on port 2121" else "Offline",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (state.isFtpEnabled) Green else MaterialTheme.colorScheme.textSecondary
                                )
                            }
                        }
                        Switch(
                            checked = state.isFtpEnabled,
                            onCheckedChange = { onToggleFtp() },
                            enabled = !state.isFtpAutoStart,
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = Green,
                                disabledCheckedThumbColor = Color.White.copy(alpha = 0.6f),
                                disabledCheckedTrackColor = Green.copy(alpha = 0.5f)
                            )
                        )
                    }
                }
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

            // ── Presentations header & Search ───────────────────────────────────────
            if (state.presentations.size > 5 || state.searchQuery.isNotEmpty()) {
                item {
                    OutlinedTextField(
                        value = state.searchQuery,
                        onValueChange = onSearchQueryChange,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        placeholder = { Text("Search presentations...") },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                        trailingIcon = if (state.searchQuery.isNotEmpty()) {
                            {
                                IconButton(onClick = { onSearchQueryChange("") }) {
                                    Icon(Icons.Default.Close, contentDescription = "Clear")
                                }
                            }
                        } else null,
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = MaterialTheme.colorScheme.cardBg,
                            unfocusedContainerColor = MaterialTheme.colorScheme.cardBg,
                            focusedBorderColor = Accent,
                            unfocusedBorderColor = MaterialTheme.colorScheme.divider,
                        )
                    )
                }
            } else {
                item {
                    Text(
                        "Presentations",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.textSecondary,
                        modifier = Modifier.padding(top = 4.dp, bottom = 2.dp)
                    )
                }
            }

            // ── Empty state / Skeleton ──────────────────────────────────────────
            if (filteredPresentations.isEmpty()) {
                if (state.isBusy && !connected) {
                    // Show skeleton when searching for bridge or connecting
                    items(3) { PresentationSkeleton() }
                } else if (state.isBusy && connected) {
                    // Show skeleton when connected but fetching presentations
                    items(2) { PresentationSkeleton() }
                } else {
                    item { 
                        EmptyStateCard(
                            connected = connected,
                            isFiltered = state.searchQuery.isNotEmpty()
                        ) 
                    }
                }
            } else {
                items(filteredPresentations, key = { it.id }) { presentation ->
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
                    color = MaterialTheme.colorScheme.textMuted,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp)
                )
            }
        }
    }

    // Floating Settings Button
    FloatingActionButton(
        onClick = onShowSettings,
        containerColor = MaterialTheme.colorScheme.primary,
        contentColor = Color.White,
        modifier = Modifier
            .align(Alignment.BottomEnd)
            .padding(16.dp)
            .padding(bottom = 12.dp) // extra padding to avoid linear progress indicator
    ) {
        Icon(Icons.Default.Settings, contentDescription = "Settings")
    }

    // Global busy indicator fixed at the bottom edge
    AnimatedVisibility(
        visible = state.isBusy,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = Modifier.align(Alignment.BottomCenter)
    ) {
        LinearProgressIndicator(
            modifier = Modifier.fillMaxWidth().height(4.dp),
            color = Accent,
            trackColor = MaterialTheme.colorScheme.screenBg,
        )
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
    bridges: List<SavedBridge>,
    activeBridgeIndex: Int,
    onBridgeUrlChange: (String) -> Unit,
    onAddBridge: (String, String) -> Unit,
    onRemoveBridge: (Int) -> Unit,
    onSelectBridge: (Int) -> Unit,
) {
    var showHistory by remember { mutableStateOf(false) }
    var showAddBridge by remember { mutableStateOf(false) }
    var newBridgeName by remember { mutableStateOf("") }
    var newBridgeUrl by remember { mutableStateOf("") }

    AppCard {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {

            // Status row
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = if (connected) Icons.Default.CheckCircle else Icons.Default.Search,
                    contentDescription = null,
                    tint = if (connected) Green else MaterialTheme.colorScheme.textSecondary,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = statusMessage,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (connected) Green else MaterialTheme.colorScheme.textSecondary,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f)
                )
                
                IconButton(
                    onClick = { showHistory = !showHistory },
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        if (showHistory) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.textSecondary
                    )
                }
            }

            if (showHistory) {
                // Saved bridges list (shown when more than 0 saved)
                if (bridges.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            "Saved Bridges",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.textSecondary,
                            fontWeight = FontWeight.Medium
                        )
                        bridges.forEachIndexed { index, bridge ->
                            val isActive = index == activeBridgeIndex
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(
                                        if (isActive) Accent.copy(alpha = 0.12f)
                                        else MaterialTheme.colorScheme.cardBgSelected
                                    )
                                    .border(
                                        1.dp,
                                        if (isActive) Accent.copy(alpha = 0.4f)
                                        else MaterialTheme.colorScheme.divider,
                                        RoundedCornerShape(6.dp)
                                    )
                                    .clickable { onSelectBridge(index) }
                                    .padding(horizontal = 10.dp, vertical = 8.dp)
                            ) {
                                Icon(
                                    imageVector = if (isActive) Icons.Default.CheckCircle else Icons.Default.Computer,
                                    contentDescription = null,
                                    tint = if (isActive) Accent else MaterialTheme.colorScheme.textSecondary,
                                    modifier = Modifier.size(16.dp)
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        bridge.name,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = if (isActive) Accent else MaterialTheme.colorScheme.textPrimary
                                    )
                                    Text(
                                        bridge.url,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.textSecondary
                                    )
                                }
                                IconButton(
                                    onClick = { onRemoveBridge(index) },
                                    modifier = Modifier.size(24.dp)
                                ) {
                                    Icon(Icons.Default.Close, contentDescription = "Remove", modifier = Modifier.size(14.dp), tint = Red.copy(alpha = 0.6f))
                                }
                            }
                        }
                    }
                }

                if (showAddBridge) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                        OutlinedTextField(
                            value = newBridgeName,
                            onValueChange = { newBridgeName = it },
                            label = { Text("Bridge Name (e.g. Office PC)", color = MaterialTheme.colorScheme.textSecondary) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Accent,
                                unfocusedBorderColor = MaterialTheme.colorScheme.divider,
                                focusedTextColor = MaterialTheme.colorScheme.textPrimary,
                                unfocusedTextColor = MaterialTheme.colorScheme.textPrimary
                            )
                        )
                        OutlinedTextField(
                            value = newBridgeUrl,
                            onValueChange = { newBridgeUrl = it },
                            label = { Text("URL (e.g. 192.168.1.5)", color = MaterialTheme.colorScheme.textSecondary) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Accent,
                                unfocusedBorderColor = MaterialTheme.colorScheme.divider,
                                focusedTextColor = MaterialTheme.colorScheme.textPrimary,
                                unfocusedTextColor = MaterialTheme.colorScheme.textPrimary
                            )
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilledTonalButton(
                                onClick = {
                                    if (newBridgeUrl.isNotBlank()) {
                                        onAddBridge(newBridgeName, newBridgeUrl)
                                        newBridgeName = ""
                                        newBridgeUrl = ""
                                        showAddBridge = false
                                    }
                                },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.filledTonalButtonColors(containerColor = Accent.copy(alpha = 0.15f), contentColor = Accent)
                            ) { Text("Save", fontWeight = FontWeight.SemiBold) }
                            FilledTonalButton(
                                onClick = { showAddBridge = false; newBridgeName = ""; newBridgeUrl = "" },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.filledTonalButtonColors(containerColor = MaterialTheme.colorScheme.cardBgSelected, contentColor = MaterialTheme.colorScheme.textSecondary)
                            ) { Text("Cancel") }
                        }
                    }
                } else {
                    TextButton(
                        onClick = { showAddBridge = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Add Bridge", style = MaterialTheme.typography.bodySmall)
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
                    enabled = hasPresentation,
                    modifier = Modifier.weight(1f),
                    onClick = onPrevious,
                )
                SlideNavButton(
                    label = "Next  ▶",
                    enabled = hasPresentation,
                    modifier = Modifier.weight(1f),
                    onClick = onNext,
                )
                FilledTonalButton(
                    onClick = onStart,
                    enabled = hasPresentation,
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
                    enabled = hasPresentation,
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
                    enabled = hasPresentation,
                    modifier = Modifier.weight(1f),
                    onClick = onPrevious,
                )
                SlideNavButton(
                    label = "Next  ▶",
                    enabled = hasPresentation,
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
                    enabled = hasPresentation,
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
                    enabled = hasPresentation,
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
            disabledContainerColor = MaterialTheme.colorScheme.cardBgSelected,
            disabledContentColor = MaterialTheme.colorScheme.textMuted,
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
        targetValue = if (presentation.totalSlides > 0)
            (presentation.currentSlide?.toFloat() ?: 0f) / presentation.totalSlides
        else 0f,
        label = "progress"
    )

    AppCard(
        onClick = onClick,
        backgroundColor = if (selected) MaterialTheme.colorScheme.cardBgSelected else MaterialTheme.colorScheme.cardBg,
        borderColor = if (selected) Accent else MaterialTheme.colorScheme.divider,
        elevation = if (selected) 4.dp else 1.dp
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (presentation.inSlideshow) Green.copy(alpha = 0.15f) else MaterialTheme.colorScheme.cardBgSelected),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (presentation.inSlideshow) Icons.Default.PlayArrow else Icons.Default.Article,
                        contentDescription = null,
                        tint = if (presentation.inSlideshow) Green else MaterialTheme.colorScheme.textSecondary,
                        modifier = Modifier.size(20.dp)
                    )
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        presentation.name,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.textPrimary
                    )
                    Text(
                        if (presentation.inSlideshow)
                            "Currently Presenting • Slide ${presentation.currentSlide} of ${presentation.totalSlides}"
                        else
                            "${presentation.totalSlides} slides • Ready",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (presentation.inSlideshow) Green else MaterialTheme.colorScheme.textSecondary
                    )
                }

                if (selected) {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = "Selected",
                        tint = Accent,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            // Mini progress bar for each presentation
            if (presentation.totalSlides > 0) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .clip(CircleShape),
                    color = if (presentation.inSlideshow) Green else Accent,
                    trackColor = MaterialTheme.colorScheme.divider.copy(alpha = 0.5f),
                )
            }

            // Preview thumbnail
            val thumbBytes = presentation.currentThumbnail
            if (presentation.inSlideshow && thumbBytes != null && thumbBytes.isNotEmpty()) {
                val bitmap = remember(thumbBytes) {
                    BitmapFactory.decodeByteArray(thumbBytes, 0, thumbBytes.size)
                }
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "Slide ${presentation.currentSlide} preview",
                        contentScale = ContentScale.FillWidth,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .border(1.dp, MaterialTheme.colorScheme.divider, RoundedCornerShape(6.dp))
                    )
                }
            }
        }
    }
}

// ─── Empty state ──────────────────────────────────────────────────────────────

@Composable
private fun EmptyStateCard(connected: Boolean, isFiltered: Boolean = false) {
    AppCard {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(if (isFiltered) "🔍" else "📂", fontSize = 40.sp)
            Text(
                when {
                    isFiltered -> "No matches found"
                    connected -> "No open presentations"
                    else -> "Not connected"
                },
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.textPrimary,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                when {
                    isFiltered -> "Try searching for a different name or path"
                    connected -> "Open a .pptx file in PowerPoint on your PC"
                    else -> "Make sure the desktop bridge is running on your PC"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.textSecondary,
                textAlign = TextAlign.Center,
            )
        }
    }
}

// ─── Shimmer / Skeleton ──────────────────────────────────────────────────────

@Composable
fun ShimmerBrush(): Brush {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val translateAnim by transition.animateFloat(
        initialValue = -1000f,
        targetValue = 2000f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "shimmer"
    )

    return Brush.linearGradient(
        colors = listOf(
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f),
            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f),
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f),
        ),
        start = Offset(x = translateAnim, y = translateAnim),
        end = Offset(x = translateAnim + 800f, y = translateAnim + 800f)
    )
}

@Composable
private fun PresentationSkeleton() {
    val shimmer = ShimmerBrush()
    AppCard {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Icon placeholder
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(shimmer)
            )
            
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Title placeholder
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.6f)
                        .height(16.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(shimmer)
                )
                // Subtitle placeholder
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.4f)
                        .height(12.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(shimmer)
                )
            }
        }
    }
}

// ─── Onboarding screen ───────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OnboardingScreen(onComplete: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Welcome to PPT Remote") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.screenBg,
                    titleContentColor = MaterialTheme.colorScheme.textPrimary
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.screenBg
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                AppCard {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            "Setup Instructions",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.textPrimary
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
                            color = MaterialTheme.colorScheme.textPrimary
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
        
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.textPrimary
            )
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.textSecondary
            )
        }
    }
}

@Composable
private fun ControlItem(key: String, action: String) {
    Row(
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier.fillMaxWidth()
    ) {
        Surface(
            color = MaterialTheme.colorScheme.cardBgSelected,
            shape = RoundedCornerShape(4.dp)
        ) {
            Text(
                key,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = Accent
            )
        }
        Text(
            action,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.textSecondary
        )
    }
}

// ─── Settings screen ─────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen(
    state: RemoteState,
    onBack: () -> Unit,
    onUpdateBridgePort: (Int) -> Unit,
    onUpdatePollingInterval: (Int) -> Unit,
    onUpdateTheme: (Boolean) -> Unit,
    onUpdateNotificationText: (String) -> Unit,
    onUpdateApiKey: (String) -> Unit,
    onUpdateFtpAutoStart: (Boolean) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.screenBg,
                    titleContentColor = MaterialTheme.colorScheme.textPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.textPrimary
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.screenBg
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            SettingsSection(title = "Appearance") {
                SettingsSwitchRow(
                    title = "Dark Theme",
                    subtitle = "Use high-contrast dark mode",
                    checked = state.isDarkTheme,
                    onCheckedChange = onUpdateTheme
                )
            }

            SettingsSection(title = "Connection") {
                SettingsInputRow(
                    title = "Bridge Port",
                    subtitle = "Default is 8787",
                    value = state.bridgePort.toString(),
                    onValueChange = { val p = it.toIntOrNull(); if (p != null) onUpdateBridgePort(p) }
                )
                SettingsInputRow(
                    title = "Polling Interval",
                    subtitle = "Frequency to check for PC slide changes",
                    value = state.pollingIntervalSeconds.toString(),
                    onValueChange = { val i = it.toIntOrNull(); if (i != null) onUpdatePollingInterval(i) }
                )
                SettingsInputRow(
                    title = "Bridge API Key",
                    subtitle = "Required if PPT_API_KEY is set on PC",
                    value = state.apiKey ?: "",
                    onValueChange = onUpdateApiKey,
                    isPassword = true
                )
                SettingsSwitchRow(
                    title = "Auto-start FTP",
                    subtitle = "Start FTP server when app opens",
                    checked = state.isFtpAutoStart,
                    onCheckedChange = onUpdateFtpAutoStart
                )
            }

            SettingsSection(title = "Notification") {
                SettingsInputRow(
                    title = "Custom Text",
                    subtitle = "Message shown when app is in background",
                    value = state.notificationText,
                    onValueChange = onUpdateNotificationText
                )
            }

            Spacer(Modifier.height(40.dp))
            Text(
                "Version 1.7.0 • Antigravity AI",
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.textMuted
            )
        }
    }
}

@Composable
private fun SettingsSection(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            title.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            color = Accent,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 4.dp)
        )
        AppCard {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                content()
            }
        }
    }
}

@Composable
private fun SettingsSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.textPrimary)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.textSecondary)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = Accent,
                uncheckedThumbColor = MaterialTheme.colorScheme.textSecondary,
                uncheckedTrackColor = MaterialTheme.colorScheme.cardBgSelected
            )
        )
    }
}

@Composable
private fun SettingsInputRow(
    title: String,
    subtitle: String,
    value: String,
    onValueChange: (String) -> Unit,
    isPassword: Boolean = false
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.textPrimary)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.textSecondary)
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            singleLine = true,
            visualTransformation = if (isPassword) androidx.compose.ui.text.input.PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
            shape = RoundedCornerShape(8.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Accent,
                unfocusedBorderColor = MaterialTheme.colorScheme.divider,
                focusedTextColor = MaterialTheme.colorScheme.textPrimary,
                unfocusedTextColor = MaterialTheme.colorScheme.textPrimary
            )
        )
    }
}

// ─── Speaker notes screen ───────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NotesScreen(
    state: RemoteState,
    onBack: () -> Unit
) {
    val pres = state.presentations.find { it.id == state.selectedPresentationId }
    val notes = state.speakerNotes

    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    Column {
                        Text("Speaker Notes", style = MaterialTheme.typography.titleMedium)
                        Text(pres?.name ?: "", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.textSecondary)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.screenBg,
                    titleContentColor = MaterialTheme.colorScheme.textPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.textPrimary
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.screenBg
    ) { padding ->
        if (notes == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Accent)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                items(count = notes.size) { index ->
                    val isCurrent = pres?.currentSlide == (index + 1)
                    AppCard(
                        borderColor = if (isCurrent) Accent else MaterialTheme.colorScheme.divider,
                        borderWidth = if (isCurrent) 2.dp else 1.dp,
                        backgroundColor = if (isCurrent) Accent.copy(alpha = 0.05f) else MaterialTheme.colorScheme.cardBg
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "Slide ${index + 1}",
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isCurrent) Accent else MaterialTheme.colorScheme.textSecondary
                                )
                                if (isCurrent) {
                                    Surface(
                                        color = Accent,
                                        shape = CircleShape
                                    ) {
                                        Text(
                                            "ACTIVE",
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = Color.White,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }
                            val rawNote = notes[index]
                            Text(
                                text = if (rawNote.isBlank()) "(No notes for this slide)" else rawNote,
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (rawNote.isBlank()) MaterialTheme.colorScheme.textMuted else MaterialTheme.colorScheme.textPrimary,
                                lineHeight = 22.sp
                            )
                        }
                    }
                }
            }
        }
    }
}

// ─── Shared UI components ────────────────────────────────────────────────────

@Composable
private fun WarningBanner(message: String) {
    Surface(
        color = Amber.copy(alpha = 0.15f),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth(),
        border = BorderStroke(1.dp, Amber.copy(alpha = 0.4f))
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(Icons.Default.Warning, contentDescription = null, tint = Amber, modifier = Modifier.size(20.dp))
            Text(message, style = MaterialTheme.typography.bodySmall, color = Amber, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun AppCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    backgroundColor: Color = MaterialTheme.colorScheme.cardBg,
    borderColor: Color = MaterialTheme.colorScheme.divider,
    borderWidth: androidx.compose.ui.unit.Dp = 1.dp,
    elevation: androidx.compose.ui.unit.Dp = 1.dp,
    content: @Composable () -> Unit
) {
    val clickableModifier = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier
    
    Surface(
        modifier = modifier.then(clickableModifier).fillMaxWidth(),
        color = backgroundColor,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(borderWidth, borderColor),
        shadowElevation = elevation
    ) {
        Box(modifier = Modifier.padding(16.dp)) {
            content()
        }
    }
}
