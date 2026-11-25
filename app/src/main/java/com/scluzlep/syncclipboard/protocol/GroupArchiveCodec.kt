package com.scluzlep.syncclipboard.protocol

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

object GroupArchiveCodec {
    private val UTF8 = Charsets.UTF_8

    data class GroupEntryDigest(
        val entryName: String,
        val isDirectory: Boolean,
        val length: Long,
        val contentSha256Upper: String?
    ) {
        fun toEntryString(): String {
            return if (isDirectory) {
                "D|$entryName\u0000"
            } else {
                val hash = contentSha256Upper?.uppercase() ?: ""
                "F|$entryName|$length|$hash\u0000"
            }
        }
    }

    data class GroupPackResult(
        val zipFile: File,
        val profileHashUpper: String,
        val transferDataHashUpper: String,
        val totalSize: Long,
        val topLevelNames: List<String>
    )

    data class GroupUnpackResult(
        val extractDir: File,
        val profileHashUpper: String,
        val totalSize: Long,
        val topLevelFiles: List<File>
    )

    private object UnsignedUtf8Comparator : Comparator<ByteArray> {
        override fun compare(a: ByteArray, b: ByteArray): Int {
            val len = minOf(a.size, b.size)
            for (i in 0 until len) {
                val av = a[i].toInt() and 0xFF
                val bv = b[i].toInt() and 0xFF
                if (av != bv) return av - bv
            }
            return a.size - b.size
        }
    }

    fun calculateEntriesHash(entries: Collection<GroupEntryDigest>): String {
        if (entries.isEmpty()) {
            return ProfileHashUtils.sha256Hex(ByteArray(0))
        }
        val ordered = entries
            .map { it to it.entryName.toByteArray(UTF8) }
            .sortedWith { left, right -> UnsignedUtf8Comparator.compare(left.second, right.second) }

        val digest = MessageDigest.getInstance("SHA-256")
        for ((entry, _) in ordered) {
            digest.update(entry.toEntryString().toByteArray(UTF8))
        }
        return ProfileHashUtils.bytesToUpperHex(digest.digest())
    }

    /**
     * Packs files and/or directories into a UTF-8 `.zip` archive and computes both the
     * SyncClipboard `GroupProfile` hash and the `.zip` transfer data SHA-256.
     */
    fun packGroup(inputs: List<File>, outputZip: File): GroupPackResult {
        require(inputs.isNotEmpty()) { "Group inputs must not be empty." }
        outputZip.parentFile?.mkdirs()

        val entries = mutableListOf<GroupEntryDigest>()
        val topLevelNames = mutableListOf<String>()
        var totalSize = 0L

        ZipOutputStream(BufferedOutputStream(FileOutputStream(outputZip)), UTF8).use { zos ->
            val buffer = ByteArray(81920)
            for (input in inputs) {
                val canonical = input.canonicalFile
                if (!canonical.exists()) {
                    throw IOException("Group input does not exist: ${input.path}")
                }
                val topName = canonical.name.ifEmpty { "root" }
                topLevelNames += topName
                if (canonical.isDirectory) {
                    val rootDirEntry = "$topName/"
                    zos.putNextEntry(ZipEntry(rootDirEntry))
                    zos.closeEntry()
                    entries += GroupEntryDigest(rootDirEntry, isDirectory = true, length = 0L, contentSha256Upper = null)
                    addDirectoryRecursive(canonical, topName, zos, entries, buffer) { totalSize += it }
                } else {
                    val digest = addFileEntry(zos, topName, canonical, buffer)
                    totalSize += digest.length
                    entries += digest
                }
            }
        }

        if (entries.isEmpty()) {
            throw IOException("Group archive contains no transferable entries.")
        }

        val profileHash = calculateEntriesHash(entries)
        val transferDataHash = ProfileHashUtils.sha256FileHex(outputZip)
        return GroupPackResult(
            zipFile = outputZip,
            profileHashUpper = profileHash,
            transferDataHashUpper = transferDataHash,
            totalSize = totalSize,
            topLevelNames = topLevelNames.distinct()
        )
    }

    private fun addDirectoryRecursive(
        dir: File,
        prefix: String,
        zos: ZipOutputStream,
        entries: MutableList<GroupEntryDigest>,
        buffer: ByteArray,
        onFileBytes: (Long) -> Unit
    ) {
        val children = dir.listFiles()?.sortedBy { it.name } ?: return
        for (child in children) {
            val entryName = "$prefix/${child.name}"
            if (child.isDirectory) {
                val dirEntryName = "$entryName/"
                zos.putNextEntry(ZipEntry(dirEntryName))
                zos.closeEntry()
                entries += GroupEntryDigest(dirEntryName, isDirectory = true, length = 0L, contentSha256Upper = null)
                addDirectoryRecursive(child, entryName, zos, entries, buffer, onFileBytes)
            } else if (child.isFile) {
                val digest = addFileEntry(zos, entryName, child, buffer)
                onFileBytes(digest.length)
                entries += digest
            }
        }
    }

    private fun addFileEntry(
        zos: ZipOutputStream,
        entryName: String,
        sourceFile: File,
        buffer: ByteArray
    ): GroupEntryDigest {
        zos.putNextEntry(ZipEntry(entryName))
        val digest = MessageDigest.getInstance("SHA-256")
        var length = 0L
        FileInputStream(sourceFile).use { fis ->
            while (true) {
                val read = fis.read(buffer)
                if (read <= 0) break
                zos.write(buffer, 0, read)
                digest.update(buffer, 0, read)
                length += read
            }
        }
        zos.closeEntry()
        val contentHash = ProfileHashUtils.bytesToUpperHex(digest.digest())
        return GroupEntryDigest(
            entryName = entryName,
            isDirectory = false,
            length = length,
            contentSha256Upper = contentHash
        )
    }

    /**
     * Unpacks a Group `.zip` archive into [targetDir] with strict Zip-Slip protection
     * and optional profile hash verification.
     */
    fun unpackGroup(
        zipFile: File,
        targetDir: File,
        expectedProfileHash: String? = null
    ): GroupUnpackResult {
        if (!zipFile.exists()) {
            throw IOException("Zip file does not exist: ${zipFile.path}")
        }
        targetDir.mkdirs()
        val canonicalTarget = targetDir.canonicalFile
        val canonicalPrefix = canonicalTarget.path.let {
            if (it.endsWith(File.separator)) it else it + File.separator
        }

        val entries = mutableListOf<GroupEntryDigest>()
        val topLevelMap = linkedMapOf<String, File>()
        var totalSize = 0L
        val buffer = ByteArray(81920)

        ZipInputStream(BufferedInputStream(FileInputStream(zipFile)), UTF8).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                val normalizedName = normalizeAndValidateEntryName(entry.name, entry.isDirectory)
                val destFile = resolveSafeDestination(canonicalTarget, canonicalPrefix, normalizedName)

                if (normalizedName.endsWith("/")) {
                    if (!destFile.exists() && !destFile.mkdirs()) {
                        throw IOException("Failed to create directory: ${destFile.path}")
                    }
                    entries += GroupEntryDigest(normalizedName, isDirectory = true, length = 0L, contentSha256Upper = null)
                } else {
                    destFile.parentFile?.let { parent ->
                        if (!parent.exists() && !parent.mkdirs()) {
                            throw IOException("Failed to create parent directory: ${parent.path}")
                        }
                    }
                    val digest = MessageDigest.getInstance("SHA-256")
                    var entryLen = 0L
                    FileOutputStream(destFile).use { fos ->
                        while (true) {
                            val read = zis.read(buffer)
                            if (read <= 0) break
                            fos.write(buffer, 0, read)
                            digest.update(buffer, 0, read)
                            entryLen += read
                        }
                    }
                    totalSize += entryLen
                    val contentHash = ProfileHashUtils.bytesToUpperHex(digest.digest())
                    entries += GroupEntryDigest(
                        entryName = normalizedName,
                        isDirectory = false,
                        length = entryLen,
                        contentSha256Upper = contentHash
                    )
                }

                val topSegment = normalizedName.trimEnd('/').substringBefore('/')
                if (topSegment.isNotEmpty() && !topLevelMap.containsKey(topSegment)) {
                    topLevelMap[topSegment] = File(canonicalTarget, topSegment)
                }
                zis.closeEntry()
            }
        }

        if (entries.isEmpty()) {
            throw IOException("Group transfer data contains no entries.")
        }

        val actualHash = calculateEntriesHash(entries)
        if (expectedProfileHash != null && !ProfileHashUtils.sha256Same(actualHash, expectedProfileHash)) {
            throw IOException("Group data hash mismatch. Expected: $expectedProfileHash, Actual: $actualHash")
        }

        return GroupUnpackResult(
            extractDir = canonicalTarget,
            profileHashUpper = actualHash,
            totalSize = totalSize,
            topLevelFiles = topLevelMap.values.toList()
        )
    }

    internal fun normalizeAndValidateEntryName(rawName: String, isDirEntry: Boolean): String {
        if (rawName.isEmpty() || rawName.startsWith("/") || rawName.startsWith("\\")) {
            throw SecurityException("Zip entry has absolute or empty path: $rawName")
        }
        val unified = rawName.replace('\\', '/')
        // Reject Windows drive prefix like C:...
        if (unified.length >= 2 && unified[1] == ':' && unified[0].isLetter()) {
            throw SecurityException("Zip entry contains drive letter: $rawName")
        }
        val segments = unified.split('/')
        for (i in segments.indices) {
            val seg = segments[i]
            val isTrailingEmpty = (i == segments.lastIndex && seg.isEmpty() && (unified.endsWith("/") || isDirEntry))
            if (isTrailingEmpty) continue
            if (seg.isEmpty() || seg == "." || seg == "..") {
                throw SecurityException("Zip entry contains unsafe path segment: $rawName")
            }
        }
        return if (isDirEntry && !unified.endsWith("/")) "$unified/" else unified
    }

    private fun resolveSafeDestination(
        canonicalTarget: File,
        canonicalPrefix: String,
        normalizedName: String
    ): File {
        val relative = normalizedName.trimEnd('/').replace('/', File.separatorChar)
        val candidate = File(canonicalTarget, relative).canonicalFile
        val candidatePath = candidate.path
        if (candidatePath != canonicalTarget.path && !candidatePath.startsWith(canonicalPrefix)) {
            throw SecurityException("Zip-Slip path traversal rejected: $normalizedName")
        }
        return candidate
    }
}
