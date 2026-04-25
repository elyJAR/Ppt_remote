package com.antigravity.pptremote

import android.content.Context
import android.content.SharedPreferences
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class RemotePrefsTest {

    private lateinit var context: Context
    private lateinit var sharedPrefs: SharedPreferences
    private lateinit var editor: SharedPreferences.Editor

    @Before
    fun setUp() {
        editor = mockk(relaxed = true)
        sharedPrefs = mockk {
            every { getString("bridge_url", "") } returns ""
            every { getString("selected_presentation_id", null) } returns null
            every { edit() } returns editor
        }
        context = mockk {
            every { getSharedPreferences("ppt_remote_prefs", Context.MODE_PRIVATE) } returns sharedPrefs
        }
    }

    @Test
    fun `getBridgeUrl returns empty string when nothing stored`() {
        every { sharedPrefs.getString("bridge_url", "") } returns ""
        val result = RemotePrefs.getBridgeUrl(context)
        assertEquals("", result)
    }

    @Test
    fun `getBridgeUrl returns stored value`() {
        every { sharedPrefs.getString("bridge_url", "") } returns "http://192.168.1.10:8787"
        val result = RemotePrefs.getBridgeUrl(context)
        assertEquals("http://192.168.1.10:8787", result)
    }

    @Test
    fun `setBridgeUrl writes to shared prefs`() {
        every { editor.putString(any(), any()) } returns editor
        RemotePrefs.setBridgeUrl(context, "http://10.0.0.5:8787")
        verify { editor.putString("bridge_url", "http://10.0.0.5:8787") }
        verify { editor.apply() }
    }

    @Test
    fun `getSelectedPresentationId returns null when nothing stored`() {
        every { sharedPrefs.getString("selected_presentation_id", null) } returns null
        val result = RemotePrefs.getSelectedPresentationId(context)
        assertNull(result)
    }

    @Test
    fun `getSelectedPresentationId returns stored path`() {
        every { sharedPrefs.getString("selected_presentation_id", null) } returns "C:\\Slides\\demo.pptx"
        val result = RemotePrefs.getSelectedPresentationId(context)
        assertEquals("C:\\Slides\\demo.pptx", result)
    }

    @Test
    fun `setSelectedPresentationId writes to shared prefs`() {
        every { editor.putString(any(), any()) } returns editor
        RemotePrefs.setSelectedPresentationId(context, "C:\\Slides\\demo.pptx")
        verify { editor.putString("selected_presentation_id", "C:\\Slides\\demo.pptx") }
        verify { editor.apply() }
    }

    @Test
    fun `setSelectedPresentationId with null clears the value`() {
        every { editor.putString(any(), any()) } returns editor
        RemotePrefs.setSelectedPresentationId(context, null)
        verify { editor.putString("selected_presentation_id", null) }
        verify { editor.apply() }
    }
}
