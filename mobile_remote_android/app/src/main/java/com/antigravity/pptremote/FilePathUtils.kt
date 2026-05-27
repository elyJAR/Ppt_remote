package com.antigravity.pptremote

import java.io.File

object FilePathUtils {
    fun relativeFtpPath(rootPath: String, currentPath: String): String {
        val normalizedRoot = File(rootPath).absolutePath.trimEnd('/', '\\')
        val normalizedCurrent = File(currentPath).absolutePath
        if (normalizedCurrent == normalizedRoot) return ""
        val relative = normalizedCurrent.removePrefix(normalizedRoot).trimStart('/', '\\')
        return relative.replace('\\', '/')
    }

    fun isPathWithinRoot(candidatePath: String, rootPath: String): Boolean {
        val normalizedRoot = File(rootPath).absolutePath.trimEnd('/', '\\')
        val normalizedCandidate = File(candidatePath).absolutePath
        return normalizedCandidate == normalizedRoot || normalizedCandidate.startsWith("$normalizedRoot/") || normalizedCandidate.startsWith("$normalizedRoot\\")
    }
}
