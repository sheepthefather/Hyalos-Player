package com.hyalos.player.files

/**
 * What counts as a text file, and what only looks like one.
 *
 * The extension decides whether tapping an entry opens the editor; the bytes
 * decide whether the editor can make sense of it. Both are needed: an
 * extensionless binary and a `.log` full of NUL bytes are different mistakes,
 * and each has its own answer.
 */

/**
 * Extensions the editor will open.
 *
 * A whitelist rather than "anything that is not a video": opening an arbitrary
 * binary in a text editor is a way to destroy it on the first save, and a
 * whitelist is the only answer available before the file is read.
 */
private val TEXT_EXTENSIONS = setOf(
    // Plain text and documents.
    "txt", "text", "md", "markdown", "log", "nfo",
    // Data and configuration.
    "json", "xml", "yaml", "yml", "toml", "ini", "conf", "cfg", "properties", "env",
    "csv", "tsv",
    // Subtitles.
    "srt", "ass", "ssa", "vtt",
    // Markup and code.
    "html", "htm", "css", "js", "ts", "kt", "kts", "java", "py", "sh", "bash",
    "bat", "cmd", "ps1", "rs", "go", "c", "h", "cpp", "hpp", "cc", "sql",
    "gradle", "lua", "rb", "php", "swift",
)

/**
 * Whether [name] is a file the editor should open.
 *
 * Case-insensitive: shares hand back whatever the file was named on the other
 * machine, and `.TXT` is a text file.
 */
internal fun isTextFile(name: String): Boolean =
    name.substringAfterLast('.', "").lowercase() in TEXT_EXTENSIONS

/**
 * Whether [bytes] are more likely a binary than a text.
 *
 * A NUL byte, because no text encoding the editor supports produces one in its
 * ordinary range. **Only meaningful for a BOM-less payload**: UTF-16 text is
 * half NUL bytes by construction, which is why `decodeText` checks for a BOM
 * first and calls this only when there is none.
 */
internal fun looksBinary(bytes: ByteArray): Boolean = bytes.any { it == 0.toByte() }
