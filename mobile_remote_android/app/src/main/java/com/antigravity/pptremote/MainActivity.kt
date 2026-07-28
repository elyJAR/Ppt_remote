package com.antigravity.pptremote

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.util.LruCache
import android.util.Size
import android.view.KeyEvent
import android.webkit.MimeTypeMap
import android.widget.Toast
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.BackHandler
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.graphics.createBitmap
import androidx.core.net.toUri
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.log
import kotlin.math.pow

// ---  Colour palette — iOS Dark Inspired ───────────────────────────────────────
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

// ---  Colour palette — light ──────────────────────────────────────────────────
private val LightTextPrimary   = Color(0xFF000000)
private val LightTextSecondary = Color(0xFF3C3C43).copy(alpha = 0.85f)

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

// ---  Theme-aware color shorthand ────────
private val ColorScheme.textPrimary    inline get() = onBackground
private val ColorScheme.textSecondary  inline get() = onSurfaceVariant
private val ColorScheme.textMuted      inline get() = onSurfaceVariant.copy(alpha = 0.5f)
private val ColorScheme.cardBg         inline get() = surface
private val ColorScheme.cardBgSelected inline get() = surfaceVariant
private val ColorScheme.screenBg       inline get() = background
private val ColorScheme.divider        inline get() = outline.copy(alpha = 0.3f)

private fun Color.isLight() = (0.299 * red + 0.587 * green + 0.114 * blue) > 0.5

// iOS Squircle helper - Increased radii for "Maximum iOS" look
private val iOSSquircle = RoundedCornerShape(32.dp)
private val iOSSquircleSmall = RoundedCornerShape(16.dp)

@Composable
private fun PPTLogo(size: Dp = 44.dp, tint: Color = iOSAccent) {
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
private fun IOSIcon(
    imageVector: ImageVector,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.textSecondary,
    backgroundColor: Color = Color.Transparent,
    size: Dp = 24.dp
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
    ) { granted ->
        if (granted) {
            RemoteControlService.start(this)
        } else {
            Toast.makeText(this, "Notification permission required for upload approval alerts.", Toast.LENGTH_LONG).show()
        }
    }
    private val openDocumentTreeLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        try {
            if (uri != null) {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
        } catch (_: Exception) {}
        try { // Ask ViewModel to refresh volumes and re-check access
            val vm = viewModel
            vm.refreshStorageVolumes()
            vm.checkStorageAccess()
            vm.refreshFiles()
        } catch (_: Exception) {}
    }

    private val selectWebServerFolderLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            try {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (_: Exception) {}
            val path = SafStorageHelper.convertUriToPath(uri)
            if (path != null) {
                viewModel.setWebServerSharedFolder(path)
            }
        }
    }

    private fun requestRestrictedFolderAccess(path: String) {
        try {
            val volumeRoot = SafStorageHelper.getVolumeRoot(path) ?: Environment.getExternalStorageDirectory().absolutePath
            val rootId = if (volumeRoot == Environment.getExternalStorageDirectory().absolutePath) {
                "primary"
            } else {
                File(volumeRoot).name
            }
            val isObb = path.replace('\\', '/').contains("/Android/obb", ignoreCase = true)
            val volRootNormalized = volumeRoot.replace('\\', '/').trimEnd('/')
            val pathNormalized = path.replace('\\', '/').trimEnd('/')
            val relPathFromRoot = if (pathNormalized.startsWith(volRootNormalized)) {
                pathNormalized.substring(volRootNormalized.length).trimStart('/')
            } else {
                if (isObb) "Android/obb" else "Android/data"
            }
            val escapedRelPath = relPathFromRoot.replace("/", "%2F")
            val documentUri = "content://com.android.externalstorage.documents/tree/$rootId%3A$escapedRelPath".toUri()
            openDocumentTreeLauncher.launch(documentUri)
        } catch (_: Exception) {
            openDocumentTreeLauncher.launch(null)
        }
    }

    private fun launchSystemFilesApp() {
        var launched = false
        // Try Action 1: android.provider.action.BROWSE with primary storage URI
        try {
            val intent = Intent("android.provider.action.BROWSE").apply {
                val uri = "content://com.android.externalstorage.documents/document/primary:".toUri()
                setDataAndType(uri, "vnd.android.document/directory")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
            launched = true
        } catch (_: Exception) {}

        if (!launched) {
            // Try Action 2: try com.google.android.documentsui directly
            try {
                val intent = Intent(Intent.ACTION_MAIN).apply {
                    setClassName("com.google.android.documentsui", "com.android.documentsui.files.FilesActivity")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(intent)
                launched = true
            } catch (_: Exception) {}
        }

        if (!launched) {
            // Try Action 3: try com.android.documentsui directly
            try {
                val intent = Intent(Intent.ACTION_MAIN).apply {
                    setClassName("com.android.documentsui", "com.android.documentsui.files.FilesActivity")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(intent)
                launched = true
            } catch (_: Exception) {}
        }

        if (!launched) {
            // Try Action 4: Fallback to ACTION_VIEW on general storage
            try {
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    val uri = "content://com.android.externalstorage.documents/root/primary".toUri()
                    setDataAndType(uri, "vnd.android.document/directory")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(intent)
                launched = true
            } catch (_: Exception) {}
        }

        if (!launched) {
            Toast.makeText(this, "Could not open system Files app", Toast.LENGTH_SHORT).show()
        }
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        SafStorageHelper.appPackageName = packageName
        // Install splash screen before calling super.onCreate()
        installSplashScreen()
        
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestBatteryOptimizationExemption()
        ensureNotificationPermissionAndStartService()
        
        setContent {
            val state by viewModel.state.collectAsState()
            val colorScheme = if (state.isDarkTheme) DarkColorScheme else LightColorScheme

            val activePres = remember(state.presentations, state.selectedPresentationId) {
                state.presentations.find { it.id == state.selectedPresentationId }
            }
            var previewSlideIndex by remember(activePres?.id, activePres?.inSlideshow) {
                mutableStateOf(activePres?.currentSlide ?: 1)
            }

            MaterialTheme(colorScheme = colorScheme) {
                val view = LocalView.current
                if (!view.isInEditMode) {
                    SideEffect {
                        val window = (view.context as ComponentActivity).window
                        WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !state.isDarkTheme
                        WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = !state.isDarkTheme
                    }
                }
                
                Box(modifier = Modifier.fillMaxSize()) {
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
                                onUpdateWebServerPort = viewModel::updateWebServerPort,
                                onUpdateFtpUsername = viewModel::updateFtpUsername,
                                onUpdateFtpPassword = viewModel::updateFtpPassword,
                                onUpdateHttpsEnabled = viewModel::updateHttpsEnabled,
                            )
                        }
                        state.showNotes -> {
                            NotesScreen(
                                state = state,
                                onBack = viewModel::hideNotes,
                                onGetThumbnail = viewModel::getCachedThumbnail,
                                onSelectSlide = { slideIndex -> 
                                    if (activePres?.inSlideshow == true) {
                                        viewModel.jumpToSlide(slideIndex)
                                    } else {
                                        previewSlideIndex = slideIndex
                                    }
                                    viewModel.hideNotes()
                                }
                            )
                        }
                        else -> {
                            if (state.showFiles) {
                                FilesScreen(
                                    state = state,
                                    onClose = viewModel::hideFiles,
                                    onSelectFilesRoot = viewModel::selectFilesRoot,
                                    onNavigateFilesTo = viewModel::navigateToFilesFolder,
                                    onNavigateFilesUp = viewModel::navigateUpFilesFolder,
                                    onRefreshFiles = viewModel::refreshFiles,
                                    onOpenCurrentFilesFolderOnPc = viewModel::openCurrentFilesFolderOnPc,
                                    onRequestStorageAccess = { requestStorageAccess() },
                                    onRequestRestrictedFolderAccess = { requestRestrictedFolderAccess(it) },
                                    onOpenFile = { path -> openFile(path) },
                                    onFilesSearchQueryChange = viewModel::updateFilesSearchQuery,
                                    onJumpToFileLocation = viewModel::jumpToFileLocation,
                                    onFilesSortChange = viewModel::setFilesSort,
                                    onLaunchSystemFilesApp = { launchSystemFilesApp() },
                                    onToggleWebServer = viewModel::toggleWebServer,
                                    onUpdateWebServerPin = viewModel::updateWebServerPin,
                                    onSelectSharedFolder = { selectWebServerFolderLauncher.launch(null) },
                                    onResetSharedFolder = { viewModel.setWebServerSharedFolder(null) },
                                    onRefreshWebServerUrl = viewModel::refreshWebServerUrl
                                )
                            } else {
                                RemoteScreen(
                                    state = state,
                                    previewSlideIndex = previewSlideIndex,
                                    onPreviewSlideIndexChange = { previewSlideIndex = it },
                                    onPresentationSelect = viewModel::selectPresentation,
                                    onStartSlideshow = { viewModel.startSelectedSlideshow(it) },
                                    onStopSlideshow = viewModel::stopSelectedSlideshow,
                                    onNext = viewModel::nextSlide,
                                    onPrevious = viewModel::previousSlide,
                                    onRefresh = viewModel::refreshPresentations,
                                    onShowSettings = viewModel::showSettings,
                                    onShowNotes = viewModel::showNotes,
                                    onSelectBridge = viewModel::selectBridge,
                                    onShowFiles = viewModel::showFiles,
                                    onGetThumbnail = viewModel::getCachedThumbnail
                                )
                            }
                        }
                    }

                    // Global Upload Progress Card
                    state.activeUploadName?.let { uploadName ->
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.Black.copy(alpha = 0.5f))
                                .pointerInput(Unit) {},
                            contentAlignment = Alignment.Center
                        ) {
                            Card(
                                shape = iOSSquircleSmall,
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.15f)),
                                modifier = Modifier
                                    .width(320.dp)
                                    .padding(16.dp),
                                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
                            ) {
                                Column(
                                    modifier = Modifier.padding(20.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.ArrowUpward,
                                        contentDescription = "Uploading",
                                        tint = iOSAccent,
                                        modifier = Modifier.size(40.dp)
                                    )
                                    Spacer(modifier = Modifier.height(12.dp))
                                    Text(
                                        text = "Uploading File",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 16.sp,
                                        color = MaterialTheme.colorScheme.textPrimary
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = uploadName,
                                        fontSize = 13.sp,
                                        color = MaterialTheme.colorScheme.textSecondary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        textAlign = TextAlign.Center
                                    )
                                    Spacer(modifier = Modifier.height(16.dp))
                                    
                                    LinearProgressIndicator(
                                        progress = state.activeUploadProgress,
                                        color = iOSAccent,
                                        trackColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.12f),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(6.dp)
                                            .clip(CircleShape)
                                    )
                                    
                                    Spacer(modifier = Modifier.height(8.dp))
                                    
                                    val percent = (state.activeUploadProgress * 100).toInt()
                                    val totalMb = state.activeUploadTotal.toFloat() / (1024 * 1024)
                                    val currentMb = state.activeUploadBytes.toFloat() / (1024 * 1024)
                                    Text(
                                        text = "$percent% (${String.format("%.1f", currentMb)} / ${String.format("%.1f", totalMb)} MB)",
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.textSecondary
                                    )
                                    
                                    Spacer(modifier = Modifier.height(20.dp))
                                    
                                    Button(
                                        onClick = { viewModel.cancelUpload() },
                                        colors = ButtonDefaults.buttonColors(containerColor = iOSRed, contentColor = Color.White),
                                        shape = RoundedCornerShape(10.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Icon(imageVector = Icons.Default.Close, contentDescription = "Cancel", modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(text = "Cancel Transfer", fontWeight = FontWeight.SemiBold)
                                    }
                                }
                            }
                        }
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
                val vibratorManager = getSystemService(VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                val vibrator = vibratorManager?.defaultVibrator
                vibrator?.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
            } else {
                @Suppress("DEPRECATION")
                val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                vibrator?.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE))
            }
        } catch (_: Exception) {
            // Haptic feedback is not critical, silently ignore errors
        }
    }

    private fun requestBatteryOptimizationExemption() {
        try {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                @Suppress("InlinedApi", "BatteryLife")
                startActivity(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                        .apply { data = "package:$packageName".toUri() }
                )
            }
        } catch (_: Exception) {}
        
        // Request storage permissions for FTP server
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11+
            if (!Environment.isExternalStorageManager()) {
                try {
                    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                    intent.data = "package:$packageName".toUri()
                    startActivity(intent)
                } catch (_: Exception) {
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

    override fun onStart() {
        super.onStart()
        setupWebServerSecurityListener()
    }

    override fun onStop() {
        super.onStop()
        RemoteControlService.securityListener = null
    }

    private fun setupWebServerSecurityListener() {
        RemoteControlService.securityListener = object : RemoteControlService.WebServerSecurityListener {
            override fun onRequestConnection(clientIp: String, onResponse: (Boolean) -> Unit) {
                runOnUiThread {
                    androidx.appcompat.app.AlertDialog.Builder(this@MainActivity)
                        .setTitle("Connection Request")
                        .setMessage("A browser at $clientIp wants to connect to the file transfer server. Allow access?")
                        .setPositiveButton("Allow") { _, _ -> onResponse(true) }
                        .setNegativeButton("Deny") { _, _ -> onResponse(false) }
                        .setCancelable(false)
                        .show()
                }
            }

            override fun onRequestDelete(clientIp: String, fileName: String, onResponse: (Boolean) -> Unit) {
                runOnUiThread {
                    androidx.appcompat.app.AlertDialog.Builder(this@MainActivity)
                        .setTitle("Delete Request")
                        .setMessage("A browser at $clientIp wants to delete \"$fileName\". Approve deletion?")
                        .setPositiveButton("Approve") { _, _ -> onResponse(true) }
                        .setNegativeButton("Deny") { _, _ -> onResponse(false) }
                        .setCancelable(false)
                        .show()
                }
            }

            override fun onRequestUpload(clientIp: String, fileName: String, onResponse: (Boolean) -> Unit) {
                runOnUiThread {
                    androidx.appcompat.app.AlertDialog.Builder(this@MainActivity)
                        .setTitle("Upload Request")
                        .setMessage("A browser at $clientIp wants to upload \"$fileName\". Allow upload?")
                        .setPositiveButton("Allow") { _, _ -> onResponse(true) }
                        .setNegativeButton("Deny") { _, _ -> onResponse(false) }
                        .setCancelable(false)
                        .show()
                }
            }

            override fun onRequestDownload(clientIp: String, fileName: String, onResponse: (Boolean) -> Unit) {
                runOnUiThread {
                    androidx.appcompat.app.AlertDialog.Builder(this@MainActivity)
                        .setTitle("Download Request")
                        .setMessage("A browser at $clientIp wants to download \"$fileName\". Allow download?")
                        .setPositiveButton("Allow") { _, _ -> onResponse(true) }
                        .setNegativeButton("Deny") { _, _ -> onResponse(false) }
                        .setCancelable(false)
                        .show()
                }
            }
        }
    }

    override fun onDestroy() { super.onDestroy() }

    override fun onResume() {
        super.onResume()
        try {
            // Re-check storage access in case user granted via Settings flow
            val vm = viewModel
            vm.checkStorageAccess()
            vm.refreshStorageVolumes()
        } catch (_: Exception) {}
    }


    private fun requestStorageAccess() {
        // Prefer the MANAGE_EXTERNAL_STORAGE settings flow on Android 11+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                intent.data = "package:$packageName".toUri()
                startActivity(intent)
                return
            } catch (_: Exception) {
                // Fall through to SAF picker
            }
        }

        // Launch SAF folder picker as a fallback for scoped storage
        try {
            openDocumentTreeLauncher.launch(null)
        } catch (_: Exception) {
            // ignore
        }
    }

    private fun openFile(path: String) {
        try {
            val isRestricted = SafStorageHelper.isPathRestricted(this, path)
            val uri = if (isRestricted) {
                val doc = SafStorageHelper.getDocumentFileForPath(this, path) ?: throw Exception("File not found under restricted path")
                doc.uri
            } else {
                val file = File(path)
                val authority = "${packageName}.provider"
                FileProvider.getUriForFile(this, authority, file)
            }
            val extension = path.substringAfterLast('.', "").lowercase()
            val mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "*/*"
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Open file with"))
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to open file: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}

// ---  Root screen ─────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RemoteScreen(
    state: RemoteState,
    previewSlideIndex: Int,
    onPreviewSlideIndexChange: (Int) -> Unit,
    onPresentationSelect: (String) -> Unit,
    onStartSlideshow: (Int?) -> Unit,
    onStopSlideshow: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onRefresh: () -> Unit,
    onShowSettings: () -> Unit,
    onShowNotes: () -> Unit,
    onSelectBridge: (BridgeInfo) -> Unit,
    onShowFiles: () -> Unit,
    onGetThumbnail: (String, Int) -> ByteArray?,
) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val connected = state.bridgeReachable
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var showPairingDialog by remember { mutableStateOf(false) }
    
    val isTablet = configuration.screenWidthDp >= 600
    val isLandscape = configuration.screenWidthDp > configuration.screenHeightDp
    val useWideLayout = isTablet || isLandscape

    var swipeHapticFired = false
    fun performGestureHapticFeedback() {
        if (swipeHapticFired) return
        swipeHapticFired = true
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vibratorManager?.defaultVibrator?.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
            } else {
                @Suppress("DEPRECATION")
                val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                vibrator?.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE))
            }
        } catch (_: Exception) {}
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                drawerContainerColor = MaterialTheme.colorScheme.screenBg.copy(alpha = 0.98f),
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
                Box(modifier = Modifier.fillMaxSize()) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer {
                                    renderEffect = RenderEffect.createBlurEffect(
                                        50f, 50f, Shader.TileMode.CLAMP
                                    ).asComposeRenderEffect()
                                }
                                .background(MaterialTheme.colorScheme.screenBg.copy(alpha = 0.4f))
                        )
                    }

                    Column(
                        modifier = Modifier.fillMaxSize().padding(24.dp),
                        verticalArrangement = Arrangement.SpaceBetween
                    ) {
                        // Top part (Logo + Presentations) - Scrollable
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(28.dp)
                        ) {
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
                            
                            HorizontalDivider(color = MaterialTheme.colorScheme.divider)

                            Column(
                                modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
                                verticalArrangement = Arrangement.spacedBy(28.dp)
                            ) {
                                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Text(
                                        "CONNECTION STATUS",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.textSecondary,
                                        fontWeight = FontWeight.Bold,
                                        letterSpacing = 1.sp
                                    )
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    IOSIcon(
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
                                }

                                HorizontalDivider(color = MaterialTheme.colorScheme.divider)

                                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
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

                                    state.discoveredBridges.forEach { bridge ->
                                        val isSelected = bridge.id == state.selectedBridgeId
                                        var expanded by remember { mutableStateOf(isSelected) }
                                        
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
                                                    .clickable {
                                                        onSelectBridge(bridge)
                                                        expanded = if (isSelected) !expanded else true
                                                    }
                                                    .padding(12.dp)
                                            ) {
                                                IOSIcon(
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
                                        Spacer(Modifier.height(8.dp))
                                    }
                                }
                            }
                        }

                        // Fixed Bottom Part (FTP + Settings)
                        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.divider)
                            val context = LocalContext.current
                            NavigationDrawerItem(
                                label = { Text("Stream Server Media", fontWeight = FontWeight.Bold) },
                                selected = false,
                                onClick = { 
                                    scope.launch { drawerState.close() }
                                    showPairingDialog = true
                                },
                                icon = { Icon(Icons.Default.PlayCircle, contentDescription = "Stream Media", tint = iOSAccent) },
                                colors = NavigationDrawerItemDefaults.colors(
                                    unselectedContainerColor = Color.Transparent,
                                    unselectedTextColor = MaterialTheme.colorScheme.textPrimary,
                                    unselectedIconColor = iOSAccent
                                ),
                                shape = iOSSquircleSmall,
                                modifier = Modifier.padding(horizontal = 0.dp)
                            )
                            
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
                        IconButton(onClick = { showPairingDialog = true }) {
                            Icon(Icons.Default.PlayCircle, contentDescription = "Stream Server Media", tint = iOSAccent)
                        }
                        IconButton(onClick = onRefresh) {
                            Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                        }
                        IconButton(onClick = onShowFiles) {
                            Icon(Icons.Default.FolderOpen, contentDescription = "Files")
                        }
                    },
                    colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                        containerColor = MaterialTheme.colorScheme.screenBg,
                        titleContentColor = MaterialTheme.colorScheme.textPrimary,
                        navigationIconContentColor = MaterialTheme.colorScheme.textPrimary,
                    )
                )
            },
            containerColor = MaterialTheme.colorScheme.screenBg
        ) { innerPadding ->
            PullToRefreshBox(
                isRefreshing = state.isRefreshing,
                onRefresh = onRefresh,
                modifier = Modifier.fillMaxSize().padding(innerPadding)
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    val activePres = state.presentations.find { it.id == state.selectedPresentationId }
                    val currentNotes = if (activePres?.inSlideshow == true) {
                        state.speakerNotes?.getOrNull((activePres.currentSlide ?: 1) - 1)
                    } else {
                        state.speakerNotes?.getOrNull(previewSlideIndex - 1)
                    }
                    val previewThumbnail = if (activePres != null && !activePres.inSlideshow) {
                        onGetThumbnail(activePres.id, previewSlideIndex)
                    } else null
                    
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = if (useWideLayout) 32.dp else 16.dp, vertical = 12.dp)
                    ) {
                        Column {
                            if (!state.networkWarning.isNullOrBlank() || !state.bridgeNetworkWarning.isNullOrBlank()) {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    if (!state.networkWarning.isNullOrBlank()) WarningBanner(message = state.networkWarning)
                                    if (!state.bridgeNetworkWarning.isNullOrBlank()) WarningBanner(message = "Desktop: ${state.bridgeNetworkWarning}")
                                }
                                Spacer(Modifier.height(12.dp))
                            }

                            if (activePres != null) {
                                PresentationHero(
                                    presentation = activePres,
                                    previewSlideIndex = if (activePres.inSlideshow) null else previewSlideIndex,
                                    previewThumbnail = previewThumbnail,
                                    modifier = Modifier.pointerInput(Unit) {
                                        var totalDrag = 0f
                                        detectHorizontalDragGestures(
                                            onDragStart = { 
                                                swipeHapticFired = false
                                                totalDrag = 0f
                                            },
                                            onDragEnd = { 
                                                swipeHapticFired = false
                                                val threshold = 150f
                                                if (totalDrag > threshold) {
                                                    performGestureHapticFeedback()
                                                    if (activePres.inSlideshow) {
                                                        onPrevious()
                                                    } else {
                                                        onPreviewSlideIndexChange((previewSlideIndex - 1).coerceAtLeast(1))
                                                    }
                                                } else if (totalDrag < -threshold) {
                                                    performGestureHapticFeedback()
                                                    if (activePres.inSlideshow) {
                                                        onNext()
                                                    } else {
                                                        onPreviewSlideIndexChange((previewSlideIndex + 1).coerceAtMost(activePres.totalSlides))
                                                    }
                                                }
                                            }
                                        ) { _, dragAmount ->
                                            totalDrag += dragAmount
                                        }
                                    }
                                )
                                Spacer(Modifier.height(16.dp))
                            }
                        }

                        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .verticalScroll(rememberScrollState()),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                AppCard(
                                    borderColor = if (currentNotes != null) iOSAccent.copy(alpha = 0.2f) else MaterialTheme.colorScheme.divider
                                ) {
                                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Icon(Icons.AutoMirrored.Filled.Notes, contentDescription = null, tint = iOSAccent, modifier = Modifier.size(20.dp))
                                            Text(
                                                "Speaker Notes",
                                                style = MaterialTheme.typography.labelLarge,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.textSecondary
                                            )
                                        }
                                        
                                        val noteText = if (activePres?.inSlideshow == true) {
                                            state.currentSlideNotes ?: state.speakerNotes?.getOrNull((activePres.currentSlide ?: 1) - 1)
                                        } else {
                                            state.speakerNotes?.getOrNull(previewSlideIndex - 1)
                                        }
                                        
                                        if (noteText != null) {
                                            Text(
                                                text = noteText.ifBlank { "(No notes for this slide)" },
                                                style = MaterialTheme.typography.bodyLarge,
                                                color = if (noteText.isBlank()) MaterialTheme.colorScheme.textMuted else MaterialTheme.colorScheme.textPrimary,
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
                                
                                Text(
                                    "Volume Buttons / Swipe to Navigate",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.textMuted,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                                )
                            }
                        }

                        Column {
                            Spacer(Modifier.height(16.dp))
                            SlideControlsCard(
                                isBusy = state.isBusy,
                                hasPresentation = state.selectedPresentationId != null,
                                inSlideshow = activePres?.inSlideshow == true,
                                useWideLayout = useWideLayout,
                                onPrevious = if (activePres?.inSlideshow == true) onPrevious else { { onPreviewSlideIndexChange((previewSlideIndex - 1).coerceAtLeast(1)) } },
                                onNext = if (activePres?.inSlideshow == true) onNext else { { onPreviewSlideIndexChange((previewSlideIndex + 1).coerceAtMost(activePres?.totalSlides ?: 1)) } },
                                onStart = { onStartSlideshow(if (activePres?.inSlideshow == true) null else previewSlideIndex) },
                                onStop = onStopSlideshow,
                                prevEnabled = if (activePres?.inSlideshow == true) true else (previewSlideIndex > 1),
                                nextEnabled = if (activePres?.inSlideshow == true) true else (previewSlideIndex < (activePres?.totalSlides ?: 1))
                            )
                        }
                    }

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

    MediaServerPairingDialog(
        showDialog = showPairingDialog,
        defaultUrl = (state.webServerUrl ?: ""),
        onDismiss = { showPairingDialog = false },
        onConnect = { targetUrl -> MediaStreamActivity.launch(context, targetUrl) }
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MediaServerPairingDialog(
    showDialog: Boolean,
    defaultUrl: String,
    onDismiss: () -> Unit,
    onConnect: (String) -> Unit
) {
    if (!showDialog) return
    val context = LocalContext.current
    val colorScheme = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()

    var serverUrlInput by remember { mutableStateOf(defaultUrl.ifBlank { "http://192.168.1.50:8686" }) }
    var testStatus by remember { mutableStateOf("NOT_TESTED") }
    var statusMessage by remember { mutableStateOf("") }
    var recentServers by remember { mutableStateOf(RemotePrefs.getRecentMediaServers(context)) }

    fun runConnectionTest() {
        testStatus = "TESTING"
        statusMessage = "Testing server reachability..."
        scope.launch(Dispatchers.IO) {
            try {
                var formatted = serverUrlInput.trim()
                if (!formatted.startsWith("http://") && !formatted.startsWith("https://")) {
                    formatted = "http://$formatted"
                }
                val url = URL(formatted)
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 3000
                conn.readTimeout = 3000
                conn.requestMethod = "HEAD"
                val responseCode = conn.responseCode
                conn.disconnect()
                withContext(Dispatchers.Main) {
                    if (responseCode in 200..399 || responseCode == 401) {
                        testStatus = "ONLINE"
                        statusMessage = "🟢 Server Online & Reachable!"
                    } else {
                        testStatus = "FAILED"
                        statusMessage = "🔴 Server test failed (HTTP $responseCode)"
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    testStatus = "FAILED"
                    statusMessage = "🔴 Cannot reach server (${e.message ?: "timeout"})"
                }
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.Wifi, contentDescription = null, tint = iOSAccent)
                Text("Media Server Pairing", fontWeight = FontWeight.Bold)
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    "Pair with another Android device or PC connected on the same WiFi to browse and stream media files in-app.",
                    style = MaterialTheme.typography.bodySmall,
                    color = colorScheme.textSecondary
                )

                OutlinedTextField(
                    value = serverUrlInput,
                    onValueChange = { serverUrlInput = it; testStatus = "NOT_TESTED" },
                    label = { Text("Server Address (e.g. http://192.168.1.50:8686)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = iOSSquircleSmall,
                    trailingIcon = {
                        IconButton(onClick = { runConnectionTest() }) {
                            Icon(Icons.Default.Refresh, contentDescription = "Test connection", tint = iOSAccent)
                        }
                    }
                )

                if (statusMessage.isNotBlank()) {
                    Text(
                        statusMessage,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Medium
                    )
                }

                if (recentServers.isNotEmpty()) {
                    Text("Recent Media Servers:", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = iOSAccent)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        recentServers.forEach { server ->
                            AssistChip(
                                onClick = {
                                    serverUrlInput = server
                                    runConnectionTest()
                                },
                                label = { Text(server, style = MaterialTheme.typography.labelSmall) },
                                leadingIcon = { Icon(Icons.Default.History, contentDescription = null, modifier = Modifier.size(14.dp)) }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    var formatted = serverUrlInput.trim()
                    if (!formatted.startsWith("http://") && !formatted.startsWith("https://")) {
                        formatted = "http://$formatted"
                    }
                    RemotePrefs.addRecentMediaServer(context, formatted)
                    onDismiss()
                    onConnect(formatted)
                },
                shape = iOSSquircleSmall
            ) {
                Icon(Icons.Default.PlayCircle, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Connect & Stream", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
        containerColor = colorScheme.surface,
        titleContentColor = colorScheme.textPrimary
    )
}

private enum class FileViewMode {
    LIST, DETAILED, GRID
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WebServerCard(
    state: RemoteState,
    colorScheme: ColorScheme,
    onToggle: () -> Unit,
    onPinChange: (String) -> Unit,
    onSelectFolder: () -> Unit,
    onResetFolder: () -> Unit,
    onRefreshUrl: () -> Unit
) {
    val context = LocalContext.current
    var showPinField by remember { mutableStateOf(false) }
    var pinInput by remember(state.webServerPin) { mutableStateOf(state.webServerPin) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (state.isWebServerRunning)
                colorScheme.primary.copy(alpha = 0.08f)
            else
                colorScheme.surfaceVariant.copy(alpha = 0.2f)
        ),
        border = BorderStroke(
            1.dp,
            if (state.isWebServerRunning) colorScheme.primary.copy(alpha = 0.4f)
            else colorScheme.outline.copy(alpha = 0.2f)
        ),
        shape = iOSSquircleSmall
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(
                        Icons.Default.Wifi,
                        contentDescription = null,
                        tint = if (state.isWebServerRunning) colorScheme.primary else colorScheme.textSecondary,
                        modifier = Modifier.size(22.dp)
                    )
                    Column {
                        Text(
                            "Browser File Transfer",
                            fontWeight = FontWeight.SemiBold,
                            color = colorScheme.textPrimary,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            if (state.isWebServerRunning) "Active — open URL in any browser"
                            else "Share files with any device on this WiFi",
                            style = MaterialTheme.typography.labelSmall,
                            color = colorScheme.textSecondary
                        )
                    }
                }
                Switch(
                    checked = state.isWebServerRunning,
                    onCheckedChange = { onToggle() },
                    colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = colorScheme.primary)
                )
            }

            if (state.isWebServerRunning && state.webServerUrl != null) {
                val clipboardManager = LocalClipboardManager.current
                Surface(
                    onClick = {
                        clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(state.webServerUrl))
                    },
                    shape = iOSSquircleSmall,
                    color = colorScheme.surfaceVariant.copy(alpha = 0.3f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Default.Link, contentDescription = null, tint = colorScheme.primary, modifier = Modifier.size(16.dp))
                        Text(
                            text = state.webServerUrl,
                            style = MaterialTheme.typography.bodySmall,
                            color = colorScheme.primary,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Icon(
                            Icons.Default.ContentCopy,
                            contentDescription = "Copy URL",
                            tint = colorScheme.textSecondary,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = "Refresh URL",
                            tint = colorScheme.textSecondary,
                            modifier = Modifier
                                .size(16.dp)
                                .clickable { onRefreshUrl() }
                        )
                    }
                }
                if (state.isHttpsEnabled) {
                    Text(
                        "⚠️ Self-signed certificate active. You may need to click 'Advanced' -> 'Proceed' in your browser.",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFFD32F2F),
                        fontWeight = FontWeight.Medium
                    )
                }
                Text(
                    "Tap the URL to copy it, then open it in any browser on the same WiFi.",
                    style = MaterialTheme.typography.labelSmall,
                    color = colorScheme.textSecondary
                )
                val context = LocalContext.current
                var showPairingDialog by remember { mutableStateOf(false) }
                Button(
                    onClick = { showPairingDialog = true },
                    shape = iOSSquircleSmall,
                    colors = ButtonDefaults.buttonColors(containerColor = iOSAccent),
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                ) {
                    Icon(Icons.Default.Wifi, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Connect & Pair Device (In-App Stream)", fontWeight = FontWeight.Bold)
                }
                MediaServerPairingDialog(
                    showDialog = showPairingDialog,
                    defaultUrl = (state.webServerUrl ?: ""),
                    onDismiss = { showPairingDialog = false },
                    onConnect = { targetUrl -> MediaStreamActivity.launch(context, targetUrl) }
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    "PIN: ${if (state.webServerPin.isBlank()) "Not set (open access)" else "••••"}",
                    style = MaterialTheme.typography.labelMedium,
                    color = colorScheme.textSecondary,
                    modifier = Modifier.weight(1f)
                )
                TextButton(
                    onClick = { showPinField = !showPinField },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(if (showPinField) "Cancel" else "Set PIN", style = MaterialTheme.typography.labelMedium)
                }
            }

            val currentSharedFolder = state.webServerSharedFolder
                ?: state.filesRootPath
                ?: state.activeFtpPath
                ?: state.availableStorages.firstOrNull()?.path
                ?: "Internal Storage root"
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Shared Folder:",
                        style = MaterialTheme.typography.labelSmall,
                        color = colorScheme.textSecondary
                    )
                    Text(
                        text = currentSharedFolder,
                        style = MaterialTheme.typography.bodySmall,
                        color = colorScheme.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (state.webServerSharedFolder != null) {
                    TextButton(
                        onClick = onResetFolder,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                        colors = ButtonDefaults.textButtonColors(contentColor = iOSRed)
                    ) {
                        Text("Reset", style = MaterialTheme.typography.labelMedium)
                    }
                }
                TextButton(
                    onClick = onSelectFolder,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text("Change Folder", style = MaterialTheme.typography.labelMedium)
                }
            }

            if (showPinField) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = pinInput,
                        onValueChange = { if (it.length <= 8 && it.all { c -> c.isDigit() }) pinInput = it },
                        modifier = Modifier.weight(1f),
                        label = { Text("PIN (digits only, leave blank for open)") },
                        singleLine = true,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.NumberPassword),
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        shape = iOSSquircleSmall,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = colorScheme.primary,
                            unfocusedBorderColor = colorScheme.outline.copy(alpha = 0.3f),
                            focusedTextColor = colorScheme.textPrimary,
                            unfocusedTextColor = colorScheme.textPrimary
                        )
                    )
                    Button(
                        onClick = {
                            onPinChange(pinInput)
                            showPinField = false
                        },
                        shape = iOSSquircleSmall
                    ) { Text("Save") }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilesScreen(
    state: RemoteState,
    onClose: () -> Unit,
    onSelectFilesRoot: (String) -> Unit,
    onNavigateFilesTo: (String) -> Unit,
    onNavigateFilesUp: () -> Unit,
    onRefreshFiles: () -> Unit,
    onOpenCurrentFilesFolderOnPc: () -> Unit,
    onRequestStorageAccess: () -> Unit,
    onRequestRestrictedFolderAccess: (String) -> Unit,
    onOpenFile: (String) -> Unit,
    onFilesSearchQueryChange: (String) -> Unit,
    onJumpToFileLocation: (FileEntry) -> Unit,
    onFilesSortChange: (SortCategory, SortOrder) -> Unit,
    onLaunchSystemFilesApp: () -> Unit,
    onToggleWebServer: () -> Unit = {},
    onUpdateWebServerPin: (String) -> Unit = {},
    onSelectSharedFolder: () -> Unit = {},
    onResetSharedFolder: () -> Unit = {},
    onRefreshWebServerUrl: () -> Unit = {}
) {
    val colorScheme = if (state.isDarkTheme) DarkColorScheme else LightColorScheme
    var showHelp by remember { mutableStateOf(false) }
    var viewMode by remember { mutableStateOf(FileViewMode.LIST) }
    var showSortMenu by remember { mutableStateOf(false) }
    var selectedTabIndex by remember { mutableIntStateOf(0) }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Files", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Close") }
                },
                actions = {
                    IconButton(onClick = onRefreshFiles) { Icon(Icons.Default.Refresh, contentDescription = "Refresh") }
                    IconButton(onClick = onLaunchSystemFilesApp) { Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = "Open system Files") }
                    IconButton(onClick = onOpenCurrentFilesFolderOnPc) { Icon(Icons.Default.OpenInBrowser, contentDescription = "Open on PC") }
                    IconButton(onClick = { showSortMenu = true }) {
                        Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = "Sort files")
                    }
                    DropdownMenu(
                        expanded = showSortMenu,
                        onDismissRequest = { showSortMenu = false },
                        modifier = Modifier.background(colorScheme.surface)
                    ) {
                        DropdownMenuItem(
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text("Name")
                                    Icon(
                                        imageVector = if (state.filesSortOrder == SortOrder.ASCENDING) Icons.Default.ArrowUpward else Icons.Default.ArrowDownward,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            },
                            onClick = {
                                val newOrder = if (state.filesSortCategory == SortCategory.NAME && state.filesSortOrder == SortOrder.ASCENDING) SortOrder.DESCENDING else SortOrder.ASCENDING
                                onFilesSortChange(SortCategory.NAME, newOrder)
                                showSortMenu = false
                            },
                            leadingIcon = {
                                if (state.filesSortCategory == SortCategory.NAME) {
                                    Icon(Icons.Default.Check, contentDescription = "Selected", tint = colorScheme.primary)
                                }
                            }
                        )
                        DropdownMenuItem(
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text("Size")
                                    Icon(
                                        imageVector = if (state.filesSortOrder == SortOrder.ASCENDING) Icons.Default.ArrowUpward else Icons.Default.ArrowDownward,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            },
                            onClick = {
                                val newOrder = if (state.filesSortCategory == SortCategory.SIZE && state.filesSortOrder == SortOrder.ASCENDING) SortOrder.DESCENDING else SortOrder.ASCENDING
                                onFilesSortChange(SortCategory.SIZE, newOrder)
                                showSortMenu = false
                            },
                            leadingIcon = {
                                if (state.filesSortCategory == SortCategory.SIZE) {
                                    Icon(Icons.Default.Check, contentDescription = "Selected", tint = colorScheme.primary)
                                }
                            }
                        )
                        DropdownMenuItem(
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text("Date Modified")
                                    Icon(
                                        imageVector = if (state.filesSortOrder == SortOrder.ASCENDING) Icons.Default.ArrowUpward else Icons.Default.ArrowDownward,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            },
                            onClick = {
                                val newOrder = if (state.filesSortCategory == SortCategory.DATE && state.filesSortOrder == SortOrder.ASCENDING) SortOrder.DESCENDING else SortOrder.ASCENDING
                                onFilesSortChange(SortCategory.DATE, newOrder)
                                showSortMenu = false
                            },
                            leadingIcon = {
                                if (state.filesSortCategory == SortCategory.DATE) {
                                    Icon(Icons.Default.Check, contentDescription = "Selected", tint = colorScheme.primary)
                                }
                            }
                        )
                    }
                    IconButton(
                        onClick = {
                            viewMode = when (viewMode) {
                                FileViewMode.LIST -> FileViewMode.DETAILED
                                FileViewMode.DETAILED -> FileViewMode.GRID
                                FileViewMode.GRID -> FileViewMode.LIST
                            }
                        }
                    ) {
                                Icon(
                                    imageVector = when (viewMode) {
                                        FileViewMode.LIST -> Icons.AutoMirrored.Filled.List
                                        FileViewMode.DETAILED -> Icons.Default.ViewHeadline
                                        FileViewMode.GRID -> Icons.Default.GridView
                                    },
                                    contentDescription = "Change view mode"
                                )
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = colorScheme.screenBg,
                    titleContentColor = colorScheme.textPrimary
                )
            )
        },
        floatingActionButton = {
            if (selectedTabIndex == 0) {
                ExtendedFloatingActionButton(
                    onClick = onOpenCurrentFilesFolderOnPc,
                    icon = { Icon(Icons.Default.OpenInBrowser, contentDescription = null) },
                    text = { Text("Open current folder on PC") },
                    containerColor = colorScheme.primary,
                    contentColor = colorScheme.onPrimary,
                    shape = iOSSquircleSmall
                )
            }
        },
        bottomBar = {
            NavigationBar(
                containerColor = colorScheme.surface,
                contentColor = colorScheme.primary,
                tonalElevation = 8.dp
            ) {
                NavigationBarItem(
                    selected = selectedTabIndex == 0,
                    onClick = { selectedTabIndex = 0 },
                    icon = { Icon(Icons.Default.Folder, contentDescription = null) },
                    label = { Text("Local Files", fontWeight = FontWeight.Bold) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = colorScheme.primary,
                        selectedTextColor = colorScheme.primary,
                        unselectedIconColor = colorScheme.textSecondary,
                        unselectedTextColor = colorScheme.textSecondary
                    )
                )
                NavigationBarItem(
                    selected = selectedTabIndex == 1,
                    onClick = { selectedTabIndex = 1 },
                    icon = { Icon(Icons.Default.Wifi, contentDescription = null) },
                    label = { Text("Web Server & Stream", fontWeight = FontWeight.Bold) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = colorScheme.primary,
                        selectedTextColor = colorScheme.primary,
                        unselectedIconColor = colorScheme.textSecondary,
                        unselectedTextColor = colorScheme.textSecondary
                    )
                )
            }
        },
        containerColor = colorScheme.screenBg
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            Column(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (selectedTabIndex == 1) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        WebServerCard(
                            state = state,
                            colorScheme = colorScheme,
                            onToggle = onToggleWebServer,
                            onPinChange = onUpdateWebServerPin,
                            onSelectFolder = onSelectSharedFolder,
                            onResetFolder = onResetSharedFolder,
                            onRefreshUrl = onRefreshWebServerUrl
                        )
                    }
                } else {
                val filesRootPath = state.filesRootPath ?: state.activeFtpPath
                val currentFilesPath = state.currentFilesPath ?: filesRootPath

                if (!state.hasStorageAccess) {
                    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text("Storage access required to browse files.")
                            Spacer(Modifier.height(8.dp))
                            Text("This app needs access to your phone storage to list and open folders. On Android 11+ we prefer the 'All files access' settings screen; otherwise we use the SAF folder picker.")
                            Spacer(Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { onRequestStorageAccess() }) { Text("Grant access") }
                                TextButton(onClick = { showHelp = true }) { Text("How it works") }
                            }
                        }
                    }
                }

                if (showHelp) {
                    AlertDialog(
                        onDismissRequest = { showHelp = false },
                        confirmButton = {
                            TextButton(onClick = { showHelp = false }) { Text("OK") }
                        },
                        title = { Text("How storage access works") },
                        text = {
                            Text("If you're on Android 11 or newer, tapping Grant access will open the system Settings page where you can allow 'All files access'. On older Android versions we'll open the SAF folder picker. If you choose Settings, return to the app after granting access.")
                        }
                    )
                }

                if (state.availableStorages.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        state.availableStorages.forEach { storage ->
                            Button(onClick = { onSelectFilesRoot(storage.path) }, modifier = Modifier.weight(1f)) {
                                Icon(if (storage.isSdCard) Icons.Default.SdCard else Icons.Default.Smartphone, contentDescription = null)
                                Spacer(Modifier.width(6.dp))
                                Text(if (storage.isSdCard) "SD Card" else "Internal")
                            }
                        }
                    }
                }

                // Search Bar
                OutlinedTextField(
                    value = state.filesSearchQuery,
                    onValueChange = onFilesSearchQueryChange,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Search files and folders...") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = colorScheme.textSecondary) },
                    trailingIcon = {
                        if (state.filesSearchQuery.isNotEmpty()) {
                            IconButton(onClick = { onFilesSearchQueryChange("") }) {
                                Icon(Icons.Default.Clear, contentDescription = "Clear search", tint = colorScheme.textSecondary)
                            }
                        }
                    },
                    singleLine = true,
                    shape = iOSSquircleSmall,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = colorScheme.surfaceVariant.copy(alpha = 0.3f),
                        unfocusedContainerColor = colorScheme.surfaceVariant.copy(alpha = 0.15f),
                        focusedBorderColor = colorScheme.primary,
                        unfocusedBorderColor = colorScheme.outline.copy(alpha = 0.2f),
                        focusedTextColor = colorScheme.textPrimary,
                        unfocusedTextColor = colorScheme.textPrimary
                    )
                )

                val isSearching = state.filesSearchQuery.isNotEmpty()

                if (isSearching) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (state.isSearchingFiles) "Searching..." else "Search Results (${state.filesSearchResults.size})",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = colorScheme.textSecondary
                        )
                        if (state.isSearchingFiles) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = colorScheme.primary)
                        }
                    }

                    if (state.filesSearchResults.isEmpty() && !state.isSearchingFiles) {
                        EmptyStateCard()
                    } else {
                        Column(modifier = Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())) {
                            state.filesSearchResults.forEach { entry ->
                                val folderPath = remember(entry.path) {
                                    val f = File(entry.path)
                                    val parent = f.parent ?: ""
                                    val root = state.filesRootPath ?: ""
                                    if (parent.startsWith(root)) parent.removePrefix(root).trimStart('/') else parent
                                }
                                Surface(
                                    onClick = { onJumpToFileLocation(entry) },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                        FileIconOrThumbnail(entry = entry, colorScheme = colorScheme)
                                        Spacer(Modifier.width(12.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(entry.name, fontWeight = FontWeight.SemiBold, color = colorScheme.textPrimary)
                                            Text(
                                                text = if (folderPath.isEmpty()) "Root" else "In /$folderPath",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = colorScheme.textSecondary,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                        Icon(Icons.Default.ChevronRight, contentDescription = null, tint = colorScheme.textSecondary)
                                    }
                                }
                            }
                        }
                    }
                } else {
                    if (currentFilesPath != null) {
                        val currentFilesPathNormalized = currentFilesPath.replace('\\', '/')
                        val rootNormalized = (filesRootPath ?: currentFilesPath).replace('\\', '/')
                        val relative = if (currentFilesPathNormalized.startsWith(rootNormalized)) {
                            currentFilesPathNormalized.removePrefix(rootNormalized).trimStart('/')
                        } else {
                            currentFilesPathNormalized
                        }
                        val segments = relative.split('/').filter { it.isNotBlank() }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            IconButton(
                                onClick = onNavigateFilesUp,
                                enabled = currentFilesPathNormalized != rootNormalized,
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ArrowUpward,
                                    contentDescription = "Up",
                                    tint = if (currentFilesPathNormalized != rootNormalized) colorScheme.primary else colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                                )
                            }

                            Row(
                                modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                AssistChip(onClick = { onSelectFilesRoot(rootNormalized) }, label = { Text("Root") })
                                var accum = rootNormalized
                                segments.forEach { seg ->
                            accum = if (accum.endsWith('/')) "$accum$seg" else "$accum/$seg"
                            val targetPath = accum
                            AssistChip(onClick = { onNavigateFilesTo(targetPath) }, label = { Text(seg) })
                        }
                            }
                        }

                        Spacer(Modifier.height(8.dp))
                        Text(currentFilesPath, style = MaterialTheme.typography.labelSmall)

                        val isCurrentRestricted = SafStorageHelper.isPathRestricted(LocalContext.current, currentFilesPath)
                        val hasRestrictedPermission = SafStorageHelper.getTreeUriForPath(LocalContext.current, currentFilesPath) != null

                        if (isCurrentRestricted && !hasRestrictedPermission) {
                            Spacer(Modifier.height(8.dp))
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = iOSRed.copy(alpha = 0.1f)),
                                border = BorderStroke(1.dp, iOSRed.copy(alpha = 0.3f))
                            ) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Text(
                                        "Restricted Folder Access Required",
                                        fontWeight = FontWeight.Bold,
                                        color = iOSRed
                                    )
                                    Spacer(Modifier.height(8.dp))
                                    Text(
                                        "To browse and manage files in Android/data or Android/obb, Android requires you to grant explicit folder permission. Tap below and choose 'Use this folder'.",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = colorScheme.textPrimary
                                    )
                                    Spacer(Modifier.height(12.dp))
                                    Button(
                                        onClick = { onRequestRestrictedFolderAccess(currentFilesPath) },
                                        colors = ButtonDefaults.buttonColors(containerColor = iOSRed, contentColor = Color.White)
                                    ) {
                                        Text("Grant Folder Access")
                                    }
                                    Spacer(Modifier.height(8.dp))
                                    Text(
                                        "Alternatively, you can open the hidden system native Files app directly to manage files manually:",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = colorScheme.textSecondary
                                    )
                                    Spacer(Modifier.height(6.dp))
                                    OutlinedButton(
                                        onClick = onLaunchSystemFilesApp,
                                        border = BorderStroke(1.dp, colorScheme.primary)
                                    ) {
                                        Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, tint = colorScheme.primary)
                                        Spacer(Modifier.width(6.dp))
                                        Text("Open Native Files App")
                                    }
                                }
                            }
                        }

                        PullToRefreshBox(
                            isRefreshing = state.filesLoading,
                            onRefresh = onRefreshFiles,
                            modifier = Modifier.fillMaxWidth().weight(1f)
                        ) {
                            Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                                when (viewMode) {
                                    FileViewMode.LIST -> {
                                        state.fileEntries.forEach { entry ->
                                            val isHighlighted = entry.path == state.highlightFilePath
                                            val itemBg = if (isHighlighted) colorScheme.primary.copy(alpha = 0.15f) else Color.Transparent
                                            Surface(
                                                onClick = {
                                                    if (entry.isDirectory) onNavigateFilesTo(entry.path)
                                                    else onOpenFile(entry.path)
                                                },
                                                color = itemBg,
                                                shape = if (isHighlighted) RoundedCornerShape(8.dp) else RectangleShape,
                                                border = if (isHighlighted) BorderStroke(1.dp, colorScheme.primary) else null,
                                                modifier = Modifier.fillMaxWidth()
                                            ) {
                                                Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                                    FileIconOrThumbnail(entry = entry, colorScheme = colorScheme)
                                                    Spacer(Modifier.width(8.dp))
                                                    Column(modifier = Modifier.weight(1f)) {
                                                        Text(entry.name, fontWeight = FontWeight.SemiBold, color = colorScheme.textPrimary)
                                                        Text(entry.path, style = MaterialTheme.typography.labelSmall, color = colorScheme.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                    }
                                                    if (entry.isDirectory) Icon(Icons.Default.ChevronRight, contentDescription = null, tint = colorScheme.textSecondary)
                                                }
                                            }
                                        }
                                    }
                                    FileViewMode.DETAILED -> {
                                        val formatter = remember { java.text.SimpleDateFormat("dd MMM yyyy HH:mm", java.util.Locale.getDefault()) }

                                        fun formatSize(bytes: Long?): String {
                                            if (bytes == null) return ""
                                            if (bytes < 1024) return "$bytes B"
                                            val exp = (kotlin.math.log(bytes.toDouble(), 1024.0)).toInt()
                                            val pre = "KMGTPE"[exp - 1]
                                            return String.format(java.util.Locale.US, "%.1f %cB", bytes / 1024.0.pow(exp.toDouble()), pre)
                                        }

                                        state.fileEntries.forEach { entry ->
                                            val dateStr = entry.lastModifiedMillis?.let { formatter.format(java.util.Date(it)) } ?: ""
                                            val isHighlighted = entry.path == state.highlightFilePath
                                            val itemBg = if (isHighlighted) colorScheme.primary.copy(alpha = 0.15f) else Color.Transparent
                                            Surface(
                                                onClick = {
                                                    if (entry.isDirectory) onNavigateFilesTo(entry.path)
                                                    else onOpenFile(entry.path)
                                                },
                                                color = itemBg,
                                                shape = if (isHighlighted) RoundedCornerShape(8.dp) else RectangleShape,
                                                border = if (isHighlighted) BorderStroke(1.dp, colorScheme.primary) else null,
                                                modifier = Modifier.fillMaxWidth()
                                            ) {
                                                Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                                    FileIconOrThumbnail(entry = entry, colorScheme = colorScheme)
                                                    Spacer(Modifier.width(12.dp))
                                                    Column(modifier = Modifier.weight(1f)) {
                                                        Text(entry.name, fontWeight = FontWeight.Bold, color = colorScheme.textPrimary)
                                                        Text(entry.path, style = MaterialTheme.typography.bodySmall, color = colorScheme.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                        Spacer(Modifier.height(4.dp))
                                                        Row(
                                                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                                                            verticalAlignment = Alignment.CenterVertically
                                                        ) {
                                                            val sizeText = formatSize(entry.sizeBytes)
                                                            if (sizeText.isNotEmpty()) {
                                                                Text(sizeText, style = MaterialTheme.typography.labelSmall, color = colorScheme.textSecondary.copy(alpha = 0.7f))
                                                            }
                                                            Text(dateStr, style = MaterialTheme.typography.labelSmall, color = colorScheme.textSecondary.copy(alpha = 0.7f))
                                                        }
                                                    }
                                                    if (entry.isDirectory) Icon(Icons.Default.ChevronRight, contentDescription = null, tint = colorScheme.textSecondary)
                                                }
                                            }
                                        }
                                    }
                                    FileViewMode.GRID -> {
                                        val columns = 3
                                        val chunks = state.fileEntries.chunked(columns)
                                        chunks.forEach { rowEntries ->
                                            Row(
                                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                                            ) {
                                                for (i in 0 until columns) {
                                                    val entry = rowEntries.getOrNull(i)
                                                    if (entry != null) {
                                                        val isHighlighted = entry.path == state.highlightFilePath
                                                        Surface(
                                                            onClick = {
                                                                if (entry.isDirectory) onNavigateFilesTo(entry.path)
                                                                else onOpenFile(entry.path)
                                                            },
                                                            modifier = Modifier.weight(1f),
                                                            shape = iOSSquircleSmall,
                                                            color = if (isHighlighted) colorScheme.primary.copy(alpha = 0.15f) else colorScheme.surfaceVariant.copy(alpha = 0.3f),
                                                            border = BorderStroke(
                                                                width = if (isHighlighted) 1.5.dp else 0.5.dp,
                                                                color = if (isHighlighted) colorScheme.primary else colorScheme.outline.copy(alpha = 0.1f)
                                                            )
                                                        ) {
                                                            Column(
                                                                modifier = Modifier.padding(12.dp),
                                                                horizontalAlignment = Alignment.CenterHorizontally,
                                                                verticalArrangement = Arrangement.spacedBy(8.dp)
                                                            ) {
                                                                FileIconOrThumbnail(entry = entry, colorScheme = colorScheme, size = 44.dp)
                                                                Text(
                                                                    entry.name,
                                                                    style = MaterialTheme.typography.labelMedium,
                                                                    fontWeight = FontWeight.SemiBold,
                                                                    color = colorScheme.textPrimary,
                                                                    textAlign = TextAlign.Center,
                                                                    maxLines = 2,
                                                                    overflow = TextOverflow.Ellipsis,
                                                                    modifier = Modifier.heightIn(min = 36.dp)
                                                                )
                                                            }
                                                        }
                                                    } else {
                                                        Spacer(Modifier.weight(1f))
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
}

object ThumbnailCache {
    private val cache = LruCache<String, Bitmap>(150)
    fun get(path: String): Bitmap? = cache.get(path)
    fun put(path: String, bitmap: Bitmap) {
        cache.put(path, bitmap)
    }
}

@Composable
private fun FileIconOrThumbnail(
    entry: FileEntry,
    colorScheme: ColorScheme,
    size: androidx.compose.ui.unit.Dp = 36.dp
) {
    var bitmap by remember(entry.path) { mutableStateOf(ThumbnailCache.get(entry.path)) }
    val isThumbnailCandidate = remember(entry.path) {
        val ext = entry.path.substringAfterLast('.', "").lowercase()
        ext in setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "mp4", "mkv", "avi", "3gp", "webm", "pdf")
    }

    if (isThumbnailCandidate && bitmap == null) {
        val context = LocalContext.current
        LaunchedEffect(entry.path) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    val isRestricted = SafStorageHelper.isPathRestricted(context, entry.path)
                    val ext = entry.path.substringAfterLast('.', "").lowercase()
                    if (isRestricted) {
                        val doc = SafStorageHelper.getDocumentFileForPath(context, entry.path)
                        if (doc != null && doc.isFile) {
                            val generatedBitmap = when (ext) {
                                "jpg", "jpeg", "png", "webp", "gif", "bmp" -> {
                                    val options = BitmapFactory.Options().apply {
                                        inJustDecodeBounds = true
                                    }
                                    context.contentResolver.openInputStream(doc.uri)?.use { stream ->
                                        BitmapFactory.decodeStream(stream, null, options)
                                    }
                                    val reqSize = 128
                                    var inSampleSize = 1
                                    if (options.outHeight > reqSize || options.outWidth > reqSize) {
                                        val halfHeight = options.outHeight / 2
                                        val halfWidth = options.outWidth / 2
                                        while (halfHeight / inSampleSize >= reqSize && halfWidth / inSampleSize >= reqSize) {
                                            inSampleSize *= 2
                                        }
                                    }
                                    options.inSampleSize = inSampleSize
                                    options.inJustDecodeBounds = false
                                    context.contentResolver.openInputStream(doc.uri)?.use { stream ->
                                        BitmapFactory.decodeStream(stream, null, options)
                                    }
                                }
                                "mp4", "mkv", "avi", "3gp", "webm" -> {
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                        try {
                                            context.contentResolver.loadThumbnail(doc.uri, Size(128, 128), null)
                                        } catch (_: Exception) {
                                            val retriever = android.media.MediaMetadataRetriever()
                                            try {
                                                retriever.setDataSource(context, doc.uri)
                                                retriever.getFrameAtTime(1000000)
                                            } finally {
                                                retriever.release()
                                            }
                                        }
                                    } else {
                                        val retriever = android.media.MediaMetadataRetriever()
                                        try {
                                            retriever.setDataSource(context, doc.uri)
                                            retriever.getFrameAtTime(1000000)
                                        } finally {
                                            retriever.release()
                                        }
                                    }
                                }
                                "pdf" -> {
                                    var pdfRenderer: android.graphics.pdf.PdfRenderer? = null
                                    var fileDescriptor: android.os.ParcelFileDescriptor? = null
                                    try {
                                        fileDescriptor = context.contentResolver.openFileDescriptor(doc.uri, "r")
                                        if (fileDescriptor != null) {
                                            pdfRenderer = PdfRenderer(fileDescriptor)
                                            if (pdfRenderer.pageCount > 0) {
                                                val page = pdfRenderer.openPage(0)
                                                val destBitmap = createBitmap(128, 128, Bitmap.Config.ARGB_8888)
                                                val canvas = Canvas(destBitmap)
                                                canvas.drawColor(AndroidColor.WHITE)
                                                page.render(destBitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                                page.close()
                                                destBitmap
                                            } else null
                                        } else null
                                    } catch (_: Exception) {
                                        null
                                    } finally {
                                        pdfRenderer?.close()
                                        fileDescriptor?.close()
                                    }
                                }
                                else -> null
                            }
                            if (generatedBitmap != null) {
                                ThumbnailCache.put(entry.path, generatedBitmap)
                                bitmap = generatedBitmap
                            }
                        }
                    } else {
                        val file = File(entry.path)
                        if (file.exists() && file.isFile) {
                            val generatedBitmap = when (ext) {
                                "jpg", "jpeg", "png", "webp", "gif", "bmp" -> {
                                    val options = BitmapFactory.Options().apply {
                                        inJustDecodeBounds = true
                                    }
                                    BitmapFactory.decodeFile(file.absolutePath, options)
                                    val reqSize = 128
                                    var inSampleSize = 1
                                    if (options.outHeight > reqSize || options.outWidth > reqSize) {
                                        val halfHeight = options.outHeight / 2
                                        val halfWidth = options.outWidth / 2
                                        while (halfHeight / inSampleSize >= reqSize && halfWidth / inSampleSize >= reqSize) {
                                            inSampleSize *= 2
                                        }
                                    }
                                    options.inSampleSize = inSampleSize
                                    options.inJustDecodeBounds = false
                                    BitmapFactory.decodeFile(file.absolutePath, options)
                                }
                                "mp4", "mkv", "avi", "3gp", "webm" -> {
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                        try {
                                            android.media.ThumbnailUtils.createVideoThumbnail(file, android.util.Size(128, 128), null)
                                        } catch (_: Exception) {
                                            @Suppress("DEPRECATION")
                                            android.media.ThumbnailUtils.createVideoThumbnail(file.absolutePath, android.provider.MediaStore.Video.Thumbnails.MINI_KIND)
                                        }
                                    } else {
                                        @Suppress("DEPRECATION")
                                        android.media.ThumbnailUtils.createVideoThumbnail(file.absolutePath, android.provider.MediaStore.Video.Thumbnails.MINI_KIND)
                                    }
                                }
                                "pdf" -> {
                                    var pdfRenderer: android.graphics.pdf.PdfRenderer? = null
                                    var fileDescriptor: android.os.ParcelFileDescriptor? = null
                                    try {
                                        fileDescriptor = android.os.ParcelFileDescriptor.open(file, android.os.ParcelFileDescriptor.MODE_READ_ONLY)
                                        pdfRenderer = PdfRenderer(fileDescriptor)
                                        if (pdfRenderer.pageCount > 0) {
                                            val page = pdfRenderer.openPage(0)
                                            val destBitmap = createBitmap(128, 128, Bitmap.Config.ARGB_8888)
                                            val canvas = Canvas(destBitmap)
                                            canvas.drawColor(AndroidColor.WHITE)
                                            page.render(destBitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                            page.close()
                                            destBitmap
                                        } else null
                                    } catch (_: Exception) {
                                        null
                                    } finally {
                                        pdfRenderer?.close()
                                        fileDescriptor?.close()
                                    }
                                }
                                else -> null
                            }
                            if (generatedBitmap != null) {
                                ThumbnailCache.put(entry.path, generatedBitmap)
                                bitmap = generatedBitmap
                            }
                        }
                    }
                } catch (_: Exception) {
                    // Ignore decoding failures
                }
            }
        }
    }

    if (bitmap != null) {
        Image(
            bitmap = bitmap!!.asImageBitmap(),
            contentDescription = null,
            modifier = Modifier
                .size(size)
                .clip(RoundedCornerShape(8.dp))
                .border(0.5.dp, colorScheme.outline.copy(alpha = 0.2f), RoundedCornerShape(8.dp)),
            contentScale = ContentScale.Crop
        )
    } else {
        val ext = entry.path.substringAfterLast('.', "").lowercase()
        val (icon, tint) = when {
            entry.isDirectory -> Pair(Icons.Default.Folder, iOSGreen)
            ext == "pdf" -> Pair(Icons.Default.PictureAsPdf, iOSRed)
            ext in setOf("ppt", "pptx") -> Pair(Icons.Default.Slideshow, iOSAmber)
            ext in setOf("doc", "docx") -> Pair(Icons.Default.Description, iOSBlue)
            ext in setOf("xls", "xlsx") -> Pair(Icons.Default.GridOn, iOSGreen)
            ext in setOf("mp3", "wav", "m4a", "ogg", "flac") -> Pair(Icons.Default.AudioFile, iOSAccent)
            ext in setOf("zip", "rar", "7z", "tar", "gz") -> Pair(Icons.Default.FolderZip, iOSAmber)
            ext in setOf("txt", "html", "css", "js", "json", "kt", "java", "xml") -> Pair(Icons.AutoMirrored.Filled.Article, iOSGray)
            else -> Pair(Icons.AutoMirrored.Filled.InsertDriveFile, colorScheme.textSecondary)
        }
        Box(
            modifier = Modifier
                .size(size)
                .clip(RoundedCornerShape(8.dp))
                .background(tint.copy(alpha = 0.1f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(size * 0.6f)
            )
        }
    }
}

@Composable
private fun SlideControlsCard(
    isBusy: Boolean,
    hasPresentation: Boolean,
    inSlideshow: Boolean,
    useWideLayout: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    prevEnabled: Boolean = hasPresentation && inSlideshow,
    nextEnabled: Boolean = hasPresentation && inSlideshow,
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
                    icon = Icons.AutoMirrored.Filled.NavigateBefore,
                    enabled = prevEnabled,
                    modifier = Modifier.weight(1f),
                    onClick = onPrevious,
                )
                SlideNavButton(
                    label = "Next",
                    icon = Icons.AutoMirrored.Filled.NavigateNext,
                    enabled = nextEnabled,
                    modifier = Modifier.weight(1f),
                    onClick = onNext,
                )
                FilledTonalButton(
                    onClick = onStart,
                    enabled = hasPresentation,
                    modifier = Modifier.weight(0.8f),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = if (!inSlideshow) iOSGreen.copy(alpha = 0.3f) else iOSGreen.copy(alpha = 0.1f),
                        contentColor = if (!inSlideshow) iOSGreen else iOSGreen.copy(alpha = 0.6f)
                    )
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Start", fontWeight = if (!inSlideshow) FontWeight.Bold else FontWeight.SemiBold)
                }
                FilledTonalButton(
                    onClick = onStop,
                    enabled = hasPresentation,
                    modifier = Modifier.weight(0.8f),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = if (inSlideshow) iOSRed.copy(alpha = 0.3f) else iOSRed.copy(alpha = 0.1f),
                        contentColor = if (inSlideshow) iOSRed else iOSRed.copy(alpha = 0.6f)
                    )
                ) {
                    Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Stop", fontWeight = if (inSlideshow) FontWeight.Bold else FontWeight.SemiBold)
                }
            }
        } else {
            // Compact layout: original two-row design
            Column(
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
            // Large Prev / Next buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                SlideNavButton(
                    label = "Prev",
                    icon = Icons.AutoMirrored.Filled.ArrowBackIos,
                    enabled = prevEnabled,
                    modifier = Modifier.weight(1f),
                    onClick = onPrevious,
                )
                SlideNavButton(
                    label = "Next",
                    icon = Icons.AutoMirrored.Filled.ArrowForwardIos,
                    enabled = nextEnabled,
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
                        containerColor = if (!inSlideshow) iOSGreen else iOSGreen.copy(alpha = 0.3f),
                        contentColor = if (!inSlideshow) Color.White else iOSGreen
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
                        containerColor = if (inSlideshow) iOSRed else iOSRed.copy(alpha = 0.15f),
                        contentColor = if (inSlideshow) Color.White else iOSRed
                    ),
                    border = if (inSlideshow) null else BorderStroke(1.dp, iOSRed.copy(alpha = 0.3f))
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

@Composable
private fun EmptyStateCard(connected: Boolean = true, isFiltered: Boolean = true) {
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

// ---  Onboarding screen ───────────────────────────────────────────────────────

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

// ---  Settings screen ─────────────────────────────────────────────────────────

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
    onUpdateWebServerPort: (Int) -> Unit,
    onUpdateFtpUsername: (String) -> Unit,
    onUpdateFtpPassword: (String) -> Unit,
    onUpdateHttpsEnabled: (Boolean) -> Unit,
) {
    BackHandler(onBack = onBack)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
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
                    value = state.apiKey,
                    onValueChange = onUpdateApiKey,
                    isPassword = true
                )
                SettingsSwitchRow(
                    title = "Auto-start FTP",
                    subtitle = "Automatically turn on Mobile Files when app starts",
                    checked = state.isFtpAutoStart,
                    onCheckedChange = onUpdateFtpAutoStart
                )
                SettingsInputRow(
                    title = "FTP Username",
                    subtitle = "Default username for FTP login",
                    value = state.ftpUsername,
                    onValueChange = onUpdateFtpUsername
                )
                SettingsInputRow(
                    title = "FTP Password",
                    subtitle = "Leave blank for anonymous FTP access",
                    value = state.ftpPassword,
                    onValueChange = onUpdateFtpPassword,
                    isPassword = true
                )
                SettingsInputRow(
                    title = "Web Server Port",
                    subtitle = "Port for browser file transfer (default is 8686)",
                    value = state.webServerPort.toString(),
                    onValueChange = { val p = it.toIntOrNull(); if (p != null) onUpdateWebServerPort(p) }
                )
                SettingsSwitchRow(
                    title = "HTTPS / SSL",
                    subtitle = "Secure file transfer using local self-signed certificate",
                    checked = state.isHttpsEnabled,
                    onCheckedChange = onUpdateHttpsEnabled
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
                "Version 2.2.2 • Premium iOS • Antigravity AI",
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

// ---  Speaker notes screen ───────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NotesScreen(
    state: RemoteState,
    onBack: () -> Unit,
    onGetThumbnail: (String, Int) -> ByteArray?,
    onSelectSlide: (Int) -> Unit
) {
    BackHandler(onBack = onBack)
    val pres = state.presentations.find { it.id == state.selectedPresentationId }
    val notes = state.speakerNotes

    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    Column {
                        Text("All Slides & Notes", style = MaterialTheme.typography.titleMedium)
                        Text(pres?.name ?: "", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.textSecondary)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
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
        if (notes == null && pres != null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    CircularProgressIndicator(color = iOSAccent)
                    Text("Fetching presentation notes...", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.textSecondary)
                }
            }
        } else if (pres == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("No active presentation", color = MaterialTheme.colorScheme.textMuted)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                items(count = pres.totalSlides) { index ->
                    val slideIndex = index + 1
                    val isCurrent = pres.currentSlide == slideIndex
                    val slideNote = notes?.getOrNull(index) ?: ""
                    val thumbnail = onGetThumbnail(pres.id, slideIndex)
                    
                    AppCard(
                        borderColor = if (isCurrent) iOSAccent else MaterialTheme.colorScheme.divider,
                        borderWidth = if (isCurrent) 2.dp else 1.dp,
                        backgroundColor = if (isCurrent) iOSAccent.copy(alpha = 0.05f) else MaterialTheme.colorScheme.cardBg,
                        onClick = { onSelectSlide(slideIndex) }
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "Slide $slideIndex",
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
                                            "CURRENT",
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = Color.White,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                // Thumbnail
                                Box(
                                    modifier = Modifier
                                        .width(120.dp)
                                        .aspectRatio(16f / 9f)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(Color.Black.copy(alpha = 0.05f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (thumbnail != null) {
                                        val bitmap = remember(thumbnail) {
                                            BitmapFactory.decodeByteArray(thumbnail, 0, thumbnail.size)
                                        }
                                        if (bitmap != null) {
                                            Image(
                                                bitmap = bitmap.asImageBitmap(),
                                                contentDescription = "Slide $slideIndex",
                                                modifier = Modifier.fillMaxSize(),
                                                contentScale = ContentScale.Fit
                                            )
                                        }
                                    } else {
                                        Icon(
                                            Icons.Default.Slideshow, 
                                            contentDescription = null, 
                                            tint = MaterialTheme.colorScheme.textMuted,
                                            modifier = Modifier.size(24.dp)
                                        )
                                    }
                                }

                                // Notes
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = slideNote.ifBlank { "(No notes)" },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (slideNote.isBlank()) MaterialTheme.colorScheme.textMuted else MaterialTheme.colorScheme.textPrimary,
                                        maxLines = 4,
                                        overflow = TextOverflow.Ellipsis,
                                        lineHeight = 18.sp
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ---  Shared UI components ────────────────────────────────────────────────────

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
    contentPadding: androidx.compose.ui.unit.Dp = 16.dp,
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
        Box(modifier = Modifier.padding(contentPadding)) {
            content()
        }
    }
}

@Composable
private fun PresentationHero(
    presentation: Presentation,
    modifier: Modifier = Modifier,
    previewSlideIndex: Int? = null,
    previewThumbnail: ByteArray? = null
) {
    val isDark = !MaterialTheme.colorScheme.surface.isLight()
    val thumbnailBytes = if (previewSlideIndex != null) previewThumbnail else presentation.currentThumbnail
    val isLive = previewSlideIndex == null && presentation.inSlideshow
    
    AppCard(
        modifier = modifier,
        backgroundColor = if (isDark) iOSGray900 else MaterialTheme.colorScheme.surfaceVariant,
        borderColor = if (isDark) iOSAccent.copy(alpha = 0.4f) else iOSAccent.copy(alpha = 0.2f),
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
                if (thumbnailBytes != null) {
                    val bitmap = remember(thumbnailBytes) {
                        BitmapFactory.decodeByteArray(thumbnailBytes, 0, thumbnailBytes.size)
                    }
                    if (bitmap != null) {
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = "Slide Preview",
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
                    color = if (isLive) iOSAccent else iOSGray,
                    shape = RoundedCornerShape(topStart = 0.dp, bottomEnd = 16.dp, topEnd = 0.dp, bottomStart = 16.dp),
                    modifier = Modifier.align(Alignment.TopEnd)
                ) {
                    Text(
                        if (isLive) "LIVE" else "PREVIEW",
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
                    color = MaterialTheme.colorScheme.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val slideNum = previewSlideIndex ?: presentation.currentSlide ?: 0
                    Text(
                        "Slide $slideNum of ${presentation.totalSlides}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = iOSAccent
                    )
                }
            }
        }
    }
}
