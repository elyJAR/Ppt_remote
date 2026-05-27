package com.antigravity.pptremote

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import androidx.documentfile.provider.DocumentFile

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private companion object {
        const val PRELOAD_THUMBNAIL_WIDTH = 720
    }

    private val client = BridgeClient()
    private val appContext = getApplication<Application>()
    private val ftpManager = FtpServerManager()
    private val thumbnailCache = ConcurrentHashMap<String, ConcurrentHashMap<Int, ByteArray>>()
    private val thumbnailWarmupComplete = ConcurrentHashMap.newKeySet<String>()
    private val thumbnailWarmupInFlight = ConcurrentHashMap.newKeySet<String>()
    private var lastNetworkType: NetworkType = NetworkType.UNKNOWN
    private var networkChangeCallbackRegistered = false
    private var lastInteractionTime = 0L

    private val _state = MutableStateFlow(
        RemoteState(
            bridgeUrl = RemotePrefs.getActiveBridgeUrl(appContext),
            showOnboarding = !RemotePrefs.isOnboardingCompleted(appContext),
            bridgePort = RemotePrefs.getBridgePort(appContext),
            pollingIntervalSeconds = RemotePrefs.getPollingInterval(appContext),
            isDarkTheme = RemotePrefs.isDarkTheme(appContext),
            connectionHistory = RemotePrefs.getConnectionHistory(appContext),
            notificationText = RemotePrefs.getNotificationText(appContext),
            apiKey = RemotePrefs.getApiKey(appContext),
            discoveredBridges = RemotePrefs.getSavedBridges(appContext),
            selectedBridgeId = RemotePrefs.getSelectedBridgeId(appContext),
            isFtpEnabled = RemotePrefs.isFtpEnabled(appContext),
            isFtpAutoStart = RemotePrefs.isFtpAutoStart(appContext),
            filesSortCategory = try { SortCategory.valueOf(RemotePrefs.getFilesSortCategory(appContext)) } catch (_: Exception) { SortCategory.NAME },
            filesSortOrder = try { SortOrder.valueOf(RemotePrefs.getFilesSortOrder(appContext)) } catch (_: Exception) { SortOrder.ASCENDING }
        )
    )
    val state: StateFlow<RemoteState> = _state.asStateFlow()

    init {
        updateNetworkType()
        updateServiceStatus()
        refreshStorageVolumes()
        registerNetworkChangeListener()
        // Sync API key into client on startup
        client.apiKey = RemotePrefs.getApiKey(appContext)
        startPolling()
        startDiscovery()
        startRegistrationLoop()
        // Restore last browsed folder if available
        try {
            val last = RemotePrefs.getLastBrowsedFolder(appContext)
            if (!last.isNullOrBlank()) {
                selectFilesRoot(last)
            }
        } catch (_: Exception) {}
        // Check storage access state
        checkStorageAccess()
    }

    fun updateSearchQuery(query: String) {
        _state.value = _state.value.copy(searchQuery = query)
    }

    private fun updateNetworkType() {
        try {
            val currentNetworkType = NetworkDetector.getNetworkType(appContext)
            val current = _state.value
            if (current.networkType == currentNetworkType) return
            lastNetworkType = currentNetworkType

            val warning = when (currentNetworkType) {
                NetworkType.HOTSPOT_USING -> {
                    "Using phone hotspot: Connection may be less stable. Using aggressive reconnection strategy."
                }
                NetworkType.HOTSPOT_PROVIDING -> {
                    "Providing hotspot to PC: Connection may be less stable. Using aggressive reconnection strategy."
                }
                NetworkType.CELLULAR -> {
                    "Using cellular data: Consider switching to WiFi for better stability."
                }
                else -> null
            }

            _state.value = current.copy(
                networkType = currentNetworkType,
                networkWarning = warning
            )
            
            // If we just connected to a network, trigger a discovery
            if (currentNetworkType != NetworkType.UNKNOWN && currentNetworkType != NetworkType.CELLULAR) {
                viewModelScope.launch(Dispatchers.IO) {
                    refreshPresentations()
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("MainViewModel", "Failed to update network type", e)
        }
    }

    private fun registerNetworkChangeListener() {
        if (networkChangeCallbackRegistered) return

        try {
            val connectivityManager = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val networkCallback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: android.net.Network) {
                    updateNetworkType()
                }

                override fun onLost(network: android.net.Network) {
                    updateNetworkType()
                }

                override fun onCapabilitiesChanged(
                    network: android.net.Network,
                    networkCapabilities: android.net.NetworkCapabilities
                ) {
                    updateNetworkType()
                }
            }

            connectivityManager.registerDefaultNetworkCallback(networkCallback)
            networkChangeCallbackRegistered = true
        } catch (e: Exception) {
            // Fallback: Will detect network type during polling
        }
    }

    fun setBridgeUrl(url: String) {
        val trimmedUrl = url.trim()
        val urlWithPort = if (trimmedUrl.isNotBlank()) {
            buildBridgeUrl(trimmedUrl, _state.value.bridgePort)
        } else {
            trimmedUrl
        }
        
        val newBridge = BridgeInfo(
            id = urlWithPort,
            name = "Manual: $trimmedUrl",
            url = urlWithPort
        )

        RemotePrefs.setBridgeUrl(appContext, urlWithPort)
        RemotePrefs.setSelectedBridgeId(appContext, newBridge.id)
        if (urlWithPort.isNotBlank()) {
            RemotePrefs.addToConnectionHistory(appContext, urlWithPort)
            // Add to saved bridges if manual
            val saved = RemotePrefs.getSavedBridges(appContext).toMutableList()
            if (saved.none { it.id == newBridge.id }) {
                saved.add(newBridge)
                RemotePrefs.saveBridges(appContext, saved)
            }
        }
        _state.value = _state.value.copy(
            bridgeUrl = urlWithPort,
            selectedBridgeId = newBridge.id,
            discoveredBridges = RemotePrefs.getSavedBridges(appContext),
            connectionHistory = RemotePrefs.getConnectionHistory(appContext)
        )
    }

    fun selectBridge(bridge: BridgeInfo) {
        RemotePrefs.setSelectedBridgeId(appContext, bridge.id)
        _state.value = _state.value.copy(
            selectedBridgeId = bridge.id,
            bridgeUrl = bridge.url
        )
        // Immediately trigger a poll
        viewModelScope.launch(Dispatchers.IO) {
            refreshPresentations()
        }
    }

    fun selectPresentation(id: String) {
        RemotePrefs.setSelectedPresentationId(appContext, id)
        _state.value = _state.value.copy(selectedPresentationId = id)
    }

    fun startSelectedSlideshow() {
        val selected = _state.value.selectedPresentationId ?: return
        runBridgeAction("Slideshow started") { url -> client.startSlideshow(url, selected) }
    }

    fun stopSelectedSlideshow() {
        val selected = _state.value.selectedPresentationId ?: return
        runBridgeAction("Slideshow stopped") { url -> client.stopSlideshow(url, selected) }
    }

    private fun optimisticUpdate(presentationId: String, delta: Int) {
        lastInteractionTime = System.currentTimeMillis()
        val current = _state.value
        val bridgeUrl = current.bridgeUrl
        if (bridgeUrl.isBlank()) return

        val updatedPresentations = current.presentations.map { pres ->
            if (pres.id == presentationId && pres.inSlideshow && pres.currentSlide != null) {
                val newSlideIndex = (pres.currentSlide + delta).coerceIn(1, maxOf(1, pres.totalSlides))
                val cachedThumb = cachedThumbnail(bridgeUrl, presentationId, newSlideIndex)
                pres.copy(
                    currentSlide = newSlideIndex,
                    currentThumbnail = cachedThumb ?: pres.currentThumbnail
                )
            } else pres
        }

        val activePres = updatedPresentations.firstOrNull { it.id == presentationId }
        val newSlideIndex = activePres?.currentSlide ?: current.lastThumbnailSlide

        _state.value = current.copy(
            presentations = updatedPresentations,
            lastThumbnailSlide = newSlideIndex
        )
    }

    fun nextSlide() {
        val current = _state.value
        val pres = current.presentations.find { it.id == current.selectedPresentationId }
        if (pres?.inSlideshow != true) return

        val selected = ensureSelectedPresentation() ?: return
        optimisticUpdate(selected, 1)
        runBridgeAction("Next slide", showBusy = false) { url -> client.next(url, selected) }
    }

    fun previousSlide() {
        val current = _state.value
        val pres = current.presentations.find { it.id == current.selectedPresentationId }
        if (pres?.inSlideshow != true) return

        val selected = ensureSelectedPresentation() ?: return
        optimisticUpdate(selected, -1)
        runBridgeAction("Previous slide", showBusy = false) { url -> client.previous(url, selected) }
    }

    fun showNotes() { _state.value = _state.value.copy(showNotes = true) }
    fun hideNotes() { _state.value = _state.value.copy(showNotes = false) }
    fun toggleService() {
        if (_state.value.isServiceRunning) {
            RemoteControlService.stop(appContext)
        } else {
            RemoteControlService.start(appContext)
        }
        updateServiceStatus()
    }

    fun refreshStorageVolumes() {
        viewModelScope.launch(Dispatchers.IO) {
            val volumes = ftpManager.getStorageVolumes(appContext)
            _state.value = _state.value.copy(
                availableStorages = volumes,
                activeFtpPath = RemoteControlService.getActiveFtpPath()
            )

            val current = _state.value
            val initialFilesRoot = current.filesRootPath
                ?: current.activeFtpPath
                ?: volumes.firstOrNull()?.path
            if (current.filesRootPath == null && initialFilesRoot != null) {
                selectFilesRoot(initialFilesRoot)
            }
            // Re-check storage permission after discovering volumes
            checkStorageAccess()
        }
    }

    fun checkStorageAccess() {
        try {
            val has = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Environment.isExternalStorageManager()
            } else {
                ContextCompat.checkSelfPermission(appContext, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
            }
            _state.value = _state.value.copy(
                hasStorageAccess = has,
                filesError = if (!has) "Storage access required. Tap Files to grant access." else _state.value.filesError
            )
        } catch (e: Exception) {
            // Fail safe: assume access granted
            _state.value = _state.value.copy(hasStorageAccess = true)
        }
    }

    fun toggleFtp(homeDir: String? = null) {
        RemoteControlService.toggleFtp(appContext, homeDir)
        // Give it a moment to update then refresh state
        viewModelScope.launch {
            delay(500)
            updateServiceStatus()
        }
    }

    fun openFtpOnPc(homeDir: String? = null) {
        // If not running, start it first on requested path
        if (!state.value.isFtpEnabled || state.value.activeFtpPath != homeDir) {
            RemoteControlService.toggleFtp(appContext, homeDir)
            // Wait for it to start
            viewModelScope.launch {
                delay(1000)
                updateServiceStatus()
                if (state.value.isFtpEnabled) {
                    runBridgeAction("Opened files on PC") { url -> client.openFtpOnPc(url) }
                }
            }
        } else {
            runBridgeAction("Opened files on PC") { url -> client.openFtpOnPc(url) }
        }
    }

    fun selectFilesRoot(path: String) {
        val normalized = File(path).absolutePath
        if (normalized.isBlank()) return
        _state.value = _state.value.copy(
            filesRootPath = normalized,
            currentFilesPath = normalized,
            filesLoading = true,
            filesError = null,
            fileEntries = emptyList()
        )
        try { RemotePrefs.setLastBrowsedFolder(appContext, normalized) } catch (_: Exception) {}
        loadFilesForPath(normalized)
    }

    fun navigateToFilesFolder(path: String) {
        val normalized = File(path).absolutePath
        if (normalized.isBlank()) return
        _state.value = _state.value.copy(currentFilesPath = normalized, filesLoading = true, filesError = null)
        try { RemotePrefs.setLastBrowsedFolder(appContext, normalized) } catch (_: Exception) {}
        loadFilesForPath(normalized)
    }

    fun navigateUpFilesFolder() {
        val currentPath = _state.value.currentFilesPath ?: return
        val rootPath = _state.value.filesRootPath ?: return
        val parent = File(currentPath).parentFile?.absolutePath ?: return
        if (parent == currentPath) return
        if (!isPathWithinRoot(parent, rootPath)) return
        navigateToFilesFolder(parent)
    }

    fun refreshFiles() {
        val currentPath = _state.value.currentFilesPath ?: _state.value.filesRootPath ?: return
        loadFilesForPath(currentPath)
    }

    fun setFilesSort(category: SortCategory, order: SortOrder) {
        RemotePrefs.setFilesSortCategory(appContext, category.name)
        RemotePrefs.setFilesSortOrder(appContext, order.name)
        _state.value = _state.value.copy(
            filesSortCategory = category,
            filesSortOrder = order
        )
        val currentEntries = _state.value.fileEntries
        if (currentEntries.isNotEmpty()) {
            _state.value = _state.value.copy(
                fileEntries = sortFileEntries(currentEntries, category, order)
            )
        }
    }

    private var filesSearchJob: kotlinx.coroutines.Job? = null

    fun updateFilesSearchQuery(query: String) {
        filesSearchJob?.cancel()
        _state.value = _state.value.copy(filesSearchQuery = query)
        if (query.isBlank()) {
            _state.value = _state.value.copy(
                filesSearchResults = emptyList(),
                isSearchingFiles = false
            )
            return
        }

        val currentPath = _state.value.currentFilesPath ?: _state.value.filesRootPath ?: return
        _state.value = _state.value.copy(isSearchingFiles = true)

        filesSearchJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val results = mutableListOf<FileEntry>()
                val isRestricted = SafStorageHelper.isPathRestricted(currentPath)
                if (isRestricted) {
                    traverseAndSearch(currentPath, query, results)
                } else {
                    val rootDir = File(currentPath)
                    if (rootDir.exists() && rootDir.isDirectory) {
                        traverseAndSearch(currentPath, query, results)
                    }
                }
                val sortedResults = sortFileEntries(
                    results,
                    _state.value.filesSortCategory,
                    _state.value.filesSortOrder
                )
                _state.value = _state.value.copy(
                    filesSearchResults = sortedResults,
                    isSearchingFiles = false
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(isSearchingFiles = false)
            }
        }
    }

    private suspend fun traverseAndSearch(path: String, query: String, results: MutableList<FileEntry>) {
        if (results.size >= 200) return
        if (!kotlinx.coroutines.currentCoroutineContext().isActive) return

        val normalized = path.replace('\\', '/')
        if (SafStorageHelper.isPathRestricted(normalized)) {
            val hasPermission = SafStorageHelper.getTreeUriForPath(appContext, normalized) != null
            if (!hasPermission) return
            val docDir = SafStorageHelper.getDocumentFileForPath(appContext, normalized) ?: return
            val files = docDir.listFiles()
            for (file in files) {
                if (results.size >= 200) return
                if (!kotlinx.coroutines.currentCoroutineContext().isActive) return

                val name = file.name ?: ""
                val childPath = File(path, name).absolutePath
                if (name.contains(query, ignoreCase = true)) {
                    results.add(
                        FileEntry(
                            name = name,
                            path = childPath,
                            isDirectory = file.isDirectory,
                            sizeBytes = if (file.isFile) file.length() else null,
                            lastModifiedMillis = file.lastModified()
                        )
                    )
                }
                if (file.isDirectory) {
                    traverseAndSearch(childPath, query, results)
                }
            }
        } else {
            val dir = File(path)
            val files = dir.listFiles() ?: return
            for (file in files) {
                if (results.size >= 200) return
                if (!kotlinx.coroutines.currentCoroutineContext().isActive) return

                if (file.name.contains(query, ignoreCase = true)) {
                    results.add(
                        FileEntry(
                            name = file.name,
                            path = file.absolutePath,
                            isDirectory = file.isDirectory,
                            sizeBytes = if (file.isFile) file.length() else null,
                            lastModifiedMillis = file.lastModified()
                        )
                    )
                }
                if (file.isDirectory) {
                    traverseAndSearch(file.absolutePath, query, results)
                }
            }
        }
    }

    fun jumpToFileLocation(entry: FileEntry) {
        val file = File(entry.path)
        if (entry.isDirectory) {
            navigateToFilesFolder(entry.path)
            _state.value = _state.value.copy(
                filesSearchQuery = "",
                filesSearchResults = emptyList()
            )
        } else {
            val parent = file.parentFile?.absolutePath
            if (parent != null) {
                _state.value = _state.value.copy(
                    highlightFilePath = entry.path,
                    filesSearchQuery = "",
                    filesSearchResults = emptyList()
                )
                navigateToFilesFolder(parent)

                viewModelScope.launch {
                    delay(3000)
                    if (_state.value.highlightFilePath == entry.path) {
                        _state.value = _state.value.copy(highlightFilePath = null)
                    }
                }
            }
        }
    }

    fun clearHighlight() {
        _state.value = _state.value.copy(highlightFilePath = null)
    }

    fun openCurrentFilesFolderOnPc() {
        val current = _state.value
        val rootPath = current.filesRootPath ?: current.activeFtpPath ?: current.availableStorages.firstOrNull()?.path
        val currentPath = current.currentFilesPath ?: rootPath ?: return
        val ftpRelativePath = relativeFtpPath(rootPath ?: currentPath, currentPath)

        if (rootPath != null && (!current.isFtpEnabled || current.activeFtpPath != rootPath)) {
            RemoteControlService.toggleFtp(appContext, rootPath)
            viewModelScope.launch {
                delay(1000)
                updateServiceStatus()
                if (state.value.isFtpEnabled) {
                    runBridgeAction("Opened current folder on PC") { url -> client.openFtpOnPc(url, ftpPath = ftpRelativePath) }
                }
            }
        } else {
            runBridgeAction("Opened current folder on PC") { url -> client.openFtpOnPc(url, ftpPath = ftpRelativePath) }
        }
    }

    private fun updateServiceStatus() {
        val isRunning = RemoteControlService.isRunning(appContext)
        val isFtpRunning = RemoteControlService.isFtpRunning()
        val activePath = RemoteControlService.getActiveFtpPath()
        
        _state.value = _state.value.copy(
            isServiceRunning = isRunning,
            isFtpEnabled = isFtpRunning,
            activeFtpPath = activePath
        )
    }

    fun completeOnboarding() {
        RemotePrefs.setOnboardingCompleted(appContext, true)
        _state.value = _state.value.copy(showOnboarding = false)
    }

    fun showSettings() {
        _state.value = _state.value.copy(showSettings = true)
    }

    fun hideSettings() {
        _state.value = _state.value.copy(showSettings = false)
    }

    fun showFiles() {
        _state.value = _state.value.copy(showFiles = true)
    }

    fun hideFiles() {
        _state.value = _state.value.copy(showFiles = false)
    }

    fun updateBridgePort(port: Int) {
        if (port in 1024..65535) {
            RemotePrefs.setBridgePort(appContext, port)
            _state.value = _state.value.copy(bridgePort = port)
        }
    }

    fun updatePollingInterval(seconds: Int) {
        if (seconds in 1..30) {
            RemotePrefs.setPollingInterval(appContext, seconds)
            _state.value = _state.value.copy(pollingIntervalSeconds = seconds)
        }
    }

    fun updateTheme(isDark: Boolean) {
        RemotePrefs.setDarkTheme(appContext, isDark)
        _state.value = _state.value.copy(isDarkTheme = isDark)
    }

    fun updateNotificationText(text: String) {
        RemotePrefs.setNotificationText(appContext, text)
        _state.value = _state.value.copy(notificationText = text)
    }

    fun updateApiKey(key: String) {
        RemotePrefs.setApiKey(appContext, key)
        client.apiKey = key
        _state.value = _state.value.copy(apiKey = key)
    }



    fun updateFtpAutoStart(enabled: Boolean) {
        RemotePrefs.setFtpAutoStart(appContext, enabled)
        _state.value = _state.value.copy(isFtpAutoStart = enabled)
        
        // If enabling auto-start, also ensure it's running now
        if (enabled && !_state.value.isFtpEnabled) {
            toggleFtp()
        }
    }

    fun getCachedThumbnail(presentationId: String, slideIndex: Int): ByteArray? {
        val bridgeUrl = _state.value.bridgeUrl
        if (bridgeUrl.isBlank()) return null
        return cachedThumbnail(bridgeUrl, presentationId, slideIndex)
    }

    fun jumpToSlide(slideIndex: Int) {
        val selected = ensureSelectedPresentation() ?: return
        runBridgeAction("Jumped to slide $slideIndex", showBusy = false) { url -> 
            client.gotoSlide(url, selected, slideIndex)
        }
    }


    private fun cacheKey(bridgeUrl: String, presentationId: String): String {
        return "${bridgeUrl.trimEnd('/')} $presentationId"
    }

    private fun thumbnailSetKey(bridgeUrl: String, presentationId: String, totalSlides: Int): String {
        return "${cacheKey(bridgeUrl, presentationId)} $totalSlides $PRELOAD_THUMBNAIL_WIDTH"
    }

    private fun cacheThumbnail(bridgeUrl: String, presentationId: String, slideIndex: Int, bytes: ByteArray) {
        val set = thumbnailCache.getOrPut(cacheKey(bridgeUrl, presentationId)) { ConcurrentHashMap() }
        set[slideIndex] = bytes
    }

    private fun cachedThumbnail(bridgeUrl: String, presentationId: String, slideIndex: Int): ByteArray? {
        return thumbnailCache[cacheKey(bridgeUrl, presentationId)]?.get(slideIndex)
    }

    private fun preloadThumbnailsIfNeeded(bridgeUrl: String, presentations: List<Presentation>) {
        presentations
            .filter { it.totalSlides > 0 }
            .forEach { presentation ->
                val key = thumbnailSetKey(bridgeUrl, presentation.id, presentation.totalSlides)
                if (!thumbnailWarmupComplete.add(key) || !thumbnailWarmupInFlight.add(key)) return@forEach

                viewModelScope.launch(Dispatchers.IO) {
                    try {
                        for (slideIndex in 1..presentation.totalSlides) {
                            val thumbnail = try {
                                client.fetchSlideThumbnail(
                                    bridgeUrl,
                                    presentation.id,
                                    slideIndex,
                                    PRELOAD_THUMBNAIL_WIDTH
                                )
                            } catch (_: Exception) {
                                null
                            }
                            if (thumbnail != null) {
                                cacheThumbnail(bridgeUrl, presentation.id, slideIndex, thumbnail)
                            }
                        }
                    } finally {
                        thumbnailWarmupInFlight.remove(key)
                        thumbnailWarmupComplete.add(key)
                    }
                }
            }
    }

    private fun buildBridgeUrl(baseUrl: String, port: Int): String {
        if (baseUrl.isBlank()) return ""
        
        // If URL already contains a port, use it as-is
        if (baseUrl.contains("://") && baseUrl.substringAfter("://").contains(":")) {
            return baseUrl
        }
        
        // If it's just an IP or hostname, add the configured port
        val cleanUrl = baseUrl.removePrefix("http://").removePrefix("https://")
        return "http://$cleanUrl:$port"
    }

    private fun sortFileEntries(entries: List<FileEntry>, category: SortCategory, order: SortOrder): List<FileEntry> {
        val comparator = when (category) {
            SortCategory.NAME -> {
                if (order == SortOrder.ASCENDING) {
                    compareByDescending<FileEntry> { it.isDirectory }
                        .thenBy { it.name.lowercase() }
                } else {
                    compareByDescending<FileEntry> { it.isDirectory }
                        .thenByDescending { it.name.lowercase() }
                }
            }
            SortCategory.SIZE -> {
                if (order == SortOrder.ASCENDING) {
                    compareByDescending<FileEntry> { it.isDirectory }
                        .thenBy { it.sizeBytes ?: 0L }
                        .thenBy { it.name.lowercase() }
                } else {
                    compareByDescending<FileEntry> { it.isDirectory }
                        .thenByDescending { it.sizeBytes ?: 0L }
                        .thenBy { it.name.lowercase() }
                }
            }
            SortCategory.DATE -> {
                if (order == SortOrder.ASCENDING) {
                    compareByDescending<FileEntry> { it.isDirectory }
                        .thenBy { it.lastModifiedMillis ?: 0L }
                        .thenBy { it.name.lowercase() }
                } else {
                    compareByDescending<FileEntry> { it.isDirectory }
                        .thenByDescending { it.lastModifiedMillis ?: 0L }
                        .thenBy { it.name.lowercase() }
                }
            }
        }
        return entries.sortedWith(comparator)
    }

    private fun loadFilesForPath(path: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val normalizedPath = path.replace('\\', '/')
                val isRestricted = SafStorageHelper.isPathRestricted(normalizedPath)
                val hasPermission = SafStorageHelper.getTreeUriForPath(appContext, normalizedPath) != null

                if (isRestricted) {
                    val isRootFolder = normalizedPath.endsWith("/Android/data", ignoreCase = true) || 
                                       normalizedPath.endsWith("/Android/obb", ignoreCase = true)
                    if (hasPermission) {
                        val doc = SafStorageHelper.getDocumentFileForPath(appContext, normalizedPath)
                        val rawEntries = doc?.listFiles()?.map {
                            FileEntry(
                                name = it.name ?: "",
                                path = File(path, it.name ?: "").absolutePath,
                                isDirectory = it.isDirectory,
                                sizeBytes = if (it.isFile) it.length() else null,
                                lastModifiedMillis = it.lastModified()
                            )
                        } ?: emptyList()

                        val sorted = sortFileEntries(
                            rawEntries,
                            _state.value.filesSortCategory,
                            _state.value.filesSortOrder
                        )

                        _state.value = _state.value.copy(
                            currentFilesPath = normalizedPath,
                            fileEntries = sorted,
                            filesLoading = false,
                            filesError = null
                        )
                    } else if (isRootFolder) {
                        val rawEntries = try {
                            val pm = appContext.packageManager
                            pm.getInstalledPackages(0).map { pkg ->
                                FileEntry(
                                    name = pkg.packageName,
                                    path = File(path, pkg.packageName).absolutePath,
                                    isDirectory = true,
                                    sizeBytes = null,
                                    lastModifiedMillis = null
                                )
                            }
                        } catch (e: Exception) {
                            emptyList()
                        }.toMutableList()

                        val ownPkg = appContext.packageName
                        if (rawEntries.none { it.name == ownPkg }) {
                            rawEntries.add(
                                FileEntry(
                                    name = ownPkg,
                                    path = File(path, ownPkg).absolutePath,
                                    isDirectory = true,
                                    sizeBytes = null,
                                    lastModifiedMillis = null
                                )
                            )
                        }

                        val sorted = sortFileEntries(
                            rawEntries.distinctBy { it.name },
                            _state.value.filesSortCategory,
                            _state.value.filesSortOrder
                        )

                        _state.value = _state.value.copy(
                            currentFilesPath = normalizedPath,
                            fileEntries = sorted,
                            filesLoading = false,
                            filesError = null
                        )
                    } else {
                        _state.value = _state.value.copy(
                            currentFilesPath = normalizedPath,
                            fileEntries = emptyList(),
                            filesLoading = false,
                            filesError = null
                        )
                    }
                    return@launch
                }

                val folder = File(path)
                if (!folder.exists() || !folder.isDirectory) {
                    _state.value = _state.value.copy(
                        filesLoading = false,
                        filesError = "Folder does not exist.",
                        fileEntries = emptyList()
                    )
                    return@launch
                }

                val rawEntries = folder.listFiles()
                    ?.map {
                        FileEntry(
                            name = it.name.ifBlank { it.absolutePath },
                            path = it.absolutePath,
                            isDirectory = it.isDirectory,
                            sizeBytes = if (it.isFile) it.length() else null,
                            lastModifiedMillis = it.lastModified()
                        )
                    }
                    ?: emptyList()

                val sorted = sortFileEntries(
                    rawEntries,
                    _state.value.filesSortCategory,
                    _state.value.filesSortOrder
                )

                _state.value = _state.value.copy(
                    currentFilesPath = folder.absolutePath,
                    fileEntries = sorted,
                    filesLoading = false,
                    filesError = null
                )
            } catch (ex: Exception) {
                _state.value = _state.value.copy(
                    filesLoading = false,
                    filesError = ex.message ?: "Failed to load folder.",
                    fileEntries = emptyList()
                )
            }
        }
    }

    private fun relativeFtpPath(rootPath: String, currentPath: String): String = FilePathUtils.relativeFtpPath(rootPath, currentPath)

    private fun isPathWithinRoot(candidatePath: String, rootPath: String): Boolean = FilePathUtils.isPathWithinRoot(candidatePath, rootPath)

    private fun ensureSelectedPresentation(): String? {
        val current = _state.value
        if (current.selectedPresentationId != null) {
            return current.selectedPresentationId
        }

        val autoPick = current.presentations.firstOrNull { it.inSlideshow }
            ?: current.presentations.firstOrNull()

        return autoPick?.id?.also { selectPresentation(it) }
    }

    private fun runBridgeAction(
        successMessage: String,
        showBusy: Boolean = true,
        action: (String) -> Unit
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val current = _state.value
            if (showBusy) {
                _state.value = current.copy(isBusy = true)
            }

            // Use smart retry logic based on network type
            val maxRetries = when (current.networkType) {
                NetworkType.HOTSPOT_USING, NetworkType.HOTSPOT_PROVIDING -> 3  // More retries for any hotspot
                NetworkType.CELLULAR -> 1
                else -> 2
            }

            var lastException: Exception? = null

            for (attempt in 1..maxRetries) {
                try {
                if (current.bridgeUrl.isNotBlank()) {
                    action(current.bridgeUrl)
                } else {
                    throw Exception("Bridge URL is missing. Please search for bridge first.")
                }
                    if (showBusy) {
                        _state.value = _state.value.copy(statusMessage = successMessage, isBusy = false)
                    }
                    refreshPresentations()
                    return@launch
                } catch (ex: Exception) {
                    lastException = ex
                    // Fail fast on 4xx client errors (e.g., PowerPoint not open, invalid command)
                    if (ex is BridgeHttpException && ex.statusCode in 400..499) {
                        break
                    }
                    if (attempt < maxRetries) {
                        // Exponential backoff with network-type adjustment
                        val backoffMs = when (current.networkType) {
                            NetworkType.HOTSPOT_USING, NetworkType.HOTSPOT_PROVIDING -> 300L * (attempt - 1)
                            NetworkType.CELLULAR -> 500L * (attempt - 1)
                            else -> 200L * (attempt - 1)
                        }
                        delay(backoffMs)
                    }
                }
            }

            _state.value = _state.value.copy(
                statusMessage = lastException?.message ?: "Bridge call failed",
                isBusy = false
            )
        }
    }


    private fun startPolling() {
        viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                // Use the user-configured polling interval, adjusted by network type
                val baseMs = (_state.value.pollingIntervalSeconds * 1000L).coerceIn(1000L, 30_000L)
                
                // Adaptive polling: if a slideshow is active, poll much faster to detect PC-side changes
                val hasActiveSlideshow = _state.value.presentations.any { it.inSlideshow }
                val configuredMs = if (hasActiveSlideshow) minOf(baseMs, 1000L) else baseMs
                
                val delayMs = when (_state.value.networkType) {
                    NetworkType.HOTSPOT_USING, NetworkType.HOTSPOT_PROVIDING ->
                        minOf(configuredMs, 2000L)   // cap at 2s on hotspot
                    NetworkType.CELLULAR ->
                        maxOf(configuredMs, 5000L)   // floor at 5s on cellular
                    else -> minOf(configuredMs, 1000L) // floor at 1s on WiFi for snappiness
                }
                refreshPresentations()
                delay(delayMs)
            }
        }
    }

    fun refreshPresentations() {
        updateNetworkType()

        val current = _state.value
        val effectiveUrl = if (current.bridgeUrl.isBlank()) {
            // Use smart discovery timeout based on network type
            val discoveryTimeoutMs = when (current.networkType) {
                NetworkType.HOTSPOT_USING, NetworkType.HOTSPOT_PROVIDING -> 2500
                NetworkType.CELLULAR -> 3000
                else -> 1500
            }

            val detectedBridges = client.discoverBridge(
                timeoutMs = discoveryTimeoutMs,
                discoveryPort = current.bridgePort + 1,
                bridgePort = current.bridgePort,
                networkType = current.networkType
            )
            _state.value = _state.value.copy(discoveredBridges = detectedBridges)
            
            if (detectedBridges.isEmpty()) {
                _state.value = _state.value.copy(
                    presentations = emptyList(),
                    isBusy = true,
                    statusMessage = "Searching for desktop bridge..."
                )
                return
            }

            val bestMatch = detectedBridges.find { it.id == current.selectedBridgeId } ?: detectedBridges.first()
            if (bestMatch.id != current.selectedBridgeId) {
                RemotePrefs.setSelectedBridgeId(appContext, bestMatch.id)
                RemotePrefs.setBridgeUrl(appContext, bestMatch.url)
            }
            
            _state.value = _state.value.copy(
                bridgeUrl = bestMatch.url,
                selectedBridgeId = bestMatch.id,
                statusMessage = "Bridge detected: ${bestMatch.name}"
            )
            bestMatch.url
        } else {
            current.bridgeUrl
        }

        try {
            val presentations = client.fetchPresentations(effectiveUrl)

            // Health check + network status in parallel with presentation fetch
            val bridgeNetworkWarning = client.getNetworkStatus(effectiveUrl)?.warning
            val bridgeReachable = true  // if fetchPresentations succeeded, bridge is reachable

            // Kick off thumbnail warmup in the background so the bridge can cache
            // every slide before the user starts browsing on the phone.
            preloadThumbnailsIfNeeded(effectiveUrl, presentations)

            // Find the active slideshow presentation
            val activePres = presentations.firstOrNull { it.inSlideshow }
            val activeSlide = activePres?.currentSlide
            val prevSlide = _state.value.lastThumbnailSlide
            val activeCachedThumb = activePres?.currentSlide?.let {
                cachedThumbnail(effectiveUrl, activePres.id, it)
            }

            // ── Optimistic state protection ─────────────────────────────────────────
            // If the user recently changed slides manually, ignore the server's slide index 
            // if it's lagging behind (smaller than our optimistic index).
            val isInGracePeriod = System.currentTimeMillis() - lastInteractionTime < 3000L
            
            // Only re-fetch thumbnail when the slide number actually changes (avoids
            // a 1-3s PowerPoint export on every 2s poll tick)
            val presentationsWithThumbnails = if (activePres != null && activeSlide != null && activeSlide != prevSlide) {
                // If in grace period, preserve our optimistic currentSlide
                if (isInGracePeriod) {
                     presentations.map { pres ->
                        if (pres.id == activePres.id) {
                            val existing = _state.value.presentations.firstOrNull { it.id == pres.id }
                            pres.copy(
                                currentSlide = existing?.currentSlide ?: pres.currentSlide,
                                currentThumbnail = existing?.currentThumbnail ?: pres.currentThumbnail
                            )
                        } else pres
                     }
                } else {
                    val thumb = activeCachedThumb ?: try {
                        client.fetchCurrentThumbnail(effectiveUrl, activePres.id, PRELOAD_THUMBNAIL_WIDTH)
                    } catch (e: Exception) { null }
                    if (thumb != null) {
                        cacheThumbnail(effectiveUrl, activePres.id, activeSlide, thumb)
                    }
                    presentations.map { pres ->
                        if (pres.id == activePres.id) pres.copy(currentThumbnail = thumb)
                        else pres.copy(currentThumbnail = null)
                    }
                }
            } else {
                // Preserve existing thumbnails from state for the active pres, clear others.
                val existingThumb = activeCachedThumb ?: _state.value.presentations
                    .firstOrNull { it.id == activePres?.id }?.currentThumbnail
                
                presentations.map { pres ->
                    if (pres.id == activePres?.id) {
                        // If in grace period, also preserve the optimistic slide index
                        val optimisticSlide = if (isInGracePeriod) {
                            _state.value.presentations.firstOrNull { it.id == pres.id }?.currentSlide
                        } else null
                        
                        pres.copy(
                            currentSlide = optimisticSlide ?: pres.currentSlide,
                            currentThumbnail = existingThumb
                        )
                    } else pres.copy(currentThumbnail = null)
                }
            }

            // Fetch current slide notes when slide changes (or first time in slideshow)
            val (newNotes, newNotesIndex) = if (activePres != null && activeSlide != null && activeSlide != prevSlide) {
                try {
                    val note = client.fetchCurrentNotes(effectiveUrl, activePres.id)
                    Pair(note?.notes, note?.slideIndex)
                } catch (e: Exception) { Pair(null, null) }
            } else if (activePres == null) {
                Pair(null, null)
            } else {
                Pair(_state.value.currentSlideNotes, _state.value.currentSlideNotesIndex)
            }

            // Fetch FULL speaker notes if missing for active presentation
            val currentSpeakerNotes = if (activePres != null && (_state.value.speakerNotes == null || activePres.id != _state.value.presentations.firstOrNull { it.inSlideshow }?.id)) {
                try {
                    client.fetchFullNotes(effectiveUrl, activePres.id)
                } catch (e: Exception) { null }
            } else if (activePres == null) {
                null
            } else {
                _state.value.speakerNotes
            }

            // Read current state at write-time to avoid clobbering showSettings/showOnboarding
            val nowState = _state.value
            val selected = when {
                nowState.selectedPresentationId != null && presentationsWithThumbnails.any { it.id == nowState.selectedPresentationId } ->
                    nowState.selectedPresentationId
                else -> presentationsWithThumbnails.firstOrNull { it.inSlideshow }?.id
                    ?: presentationsWithThumbnails.firstOrNull()?.id
            }

            RemotePrefs.setSelectedPresentationId(appContext, selected)
            _state.value = nowState.copy(
                presentations = presentationsWithThumbnails,
                selectedPresentationId = selected,
                bridgeNetworkWarning = bridgeNetworkWarning,
                bridgeReachable = bridgeReachable,
                failureCount = 0, // Reset failures on success
                currentSlideNotes = newNotes,
                currentSlideNotesIndex = newNotesIndex,
                speakerNotes = currentSpeakerNotes,
                lastThumbnailSlide = if (activePres != null) activeSlide else null,
                statusMessage = if (presentationsWithThumbnails.isEmpty()) {
                    "No open PowerPoint files detected"
                } else {
                    "Connected"
                },
                isBusy = false
            )
        } catch (ex: Exception) {
            val nowState = _state.value

            // Bridge responded with an HTTP error (4xx/5xx) — the bridge IS reachable,
            // so do NOT clear the URL or increment the network-failure counter.
            // Just show a user-friendly status message.
            if (ex is BridgeHttpException) {
                val friendlyMsg = when (ex.statusCode) {
                    400 -> "Bridge reached — no PowerPoint open or controller error"
                    401 -> "Bridge reached — check your API key in Settings"
                    429 -> "Bridge reached — too many requests, slowing down"
                    else -> ex.message ?: "Bridge HTTP error ${ex.statusCode}"
                }
                _state.value = nowState.copy(
                    bridgeReachable = true,
                    isBusy = false,
                    statusMessage = friendlyMsg
                )
                return
            }

            // Network / IO error — bridge may have moved. Increment failure counter
            // and clear URL after 2 consecutive failures to trigger re-discovery.
            val newFailCount = nowState.failureCount + 1
            if (newFailCount >= 3) {
                _state.value = nowState.copy(
                    bridgeUrl = "",
                    failureCount = 0,
                    bridgeReachable = false,
                    isBusy = true,
                    statusMessage = "Connection lost. Searching for bridge..."
                )
                // We don't clear RemotePrefs.setSelectedBridgeId here, 
                // just the runtime URL to trigger re-discovery.
            } else {
                _state.value = nowState.copy(
                    failureCount = newFailCount,
                    bridgeReachable = false,
                    isBusy = false,
                    statusMessage = ex.message ?: "Unable to reach bridge"
                )
                // Trigger background discovery on first failure
                if (newFailCount == 1) {
                    startDiscovery()
                }
            }
        }
    }

    private fun startDiscovery() {
        viewModelScope.launch(Dispatchers.IO) {
            val bridges = client.discoverBridge(
                discoveryPort = _state.value.bridgePort + 1,
                bridgePort = _state.value.bridgePort,
                networkType = _state.value.networkType
            )
            _state.value = _state.value.copy(discoveredBridges = bridges)
            
            // If we have a selected ID, update its URL if it changed in discovery
            val selectedId = _state.value.selectedBridgeId
            if (selectedId != null) {
                bridges.find { it.id == selectedId }?.let {
                    if (it.url != _state.value.bridgeUrl) {
                        _state.value = _state.value.copy(bridgeUrl = it.url)
                        RemotePrefs.setBridgeUrl(appContext, it.url)
                    }
                }
            }
        }
    }

    private fun startRegistrationLoop() {
        viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                val url = _state.value.bridgeUrl
                if (url.isNotBlank() && _state.value.bridgeReachable) {
                    try {
                        client.registerClient(
                            url = url,
                            deviceId = RemotePrefs.getDeviceId(appContext),
                            deviceName = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}"
                        )
                    } catch (_: Exception) {}
                }
                delay(60000) // Register once a minute
            }
        }
    }
}
