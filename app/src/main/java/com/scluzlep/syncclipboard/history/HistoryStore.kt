package com.scluzlep.syncclipboard.history

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.scluzlep.syncclipboard.config.ConfigStore
import com.scluzlep.syncclipboard.model.HistoryFilterState
import com.scluzlep.syncclipboard.model.HistoryRecordDto
import com.scluzlep.syncclipboard.model.HistoryRecordUpdateDto
import com.scluzlep.syncclipboard.model.LocalHistoryItem
import com.scluzlep.syncclipboard.model.ProfileType
import com.scluzlep.syncclipboard.model.ServerCapabilitiesDto
import com.scluzlep.syncclipboard.model.ServerNode
import com.scluzlep.syncclipboard.model.ServerType
import com.scluzlep.syncclipboard.protocol.ProfileHashUtils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

data class SyncCursorRecord(
    val nodeStorageKey: String,
    val lastSyncTimeMs: Long = 0L,
    val lastModifiedCursor: String? = null,
    val lastRemoteHash: String? = null,
    val lastUploadedHash: String? = null,
    val cachedCapabilities: ServerCapabilitiesDto? = null,
    val remoteEtag: String? = null
)

data class PendingUploadItem(
    val id: Long = 0L,
    val nodeStorageKey: String,
    val type: ProfileType,
    val hashUpper: String,
    val text: String,
    val dataFilePath: String? = null,
    val dataName: String? = null,
    val transferDataHash: String? = null,
    val sizeBytes: Long = 0L,
    val createdAtMs: Long,
    val retryCount: Int = 0,
    val nextAttemptAtMs: Long = 0L,
    val lastError: String? = null
)

object HistoryIsoTime {
    fun parseEpochMs(raw: String?, fallbackMs: Long = 0L): Long {
        if (raw.isNullOrBlank()) return fallbackMs
        val trimmed = raw.trim()
        return runCatching { OffsetDateTime.parse(trimmed).toInstant().toEpochMilli() }
            .recoverCatching { Instant.parse(trimmed).toEpochMilli() }
            .getOrDefault(fallbackMs)
    }

    fun formatUtcIso(epochMs: Long): String {
        return DateTimeFormatter.ISO_OFFSET_DATE_TIME
            .format(Instant.ofEpochMilli(epochMs).atOffset(ZoneOffset.UTC))
    }
}

object NodeStorageKeys {
    fun forNode(node: ServerNode): String =
        forEndpoint(node.serverType, node.baseUrl, node.username)

    fun forEndpoint(serverType: ServerType, baseUrl: String, username: String): String {
        val canonical = "${serverType.name}|${ConfigStore.normalizeBaseUrl(baseUrl)}|${username.trim()}"
        return ProfileHashUtils.sha256Hex(canonical).lowercase(Locale.ROOT).take(24)
    }
}

/**
 * Implements SyncClipboard v3.3.4 5-minute `HistoryHelper.ShouldUpdate` conflict resolution,
 * `Tags` `null` vs `[]` preservation, and missing `Title`/`Tags` backfill flagging.
 */
object HistoryMergePolicy {
    const val FIVE_MINUTES_MS = 5L * 60L * 1000L

    fun shouldUpdate(
        existingLastModifiedMs: Long,
        existingVersion: Int,
        incomingLastModifiedMs: Long,
        incomingVersion: Int
    ): Boolean {
        val gap = abs(incomingLastModifiedMs - existingLastModifiedMs)
        return if (gap <= FIVE_MINUTES_MS) {
            incomingVersion >= existingVersion
        } else {
            incomingLastModifiedMs >= existingLastModifiedMs
        }
    }

    fun mergeRemoteRecord(
        existing: LocalHistoryItem?,
        remote: HistoryRecordDto,
        nodeStorageKey: String,
        capabilities: ServerCapabilitiesDto
    ): LocalHistoryItem {
        val hashUpper = remote.hash.trim().uppercase(Locale.ROOT)
        val nowMs = System.currentTimeMillis()
        val remoteCreatedMs = HistoryIsoTime.parseEpochMs(remote.createTime, nowMs)
        val remoteModifiedMs = HistoryIsoTime.parseEpochMs(remote.lastModified, remoteCreatedMs)
        val remoteAccessedMs = HistoryIsoTime.parseEpochMs(remote.lastAccessed, remoteModifiedMs)
        val normalizedRemoteTags = remote.tags?.let { ProfileHashUtils.normalizeTags(it) }
        val normalizedRemoteTitle = remote.title?.let {
            runCatching { ProfileHashUtils.normalizeTitle(it) }.getOrNull() ?: it.trim()
        }

        if (existing == null) {
            return LocalHistoryItem(
                nodeStorageKey = nodeStorageKey,
                type = remote.type,
                hashUpper = hashUpper,
                text = remote.text,
                title = normalizedRemoteTitle,
                tags = normalizedRemoteTags,
                starred = remote.starred,
                pinned = remote.pinned,
                size = remote.size,
                hasData = remote.hasData,
                transferDataHash = null,
                localDataPath = null,
                createTimeEpochMs = remoteCreatedMs,
                lastModifiedEpochMs = remoteModifiedMs,
                lastAccessedEpochMs = remoteAccessedMs,
                version = remote.version,
                isDeleted = remote.isDeleted,
                needsMetadataBackfill = false
            )
        }

        val acceptRemote = shouldUpdate(
            existingLastModifiedMs = existing.lastModifiedEpochMs,
            existingVersion = existing.version,
            incomingLastModifiedMs = remoteModifiedMs,
            incomingVersion = remote.version
        )

        if (!acceptRemote) {
            // Local record won arbitration; preserve local metadata and flag backfill if needed.
            val missingRemoteTags = capabilities.historyTags &&
                normalizedRemoteTags == null &&
                !existing.tags.isNullOrEmpty()
            val missingRemoteTitle = capabilities.historyTitle &&
                normalizedRemoteTitle == null &&
                !existing.title.isNullOrEmpty()
            return if (missingRemoteTags || missingRemoteTitle) {
                existing.copy(needsMetadataBackfill = true)
            } else {
                existing
            }
        }

        val missingRemoteTags = capabilities.historyTags &&
            normalizedRemoteTags == null &&
            !existing.tags.isNullOrEmpty()
        val missingRemoteTitle = capabilities.historyTitle &&
            normalizedRemoteTitle == null &&
            !existing.title.isNullOrEmpty()

        val mergedTags: List<String>? = when {
            normalizedRemoteTags != null -> normalizedRemoteTags // [] explicitly clears tags
            missingRemoteTags -> existing.tags
            else -> existing.tags
        }

        val mergedTitle: String? = when {
            normalizedRemoteTitle != null -> normalizedRemoteTitle
            missingRemoteTitle -> existing.title
            else -> existing.title
        }

        return existing.copy(
            text = if (remote.text.isNotEmpty() || existing.text.isEmpty()) remote.text else existing.text,
            title = mergedTitle,
            tags = mergedTags,
            starred = remote.starred,
            pinned = remote.pinned,
            size = if (remote.size > 0L) remote.size else existing.size,
            hasData = remote.hasData || existing.hasData,
            createTimeEpochMs = minOf(existing.createTimeEpochMs, remoteCreatedMs),
            lastModifiedEpochMs = remoteModifiedMs,
            lastAccessedEpochMs = maxOf(existing.lastAccessedEpochMs, remoteAccessedMs),
            version = remote.version,
            isDeleted = remote.isDeleted,
            needsMetadataBackfill = existing.needsMetadataBackfill || missingRemoteTags || missingRemoteTitle
        )
    }

    fun applyServerUpdate(
        existing: LocalHistoryItem,
        update: HistoryRecordUpdateDto,
        capabilities: ServerCapabilitiesDto
    ): LocalHistoryItem {
        val incomingModifiedMs = update.lastModified?.let {
            HistoryIsoTime.parseEpochMs(it, existing.lastModifiedEpochMs)
        } ?: existing.lastModifiedEpochMs
        val incomingVersion = update.version ?: existing.version

        val acceptUpdate = shouldUpdate(
            existingLastModifiedMs = existing.lastModifiedEpochMs,
            existingVersion = existing.version,
            incomingLastModifiedMs = incomingModifiedMs,
            incomingVersion = incomingVersion
        )
        if (!acceptUpdate) return existing

        val nextTags = update.tags?.let { ProfileHashUtils.normalizeTags(it) } ?: existing.tags
        val nextTitle = update.title?.let {
            runCatching { ProfileHashUtils.normalizeTitle(it) }.getOrNull() ?: it.trim()
        } ?: existing.title

        val missingTags = capabilities.historyTags && update.tags == null && !existing.tags.isNullOrEmpty()
        val missingTitle = capabilities.historyTitle && update.title == null && !existing.title.isNullOrEmpty()

        return existing.copy(
            tags = nextTags,
            title = nextTitle,
            starred = update.starred ?: existing.starred,
            pinned = update.pinned ?: existing.pinned,
            isDeleted = update.isDelete ?: existing.isDeleted,
            version = incomingVersion,
            lastModifiedEpochMs = incomingModifiedMs,
            lastAccessedEpochMs = update.lastAccessed?.let {
                HistoryIsoTime.parseEpochMs(it, existing.lastAccessedEpochMs)
            } ?: existing.lastAccessedEpochMs,
            needsMetadataBackfill = missingTags || missingTitle
        )
    }

    fun filterAndSort(items: List<LocalHistoryItem>, filter: HistoryFilterState): List<LocalHistoryItem> {
        val query = filter.searchText.trim().lowercase(Locale.ROOT)
        return items.asSequence()
            .filter { item ->
                if (!filter.includeDeleted && item.isDeleted) return@filter false
                if (filter.selectedType != null && item.type != filter.selectedType) return@filter false
                if (filter.starredOnly && !item.starred) return@filter false
                if (filter.onlyUntagged && !item.tags.isNullOrEmpty()) return@filter false
                if (!filter.selectedTag.isNullOrBlank()) {
                    val tags = item.tags ?: return@filter false
                    if (filter.selectedTag !in tags) return@filter false
                }
                if (query.isNotEmpty()) {
                    val textMatch = item.text.lowercase(Locale.ROOT).contains(query)
                    val titleMatch = filter.searchTitles &&
                        (item.title?.lowercase(Locale.ROOT)?.contains(query) == true)
                    if (!textMatch && !titleMatch) return@filter false
                }
                true
            }
            .sortedWith(
                compareByDescending<LocalHistoryItem> { it.pinned }
                    .thenByDescending { it.lastModifiedEpochMs }
                    .thenByDescending { it.createTimeEpochMs }
            )
            .toList()
    }
}

private class HistorySqliteHelper(
    context: Context,
    dbName: String = DB_NAME
) : SQLiteOpenHelper(context, dbName, null, DB_VERSION) {

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.enableWriteAheadLogging()
    }

    override fun onOpen(db: SQLiteDatabase) {
        super.onOpen(db)
        if (!db.isReadOnly) {
            db.rawQuery("PRAGMA synchronous=NORMAL", null).use { it.moveToFirst() }
        }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS history_items (
                node_storage_key TEXT NOT NULL,
                type TEXT NOT NULL,
                hash_upper TEXT NOT NULL,
                text_content TEXT NOT NULL,
                title TEXT,
                tags_json TEXT,
                starred INTEGER NOT NULL DEFAULT 0,
                pinned INTEGER NOT NULL DEFAULT 0,
                size_bytes INTEGER NOT NULL DEFAULT 0,
                has_data INTEGER NOT NULL DEFAULT 0,
                transfer_data_hash TEXT,
                local_data_path TEXT,
                create_time_ms INTEGER NOT NULL,
                last_modified_ms INTEGER NOT NULL,
                last_accessed_ms INTEGER NOT NULL,
                version INTEGER NOT NULL DEFAULT 0,
                is_deleted INTEGER NOT NULL DEFAULT 0,
                needs_metadata_backfill INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY (node_storage_key, type, hash_upper)
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE INDEX IF NOT EXISTS idx_history_node_active
            ON history_items (node_storage_key, is_deleted, pinned, last_modified_ms DESC)
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS sync_cursors (
                node_storage_key TEXT PRIMARY KEY,
                last_sync_time_ms INTEGER NOT NULL DEFAULT 0,
                last_modified_cursor TEXT,
                last_remote_hash TEXT,
                last_uploaded_hash TEXT,
                cached_capabilities_json TEXT,
                remote_etag TEXT
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS upload_queue (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                node_storage_key TEXT NOT NULL,
                type TEXT NOT NULL,
                hash_upper TEXT NOT NULL,
                text_content TEXT NOT NULL,
                data_file_path TEXT,
                data_name TEXT,
                transfer_data_hash TEXT,
                size_bytes INTEGER NOT NULL DEFAULT 0,
                created_at_ms INTEGER NOT NULL,
                retry_count INTEGER NOT NULL DEFAULT 0,
                next_attempt_at_ms INTEGER NOT NULL DEFAULT 0,
                last_error TEXT,
                UNIQUE (node_storage_key, type, hash_upper)
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Initial schema v1.
    }

    companion object {
        private const val DB_NAME = "syncclipboard_history.db"
        private const val DB_VERSION = 1
    }
}

/**
 * Abstraction over history, cursor, and upload-queue storage (SQLite in production, in-memory in JVM tests).
 */
interface HistoryRepository {
    val filterFlow: StateFlow<HistoryFilterState>
    val historyFlow: StateFlow<List<LocalHistoryItem>>
    val availableTagsFlow: StateFlow<List<String>>

    fun setActiveNode(nodeStorageKey: String?)
    fun setFilter(filter: HistoryFilterState)
    fun getItem(nodeStorageKey: String, type: ProfileType, hashUpper: String): LocalHistoryItem?
    fun getAllForNode(nodeStorageKey: String, includeDeleted: Boolean = false): List<LocalHistoryItem>
    fun upsertItem(item: LocalHistoryItem)
    fun mergeRemoteRecords(
        nodeStorageKey: String,
        records: List<HistoryRecordDto>,
        capabilities: ServerCapabilitiesDto
    ): List<LocalHistoryItem>
    fun applyServerUpdate(
        nodeStorageKey: String,
        type: ProfileType,
        hashUpper: String,
        update: HistoryRecordUpdateDto,
        capabilities: ServerCapabilitiesDto
    ): LocalHistoryItem?
    fun updateLocalDataPath(
        nodeStorageKey: String,
        type: ProfileType,
        hashUpper: String,
        localDataPath: String?,
        transferDataHash: String? = null
    )
    fun clearBackfillFlag(nodeStorageKey: String, type: ProfileType, hashUpper: String)
    fun itemsNeedingMetadataBackfill(nodeStorageKey: String): List<LocalHistoryItem>
    fun clearNodeHistory(nodeStorageKey: String)
    fun getCursor(nodeStorageKey: String): SyncCursorRecord
    fun saveCursor(cursor: SyncCursorRecord)
    fun enqueueUpload(item: PendingUploadItem)
    fun peekDueUploads(nodeStorageKey: String, nowMs: Long = System.currentTimeMillis(), limit: Int = 10): List<PendingUploadItem>
    fun removeUpload(nodeStorageKey: String, type: ProfileType, hashUpper: String)
    fun markUploadRetry(
        nodeStorageKey: String,
        type: ProfileType,
        hashUpper: String,
        retryCount: Int,
        nextAttemptAtMs: Long,
        lastError: String?
    )
}

/**
 * SQLite-backed per-node isolated history, sync cursor, and upload queue store (WAL enabled).
 */
class HistoryStore(
    context: Context,
    dbName: String = "syncclipboard_history.db"
) : HistoryRepository {
    private val dbHelper = HistorySqliteHelper(context.applicationContext, dbName)
    private val dbLock = Any()

    @Volatile
    private var activeNodeKey: String? = null

    private val _filterFlow = MutableStateFlow(HistoryFilterState())
    override val filterFlow: StateFlow<HistoryFilterState> = _filterFlow.asStateFlow()

    private val _historyFlow = MutableStateFlow<List<LocalHistoryItem>>(emptyList())
    override val historyFlow: StateFlow<List<LocalHistoryItem>> = _historyFlow.asStateFlow()

    private val _availableTagsFlow = MutableStateFlow<List<String>>(emptyList())
    override val availableTagsFlow: StateFlow<List<String>> = _availableTagsFlow.asStateFlow()

    override fun setActiveNode(nodeStorageKey: String?) {
        synchronized(dbLock) {
            activeNodeKey = nodeStorageKey
            refreshFlowsLocked()
        }
    }

    override fun setFilter(filter: HistoryFilterState) {
        synchronized(dbLock) {
            _filterFlow.value = filter
            refreshFlowsLocked()
        }
    }

    override fun getItem(nodeStorageKey: String, type: ProfileType, hashUpper: String): LocalHistoryItem? {
        val normHash = hashUpper.trim().uppercase(Locale.ROOT)
        synchronized(dbLock) {
            val db = dbHelper.readableDatabase
            db.rawQuery(
                "SELECT * FROM history_items WHERE node_storage_key = ? AND type = ? AND hash_upper = ? LIMIT 1",
                arrayOf(nodeStorageKey, type.name, normHash)
            ).use { cursor ->
                return if (cursor.moveToFirst()) readHistoryRow(cursor) else null
            }
        }
    }

    override fun getAllForNode(nodeStorageKey: String, includeDeleted: Boolean): List<LocalHistoryItem> {
        synchronized(dbLock) {
            return queryNodeItemsLocked(nodeStorageKey, includeDeleted)
        }
    }

    override fun upsertItem(item: LocalHistoryItem) {
        synchronized(dbLock) {
            val db = dbHelper.writableDatabase
            db.insertWithOnConflict(
                "history_items",
                null,
                item.toContentValues(),
                SQLiteDatabase.CONFLICT_REPLACE
            )
            if (item.nodeStorageKey == activeNodeKey) {
                refreshFlowsLocked()
            }
        }
    }

    override fun mergeRemoteRecords(
        nodeStorageKey: String,
        records: List<HistoryRecordDto>,
        capabilities: ServerCapabilitiesDto
    ): List<LocalHistoryItem> {
        if (records.isEmpty()) return emptyList()
        val mergedList = ArrayList<LocalHistoryItem>(records.size)
        synchronized(dbLock) {
            val db = dbHelper.writableDatabase
            db.beginTransaction()
            try {
                for (dto in records) {
                    val normHash = dto.hash.trim().uppercase(Locale.ROOT)
                    if (normHash.isEmpty() || dto.type == ProfileType.None || dto.type == ProfileType.Unknown) {
                        continue
                    }
                    val existing = getItemLocked(db, nodeStorageKey, dto.type, normHash)
                    val merged = HistoryMergePolicy.mergeRemoteRecord(
                        existing = existing,
                        remote = dto,
                        nodeStorageKey = nodeStorageKey,
                        capabilities = capabilities
                    )
                    db.insertWithOnConflict(
                        "history_items",
                        null,
                        merged.toContentValues(),
                        SQLiteDatabase.CONFLICT_REPLACE
                    )
                    mergedList += merged
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            if (nodeStorageKey == activeNodeKey) {
                refreshFlowsLocked()
            }
        }
        return mergedList
    }

    override fun applyServerUpdate(
        nodeStorageKey: String,
        type: ProfileType,
        hashUpper: String,
        update: HistoryRecordUpdateDto,
        capabilities: ServerCapabilitiesDto
    ): LocalHistoryItem? {
        val normHash = hashUpper.trim().uppercase(Locale.ROOT)
        synchronized(dbLock) {
            val db = dbHelper.writableDatabase
            val existing = getItemLocked(db, nodeStorageKey, type, normHash) ?: return null
            val merged = HistoryMergePolicy.applyServerUpdate(existing, update, capabilities)
            db.insertWithOnConflict(
                "history_items",
                null,
                merged.toContentValues(),
                SQLiteDatabase.CONFLICT_REPLACE
            )
            if (nodeStorageKey == activeNodeKey) {
                refreshFlowsLocked()
            }
            return merged
        }
    }

    override fun updateLocalDataPath(
        nodeStorageKey: String,
        type: ProfileType,
        hashUpper: String,
        localDataPath: String?,
        transferDataHash: String?
    ) {
        val normHash = hashUpper.trim().uppercase(Locale.ROOT)
        synchronized(dbLock) {
            val db = dbHelper.writableDatabase
            val cv = ContentValues().apply {
                put("local_data_path", localDataPath)
                if (transferDataHash != null) {
                    put("transfer_data_hash", transferDataHash.uppercase(Locale.ROOT))
                }
            }
            db.update(
                "history_items",
                cv,
                "node_storage_key = ? AND type = ? AND hash_upper = ?",
                arrayOf(nodeStorageKey, type.name, normHash)
            )
            if (nodeStorageKey == activeNodeKey) {
                refreshFlowsLocked()
            }
        }
    }

    override fun clearBackfillFlag(nodeStorageKey: String, type: ProfileType, hashUpper: String) {
        val normHash = hashUpper.trim().uppercase(Locale.ROOT)
        synchronized(dbLock) {
            val db = dbHelper.writableDatabase
            val cv = ContentValues().apply { put("needs_metadata_backfill", 0) }
            db.update(
                "history_items",
                cv,
                "node_storage_key = ? AND type = ? AND hash_upper = ?",
                arrayOf(nodeStorageKey, type.name, normHash)
            )
        }
    }

    override fun itemsNeedingMetadataBackfill(nodeStorageKey: String): List<LocalHistoryItem> {
        synchronized(dbLock) {
            val db = dbHelper.readableDatabase
            val out = mutableListOf<LocalHistoryItem>()
            db.rawQuery(
                "SELECT * FROM history_items WHERE node_storage_key = ? AND needs_metadata_backfill = 1 AND is_deleted = 0",
                arrayOf(nodeStorageKey)
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    out += readHistoryRow(cursor)
                }
            }
            return out
        }
    }

    override fun clearNodeHistory(nodeStorageKey: String) {
        synchronized(dbLock) {
            val db = dbHelper.writableDatabase
            db.delete("history_items", "node_storage_key = ?", arrayOf(nodeStorageKey))
            if (nodeStorageKey == activeNodeKey) {
                refreshFlowsLocked()
            }
        }
    }

    override fun getCursor(nodeStorageKey: String): SyncCursorRecord {
        synchronized(dbLock) {
            val db = dbHelper.readableDatabase
            db.rawQuery(
                "SELECT * FROM sync_cursors WHERE node_storage_key = ? LIMIT 1",
                arrayOf(nodeStorageKey)
            ).use { c ->
                if (!c.moveToFirst()) return SyncCursorRecord(nodeStorageKey = nodeStorageKey)
                val capsJson = c.getStringOrNull("cached_capabilities_json")
                val caps = capsJson?.let { decodeCapabilities(it) }
                return SyncCursorRecord(
                    nodeStorageKey = nodeStorageKey,
                    lastSyncTimeMs = c.getLong(c.getColumnIndexOrThrow("last_sync_time_ms")),
                    lastModifiedCursor = c.getStringOrNull("last_modified_cursor"),
                    lastRemoteHash = c.getStringOrNull("last_remote_hash"),
                    lastUploadedHash = c.getStringOrNull("last_uploaded_hash"),
                    cachedCapabilities = caps,
                    remoteEtag = c.getStringOrNull("remote_etag")
                )
            }
        }
    }

    override fun saveCursor(cursor: SyncCursorRecord) {
        synchronized(dbLock) {
            val db = dbHelper.writableDatabase
            val cv = ContentValues().apply {
                put("node_storage_key", cursor.nodeStorageKey)
                put("last_sync_time_ms", cursor.lastSyncTimeMs)
                put("last_modified_cursor", cursor.lastModifiedCursor)
                put("last_remote_hash", cursor.lastRemoteHash)
                put("last_uploaded_hash", cursor.lastUploadedHash)
                put("cached_capabilities_json", cursor.cachedCapabilities?.let(::encodeCapabilities))
                put("remote_etag", cursor.remoteEtag)
            }
            db.insertWithOnConflict("sync_cursors", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
        }
    }

    override fun enqueueUpload(item: PendingUploadItem) {
        synchronized(dbLock) {
            val db = dbHelper.writableDatabase
            val cv = ContentValues().apply {
                put("node_storage_key", item.nodeStorageKey)
                put("type", item.type.name)
                put("hash_upper", item.hashUpper.uppercase(Locale.ROOT))
                put("text_content", item.text)
                put("data_file_path", item.dataFilePath)
                put("data_name", item.dataName)
                put("transfer_data_hash", item.transferDataHash?.uppercase(Locale.ROOT))
                put("size_bytes", item.sizeBytes)
                put("created_at_ms", item.createdAtMs)
                put("retry_count", item.retryCount)
                put("next_attempt_at_ms", item.nextAttemptAtMs)
                put("last_error", item.lastError)
            }
            db.insertWithOnConflict("upload_queue", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
        }
    }

    override fun peekDueUploads(nodeStorageKey: String, nowMs: Long, limit: Int): List<PendingUploadItem> {
        synchronized(dbLock) {
            val db = dbHelper.readableDatabase
            val out = mutableListOf<PendingUploadItem>()
            db.rawQuery(
                """
                SELECT * FROM upload_queue
                WHERE node_storage_key = ? AND next_attempt_at_ms <= ?
                ORDER BY created_at_ms ASC LIMIT ?
                """.trimIndent(),
                arrayOf(nodeStorageKey, nowMs.toString(), limit.toString())
            ).use { c ->
                while (c.moveToNext()) {
                    out += readUploadRow(c)
                }
            }
            return out
        }
    }

    override fun removeUpload(nodeStorageKey: String, type: ProfileType, hashUpper: String) {
        synchronized(dbLock) {
            val db = dbHelper.writableDatabase
            db.delete(
                "upload_queue",
                "node_storage_key = ? AND type = ? AND hash_upper = ?",
                arrayOf(nodeStorageKey, type.name, hashUpper.uppercase(Locale.ROOT))
            )
        }
    }

    override fun markUploadRetry(
        nodeStorageKey: String,
        type: ProfileType,
        hashUpper: String,
        retryCount: Int,
        nextAttemptAtMs: Long,
        lastError: String?
    ) {
        synchronized(dbLock) {
            val db = dbHelper.writableDatabase
            val cv = ContentValues().apply {
                put("retry_count", retryCount)
                put("next_attempt_at_ms", nextAttemptAtMs)
                put("last_error", lastError)
            }
            db.update(
                "upload_queue",
                cv,
                "node_storage_key = ? AND type = ? AND hash_upper = ?",
                arrayOf(nodeStorageKey, type.name, hashUpper.uppercase(Locale.ROOT))
            )
        }
    }

    private fun refreshFlowsLocked() {
        val key = activeNodeKey
        if (key == null) {
            _historyFlow.value = emptyList()
            _availableTagsFlow.value = emptyList()
            return
        }
        val allForNode = queryNodeItemsLocked(key, includeDeleted = true)
        val filtered = HistoryMergePolicy.filterAndSort(allForNode, _filterFlow.value)
        val tags = allForNode.asSequence()
            .filter { !it.isDeleted }
            .flatMap { it.tags?.asSequence() ?: emptySequence() }
            .distinct()
            .sorted()
            .toList()
        _historyFlow.value = filtered
        _availableTagsFlow.value = tags
    }

    private fun queryNodeItemsLocked(nodeStorageKey: String, includeDeleted: Boolean): List<LocalHistoryItem> {
        val db = dbHelper.readableDatabase
        val sql = if (includeDeleted) {
            "SELECT * FROM history_items WHERE node_storage_key = ?"
        } else {
            "SELECT * FROM history_items WHERE node_storage_key = ? AND is_deleted = 0"
        }
        val out = mutableListOf<LocalHistoryItem>()
        db.rawQuery(sql, arrayOf(nodeStorageKey)).use { c ->
            while (c.moveToNext()) {
                out += readHistoryRow(c)
            }
        }
        return out
    }

    private fun getItemLocked(
        db: SQLiteDatabase,
        nodeStorageKey: String,
        type: ProfileType,
        hashUpper: String
    ): LocalHistoryItem? {
        db.rawQuery(
            "SELECT * FROM history_items WHERE node_storage_key = ? AND type = ? AND hash_upper = ? LIMIT 1",
            arrayOf(nodeStorageKey, type.name, hashUpper)
        ).use { c ->
            return if (c.moveToFirst()) readHistoryRow(c) else null
        }
    }

    private fun LocalHistoryItem.toContentValues(): ContentValues = ContentValues().apply {
        put("node_storage_key", nodeStorageKey)
        put("type", type.name)
        put("hash_upper", hashUpper.uppercase(Locale.ROOT))
        put("text_content", text)
        put("title", title)
        put("tags_json", tags?.let(::encodeTagsJson))
        put("starred", if (starred) 1 else 0)
        put("pinned", if (pinned) 1 else 0)
        put("size_bytes", size)
        put("has_data", if (hasData) 1 else 0)
        put("transfer_data_hash", transferDataHash?.uppercase(Locale.ROOT))
        put("local_data_path", localDataPath)
        put("create_time_ms", createTimeEpochMs)
        put("last_modified_ms", lastModifiedEpochMs)
        put("last_accessed_ms", lastAccessedEpochMs)
        put("version", version)
        put("is_deleted", if (isDeleted) 1 else 0)
        put("needs_metadata_backfill", if (needsMetadataBackfill) 1 else 0)
    }

    private fun readHistoryRow(c: Cursor): LocalHistoryItem {
        val tagsRaw = c.getStringOrNull("tags_json")
        return LocalHistoryItem(
            nodeStorageKey = c.getString(c.getColumnIndexOrThrow("node_storage_key")),
            type = ProfileType.fromWire(c.getString(c.getColumnIndexOrThrow("type"))),
            hashUpper = c.getString(c.getColumnIndexOrThrow("hash_upper")),
            text = c.getString(c.getColumnIndexOrThrow("text_content")),
            title = c.getStringOrNull("title"),
            tags = tagsRaw?.let(::decodeTagsJson),
            starred = c.getInt(c.getColumnIndexOrThrow("starred")) != 0,
            pinned = c.getInt(c.getColumnIndexOrThrow("pinned")) != 0,
            size = c.getLong(c.getColumnIndexOrThrow("size_bytes")),
            hasData = c.getInt(c.getColumnIndexOrThrow("has_data")) != 0,
            transferDataHash = c.getStringOrNull("transfer_data_hash"),
            localDataPath = c.getStringOrNull("local_data_path"),
            createTimeEpochMs = c.getLong(c.getColumnIndexOrThrow("create_time_ms")),
            lastModifiedEpochMs = c.getLong(c.getColumnIndexOrThrow("last_modified_ms")),
            lastAccessedEpochMs = c.getLong(c.getColumnIndexOrThrow("last_accessed_ms")),
            version = c.getInt(c.getColumnIndexOrThrow("version")),
            isDeleted = c.getInt(c.getColumnIndexOrThrow("is_deleted")) != 0,
            needsMetadataBackfill = c.getInt(c.getColumnIndexOrThrow("needs_metadata_backfill")) != 0
        )
    }

    private fun readUploadRow(c: Cursor): PendingUploadItem {
        return PendingUploadItem(
            id = c.getLong(c.getColumnIndexOrThrow("id")),
            nodeStorageKey = c.getString(c.getColumnIndexOrThrow("node_storage_key")),
            type = ProfileType.fromWire(c.getString(c.getColumnIndexOrThrow("type"))),
            hashUpper = c.getString(c.getColumnIndexOrThrow("hash_upper")),
            text = c.getString(c.getColumnIndexOrThrow("text_content")),
            dataFilePath = c.getStringOrNull("data_file_path"),
            dataName = c.getStringOrNull("data_name"),
            transferDataHash = c.getStringOrNull("transfer_data_hash"),
            sizeBytes = c.getLong(c.getColumnIndexOrThrow("size_bytes")),
            createdAtMs = c.getLong(c.getColumnIndexOrThrow("created_at_ms")),
            retryCount = c.getInt(c.getColumnIndexOrThrow("retry_count")),
            nextAttemptAtMs = c.getLong(c.getColumnIndexOrThrow("next_attempt_at_ms")),
            lastError = c.getStringOrNull("last_error")
        )
    }

    private fun Cursor.getStringOrNull(col: String): String? {
        val idx = getColumnIndexOrThrow(col)
        return if (isNull(idx)) null else getString(idx)
    }

    private fun encodeTagsJson(tags: List<String>): String {
        val arr = JSONArray()
        tags.forEach { arr.put(it) }
        return arr.toString()
    }

    private fun decodeTagsJson(raw: String): List<String> = runCatching {
        val arr = JSONArray(raw)
        List(arr.length()) { i -> arr.getString(i) }
    }.getOrDefault(emptyList())

    private fun encodeCapabilities(caps: ServerCapabilitiesDto): String {
        return JSONObject().apply {
            put("historySync", caps.historySync)
            put("historyTags", caps.historyTags)
            put("historyTitle", caps.historyTitle)
        }.toString()
    }

    private fun decodeCapabilities(raw: String): ServerCapabilitiesDto? = runCatching {
        val obj = JSONObject(raw)
        ServerCapabilitiesDto(
            historySync = obj.optBoolean("historySync", false),
            historyTags = obj.optBoolean("historyTags", false),
            historyTitle = obj.optBoolean("historyTitle", false)
        )
    }.getOrNull()
}
