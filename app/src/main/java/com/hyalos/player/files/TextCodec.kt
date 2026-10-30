package com.hyalos.player.files

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * Bytes to text and back, without changing what was there.
 *
 * A text editor that normalises the encoding or the line endings corrupts
 * files quietly: the user sees their text, saves, and the file that their
 * other tools read is not the one they had. So the encoding, the byte-order
 * mark and the line terminator are all kept, and the editable form is a
 * separate, normalised view of the same content.
 */

/** The most the editor will load. Beyond this it is not an editing session. */
internal const val MAX_EDIT_BYTES = 1 shl 20

/** GBK, by name: it is present on Android and on the JVM, and has no constant. */
private val GBK: Charset = Charset.forName("GBK")

/**
 * A text file's content and everything needed to write it back unchanged.
 *
 * Public because the editor screen shows [charset] and the save's failures talk
 * about [eol]; the two functions that produce and consume it stay internal.
 */
data class TextFile(
    /** The content, with every line ending normalised to `\n`. */
    val text: String,
    val charset: Charset,
    /** Empty when the file had none. Put back verbatim on save. */
    val bom: ByteArray,
    /** What a newline in this file is: `"\n"`, `"\r\n"` or `"\r"`. */
    val eol: String,
)

internal sealed interface DecodeResult {
    data class Text(val file: TextFile) : DecodeResult

    /** A NUL byte in a BOM-less payload: not something the editor may touch. */
    data object Binary : DecodeResult

    data object TooLarge : DecodeResult

    /** Neither UTF-8 nor GBK: the editor cannot preserve what it does not know. */
    data object Unsupported : DecodeResult
}

private val UTF8_BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
private val UTF16LE_BOM = byteArrayOf(0xFF.toByte(), 0xFE.toByte())
private val UTF16BE_BOM = byteArrayOf(0xFE.toByte(), 0xFF.toByte())

/**
 * Decode [bytes] into something the editor can show.
 *
 * **The BOM is consulted before the NUL test**, because UTF-16 text contains
 * NUL bytes as a matter of course. Without a BOM the order is UTF-8 first —
 * strict, not replacing — and then GBK, which is what a great many Chinese
 * text files on these shares are; a strict decoder is what makes the fallback
 * meaningful, since a lenient UTF-8 decode succeeds on almost any bytes and
 * would show replacement characters instead of trying GBK.
 */
internal fun decodeText(bytes: ByteArray, maxBytes: Int = MAX_EDIT_BYTES): DecodeResult {
    if (bytes.size > maxBytes) return DecodeResult.TooLarge

    val bom: ByteArray
    val declared: Charset
    val body: ByteArray
    when {
        bytes.startsWith(UTF8_BOM) -> {
            bom = UTF8_BOM
            declared = Charsets.UTF_8
            body = bytes.copyOfRange(UTF8_BOM.size, bytes.size)
        }
        bytes.startsWith(UTF16LE_BOM) -> {
            bom = UTF16LE_BOM
            declared = Charsets.UTF_16LE
            body = bytes.copyOfRange(UTF16LE_BOM.size, bytes.size)
        }
        bytes.startsWith(UTF16BE_BOM) -> {
            bom = UTF16BE_BOM
            declared = Charsets.UTF_16BE
            body = bytes.copyOfRange(UTF16BE_BOM.size, bytes.size)
        }
        else -> {
            bom = ByteArray(0)
            declared = Charsets.UTF_8
            body = bytes
        }
    }

    if (bom.isEmpty() && looksBinary(body)) return DecodeResult.Binary

    // A BOM is a promise: if the bytes contradict it, guessing again would be
    // inventing an encoding the file did not claim.
    var charset = declared
    var decoded = strictDecode(body, declared)
    if (decoded == null && bom.isEmpty()) {
        charset = GBK
        decoded = strictDecode(body, GBK)
    }
    val text = decoded ?: return DecodeResult.Unsupported

    // The file's own convention, detected once and put back on save. A file with
    // mixed endings is normalised to the one it mostly uses; there is no
    // representation that keeps both and still behaves like a text field.
    val eol = when {
        text.contains("\r\n") -> "\r\n"
        text.contains('\r') -> "\r"
        else -> "\n"
    }
    return DecodeResult.Text(
        TextFile(
            text = text.replace("\r\n", "\n").replace('\r', '\n'),
            charset = charset,
            bom = bom,
            eol = eol,
        ),
    )
}

/** What a save can make of the edited text. */
internal sealed interface EncodeResult {
    data class Bytes(val value: ByteArray) : EncodeResult

    /**
     * A character the file's own encoding cannot hold — an emoji in a GBK file,
     * or a symbol outside the old charset's repertoire.
     *
     * Reported rather than replaced: silently writing `?` over the user's
     * character produces a file that looks saved and has lost something.
     */
    data object Unmappable : EncodeResult
}

/**
 * The bytes a save should produce, from the editor's normalised [text].
 *
 * The inverse of [decodeText] for the fields it kept: the same charset, the
 * same BOM, the same line terminator. A file with no trailing newline stays
 * without one; nothing here adds or removes anything.
 */
internal fun encodeText(file: TextFile, text: String): EncodeResult {
    // Normalised again rather than trusted: text pasted into the field can
    // arrive with the line endings of wherever it came from.
    val normalised = text.replace("\r\n", "\n").replace('\r', '\n')
    val body = if (file.eol == "\n") normalised else normalised.replace("\n", file.eol)
    val encoded = strictEncode(body, file.charset) ?: return EncodeResult.Unmappable
    return EncodeResult.Bytes(if (file.bom.isEmpty()) encoded else file.bom + encoded)
}

/**
 * Strict decoding: null on malformed input rather than a string full of
 * replacement characters, which is what lets the GBK fallback mean something.
 */
private fun strictDecode(bytes: ByteArray, charset: Charset): String? =
    try {
        charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (_: CharacterCodingException) {
        null
    }

/** The strict counterpart of [strictDecode]: null rather than `?` in the file. */
private fun strictEncode(text: String, charset: Charset): ByteArray? =
    try {
        val buffer = charset.newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .encode(CharBuffer.wrap(text))
        ByteArray(buffer.remaining()).also { buffer.get(it) }
    } catch (_: CharacterCodingException) {
        null
    }

private fun ByteArray.startsWith(prefix: ByteArray): Boolean {
    if (size < prefix.size) return false
    for (i in prefix.indices) if (this[i] != prefix[i]) return false
    return true
}
