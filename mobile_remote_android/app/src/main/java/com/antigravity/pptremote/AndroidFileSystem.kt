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

        return if (SafStorageHelper.isPathRestricted(physicalPath)) {
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
        val normalized = physicalPath.replace('\\', '/')
        if (normalized.endsWith("/Android/data", ignoreCase = true) || normalized.endsWith("/Android/obb", ignoreCase = true)) {
            return true
        }
        val doc = getDoc()
        return doc?.isDirectory == true
    }

    override fun isFile(): Boolean {
        val normalized = physicalPath.replace('\\', '/')
        if (normalized.endsWith("/Android/data", ignoreCase = true) || normalized.endsWith("/Android/obb", ignoreCase = true)) {
            return false
        }
        val doc = getDoc()
        return doc?.isFile == true
    }

    override fun doesExist(): Boolean {
        val normalized = physicalPath.replace('\\', '/')
        if (normalized.endsWith("/Android/data", ignoreCase = true) || normalized.endsWith("/Android/obb", ignoreCase = true)) {
            return true
        }
        return getDoc() != null
    }

    override fun getSize(): Long {
        return getDoc()?.length() ?: 0L
    }

    override fun getLastModified(): Long {
        return getDoc()?.lastModified() ?: 0L
    }

    override fun setLastModified(lastModified: Long): Boolean {
        return false
    }

    override fun isReadable(): Boolean {
        return SafStorageHelper.getTreeUriForPath(context, physicalPath) != null
    }

    override fun isWritable(): Boolean {
        return SafStorageHelper.getTreeUriForPath(context, physicalPath) != null
    }

    override fun isRemovable(): Boolean {
        val normalized = physicalPath.replace('\\', '/')
        val isRoot = normalized.endsWith("/Android/data", ignoreCase = true) || normalized.endsWith("/Android/obb", ignoreCase = true)
        return !isRoot && SafStorageHelper.getTreeUriForPath(context, physicalPath) != null
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
        val doc = getDoc() ?: return false
        return doc.delete()
    }

    override fun mkdir(): Boolean {
        return SafStorageHelper.getOrCreateDocumentFileForPath(context, physicalPath, true) != null
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
        val doc = getDoc() ?: throw IOException("File not found: $physicalPath")
        val pfd = context.contentResolver.openFileDescriptor(doc.uri, "r") ?: throw IOException("Failed to open file descriptor for $physicalPath")
        val stream = ParcelFileDescriptor.AutoCloseInputStream(pfd)
        if (offset > 0) {
            stream.channel.position(offset)
        }
        return stream
    }

    override fun createOutputStream(offset: Long): OutputStream {
        val doc = SafStorageHelper.getOrCreateDocumentFileForPath(context, physicalPath, false)
            ?: throw IOException("Failed to create/open file: $physicalPath")
        val mode = if (offset == 0L) "wt" else "rw"
        val pfd = context.contentResolver.openFileDescriptor(doc.uri, mode) ?: throw IOException("Failed to open file descriptor for $physicalPath")
        val stream = ParcelFileDescriptor.AutoCloseOutputStream(pfd)
        if (offset > 0) {
            stream.channel.position(offset)
        }
        return stream
    }

    override fun listFiles(): List<FtpFile> {
        val doc = getDoc() ?: return emptyList()
        return doc.listFiles().map { child ->
            val childName = child.name ?: ""
            val childPhysicalPath = File(physicalPath, childName).absolutePath
            val childVirtualPath = if (virtualPath.endsWith("/")) "$virtualPath$childName" else "$virtualPath/$childName"
            AndroidFtpFile(context, childPhysicalPath, childVirtualPath, homeDir)
        }
    }
}
