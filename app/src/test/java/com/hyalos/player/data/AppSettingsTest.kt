package com.hyalos.player.data

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class AppSettingsTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun `defaults are the ones the app ships with`() {
        val defaults = AppSettings()
        assertEquals(100, defaults.thumbnailCacheMb)
        assertEquals(BrowserLayout.LIST, defaults.browserLayout)
    }

    @Test
    fun `settings round-trip`() {
        val settings = AppSettings(thumbnailCacheMb = 250, browserLayout = BrowserLayout.GRID)
        val decoded = json.decodeFromString(AppSettings.serializer(), json.encodeToString(settings))
        assertEquals(settings, decoded)
    }

    @Test
    fun `the layout is stored by name`() {
        // Pinned deliberately: kotlinx-serialization writes enum *names*, so
        // renaming LIST or GRID would make every already-saved settings file
        // fail to decode — which the corruption handler turns into "reset to
        // defaults", silently losing the user's choices.
        val encoded = json.encodeToString(AppSettings.serializer(), AppSettings(browserLayout = BrowserLayout.GRID))
        assertEquals(true, encoded.contains("\"GRID\""))
    }

    @Test
    fun `playback defaults are the ones that read as working`() {
        val defaults = AppSettings()
        // Stopping after each episode would read as broken, and distorting or
        // cropping the picture is something a user should choose, not meet.
        assertEquals(true, defaults.autoPlayNext)
        assertEquals(VideoScale.FIT, defaults.videoScale)
    }

    @Test
    fun `the video scale is stored by name too`() {
        val encoded = json.encodeToString(AppSettings.serializer(), AppSettings(videoScale = VideoScale.ZOOM))
        assertEquals(true, encoded.contains("\"ZOOM\""))
    }

    @Test
    fun `a settings file from an older version still loads`() {
        // The file that exists on disk today has no `browserLayout` key at all.
        val decoded = json.decodeFromString(AppSettings.serializer(), """{"thumbnailCacheMb":250}""")
        assertEquals(250, decoded.thumbnailCacheMb)
        assertEquals(BrowserLayout.LIST, decoded.browserLayout)
    }
}
