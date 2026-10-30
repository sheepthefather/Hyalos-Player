package com.hyalos.player.files

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What counts as a text file.
 *
 * The extension is the only thing known before the file is read, so it is the
 * whole of this decision — and the two suffixes this app writes are the entries
 * that must never come back as files to edit.
 */
class TextFilesTest {

    @Test
    fun `ordinary text extensions are accepted`() {
        for (name in listOf("notes.txt", "README.md", "config.json", "movie.srt", "x.ass", "y.vtt", "a.log")) {
            assertTrue(name, isTextFile(name))
        }
    }

    @Test
    fun `the extension is case-insensitive`() {
        // Shares hand back whatever the file was named on the other machine.
        assertTrue(isTextFile("NOTES.TXT"))
        assertTrue(isTextFile("ReadMe.MarkDown"))
    }

    @Test
    fun `media and unknown extensions are not text`() {
        for (name in listOf("film.mkv", "song.flac", "archive.7z", "noext", "photo.jpg")) {
            assertFalse(name, isTextFile(name))
        }
    }

    @Test
    fun `this app's own working files are never text`() {
        // The editor must not offer to open the half-written copy of a save.
        assertFalse(isTextFile("notes.txt.hyalos-edit"))
        assertFalse(isTextFile("notes.txt.hyalos-old"))
    }

    @Test
    fun `a nul byte means binary only where no BOM said otherwise`() {
        assertTrue(looksBinary(byteArrayOf(0x41, 0x00, 0x42)))
        assertFalse(looksBinary("plain ascii".toByteArray()))
    }
}
