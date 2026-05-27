package com.antigravity.pptremote

import android.content.Context
import android.net.Uri
import android.os.Environment
import androidx.documentfile.provider.DocumentFile
import java.io.File

object SafStorageHelper {
    
    var appPackageName: String = "com.antigravity.pptremote"

    fun isPathRestricted(path: String): Boolean {
        val normalized = path.replace('\\', '/')
        if (normalized.contains("/Android/data/$appPackageName", ignoreCase = true) || 
            normalized.contains("/Android/obb/$appPackageName", ignoreCase = true)) {
            return false
        }
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

    data class MatchingTree(val treeUri: Uri, val relativePath: String)

    fun findMatchingTree(context: Context, path: String): MatchingTree? {
        val normalizedPath = path.replace('\\', '/').trimEnd('/')
        val persisted = context.contentResolver.persistedUriPermissions
        
        var bestMatch: MatchingTree? = null
        var longestMatchLen = -1

        for (perm in persisted) {
            val uriStr = perm.uri.toString()
            val treePart = uriStr.substringAfter("/tree/", "")
            if (treePart.isEmpty()) continue
            
            val decodedPart = Uri.decode(treePart)
            val colonIdx = decodedPart.indexOf(':')
            if (colonIdx == -1) continue
            
            val rootId = decodedPart.substring(0, colonIdx)
            val relPath = decodedPart.substring(colonIdx + 1)
            
            val volumeRoot = if (rootId.equals("primary", ignoreCase = true)) {
                Environment.getExternalStorageDirectory().absolutePath
            } else {
                "/storage/$rootId"
            }
            
            val cleanRelPath = relPath.trim('/')
            val treePhysicalPath = if (cleanRelPath.isEmpty()) {
                File(volumeRoot).absolutePath
            } else {
                File(volumeRoot, cleanRelPath).absolutePath
            }.replace('\\', '/').trimEnd('/')
            
            if (normalizedPath.equals(treePhysicalPath, ignoreCase = true)) {
                return MatchingTree(perm.uri, "")
            } else if (normalizedPath.startsWith("$treePhysicalPath/", ignoreCase = true)) {
                val matchLen = treePhysicalPath.length
                if (matchLen > longestMatchLen) {
                    longestMatchLen = matchLen
                    val subPath = normalizedPath.substring(matchLen + 1).trimStart('/')
                    bestMatch = MatchingTree(perm.uri, subPath)
                }
            }
        }
        return bestMatch
    }

    fun getTreeUriForPath(context: Context, path: String): Uri? {
        return findMatchingTree(context, path)?.treeUri
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
        val match = findMatchingTree(context, path) ?: return null
        val rootDoc = DocumentFile.fromTreeUri(context, match.treeUri) ?: return null
        if (match.relativePath.isEmpty()) {
            return rootDoc
        }
        var currentDoc = rootDoc
        for (segment in match.relativePath.split('/')) {
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
