package com.homebudget.core.drive

import com.homebudget.core.auth.AccessTokenProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.time.Instant
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Google Drive REST v3 client using OkHttp. Access tokens are fetched per call from
 * [AccessTokenProvider] (Google Identity Services) and never persisted.
 */
@Singleton
class HttpDriveRemote @Inject constructor(
    private val client: OkHttpClient,
    private val tokens: AccessTokenProvider,
) : DriveRemote {

    private val base = "https://www.googleapis.com/drive/v3"
    private val upload = "https://www.googleapis.com/upload/drive/v3"
    private val fileFields = "id,name,mimeType,createdTime,ownedByMe,owners(emailAddress),lastModifyingUser(emailAddress),parents,version"
    private val json = Json { ignoreUnknownKeys = true }
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    override suspend fun createFolder(name: String, parentId: String?): DriveFile {
        val body = buildJsonObject {
            put("name", name)
            put("mimeType", FOLDER_MIME)
            if (parentId != null) put("parents", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive(parentId)) })
        }.toString().toRequestBody(jsonType)
        val url = "$base/files".toHttpUrl().newBuilder().addQueryParameter("fields", fileFields).build()
        return parseFile(call(Request.Builder().url(url).post(body)))
    }

    override suspend fun findHouseholdLedgers(): List<DriveFile> {
        val q = "mimeType = '$LEDGER_MIME' and name contains '${HOUSEHOLD_FOLDER_PREFIX.trim()}' and trashed = false"
        val out = ArrayList<DriveFile>()
        var token: String? = null
        do {
            val url = "$base/files".toHttpUrl().newBuilder()
                .addQueryParameter("q", q)
                .addQueryParameter("fields", "nextPageToken,files($fileFields)")
                .addQueryParameter("pageSize", "100")
                .addQueryParameter("corpora", "user")
                .apply { token?.let { addQueryParameter("pageToken", it) } }
                .build()
            val obj = call(Request.Builder().url(url).get())
            obj["files"]?.jsonArray?.forEach { out += parseFile(it.jsonObject) }
            token = obj["nextPageToken"]?.jsonPrimitive?.content
        } while (token != null)
        return out
    }

    override suspend fun getFile(fileId: String): DriveFile {
        val url = "$base/files/$fileId".toHttpUrl().newBuilder().addQueryParameter("fields", "$fileFields,trashed").build()
        val obj = call(Request.Builder().url(url).get(), folderAccess = true)
        if (obj["trashed"]?.jsonPrimitive?.booleanOrNull == true) throw DriveAccessDeniedException("Household folder is in trash")
        return parseFile(obj)
    }

    override suspend fun listChildren(parentId: String, createdAfterMillis: Long?, pageToken: String?): DrivePage {
        val q = buildString {
            append("'$parentId' in parents and trashed = false")
            if (createdAfterMillis != null) append(" and createdTime > '${rfc3339(createdAfterMillis)}'")
        }
        val url = "$base/files".toHttpUrl().newBuilder()
            .addQueryParameter("q", q)
            .addQueryParameter("orderBy", "createdTime")
            .addQueryParameter("pageSize", "200")
            .addQueryParameter("fields", "nextPageToken,files($fileFields)")
            .apply { pageToken?.let { addQueryParameter("pageToken", it) } }
            .build()
        val obj = call(Request.Builder().url(url).get(), folderAccess = true)
        return DrivePage(obj["files"]?.jsonArray?.map { parseFile(it.jsonObject) } ?: emptyList(), obj["nextPageToken"]?.jsonPrimitive?.content)
    }

    override suspend fun findChildByName(parentId: String, name: String): DriveFile? {
        val safe = name.replace("'", "\\'")
        val url = "$base/files".toHttpUrl().newBuilder()
            .addQueryParameter("q", "'$parentId' in parents and name = '$safe' and trashed = false")
            .addQueryParameter("fields", "files($fileFields)")
            .addQueryParameter("pageSize", "10")
            .build()
        val obj = call(Request.Builder().url(url).get(), folderAccess = true)
        return obj["files"]?.jsonArray?.firstOrNull()?.let { parseFile(it.jsonObject) }
    }

    override suspend fun createTextFile(parentId: String?, name: String, content: String): DriveFile {
        val meta = buildJsonObject {
            put("name", name)
            put("mimeType", LEDGER_MIME)
            if (parentId != null) put("parents", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive(parentId)) })
        }.toString()
        val multipart = MultipartBody.Builder().setType("multipart/related".toMediaType())
            .addPart(meta.toRequestBody(jsonType))
            .addPart(content.toRequestBody(jsonType))
            .build()
        val url = "$upload/files".toHttpUrl().newBuilder()
            .addQueryParameter("uploadType", "multipart")
            .addQueryParameter("fields", fileFields)
            .build()
        return parseFile(call(Request.Builder().url(url).post(multipart), folderAccess = true))
    }

    override suspend fun updateTextFile(fileId: String, content: String) {
        val url = "$upload/files/$fileId".toHttpUrl().newBuilder().addQueryParameter("uploadType", "media").build()
        call(Request.Builder().url(url).patch(content.toRequestBody(jsonType)), folderAccess = true)
    }

    override suspend fun download(fileId: String): String {
        val url = "$base/files/$fileId".toHttpUrl().newBuilder().addQueryParameter("alt", "media").build()
        return callRaw(Request.Builder().url(url).get(), folderAccess = true)
    }

    override suspend fun share(fileId: String, email: String, role: String, message: String?): String {
        val url = "$base/files/$fileId/permissions".toHttpUrl().newBuilder()
            .addQueryParameter("sendNotificationEmail", "true")
            .apply { if (message != null) addQueryParameter("emailMessage", message) }
            .addQueryParameter("fields", "id")
            .build()
        val body = buildJsonObject { put("type", "user"); put("role", role); put("emailAddress", email) }.toString().toRequestBody(jsonType)
        return call(Request.Builder().url(url).post(body))["id"]!!.jsonPrimitive.content
    }

    override suspend fun listPermissions(fileId: String): List<DrivePermission> {
        val url = "$base/files/$fileId/permissions".toHttpUrl().newBuilder()
            .addQueryParameter("fields", "permissions(id,emailAddress,role,type)").build()
        val obj = call(Request.Builder().url(url).get())
        return obj["permissions"]?.jsonArray?.map {
            val p = it.jsonObject
            DrivePermission(p.str("id")!!, p.str("emailAddress"), p.str("role") ?: "", p.str("type") ?: "")
        } ?: emptyList()
    }

    override suspend fun deletePermission(fileId: String, permissionId: String) {
        callRaw(Request.Builder().url("$base/files/$fileId/permissions/$permissionId").delete())
    }

    override suspend fun updatePermissionRole(fileId: String, permissionId: String, role: String) {
        val body = buildJsonObject { put("role", role) }.toString().toRequestBody(jsonType)
        call(Request.Builder().url("$base/files/$fileId/permissions/$permissionId").patch(body))
    }

    // ---------------------------------------------------------------------------------------------

    private suspend fun call(builder: Request.Builder, folderAccess: Boolean = false): JsonObject {
        val text = callRaw(builder, folderAccess)
        return if (text.isBlank()) JsonObject(emptyMap()) else json.parseToJsonElement(text).jsonObject
    }

    private suspend fun callRaw(builder: Request.Builder, folderAccess: Boolean = false): String = withContext(Dispatchers.IO) {
        var attempt = 0
        var lastError: Exception? = null
        while (attempt < 4) {
            val token = tokens.accessToken()
            val request = builder.header("Authorization", "Bearer $token").build()
            try {
                client.newCall(request).execute().use { resp ->
                    val body = resp.body?.string().orEmpty()
                    when {
                        resp.isSuccessful -> return@withContext body
                        resp.code == 401 -> {
                            tokens.invalidate(token)
                            if (attempt >= 1) throw DriveAuthException("Drive authorization expired")
                        }
                        resp.code == 429 || resp.code >= 500 || (resp.code == 403 && body.contains("rateLimitExceeded")) ->
                            lastError = DriveTransientException("Drive HTTP ${resp.code}")
                        resp.code == 403 && body.contains("insufficientFilePermissions") ->
                            // Account can see the folder but may not write (e.g. Viewer role).
                            throw if (folderAccess) DriveAccessDeniedException("No write permission") else DriveException("No permission")
                        resp.code == 403 && (body.contains("ACCESS_TOKEN_SCOPE_INSUFFICIENT") || body.contains("insufficientPermissions")) ->
                            throw DriveAuthException("Drive scope not granted")
                        (resp.code == 403 || resp.code == 404) && folderAccess ->
                            throw DriveAccessDeniedException("Drive access denied (HTTP ${resp.code})")
                        else -> throw DriveException("Drive HTTP ${resp.code}")
                    }
                }
            } catch (e: IOException) {
                lastError = DriveTransientException("Network error", e)
            }
            attempt++
            delay(500L * (1 shl attempt)) // exponential backoff
        }
        throw (lastError as? DriveException) ?: DriveTransientException("Drive request failed", lastError)
    }

    private fun parseFile(o: JsonObject): DriveFile = DriveFile(
        id = o.str("id")!!,
        name = o.str("name") ?: "",
        mimeType = o.str("mimeType") ?: "",
        createdTime = o.str("createdTime")?.let { Instant.parse(it).toEpochMilli() } ?: 0L,
        ownerEmail = (o["owners"] as? JsonArray)?.firstOrNull()?.jsonObject?.str("emailAddress"),
        lastModifyingEmail = (o["lastModifyingUser"] as? JsonObject)?.str("emailAddress"),
        ownedByMe = o["ownedByMe"]?.jsonPrimitive?.boolean ?: false,
        version = o.str("version")?.toLongOrNull() ?: 0L,
        parents = (o["parents"] as? JsonArray)?.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }.orEmpty(),
    )

    private fun JsonObject.str(k: String): String? = (this[k] as? kotlinx.serialization.json.JsonPrimitive)?.content

    private fun rfc3339(millis: Long): String = DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(millis))
}
