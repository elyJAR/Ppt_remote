package com.antigravity.pptremote

import android.content.Context
import android.net.Uri
import android.os.Environment
import androidx.documentfile.provider.DocumentFile
import java.io.File

object SafStorageHelper {
    
    fun isPathRestricted(path: String): Boolean {
        val normalized = path.replace('\\', '/')
        return normalized.contains("/Android/data", ignoreCase = true) || 
               normalized.contains("/Android/obb", ignoreCase = true)
    }

    fun getVolumeRoot(path: String): String? {
        val normalized = File(path).absolutePath
        val internal = Environment.getExternalStorageDirectory().absolutePath
        if (normalized.startsWith(internal)) {
            return internal
        }
        val file = File(path)
        var p = file
        while (p.parentFile != null && p.parentFile?.absolutePath != "/storage" && p.parentFile?.absolutePath != "/") {
            p = p.parentFile!!
        }
        if (p.parentFile?.absolutePath == "/storage") {
            return p.absolutePath
        }
        return null
    }

    fun getTreeUriForPath(context: Context, path: String): Uri? {
        val normalized = path.replace('\\', '/')
        val volumeRoot = getVolumeRoot(path) ?: return null
        val rootId = if (volumeRoot == Environment.getExternalStorageDirectory().absolutePath) "primary" else File(volumeRoot).name
        val isObb = normalized.contains("/Android/obb", ignoreCase = true)
        val folderName = if (isObb) "Android/obb" else "Android/data"
        
        val persistedPermissions = context.contentResolver.persistedUriPermissions
        return persistedPermissions.find { perm ->
            val permStr = perm.uri.toString()
            permStr.contains(rootId, ignoreCase = true) && 
            permStr.contains(folderName.replace("/", "%2F"), ignoreCase = true)
        }?.uri
    }

    fun getRelativePathUnderRestricted(path: String): String {
        val normalized = path.replace('\\', '/')
        val indexData = normalized.indexOf("/Android/data", ignoreCase = true)
        if (indexData != -1) {
            return normalized.substring(indexData + "/Android/data".length).trimStart('/')
        }
        val indexObb = normalized.indexOf("/Android/obb", ignoreCase = true)
        if (indexObb != -1) {
            return normalized.substring(indexObb + "/Android/obb".length).trimStart('/')
        }
        return ""
    }

    fun getDocumentFileForPath(context: Context, path: String): DocumentFile? {
        val treeUri = getTreeUriForPath(context, path) ?: return null
        val rootDoc = DocumentFile.fromTreeUri(context, treeUri) ?: return null
        val relativePath = getRelativePathUnderRestricted(path)
        if (relativePath.isEmpty()) {
            return rootDoc
        }
        var currentDoc = rootDoc
        for (segment in relativePath.split('/')) {
            if (segment.isEmpty()) continue
            currentDoc = currentDoc.findFile(segment) ?: return null
        }
        return currentDoc
    }

    fun getOrCreateDocumentFileForPath(context: Context, path: String, isDirectory: Boolean): DocumentFile? {
        val existing = getDocumentFileForPath(context, path)
        if (existing != null) return existing
        
        val file = File(path)
        val parentPath = file.parent ?: return null
        val parentDoc = getOrCreateDocumentFileForPath(context, parentPath, true) ?: return null
        
        return if (isDirectory) {
            parentDoc.createDirectory(file.name)
        } else {
            val extension = file.extension.lowercase()
            val mime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "application/octet-stream"
            parentDoc.createFile(mime, file.name)
        }
    }
}
