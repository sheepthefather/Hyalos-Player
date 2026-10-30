package com.hyalos.player.files

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.Charset

/**
 * Bytes to text and back.
 *
 * Every case here is a file that would be silently damaged by a decoder or
 * encoder that guessed: the point is not that the right characters come out,
 * it is that the *same bytes* come back on save.
 */
class TextCodecTest {

    private val gbk = Charset.forName("GBK")

    private fun text(bytes: ByteArray): TextFile {
        val decoded = decodeText(bytes)
        assertTrue("expected text, got $decoded", decoded is DecodeResult.Text)
        return (decoded as DecodeResult.Text).file
    }

    private fun bytesOf(file: TextFile, text: String = file.text): ByteArray {
        val encoded = encodeText(file, text)
        assertTrue("expected bytes, got $encoded", encoded is EncodeResult.Bytes)
        return (encoded as EncodeResult.Bytes).value
    }

    @Test
    fun `utf-8 without a bom round-trips byte for byte`() {
        val original = "第一行\nsecond line\n".toByteArray()
        val file = text(original)
        assertEquals(Charsets.UTF_8, file.charset)
        assertEquals(0, file.bom.size)
        assertEquals("\n", file.eol)
        assertArrayEquals(original, bytesOf(file))
    }

    @Test
    fun `a utf-8 bom is stripped and put back`() {
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        val original = bom + "hello".toByteArray()
        val file = text(original)
        assertArrayEquals(bom, file.bom)
        assertEquals("hello", file.text)
        assertArrayEquals(original, bytesOf(file))
    }

    @Test
    fun `utf-16 text is not mistaken for binary`() {
        // Half of a UTF-16 file's bytes are NUL. Checking for NUL before the
        // BOM would refuse every one of them.
        val little = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "hi".toByteArray(Charsets.UTF_16LE)
        val file = text(little)
        assertEquals(Charsets.UTF_16LE, file.charset)
        assertEquals("hi", file.text)
        assertArrayEquals(little, bytesOf(file))

        val big = byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + "hi".toByteArray(Charsets.UTF_16BE)
        assertEquals("hi", text(big).text)
        assertArrayEquals(big, bytesOf(text(big)))
    }

    @Test
    fun `gbk is the fallback for bytes that are not utf-8`() {
        val original = "中文内容".toByteArray(gbk)
        val file = text(original)
        assertEquals(gbk, file.charset)
        assertEquals("中文内容", file.text)
        // The important half: saving without a change must not "upgrade" the
        // file to UTF-8 for the next tool that reads it.
        assertArrayEquals(original, bytesOf(file))
    }

    @Test
    fun `a character the file's encoding cannot hold is reported, not replaced`() {
        val file = text("中文".toByteArray(gbk))
        val result = encodeText(file, "中文😀")
        assertEquals(EncodeResult.Unmappable, result)
    }

    @Test
    fun `a nul byte with no bom is binary`() {
        assertEquals(DecodeResult.Binary, decodeText(byteArrayOf(0x50, 0x4B, 0x00, 0x04)))
    }

    @Test
    fun `neither utf-8 nor gbk is unsupported`() {
        // A lone 0xFF can start nothing in either encoding.
        assertEquals(DecodeResult.Unsupported, decodeText(byteArrayOf(0xFF.toByte(), 0xFF.toByte())))
    }

    @Test
    fun `over the limit is refused before it is decoded`() {
        val big = ByteArray(MAX_EDIT_BYTES + 1) { 'a'.code.toByte() }
        assertEquals(DecodeResult.TooLarge, decodeText(big))
        // Exactly at the limit is still a file the editor will open.
        assertNotNull(decodeText(ByteArray(MAX_EDIT_BYTES) { 'a'.code.toByte() }) as? DecodeResult.Text)
    }

    @Test
    fun `crlf is detected, normalised for editing, and restored on save`() {
        val original = "one\r\ntwo\r\n".toByteArray()
        val file = text(original)
        assertEquals("\r\n", file.eol)
        assertEquals("one\ntwo\n", file.text)
        assertArrayEquals(original, bytesOf(file))
    }

    @Test
    fun `a lone carriage return is treated as a line ending too`() {
        val file = text("one\rtwo\r".toByteArray())
        assertEquals("\r", file.eol)
        assertEquals("one\ntwo\n", file.text)
        assertArrayEquals("one\rtwo\r".toByteArray(), bytesOf(file))
    }

    @Test
    fun `line endings pasted into the field are re-emitted as the file's own`() {
        // The field can receive text from anywhere; a save must not mix the two
        // conventions because that is how a file starts rendering oddly in
        // somebody else's editor.
        val file = text("one\r\ntwo\r\n".toByteArray())
        val edited = "one\r\nTWO\nthree"
        assertArrayEquals("one\r\nTWO\r\nthree".toByteArray(), bytesOf(file, edited))
    }

    @Test
    fun `an empty file is text`() {
        val file = text(ByteArray(0))
        assertEquals("", file.text)
        assertArrayEquals(ByteArray(0), bytesOf(file))
    }

    @Test
    fun `a file whose bytes contradict its bom is not re-guessed`() {
        // A UTF-8 BOM followed by malformed UTF-8 is a broken file, not a GBK
        // file wearing a BOM: inventing an encoding it never claimed would
        // produce text that saves back to different bytes.
        val broken = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte(), 0xFF.toByte(), 0xFF.toByte())
        assertEquals(DecodeResult.Unsupported, decodeText(broken))
    }
}
