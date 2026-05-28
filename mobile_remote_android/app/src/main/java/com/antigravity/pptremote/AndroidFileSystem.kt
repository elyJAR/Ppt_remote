package com.antigravity.pptremote

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.documentfile.provider.DocumentFile
import org.apache.ftpserver.filesystem.nativefs.NativeFileSystemFactory
import org.apache.ftpserver.ftplet.FileSystemView
import org.apache.ftpserver.ftplet.FtpFile
import org.apache.ftpserver.ftplet.User
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

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
        delegateView.dispose()
    }
}

class AndroidFtpFile(
    private val context: Context,
    val physicalPath: String,
    private val virtualPath: String,
    private val homeDir: String
) : FtpFile {

    private val cleanPath: String
        get() = physicalPath.replace('\\', '/').trimEnd('/')

    private fun isPackageFolder(path: String): Boolean {
        val parts = path.split('/')
        if (parts.size >= 3) {
            val parent2 = parts[parts.size - 2]
            val parent3 = parts[parts.size - 3]
            return (parent2.equals("data", ignoreCase = true) || parent2.equals("obb", ignoreCase = true)) &&
                   parent3.equals("Android", ignoreCase = true)
        }
        return false
    }

    private fun getDoc(): DocumentFile? {
        return SafStorageHelper.getDocumentFileForPath(context, physicalPath)
    }

    override fun getAbsolutePath(): String {
        return virtualPath
    }

    override fun getName(): String {
        return File(physicalPath).name
    }

    override fun isHidden(): Boolean {
        return false
    }

    override fun isDirectory(): Boolean {
        val path = cleanPath
        if (path.endsWith("/Android/data", ignoreCase = true) || path.endsWith("/Android/obb", ignoreCase = true)) {
            return true
        }
        val ownPkg = context.packageName
        if (path.endsWith("/Android/data/$ownPkg", ignoreCase = true) || path.endsWith("/Android/obb/$ownPkg", ignoreCase = true)) {
            return true
        }
        if (isPackageFolder(path)) {
            return true
        }
        val doc = getDoc()
        if (doc != null) {
            return doc.isDirectory
        }
        return File(physicalPath).isDirectory
    }

    override fun isFile(): Boolean {
        val path = cleanPath
        if (path.endsWith("/Android/data", ignoreCase = true) || path.endsWith("/Android/obb", ignoreCase = true)) {
            return false
        }
        val ownPkg = context.packageName
        if (path.endsWith("/Android/data/$ownPkg", ignoreCase = true) || path.endsWith("/Android/obb/$ownPkg", ignoreCase = true)) {
            return false
        }
        if (isPackageFolder(path)) {
            return false
        }
        val doc = getDoc()
        if (doc != null) {
            return doc.isFile
        }
        return File(physicalPath).isFile
    }

    override fun doesExist(): Boolean {
        val path = cleanPath
        if (path.endsWith("/Android/data", ignoreCase = true) || path.endsWith("/Android/obb", ignoreCase = true)) {
            return true
        }
        val ownPkg = context.packageName
        if (path.endsWith("/Android/data/$ownPkg", ignoreCase = true) || path.endsWith("/Android/obb/$ownPkg", ignoreCase = true)) {
            return true
        }
        if (isPackageFolder(path)) {
            return true
        }
        return getDoc() != null || File(physicalPath).exists()
    }

    override fun getSize(): Long {
        return getDoc()?.length() ?: File(physicalPath).length()
    }

    override fun getLastModified(): Long {
        return getDoc()?.lastModified() ?: File(physicalPath).lastModified()
    }

    override fun setLastModified(lastModified: Long): Boolean {
        return false
    }

    override fun getPhysicalFile(): Any? {
        return File(physicalPath)
    }

    override fun isReadable(): Boolean {
        val path = cleanPath
        val ownPkg = context.packageName
        if (path.endsWith("/Android/data/$ownPkg", ignoreCase = true) || path.endsWith("/Android/obb/$ownPkg", ignoreCase = true)) {
            return true
        }
        if (isPackageFolder(path)) {
            return true
        }
        return getDoc() != null || File(physicalPath).canRead()
    }

    override fun isWritable(): Boolean {
        val path = cleanPath
        val ownPkg = context.packageName
        if (path.endsWith("/Android/data/$ownPkg", ignoreCase = true) || path.endsWith("/Android/obb/$ownPkg", ignoreCase = true)) {
            return false
        }
        if (isPackageFolder(path)) {
            return false
        }
        return getDoc() != null || File(physicalPath).canWrite()
    }

    override fun isRemovable(): Boolean {
        val path = cleanPath
        val isRoot = path.endsWith("/Android/data", ignoreCase = true) || path.endsWith("/Android/obb", ignoreCase = true)
        val ownPkg = context.packageName
        val isOwnAppFolder = path.endsWith("/Android/data/$ownPkg", ignoreCase = true) ||
                             path.endsWith("/Android/obb/$ownPkg", ignoreCase = true)
        if (isPackageFolder(path)) {
            return false
        }
        return !isRoot && !isOwnAppFolder && (SafStorageHelper.getTreeUriForPath(context, physicalPath) != null || File(physicalPath).exists())
    }

    override fun getOwnerName(): String {
        return "anonymous"
    }

    override fun getGroupName(): String {
        return "anonymous"
    }

    override fun getLinkCount(): Int {
        return if (isDirectory) 3 else 1
    }

    override fun delete(): Boolean {
        val doc = getDoc()
        if (doc != null) {
            return doc.delete()
        }
        return File(physicalPath).delete()
    }

    override fun mkdir(): Boolean {
        val doc = SafStorageHelper.getOrCreateDocumentFileForPath(context, physicalPath, true)
        if (doc != null) return true
        return File(physicalPath).mkdir()
    }

    override fun move(destination: FtpFile): Boolean {
        val doc = getDoc() ?: return false
        
        val destPhysicalPath = if (destination is AndroidFtpFile) {
            destination.physicalPath
        } else {
            val destVirtual = destination.absolutePath
            val cleanDestVirtual = if (destVirtual.startsWith("/")) destVirtual.substring(1) else destVirtual
            File(homeDir, cleanDestVirtual).absolutePath
        }

        val srcFile = File(physicalPath)
        val destFile = File(destPhysicalPath)

        if (srcFile.parent == destFile.parent) {
            return doc.renameTo(destFile.name)
        }

        val destParentDoc = SafStorageHelper.getOrCreateDocumentFileForPath(context, destFile.parent ?: "", true) ?: return false
        return if (doc.isFile) {
            val destDoc = destParentDoc.createFile(doc.type ?: "application/octet-stream", destFile.name) ?: return false
            try {
                context.contentResolver.openInputStream(doc.uri)?.use { input ->
                    context.contentResolver.openOutputStream(destDoc.uri)?.use { output ->
                        input.copyTo(output)
                    }
                }
                doc.delete()
            } catch (e: Exception) {
                false
            }
        } else if (doc.isDirectory) {
            recursiveMove(doc, destParentDoc, destFile.name)
        } else {
            false
        }
    }

    private fun recursiveMove(srcDir: DocumentFile, destParent: DocumentFile, name: String): Boolean {
        val destDir = destParent.createDirectory(name) ?: return false
        var success = true
        for (file in srcDir.listFiles()) {
            if (file.isFile) {
                val destFile = destDir.createFile(file.type ?: "application/octet-stream", file.name ?: "")
                if (destFile == null) {
                    success = false
                    continue
                }
                try {
                    context.contentResolver.openInputStream(file.uri)?.use { input ->
                        context.contentResolver.openOutputStream(destFile.uri)?.use { output ->
                            input.copyTo(output)
                        }
                    }
                    file.delete()
                } catch (e: Exception) {
                    success = false
                }
            } else if (file.isDirectory) {
                if (!recursiveMove(file, destDir, file.name ?: "")) {
                    success = false
                }
            }
        }
        if (success) {
            srcDir.delete()
        }
        return success
    }

    override fun createInputStream(offset: Long): InputStream {
        val doc = getDoc()
        if (doc != null) {
            val pfd = context.contentResolver.openFileDescriptor(doc.uri, "r") ?: throw IOException("Failed to open file descriptor for $physicalPath")
            val stream = ParcelFileDescriptor.AutoCloseInputStream(pfd)
            if (offset > 0) {
                stream.channel.position(offset)
            }
            return stream
        }
        val file = File(physicalPath)
        if (!file.exists()) throw IOException("File not found: $physicalPath")
        val stream = java.io.FileInputStream(file)
        if (offset > 0) {
            stream.channel.position(offset)
        }
        return stream
    }

    override fun createOutputStream(offset: Long): OutputStream {
        val doc = SafStorageHelper.getOrCreateDocumentFileForPath(context, physicalPath, false)
        if (doc != null) {
            val mode = if (offset == 0L) "wt" else "rw"
            val pfd = context.contentResolver.openFileDescriptor(doc.uri, mode) ?: throw IOException("Failed to open file descriptor for $physicalPath")
            val stream = ParcelFileDescriptor.AutoCloseOutputStream(pfd)
            if (offset > 0) {
                stream.channel.position(offset)
            }
            return stream
        }
        val file = File(physicalPath)
        val stream = java.io.FileOutputStream(file, offset > 0)
        if (offset > 0) {
            stream.channel.position(offset)
        }
        return stream
    }

    override fun listFiles(): List<FtpFile> {
        val normalized = physicalPath.replace('\\', '/').trimEnd('/')
        val isRootData = normalized.endsWith("/Android/data", ignoreCase = true)
        val isRootObb = normalized.endsWith("/Android/obb", ignoreCase = true)
        val ownPkg = context.packageName
        val isOwnAppFolder = normalized.endsWith("/Android/data/$ownPkg", ignoreCase = true) ||
                             normalized.endsWith("/Android/obb/$ownPkg", ignoreCase = true)
        
        if ((isRootData || isRootObb) && SafStorageHelper.getTreeUriForPath(context, physicalPath) == null) {
            val pkgs = try {
                val pm = context.packageManager
                pm.getInstalledPackages(0).map { it.packageName }
            } catch (e: Exception) {
                emptyList()
            }.toMutableList()
            if (!pkgs.contains(ownPkg)) {
                pkgs.add(ownPkg)
            }
            return pkgs.distinct().map { pkgName ->
                val childPhysical = File(physicalPath, pkgName).absolutePath
                val childVirtual = if (virtualPath.endsWith("/")) "$virtualPath$pkgName" else "$virtualPath/$pkgName"
                AndroidFtpFile(context, childPhysical, childVirtual, homeDir)
            }
        }

        if (isOwnAppFolder) {
            try {
                context.getExternalFilesDir(null)
                context.externalCacheDir
            } catch (_: Exception) {}

            val folder = File(physicalPath)
            val subdirs = listOf("files", "cache")
            return subdirs.map { dirName ->
                val childPhysical = File(folder, dirName).absolutePath
                val childVirtual = if (virtualPath.endsWith("/")) "$virtualPath$dirName" else "$virtualPath/$dirName"
                AndroidFtpFile(context, childPhysical, childVirtual, homeDir)
            }
        }

        val doc = getDoc()
        if (doc != null) {
            return doc.listFiles().map { child ->
                val childName = child.name ?: ""
                val childPhysicalPath = File(physicalPath, childName).absolutePath
                val childVirtualPath = if (virtualPath.endsWith("/")) "$virtualPath$childName" else "$virtualPath/$childName"
                AndroidFtpFile(context, childPhysicalPath, childVirtualPath, homeDir)
            }
        }

        val folder = File(physicalPath)
        return folder.listFiles()?.map { child ->
            val childPhysicalPath = child.absolutePath
            val childVirtualPath = if (virtualPath.endsWith("/")) "$virtualPath${child.name}" else "$virtualPath/${child.name}"
            AndroidFtpFile(context, childPhysicalPath, childVirtualPath, homeDir)
        } ?: emptyList()
    }
}
