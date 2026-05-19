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

    fun start(context: Context, port: Int = 2121) {
        if (server != null) return

        try {
            val serverFactory = FtpServerFactory()
            val listenerFactory = ListenerFactory()
            listenerFactory.port = port

            serverFactory.addListener("default", listenerFactory.createListener())

            // Detect all available storage volumes
            val volumes = getStorageVolumes(context)
            Log.i("FtpServerManager", "Detected storage volumes: $volumes")

            // Anonymous user with write access to external storage
            val user = BaseUser()
            user.name = "anonymous"
            
            // Determine the best home directory
            // If we have an SD card, we want to show both internal and SD card.
            // On modern Android, /storage usually contains both.
            val internalStorage = Environment.getExternalStorageDirectory().absolutePath
            val hasExternalSd = volumes.any { it != internalStorage && !it.contains("emulated") }
            
            if (hasExternalSd) {
                val storageRoot = File("/storage")
                if (storageRoot.exists() && storageRoot.canRead()) {
                    user.homeDirectory = "/storage"
                    Log.i("FtpServerManager", "Multiple volumes detected, using /storage as root")
                } else {
                    user.homeDirectory = internalStorage
                    Log.i("FtpServerManager", "/storage not accessible, falling back to internal storage")
                }
            } else {
                user.homeDirectory = internalStorage
                Log.i("FtpServerManager", "Using internal storage as root: $internalStorage")
            }
            
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
        }
    }

    private fun getStorageVolumes(context: Context): List<String> {
        val paths = mutableListOf<String>()
        
        // Internal storage
        paths.add(Environment.getExternalStorageDirectory().absolutePath)
        
        // External SD cards
         if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
             val sm = context.getSystemService(Context.STORAGE_SERVICE) as StorageManager
             sm.storageVolumes.forEach { volume ->
                 if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                     volume.directory?.absolutePath?.let { paths.add(it) }
                 } else {
                     // Older versions (N, O, P, Q)
                     try {
                         val getPath = volume.javaClass.getMethod("getPath")
                         val path = getPath.invoke(volume) as String
                         if (path.isNotBlank()) paths.add(path)
                     } catch (e: Exception) {
                         // Fallback to simpler method if reflection fails
                         if (!volume.isPrimary) {
                             Log.d("FtpServerManager", "Detected non-primary volume: ${volume.uuid}")
                         }
                     }
                 }
             }
         }
        
        // Fallback for older versions or if directory is null
        val externalFilesDirs = context.getExternalFilesDirs(null)
        externalFilesDirs.forEach { file ->
            if (file != null) {
                val path = file.absolutePath
                if (path.contains("/Android/data/")) {
                    val root = path.split("/Android/data/")[0]
                    if (!paths.contains(root)) {
                        paths.add(root)
                    }
                }
            }
        }
        
        return paths.distinct()
    }

    fun stop() {
        try {
            server?.stop()
            server = null
            Log.i("FtpServerManager", "FTP Server stopped")
        } catch (e: Exception) {
            Log.e("FtpServerManager", "Failed to stop FTP server", e)
        }
    }

    fun isRunning(): Boolean = server != null && !server!!.isStopped
}
