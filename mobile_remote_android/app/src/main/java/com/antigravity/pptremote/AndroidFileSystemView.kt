package com.antigravity.pptremote

import android.content.Context
import org.apache.ftpserver.filesystem.nativefs.NativeFileSystemFactory
import org.apache.ftpserver.ftplet.FileSystemView
import org.apache.ftpserver.ftplet.FtpFile
import org.apache.ftpserver.ftplet.User
import java.io.File

class AndroidFileSystemView(
    private val context: Context,
    private val homeDir: String,
    private val user: User
) : FileSystemView {

    private val delegateView: FileSystemView = NativeFileSystemFactory().createFileSystemView(user)
    private var currDir: String = "/"

    private fun resolvePath(dir: String): String {
        if (dir.startsWith("/")) {
            return normalizePath(dir)
        }
        val combined = if (currDir.endsWith("/")) "$currDir$dir" else "$currDir/$dir"
        return normalizePath(combined)
    }

    private fun normalizePath(path: String): String {
        val segments = path.replace('\\', '/').split('/')
        val resolvedSegments = mutableListOf<String>()
        for (segment in segments) {
            if (segment.isEmpty() || segment == ".") continue
            if (segment == "..") {
                if (resolvedSegments.isNotEmpty()) {
                    resolvedSegments.removeAt(resolvedSegments.size - 1)
                }
            } else {
                resolvedSegments.add(segment)
            }
        }
        return "/" + resolvedSegments.joinToString("/")
    }

    override fun getHomeDirectory(): FtpFile {
        return getFile("/")
    }

    override fun getWorkingDirectory(): FtpFile {
        return getFile(currDir)
    }

    override fun changeWorkingDirectory(dir: String): Boolean {
        val targetVirtual = resolvePath(dir)
        val targetFile = getFile(targetVirtual)
        if (targetFile.doesExist() && targetFile.isDirectory) {
            currDir = targetVirtual
            try {
                delegateView.changeWorkingDirectory(targetVirtual)
            } catch (_: Exception) {}
            return true
        }
        return false
    }

    override fun getFile(file: String): FtpFile {
        val virtualPath = resolvePath(file)
        val cleanVirtual = if (virtualPath.startsWith("/")) virtualPath.substring(1) else virtualPath
        val physicalPath = File(homeDir, cleanVirtual).absolutePath

        val normalized = physicalPath.replace('\\', '/').trimEnd('/')
        val ownPkg = context.packageName
        val isRoot = normalized.endsWith("/Android/data", ignoreCase = true) || normalized.endsWith("/Android/obb", ignoreCase = true)
        val isOwnAppFolder = normalized.endsWith("/Android/data/$ownPkg", ignoreCase = true) ||
                             normalized.endsWith("/Android/obb/$ownPkg", ignoreCase = true)

        return if (SafStorageHelper.isPathRestricted(context, physicalPath) || isRoot || isOwnAppFolder) {
            AndroidFtpFile(context, physicalPath, virtualPath, homeDir)
        } else {
            delegateView.getFile(virtualPath)
        }
    }

    override fun isRandomAccessible(): Boolean {
        return true
    }

    override fun dispose() {
        // delegateView.dispose() // Commented to bypass Kotlin 2.0 compiler crash
    }
}
