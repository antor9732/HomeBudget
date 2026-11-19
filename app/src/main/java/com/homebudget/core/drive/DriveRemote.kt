package com.homebudget.core.drive

data class DriveFile(
    val id: String,
    val name: String,
    val mimeType: String,
    /** Server-assigned creation time (epoch millis); not a device clock. */
    val createdTime: Long,
    val ownerEmail: String?,
    val lastModifyingEmail: String?,
    val ownedByMe: Boolean,
    /** Parent folder ids (only filled when Drive returns them). */
    val parents: List<String> = emptyList(),
    /** Drive's monotonically increasing file version; changes whenever content changes. */
    val version: Long = 0,
)

data class DrivePage(val files: List<DriveFile>, val nextPageToken: String?)

data class DrivePermission(val id: String, val email: String?, val role: String, val type: String)

open class DriveException(message: String, cause: Throwable? = null) : Exception(message, cause)
/** 401 or authorization missing/expired. User interaction may be required. */
class DriveAuthException(message: String) : DriveException(message)
/** 403/404 on the household folder: access was revoked or never granted. */
class DriveAccessDeniedException(message: String) : DriveException(message)
/** 429/5xx/network – safe to retry later. */
class DriveTransientException(message: String, cause: Throwable? = null) : DriveException(message, cause)

const val FOLDER_MIME = "application/vnd.google-apps.folder"

/** Thin abstraction over Drive API v3 so sync logic is testable with an in-memory fake. */
interface DriveRemote {
    suspend fun createFolder(name: String, parentId: String?): DriveFile
    /** Household ledger files the app can see for this account (created by it, or picked). */
    suspend fun findHouseholdLedgers(): List<DriveFile>
    suspend fun getFile(fileId: String): DriveFile
    suspend fun listChildren(parentId: String, createdAfterMillis: Long?, pageToken: String?): DrivePage
    suspend fun findChildByName(parentId: String, name: String): DriveFile?
    /** Creates a JSON file; [parentId] null means the user's My Drive root. */
    suspend fun createTextFile(parentId: String?, name: String, content: String): DriveFile
    suspend fun updateTextFile(fileId: String, content: String)
    suspend fun download(fileId: String): String
    suspend fun share(fileId: String, email: String, role: String, message: String?): String
    suspend fun listPermissions(fileId: String): List<DrivePermission>
    suspend fun deletePermission(fileId: String, permissionId: String)
    suspend fun updatePermissionRole(fileId: String, permissionId: String, role: String)
}

const val HOUSEHOLD_FOLDER_PREFIX = "HomeBudget - "
const val MANIFEST_NAME = "household-manifest.json"
const val LEDGER_SUFFIX = ".homebudget.json"
const val LEDGER_MIME = "application/json"
const val KEY_ENVELOPE_NAME = "key-envelope.json"
