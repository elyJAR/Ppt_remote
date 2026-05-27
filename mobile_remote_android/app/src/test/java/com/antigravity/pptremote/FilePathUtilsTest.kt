package com.antigravity.pptremote

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class FilePathUtilsTest {

    @Test
    fun relativePath_sameRoot_returnsEmpty() {
        val root = File("/storage/emulated/0/DCIM").absolutePath
        val current = root
        val rel = FilePathUtils.relativeFtpPath(root, current)
        assertEquals("", rel)
    }

    @Test
    fun relativePath_nested_returnsRelative() {
        val root = File("/storage/emulated/0/DCIM").absolutePath
        val current = File(root, "Camera/2024").absolutePath
        val rel = FilePathUtils.relativeFtpPath(root, current)
        assertEquals("Camera/2024", rel)
    }

    @Test
    fun relativePath_windowsStyleRoots_normalizesSlashes() {
        val root = File("C:/Photos").absolutePath
        val current = File(root, "Album\Summer").absolutePath
        val rel = FilePathUtils.relativeFtpPath(root, current)
        assertEquals("Album/Summer", rel)
    }

    @Test
    fun isPathWithinRoot_sameRoot_true() {
        val root = File("/storage/emulated/0").absolutePath
        val candidate = root
        assertTrue(FilePathUtils.isPathWithinRoot(candidate, root))
    }

    @Test
    fun isPathWithinRoot_child_true() {
        val root = File("/storage/emulated/0").absolutePath
        val candidate = File(root, "Documents/Notes").absolutePath
        assertTrue(FilePathUtils.isPathWithinRoot(candidate, root))
    }

    @Test
    fun isPathWithinRoot_outside_false() {
        val root = File("/storage/emulated/0/Pictures").absolutePath
        val candidate = File("/storage/emulated/0/DCIM").absolutePath
        assertFalse(FilePathUtils.isPathWithinRoot(candidate, root))
    }
}
