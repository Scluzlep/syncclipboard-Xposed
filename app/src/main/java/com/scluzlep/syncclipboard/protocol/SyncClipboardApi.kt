package com.scluzlep.syncclipboard.protocol

import com.scluzlep.syncclipboard.model.HistoryRecordUpdateDto
import com.scluzlep.syncclipboard.model.ProfileDto
import java.io.File

open class RemoteProtocolException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

class RemoteConflictException(
    val serverUpdate: HistoryRecordUpdateDto?,
    message: String
) : RemoteProtocolException(message)

class RemoteNotFoundException(message: String) : RemoteProtocolException(message)

class RemoteRejectedException(
    val statusCode: Int,
    message: String
) : RemoteProtocolException(message)

data class RemoteProfileSnapshot(
    val profile: ProfileDto,
    val etag: String? = null
)

interface ClipboardTransportClient : AutoCloseable {
    suspend fun testConnection()
    suspend fun initialize()
    suspend fun getProfile(): RemoteProfileSnapshot
    suspend fun setProfile(profile: ProfileDto)
    suspend fun uploadFile(fileName: String, localFile: File)
    suspend fun downloadFile(fileName: String, destFile: File)
}
