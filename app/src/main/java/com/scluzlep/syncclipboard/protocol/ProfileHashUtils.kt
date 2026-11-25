package com.scluzlep.syncclipboard.protocol

import com.scluzlep.syncclipboard.model.ProfileType
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.security.MessageDigest
import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

object ProfileHashUtils {
    const val TEXT_TRANSFER_THRESHOLD = 10240
    const val MAX_TITLE_RUNES = 256
    const val MAX_TAG_RUNES = 64
    const val MAX_TAG_COUNT = 32
    const val MIN_COMPATIBLE_SERVER_VERSION = "3.1.1"

    private val IMAGE_EXTENSIONS = setOf(".jpg", ".jpeg", ".gif", ".bmp", ".png")
    private val INVALID_FILENAME_CHARS = setOf('<', '>', ':', '"', '/', '\\', '|', '?', '*')
    private val WINDOWS_RESERVED_STEMS = setOf("CON", "PRN", "AUX", "NUL")
    private val HEX_CHARS = "0123456789ABCDEF".toCharArray()

    fun bytesToUpperHex(bytes: ByteArray): String {
        val out = CharArray(bytes.size * 2)
        for (i in bytes.indices) {
            val v = bytes[i].toInt() and 0xFF
            out[i * 2] = HEX_CHARS[v ushr 4]
            out[i * 2 + 1] = HEX_CHARS[v and 0x0F]
        }
        return String(out)
    }

    fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return bytesToUpperHex(digest)
    }

    fun sha256Hex(text: String): String = sha256Hex(text.toByteArray(Charsets.UTF_8))

    fun sha256StreamHex(stream: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(81920)
        while (true) {
            val read = stream.read(buffer)
            if (read <= 0) break
            digest.update(buffer, 0, read)
        }
        return bytesToUpperHex(digest.digest())
    }

    fun sha256FileHex(file: File): String = FileInputStream(file).use { sha256StreamHex(it) }

    fun isValidSha256(hash: String?): Boolean {
        if (hash == null || hash.length != 64) return false
        for (c in hash) {
            val isHex = c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'
            if (!isHex) return false
        }
        return true
    }

    fun normalizeSha256(hash: String?): String? {
        if (hash == null) return null
        val cleaned = hash.trim().replace(":", "")
        require(isValidSha256(cleaned)) { "Hash must be a 64-character SHA-256 hex string." }
        return cleaned.uppercase(Locale.ROOT)
    }

    fun sha256Same(first: String?, second: String?): Boolean {
        if (first == null && second == null) return true
        if (first == null || second == null) return false
        return first.equals(second, ignoreCase = true)
    }

    fun combineHash(fileName: String, contentSha256Upper: String): String {
        val normalizedContent = normalizeSha256(contentSha256Upper)!!
        return sha256Hex("$fileName|$normalizedContent")
    }

    fun isImageFileName(fileName: String?): Boolean {
        if (fileName.isNullOrBlank()) return false
        val dot = fileName.lastIndexOf('.')
        if (dot < 0) return false
        val ext = fileName.substring(dot).lowercase(Locale.ROOT)
        return ext in IMAGE_EXTENSIONS
    }

    fun resolveEffectiveProfileType(type: ProfileType, dataName: String?): ProfileType {
        return if (type == ProfileType.File && isImageFileName(dataName)) {
            ProfileType.Image
        } else {
            type
        }
    }

    fun safeFileName(name: String): String {
        val sb = StringBuilder(name.length)
        for (c in name) {
            sb.append(if (c.code < 0x20 || c in INVALID_FILENAME_CHARS) '_' else c)
        }
        var safe = sb.toString().trimEnd('.', ' ')
        val stem = safe.substringBefore('.').uppercase(Locale.ROOT)
        val isComOrLpt = stem.length == 4 &&
            (stem.startsWith("COM") || stem.startsWith("LPT")) &&
            stem[3] in '1'..'9'
        if (stem in WINDOWS_RESERVED_STEMS || isComOrLpt) {
            safe = "_$safe"
        }
        return if (safe.isEmpty()) "data" else safe
    }

    fun createTimeBasedFileName(prefix: String, extension: String): String {
        val ts = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
        val rand = UUID.randomUUID().toString().replace("-", "").take(8)
        val cleanExt = if (extension.startsWith(".")) extension else ".$extension"
        return "${prefix}_${ts}_${rand}$cleanExt"
    }

    data class TextProfileParts(
        val inlineText: String,
        val fullHashUpper: String,
        val hasTransferData: Boolean,
        val utf16Size: Long,
        val transferDataName: String?
    )

    fun prepareTextProfile(fullText: String): TextProfileParts {
        val fullHash = sha256Hex(fullText)
        val utf16Len = fullText.length
        return if (utf16Len > TEXT_TRANSFER_THRESHOLD) {
            TextProfileParts(
                inlineText = fullText.substring(0, TEXT_TRANSFER_THRESHOLD),
                fullHashUpper = fullHash,
                hasTransferData = true,
                utf16Size = utf16Len.toLong(),
                transferDataName = createTimeBasedFileName("Text", "txt")
            )
        } else {
            TextProfileParts(
                inlineText = fullText,
                fullHashUpper = fullHash,
                hasTransferData = false,
                utf16Size = utf16Len.toLong(),
                transferDataName = null
            )
        }
    }

    fun writeUtf8NoBom(file: File, text: String) {
        file.parentFile?.mkdirs()
        file.writeBytes(text.toByteArray(Charsets.UTF_8))
    }

    fun readUtf8NoBom(file: File): String = String(file.readBytes(), Charsets.UTF_8)

    fun normalizeTitle(title: String?): String? {
        if (title == null) return null
        val normalized = Normalizer.normalize(title, Normalizer.Form.NFC).trim()
        val runeCount = normalized.codePointCount(0, normalized.length)
        require(runeCount <= MAX_TITLE_RUNES) { "Title exceeds $MAX_TITLE_RUNES characters." }
        for (c in normalized) {
            require(c != '\r' && c != '\n' && c != '\u0085' && c != '\u2028' && c != '\u2029') {
                "Title cannot contain line breaks."
            }
            require(!Character.isISOControl(c)) {
                "Title cannot contain control characters."
            }
        }
        return normalized
    }

    fun normalizeTagName(tag: String): String {
        val normalized = Normalizer.normalize(tag, Normalizer.Form.NFC).trim()
        val runeCount = normalized.codePointCount(0, normalized.length)
        require(normalized.isNotEmpty() && runeCount <= MAX_TAG_RUNES) {
            "Tag names must contain 1-$MAX_TAG_RUNES characters."
        }
        for (c in normalized) {
            require(!Character.isISOControl(c) && c != '\u2028' && c != '\u2029') {
                "Tag names cannot contain line breaks or control characters."
            }
        }
        return normalized
    }

    fun normalizeTags(tags: Iterable<String>): List<String> {
        val result = tags.map { normalizeTagName(it) }.distinct().sorted()
        require(result.size <= MAX_TAG_COUNT) { "A record can have at most $MAX_TAG_COUNT tags." }
        return result
    }

    fun parseNumericVersion(raw: String): List<Int>? {
        val clean = raw.trim().trim('"').removePrefix("v").removePrefix("V")
            .substringBefore('-').substringBefore('+')
        if (clean.isEmpty()) return null
        val parts = clean.split('.')
        if (parts.isEmpty()) return null
        return parts.map { it.toIntOrNull() ?: return null }
    }

    fun isVersionAtLeast(actual: String, minimum: String = MIN_COMPATIBLE_SERVER_VERSION): Boolean {
        val a = parseNumericVersion(actual) ?: return false
        val m = parseNumericVersion(minimum) ?: return false
        val len = maxOf(a.size, m.size)
        for (i in 0 until len) {
            val av = a.getOrElse(i) { 0 }
            val mv = m.getOrElse(i) { 0 }
            if (av != mv) return av > mv
        }
        return true
    }
}
