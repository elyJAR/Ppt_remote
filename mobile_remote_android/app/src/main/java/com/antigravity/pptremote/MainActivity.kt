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
import androidx.activity.compose.BackHandler
import androidx.activity.viewModels
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import kotlinx.coroutines.launch

// ─── Colour palette — iOS Dark Inspired ───────────────────────────────────────
private val iOSBlack     = Color(0xFF000000)
private val iOSGray900   = Color(0xFF1C1C1E)
private val iOSGray800   = Color(0xFF2C2C2E)
private val iOSGray700   = Color(0xFF3A3A3C)
private val iOSAccent     = Color(0xFF0A84FF)
private val iOSAccentDim  = Color(0xFF007AFF).copy(alpha = 0.8f)
private val iOSGreen      = Color(0xFF32D74B)
private val iOSBlue       = Color(0xFF0A84FF)
private val iOSGray       = Color(0xFF8E8E93)
private val iOSAmber      = Color(0xFFFF9F0A)
private val iOSRed        = Color(0xFFFF453A)

private val DarkTextPrimary   = Color(0xFFFFFFFF)
private val DarkTextSecondary = Color(0xFFEBEBF5).copy(alpha = 0.6f)
private val DarkTextMuted     = Color(0xFFEBEBF5).copy(alpha = 0.3f)

// ─── Colour palette — light ──────────────────────────────────────────────────
private val LightTextPrimary   = Color(0xFF000000)
private val LightTextSecondary = Color(0xFF3C3C43).copy(alpha = 0.6f)
private val LightTextMuted     = Color(0xFF3C3C43).copy(alpha = 0.3f)

private val DarkColorScheme = darkColorScheme(
    primary          = iOSAccent,
    onPrimary        = Color.White,
    primaryContainer = iOSAccentDim,
    background       = iOSBlack,
    surface          = iOSGray900,
    surfaceVariant   = iOSGray800,
    onBackground     = DarkTextPrimary,
    onSurface        = DarkTextPrimary,
    onSurfaceVariant = DarkTextSecondary,
    outline          = iOSGray700,
)

private val LightColorScheme = lightColorScheme(
    primary          = iOSAccent,
    onPrimary        = Color.White,
    primaryContainer = Color(0xFFDBEAFE),
    background       = Color(0xFFF2F2F7),
    surface          = Color(0xFFFFFFFF),
    surfaceVariant   = Color(0xFFE5E5EA),
    onBackground     = LightTextPrimary,
    onSurface        = LightTextPrimary,
    onSurfaceVariant = LightTextSecondary,
    outline          = Color(0xFFC7C7CC),
)

// ─── Theme-aware color shorthand ────────
private val androidx.compose.material3.ColorScheme.textPrimary    inline get() = onBackground
private val androidx.compose.material3.ColorScheme.textSecondary  inline get() = onSurfaceVariant
private val androidx.compose.material3.ColorScheme.textMuted      inline get() = onSurfaceVariant.copy(alpha = 0.5f)
private val androidx.compose.material3.ColorScheme.cardBg         inline get() = surface
private val androidx.compose.material3.ColorScheme.cardBgSelected inline get() = surfaceVariant
private val androidx.compose.material3.ColorScheme.screenBg       inline get() = background
private val androidx.compose.material3.ColorScheme.divider        inline get() = outline.copy(alpha = 0.3f)

// iOS Squircle helper - Increased radii for "Maximum iOS" look
private val iOSSquircle = RoundedCornerShape(32.dp)
private val iOSSquircleSmall = RoundedCornerShape(16.dp)

@Composable
private fun PPTLogo(size: androidx.compose.ui.unit.Dp = 44.dp, tint: Color = iOSAccent) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(iOSSquircleSmall)
            .background(tint.copy(alpha = 0.15f)),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Default.SettingsRemote, 
            contentDescription = "PPT Remote Logo", 
            tint = tint,
            modifier = Modifier.size(size * 0.6f)
        )
    }
}

@Composable
private fun iOSIcon(
    imageVector: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.textSecondary,
    backgroundColor: Color = Color.Transparent,
    size: androidx.compose.ui.unit.Dp = 24.dp
) {
    Box(
        modifier = Modifier
            .size(size + 12.dp)
            .clip(RoundedCornerShape(size * 0.4f))
            .background(backgroundColor),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = imageVector,
            contentDescription = contentDescription,
            tint = tint,
            modifier = modifier.size(size)
        )
    }
}

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
                            onSelectBridge = viewModel::selectBridge,
                            onSearchQueryChange = viewModel::updateSearchQuery,
                            onToggleFtp = viewModel::toggleFtp,
                            onOpenFtpOnPc = viewModel::openFtpOnPc,
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
        
        // Request storage permissions for FTP server
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11+
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
        } else {
            // Android 10 and below: request legacy permissions
            val readGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
            val writeGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
            if (!readGranted || !writeGranted) {
                requestPermissions(
                    arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE),
                    1001
                )
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
    onSelectBridge: (BridgeInfo) -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onToggleFtp: () -> Unit,
    onOpenFtpOnPc: () -> Unit,
) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val connected = state.bridgeReachable
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    
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

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                drawerContainerColor = MaterialTheme.colorScheme.screenBg.copy(alpha = 0.85f),
                drawerTonalElevation = 0.dp,
                modifier = Modifier
                    .fillMaxHeight()
                    .width(310.dp)
                    .border(
                        1.dp, 
                        Color.White.copy(alpha = 0.1f), 
                        RoundedCornerShape(topEnd = 32.dp, bottomEnd = 32.dp)
                    ),
                drawerShape = RoundedCornerShape(topEnd = 32.dp, bottomEnd = 32.dp)
            ) {
                // Glassmorphism effect overlay with native blur (Android 12+)
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.screenBg.copy(alpha = 0.6f))
                        .then(
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                Modifier.graphicsLayer {
                                    renderEffect = android.graphics.RenderEffect.createBlurEffect(
                                        50f, 50f, android.graphics.Shader.TileMode.CLAMP
                                    ).asComposeRenderEffect()
                                }
                            } else Modifier
                        )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(28.dp)
                    ) {
                        // Header
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            PPTLogo(size = 48.dp)
                            Text(
                                "PPT Remote",
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.textPrimary
                            )
                        }
                        
                        Divider(color = MaterialTheme.colorScheme.divider)

                        // Connection Status
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(
                                "CONNECTION STATUS",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.textSecondary,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.sp
                            )
                            iOSIcon(
                                imageVector = if (connected) Icons.Default.CheckCircle else Icons.Default.Search,
                                contentDescription = null,
                                tint = if (connected) iOSGreen else MaterialTheme.colorScheme.textSecondary,
                                backgroundColor = if (connected) iOSGreen.copy(alpha = 0.1f) else MaterialTheme.colorScheme.cardBgSelected,
                                size = 20.dp
                            )
                            Text(
                                text = if (connected) "Connected" else state.statusMessage,
                                style = MaterialTheme.typography.titleMedium,
                                color = if (connected) iOSGreen else MaterialTheme.colorScheme.textPrimary,
                                fontWeight = FontWeight.SemiBold
                            )
                        }

                        Divider(color = MaterialTheme.colorScheme.divider)

                        // Bridges List
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(
                                "AVAILABLE PCS",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.textSecondary,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.sp
                            )
                            
                            if (state.discoveredBridges.isEmpty()) {
                                Text(
                                    "No PCs found. Scanning network...",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.textMuted
                                )
                            }

                            LazyColumn(
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                items(state.discoveredBridges) { bridge ->
                                    val isSelected = bridge.id == state.selectedBridgeId
                                    var expanded by remember { mutableStateOf(isSelected) }
                                    val interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                                    
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(iOSSquircleSmall)
                                            .background(if (isSelected) iOSAccent.copy(alpha = 0.1f) else Color.Transparent)
                                            .border(
                                                1.dp,
                                                if (isSelected) iOSAccent.copy(alpha = 0.3f) else Color.Transparent,
                                                iOSSquircleSmall
                                            )
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable(interactionSource = interactionSource, indication = ripple()) {
                                                    onSelectBridge(bridge)
                                                    if (isSelected) expanded = !expanded else expanded = true
                                                }
                                                .padding(12.dp)
                                        ) {
                                            iOSIcon(
                                                imageVector = Icons.Default.Computer, 
                                                contentDescription = null,
                                                tint = if (isSelected) iOSAccent else MaterialTheme.colorScheme.textSecondary,
                                                backgroundColor = if (isSelected) iOSAccent.copy(alpha = 0.15f) else MaterialTheme.colorScheme.cardBgSelected,
                                                size = 20.dp
                                            )
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    bridge.name, 
                                                    style = MaterialTheme.typography.bodyLarge,
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = if (isSelected) iOSAccent else MaterialTheme.colorScheme.textPrimary
                                                )
                                                Text(
                                                    bridge.url.substringAfter("://").substringBefore(":"),
                                                    style = MaterialTheme.typography.labelMedium,
                                                    color = MaterialTheme.colorScheme.textSecondary
                                                )
                                            }
                                            if (isSelected) {
                                                Icon(
                                                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                                    contentDescription = null,
                                                    tint = iOSAccent,
                                                    modifier = Modifier.size(20.dp)
                                                )
                                            }
                                        }

                                        // Dropdown for presentations
                                        AnimatedVisibility(visible = isSelected && expanded) {
                                            Column(
                                                modifier = Modifier.padding(start = 44.dp, end = 12.dp, bottom = 8.dp),
                                                verticalArrangement = Arrangement.spacedBy(4.dp)
                                            ) {
                                                state.presentations.forEach { presentation ->
                                                    val isPresSelected = presentation.id == state.selectedPresentationId
                                                    Surface(
                                                        onClick = { 
                                                            onPresentationSelect(presentation.id)
                                                            scope.launch { drawerState.close() }
                                                        },
                                                        color = if (isPresSelected) iOSAccent.copy(alpha = 0.15f) else Color.Transparent,
                                                        shape = RoundedCornerShape(8.dp),
                                                        modifier = Modifier.fillMaxWidth()
                                                    ) {
                                                        Text(
                                                            presentation.name,
                                                            modifier = Modifier.padding(8.dp),
                                                            style = MaterialTheme.typography.bodySmall,
                                                            maxLines = 1,
                                                            overflow = TextOverflow.Ellipsis,
                                                            color = if (isPresSelected) iOSAccent else MaterialTheme.colorScheme.textPrimary
                                                        )
                                                    }
                                                }
                                                if (state.presentations.isEmpty()) {
                                                    Text(
                                                        "No open presentations",
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = MaterialTheme.colorScheme.textMuted,
                                                        modifier = Modifier.padding(8.dp)
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // Bottom Dock
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            // FTP Quick Toggle in Sidebar
                            Surface(
                                onClick = { onToggleFtp() },
                                color = if (state.isFtpEnabled) iOSGreen.copy(alpha = 0.1f) else Color.Transparent,
                                shape = iOSSquircleSmall,
                                border = BorderStroke(1.dp, if (state.isFtpEnabled) iOSGreen.copy(alpha = 0.3f) else MaterialTheme.colorScheme.divider),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    iOSIcon(
                                        imageVector = Icons.Default.Folder, 
                                        contentDescription = null, 
                                        tint = if (state.isFtpEnabled) iOSGreen else MaterialTheme.colorScheme.textSecondary,
                                        backgroundColor = if (state.isFtpEnabled) iOSGreen.copy(alpha = 0.15f) else MaterialTheme.colorScheme.cardBgSelected,
                                        size = 20.dp
                                    )
                                    Text(
                                        "FTP Server", 
                                        fontWeight = FontWeight.SemiBold,
                                        color = if (state.isFtpEnabled) iOSGreen else MaterialTheme.colorScheme.textPrimary,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Switch(
                                        checked = state.isFtpEnabled || state.isFtpAutoStart,
                                        onCheckedChange = { onToggleFtp() },
                                        enabled = !state.isFtpAutoStart,
                                        scale = 0.8f
                                    )
                                }
                            }

                            NavigationDrawerItem(
                                label = { Text("App Settings", fontWeight = FontWeight.Bold) },
                                selected = false,
                                onClick = { 
                                    onShowSettings()
                                    scope.launch { drawerState.close() }
                                },
                                icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                                colors = NavigationDrawerItemDefaults.colors(
                                    unselectedContainerColor = Color.Transparent,
                                    unselectedTextColor = MaterialTheme.colorScheme.textPrimary,
                                    unselectedIconColor = MaterialTheme.colorScheme.textSecondary
                                ),
                                shape = iOSSquircleSmall,
                                modifier = Modifier.padding(horizontal = 0.dp)
                            )
                        }
                    }
                }
            }
        }
    ) {
        Scaffold(
            topBar = {
                CenterAlignedTopAppBar(
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.SettingsRemote,
                                contentDescription = null,
                                tint = iOSAccent,
                                modifier = Modifier.size(28.dp)
                            )
                            Spacer(Modifier.width(10.dp))
                            Text("PPT Remote", fontWeight = FontWeight.ExtraBold, fontSize = 20.sp)
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Default.Menu, contentDescription = "Menu")
                        }
                    },
                    actions = {
                        IconButton(onClick = onRefresh) {
                            Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                        }
                    },
                    colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                        containerColor = MaterialTheme.colorScheme.screenBg,
                        titleContentColor = MaterialTheme.colorScheme.textPrimary,
                        navigationIconContentColor = MaterialTheme.colorScheme.textPrimary,
                        actionIconContentColor = MaterialTheme.colorScheme.textPrimary
                    )
                )
            },
            containerColor = MaterialTheme.colorScheme.screenBg
        ) { paddingValues ->
            Box(modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
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
                        // ── Warning banners ──────────────────────────────────────────────
                        if (!state.networkWarning.isNullOrBlank()) {
                            item { WarningBanner(message = state.networkWarning) }
                        }
                        if (!state.bridgeNetworkWarning.isNullOrBlank()) {
                            item { WarningBanner(message = "Desktop: ${state.bridgeNetworkWarning}") }
                        }

                        // ── 1. Hero: Active Presentation Thumbnail ───────────────────────────────
                        val activePres = state.presentations.find { it.id == state.selectedPresentationId }
                        item {
                            if (activePres != null) {
                                PresentationHero(
                                    presentation = activePres,
                                    onNotesClick = onShowNotes
                                )
                            }
                        }

                        // ── 2. Middle Section: Current Slide Notes ───────────────────────────
                        item {
                            val currentNotes = state.speakerNotes?.getOrNull((activePres?.currentSlide ?: 1) - 1)
                            AppCard(
                                borderColor = if (currentNotes != null) iOSAccent.copy(alpha = 0.2f) else MaterialTheme.colorScheme.divider
                            ) {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Icon(Icons.Default.Notes, contentDescription = null, tint = iOSAccent, modifier = Modifier.size(20.dp))
                                        Text(
                                            "Speaker Notes",
                                            style = MaterialTheme.typography.labelLarge,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.textSecondary
                                        )
                                    }
                                    
                                    if (currentNotes != null) {
                                        Text(
                                            text = if (currentNotes.isBlank()) "(No notes for this slide)" else currentNotes,
                                            style = MaterialTheme.typography.bodyLarge,
                                            color = if (currentNotes.isBlank()) MaterialTheme.colorScheme.textMuted else MaterialTheme.colorScheme.textPrimary,
                                            lineHeight = 24.sp
                                        )
                                    } else {
                                        Text(
                                            "Notes not available. Pull to refresh or check connection.",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.textMuted
                                        )
                                    }

                                    if (activePres != null) {
                                        TextButton(
                                            onClick = onShowNotes,
                                            contentPadding = PaddingValues(0.dp),
                                            modifier = Modifier.height(32.dp)
                                        ) {
                                            Text("View all slides", style = MaterialTheme.typography.labelMedium, color = iOSAccent)
                                        }
                                    }
                                }
                            }
                        }

                        // ── 3. FTP Server Quick Access (Moved slightly down) ─────────────
                        item {
                            val ftpActive = state.isFtpEnabled || state.isFtpAutoStart
                            AppCard {
                                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
                                                    .size(40.dp)
                                                    .clip(iOSSquircleSmall)
                                                    .background(if (ftpActive) iOSGreen.copy(alpha = 0.15f) else MaterialTheme.colorScheme.cardBgSelected),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.Folder,
                                                    contentDescription = null,
                                                    tint = if (ftpActive) iOSGreen else MaterialTheme.colorScheme.textSecondary,
                                                    modifier = Modifier.size(22.dp)
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
                                                    if (ftpActive) "Running (Port 2121)" else "Offline",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = if (ftpActive) iOSGreen else MaterialTheme.colorScheme.textSecondary
                                                )
                                            }
                                        }
                                        Switch(
                                            checked = ftpActive,
                                            onCheckedChange = { onToggleFtp() },
                                            enabled = !state.isFtpAutoStart
                                        )
                                    }

                                    if (ftpActive) {
                                        Button(
                                            onClick = onOpenFtpOnPc,
                                            enabled = connected,
                                            modifier = Modifier.fillMaxWidth(),
                                            shape = iOSSquircleSmall,
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = if (connected) iOSAccent else MaterialTheme.colorScheme.cardBgSelected,
                                                contentColor = if (connected) Color.White else MaterialTheme.colorScheme.textMuted
                                            ),
                                            contentPadding = PaddingValues(vertical = 12.dp)
                                        ) {
                                            Icon(Icons.Default.Launch, contentDescription = null, modifier = Modifier.size(18.dp))
                                            Spacer(Modifier.width(8.dp))
                                            Text("Open Files on PC", fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                            }
                        }

                        // ── 4. Slide Controls (Bottom) ────────────────────────────────────
                        item {
                            Spacer(Modifier.height(16.dp))
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
                                                contentColor = if (connected) Color.White else MaterialTheme.colorScheme.textMuted
                                            ),
                                            contentPadding = PaddingValues(vertical = 12.dp)
                                        ) {
                                            Icon(
                                                Icons.Default.Devices, 
                                                contentDescription = null, 
                                                modifier = Modifier.size(20.dp),
                                                tint = if (connected) Color.White else MaterialTheme.colorScheme.textMuted
                                            )
                                            Spacer(Modifier.width(10.dp))
                                            Text("Open on PC", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                        }
                                    }
                                }
                            }
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
                                        focusedBorderColor = iOSAccent,
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
                                items(3) { PresentationSkeleton() }
                            } else if (state.isBusy && connected) {
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

                // Global busy indicator fixed at the bottom edge
                AnimatedVisibility(
                    visible = state.isBusy,
                    enter = fadeIn(),
                    exit = fadeOut(),
                    modifier = Modifier.align(Alignment.BottomCenter)
                ) {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth().height(4.dp),
                        color = iOSAccent,
                        trackColor = MaterialTheme.colorScheme.screenBg,
                    )
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
                    label = "Prev",
                    icon = Icons.Default.NavigateBefore,
                    enabled = hasPresentation,
                    modifier = Modifier.weight(1f),
                    onClick = onPrevious,
                )
                SlideNavButton(
                    label = "Next",
                    icon = Icons.Default.NavigateNext,
                    enabled = hasPresentation,
                    modifier = Modifier.weight(1f),
                    onClick = onNext,
                )
                FilledTonalButton(
                    onClick = onStart,
                    enabled = hasPresentation,
                    modifier = Modifier.weight(0.8f),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = iOSGreen.copy(alpha = 0.2f),
                        contentColor = iOSGreen
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
                        containerColor = iOSRed.copy(alpha = 0.2f),
                        contentColor = iOSRed
                    )
                ) {
                    Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Stop", fontWeight = FontWeight.SemiBold)
                }
            }
        } else {
            // Compact layout: original two-row design
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            // Large Prev / Next buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                SlideNavButton(
                    label = "Prev",
                    icon = Icons.Default.ArrowBackIosNew,
                    enabled = hasPresentation,
                    modifier = Modifier.weight(1f),
                    onClick = onPrevious,
                )
                SlideNavButton(
                    label = "Next",
                    icon = Icons.Default.ArrowForwardIos,
                    enabled = hasPresentation,
                    modifier = Modifier.weight(1f),
                    onClick = onNext,
                )
            }

            // Start / Stop row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = onStart,
                    enabled = hasPresentation && !isBusy,
                    modifier = Modifier.weight(1f).height(60.dp),
                    shape = iOSSquircle,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = iOSGreen,
                        contentColor = Color.White
                    )
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Start", fontWeight = FontWeight.Bold)
                }
                
                Button(
                    onClick = onStop,
                    enabled = hasPresentation && !isBusy,
                    modifier = Modifier.weight(1f).height(60.dp),
                    shape = iOSSquircle,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = iOSRed.copy(alpha = 0.15f),
                        contentColor = iOSRed
                    ),
                    border = BorderStroke(1.dp, iOSRed.copy(alpha = 0.3f))
                ) {
                    Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Stop", fontWeight = FontWeight.Bold)
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
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(68.dp),
        shape = iOSSquircle,
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.textPrimary,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            disabledContentColor = MaterialTheme.colorScheme.textMuted,
        ),
        elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.05f))
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(26.dp))
            }
            Text(label, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        }
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

    Surface(
        onClick = onClick,
        color = if (selected) iOSAccent.copy(alpha = 0.1f) else MaterialTheme.colorScheme.cardBg,
        shape = iOSSquircle,
        border = BorderStroke(
            if (selected) 2.dp else 0.5.dp,
            if (selected) iOSAccent else Color.White.copy(alpha = 0.05f)
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(iOSSquircleSmall)
                        .background(if (presentation.inSlideshow) iOSGreen.copy(alpha = 0.2f) else MaterialTheme.colorScheme.cardBgSelected),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (presentation.inSlideshow) Icons.Default.PlayArrow else Icons.Default.Slideshow,
                        contentDescription = null,
                        tint = if (presentation.inSlideshow) iOSGreen else MaterialTheme.colorScheme.textSecondary,
                        modifier = Modifier.size(28.dp)
                    )
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        presentation.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.textPrimary
                    )
                    Text(
                        if (presentation.inSlideshow)
                            "Presenting • Slide ${presentation.currentSlide} of ${presentation.totalSlides}"
                        else
                            "${presentation.totalSlides} slides",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (presentation.inSlideshow) iOSGreen else MaterialTheme.colorScheme.textSecondary
                    )
                }

                if (selected) {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = "Selected",
                        tint = iOSAccent,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            // Preview thumbnail with premium framing
            val thumbBytes = presentation.currentThumbnail
            if (presentation.inSlideshow && thumbBytes != null && thumbBytes.isNotEmpty()) {
                val bitmap = remember(thumbBytes) {
                    BitmapFactory.decodeByteArray(thumbBytes, 0, thumbBytes.size)
                }
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "Preview",
                        contentScale = ContentScale.FillWidth,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(iOSSquircleSmall)
                            .border(0.5.dp, Color.White.copy(alpha = 0.1f), iOSSquircleSmall)
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
                        containerColor = iOSAccent,
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
                .background(iOSAccent),
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
                color = iOSAccent
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
    onUpdateApiKey: (String) -> Unit,
    onUpdateFtpAutoStart: (Boolean) -> Unit,
) {
    BackHandler(onBack = onBack)
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
                "Version 2.1.0 • iOS Style • Antigravity AI",
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
            color = iOSAccent,
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
                checkedTrackColor = iOSAccent,
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
                focusedBorderColor = iOSAccent,
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
    BackHandler(onBack = onBack)
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
                CircularProgressIndicator(color = iOSAccent)
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
                        borderColor = if (isCurrent) iOSAccent else MaterialTheme.colorScheme.divider,
                        borderWidth = if (isCurrent) 2.dp else 1.dp,
                        backgroundColor = if (isCurrent) iOSAccent.copy(alpha = 0.05f) else MaterialTheme.colorScheme.cardBg
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
                                    color = if (isCurrent) iOSAccent else MaterialTheme.colorScheme.textSecondary
                                )
                                if (isCurrent) {
                                    Surface(
                                        color = iOSAccent,
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
        color = iOSAmber.copy(alpha = 0.15f),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth(),
        border = BorderStroke(1.dp, iOSAmber.copy(alpha = 0.4f))
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(Icons.Default.Warning, contentDescription = null, tint = iOSAmber, modifier = Modifier.size(20.dp))
            Text(message, style = MaterialTheme.typography.bodySmall, color = iOSAmber, fontWeight = FontWeight.Medium)
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
        shape = iOSSquircle,
        border = BorderStroke(borderWidth, borderColor),
        shadowElevation = elevation
    ) {
        Box(modifier = Modifier.padding(20.dp)) {
            content()
        }
    }
}

@Composable
private fun PresentationHero(
    presentation: Presentation,
    onNotesClick: () -> Unit
) {
    AppCard(
        backgroundColor = iOSGray900,
        borderColor = iOSAccent.copy(alpha = 0.4f),
        borderWidth = 2.dp
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .clip(iOSSquircleSmall)
                    .background(Color.Black)
            ) {
                if (presentation.thumbnail != null) {
                    val bitmap = remember(presentation.thumbnail) {
                        BitmapFactory.decodeByteArray(presentation.thumbnail, 0, presentation.thumbnail.size)
                    }
                    if (bitmap != null) {
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = "Current Slide",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit
                        )
                    }
                } else {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(Icons.Default.Slideshow, contentDescription = null, tint = iOSGray, modifier = Modifier.size(48.dp))
                        Text("No Preview Available", color = iOSGray, style = MaterialTheme.typography.labelMedium)
                    }
                }
                
                // Overlay badge
                Surface(
                    color = iOSAccent,
                    shape = RoundedCornerShape(topStart = 0.dp, bottomEnd = 16.dp, topEnd = 0.dp, bottomStart = 16.dp),
                    modifier = Modifier.align(Alignment.TopEnd)
                ) {
                    Text(
                        "LIVE",
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    presentation.name,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Slide ${presentation.currentSlide} of ${presentation.slideCount}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = iOSAccent
                    )
                }
            }
        }
    }
}
