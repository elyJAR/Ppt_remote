package com.antigravity.pptremote

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.util.Log
import org.apache.ftpserver.FtpServer
import org.apache.ftpserver.FtpServerFactory
import org.apache.ftpserver.ftplet.Authority
import org.apache.ftpserver.ftplet.UserManager
import org.apache.ftpserver.listener.ListenerFactory
import org.apache.ftpserver.usermanager.impl.BaseUser
import org.apache.ftpserver.usermanager.impl.WritePermission
import java.io.File

class FtpServerManager {
    private var server: FtpServer? = null
    var activePath: String? = null
        private set

    fun start(context: Context, port: Int = 2121, homeDir: String? = null) {
        if (server != null) {
            // If already running on the same path, ignore
            if (homeDir == activePath) return
            // If running on different path, stop first to restart on new path
            stop()
        }

        try {
            val serverFactory = FtpServerFactory()
            val listenerFactory = ListenerFactory()
            listenerFactory.port = port

            serverFactory.addListener("default", listenerFactory.createListener())

            // Anonymous user with write access
            val user = BaseUser()
            user.name = "anonymous"
            
            val internalStorage = Environment.getExternalStorageDirectory().absolutePath
            
            // 1. If a specific directory is requested, use it
            if (homeDir != null) {
                user.homeDirectory = homeDir
                Log.i("FtpServerManager", "Starting FTP on requested path: $homeDir")
            } 
            // 2. Otherwise, use the best-guess automatic logic
            else {
                val volumes = getStorageVolumes(context)
                val externalVolumes = volumes.filter { it.path != internalStorage && !it.path.contains("emulated") }
                
                if (externalVolumes.isNotEmpty()) {
                    val storageRoot = File("/storage")
                    val internalFile = File(internalStorage)
                    val storageParent = internalFile.parentFile?.parentFile
                    
                    val bestRoot = when {
                        storageRoot.exists() && storageRoot.canRead() && (storageRoot.list()?.isNotEmpty() == true) -> "/storage"
                        storageParent != null && storageParent.exists() && storageParent.canRead() && (storageParent.list()?.isNotEmpty() == true) -> storageParent.absolutePath
                        else -> internalStorage
                    }
                    user.homeDirectory = bestRoot
                    Log.i("FtpServerManager", "Starting FTP on auto-detected root: $bestRoot")
                } else {
                    user.homeDirectory = internalStorage
                    Log.i("FtpServerManager", "Starting FTP on internal storage: $internalStorage")
                }
            }
            
            activePath = user.homeDirectory
            
            val authorities = mutableListOf<Authority>()
            authorities.add(WritePermission())
            user.authorities = authorities

            serverFactory.userManager.save(user)

            server = serverFactory.createServer()
            server?.start()
            Log.i("FtpServerManager", "FTP Server started on port $port")
        } catch (e: Exception) {
            Log.e("FtpServerManager", "Failed to start FTP server", e)
            server = null
            activePath = null
        }
    }

    fun getStorageVolumes(context: Context): List<StorageVolume> {
        val volumes = mutableListOf<StorageVolume>()
        val internalPath = Environment.getExternalStorageDirectory().absolutePath
        
        // Always add Internal Storage
        volumes.add(StorageVolume("Internal Storage", internalPath, false))
        
        // Detect External SD cards
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val sm = context.getSystemService(Context.STORAGE_SERVICE) as StorageManager
            sm.storageVolumes.forEach { volume ->
                val path = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    volume.directory?.absolutePath
                } else {
                    try {
                        val getPath = volume.javaClass.getMethod("getPath")
                        getPath.invoke(volume) as String
                    } catch (_: Exception) { null }
                }
                
                if (path != null && path != internalPath && !path.contains("emulated")) {
                    val name = volume.getDescription(context) ?: "SD Card"
                    volumes.add(StorageVolume(name, path, true))
                }
            }
        }
        
        // Fallback for older versions
        if (volumes.size == 1) {
            context.getExternalFilesDirs(null).forEach { file ->
                if (file != null) {
                    val path = file.absolutePath
                    if (path.contains("/Android/data/")) {
                        val root = path.split("/Android/data/")[0]
                        if (root != internalPath && !volumes.any { it.path == root }) {
                            volumes.add(StorageVolume("SD Card", root, true))
                        }
                    }
                }
            }
        }
        
        return volumes.distinctBy { it.path }
    }

    fun stop() {
        try {
            server?.stop()
            server = null
            activePath = null
            Log.i("FtpServerManager", "FTP Server stopped")
        } catch (e: Exception) {
            Log.e("FtpServerManager", "Failed to stop FTP server", e)
        }
    }

    fun isRunning(): Boolean = server != null && !server!!.isStopped
}
