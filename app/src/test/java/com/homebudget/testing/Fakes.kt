package com.homebudget.testing

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.homebudget.core.common.AccountInfo
import com.homebudget.core.common.LocalIdentity
import com.homebudget.core.common.Session
import com.homebudget.core.common.TimeProvider
import com.homebudget.core.database.AppDatabase
import com.homebudget.core.drive.DriveAccessDeniedException
import com.homebudget.core.drive.DriveFile
import com.homebudget.core.drive.DrivePage
import com.homebudget.core.drive.DrivePermission
import com.homebudget.core.drive.DriveRemote
import com.homebudget.core.drive.DriveTransientException
import com.homebudget.core.drive.FOLDER_MIME
import com.homebudget.core.drive.HOUSEHOLD_FOLDER_PREFIX
import com.homebudget.core.security.DeviceKeyWrapper
import com.homebudget.core.security.HouseholdCrypto
import com.homebudget.core.security.HouseholdKeyStore
import com.homebudget.core.sync.BackupService
import com.homebudget.core.sync.ChangeListener
import com.homebudget.core.sync.HouseholdCloudService
import com.homebudget.core.sync.LocalChangeWriter
import com.homebudget.core.sync.SyncEngine
import com.homebudget.data.repository.FinanceRepositoryImpl
import com.homebudget.data.repository.HouseholdRepositoryImpl
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

class FakeTime(var instant: Instant = Instant.parse("2026-11-16T06:00:00Z")) : TimeProvider {
    override fun now(): Instant = instant
    override fun zone(): ZoneId = ZoneId.of("Asia/Dhaka")
}

class FakeIdentity(account: AccountInfo?) : LocalIdentity {
    private val deviceId = UUID.randomUUID().toString()
    val state = MutableStateFlow(Session(null, null, account))
    override val session: Flow<Session> = state
    override suspend fun deviceId() = deviceId
    override suspend fun currentSession() = state.value
    override suspend fun setHousehold(householdId: String?, memberId: String?) {
        state.value = state.value.copy(householdId = householdId, memberId = memberId)
    }
    fun setAccount(a: AccountInfo?) { state.value = state.value.copy(account = a) }
}

/** Not secure: test-only stand-in for the Android Keystore. */
class FakeKeyWrapper : DeviceKeyWrapper {
    override fun wrap(plain: ByteArray) = HouseholdCrypto.b64(plain)
    override fun unwrap(wrapped: String) = HouseholdCrypto.unb64(wrapped)
}

class CountingListener : ChangeListener {
    var local = 0; var data = 0
    override fun onLocalChange(householdId: String) { local++ }
    override fun onDataChanged(householdId: String) { data++ }
}

/** In-memory Google Drive shared by several simulated accounts/devices. */
class FakeDriveServer {
    data class Node(
        val id: String, var name: String, val mime: String, val parent: String?, val owner: String,
        var content: String, val createdTime: Long, var lastModifier: String, var version: Long = 1,
    )
    val nodes = LinkedHashMap<String, Node>()
    val permissions = HashMap<String, MutableList<DrivePermission>>()
    private val clock = AtomicLong(1_800_000_000_000L)
    var pageSize = 3
    var failUploadsAfterWrite = 0 // simulate "uploaded but response lost"
    /** Hook to simulate another phone writing right after an upload. */
    var afterUpdate: ((String) -> Unit)? = null

    fun tick() = clock.addAndGet(1000)

    fun canRead(email: String, id: String): Boolean {
        var n = nodes[id] ?: return false
        while (true) {
            if (n.owner.equals(email, true)) return true
            if (permissions[n.id].orEmpty().any { it.email.equals(email, true) }) return true
            n = nodes[n.parent ?: return false] ?: return false
        }
    }

    fun canWrite(email: String, id: String): Boolean {
        var n = nodes[id] ?: return false
        while (true) {
            if (n.owner.equals(email, true)) return true
            if (permissions[n.id].orEmpty().any { it.email.equals(email, true) && it.role == "writer" }) return true
            n = nodes[n.parent ?: return false] ?: return false
        }
    }
}

class FakeDriveRemote(private val server: FakeDriveServer, var email: String) : DriveRemote {
    private fun Node(n: FakeDriveServer.Node) = DriveFile(n.id, n.name, n.mime, n.createdTime, n.owner, n.lastModifier, n.owner.equals(email, true), listOfNotNull(n.parent), n.version)

    private fun requireRead(id: String) { if (!server.canRead(email, id)) throw DriveAccessDeniedException("denied") }
    private fun requireWrite(id: String) { if (!server.canWrite(email, id)) throw DriveAccessDeniedException("denied") }

    private fun create(name: String, mime: String, parent: String?, content: String): DriveFile {
        val n = FakeDriveServer.Node(UUID.randomUUID().toString(), name, mime, parent, email, content, server.tick(), email)
        server.nodes[n.id] = n
        return Node(n)
    }

    override suspend fun createFolder(name: String, parentId: String?): DriveFile {
        parentId?.let { requireWrite(it) }
        return create(name, FOLDER_MIME, parentId, "")
    }

    override suspend fun findHouseholdLedgers() = server.nodes.values
        .filter { it.mime == "application/json" && it.name.startsWith(HOUSEHOLD_FOLDER_PREFIX) && server.canRead(email, it.id) }
        .map { Node(it) }

    override suspend fun getFile(fileId: String): DriveFile { requireRead(fileId); return Node(server.nodes[fileId]!!) }

    override suspend fun listChildren(parentId: String, createdAfterMillis: Long?, pageToken: String?): DrivePage {
        requireRead(parentId)
        val all = server.nodes.values.filter { it.parent == parentId && (createdAfterMillis == null || it.createdTime > createdAfterMillis) }
            .sortedBy { it.createdTime }
        val start = pageToken?.toInt() ?: 0
        val page = all.drop(start).take(server.pageSize)
        val next = if (start + server.pageSize < all.size) (start + server.pageSize).toString() else null
        return DrivePage(page.map { Node(it) }, next)
    }

    override suspend fun findChildByName(parentId: String, name: String): DriveFile? {
        requireRead(parentId)
        return server.nodes.values.firstOrNull { it.parent == parentId && it.name == name }?.let { Node(it) }
    }

    override suspend fun createTextFile(parentId: String?, name: String, content: String): DriveFile {
        parentId?.let { requireWrite(it) }
        val f = create(name, "application/json", parentId, content)
        if (server.failUploadsAfterWrite > 0) {
            server.failUploadsAfterWrite--
            throw DriveTransientException("response lost")
        }
        return f
    }

    override suspend fun updateTextFile(fileId: String, content: String) {
        requireWrite(fileId)
        server.nodes[fileId]!!.apply { this.content = content; lastModifier = email; version++ }
        server.afterUpdate?.invoke(fileId)
        if (server.failUploadsAfterWrite > 0) {
            server.failUploadsAfterWrite--
            throw DriveTransientException("response lost")
        }
    }

    override suspend fun download(fileId: String): String { requireRead(fileId); return server.nodes[fileId]!!.content }

    override suspend fun share(fileId: String, email: String, role: String, message: String?): String {
        requireWrite(fileId)
        val p = DrivePermission(UUID.randomUUID().toString(), email, role, "user")
        server.permissions.getOrPut(fileId) { mutableListOf() } += p
        return p.id
    }

    override suspend fun listPermissions(fileId: String): List<DrivePermission> {
        requireRead(fileId)
        val owner = server.nodes[fileId]!!.owner
        return listOf(DrivePermission("owner", owner, "owner", "user")) + server.permissions[fileId].orEmpty()
    }

    override suspend fun deletePermission(fileId: String, permissionId: String) {
        requireWrite(fileId); server.permissions[fileId]?.removeIf { it.id == permissionId }
    }

    override suspend fun updatePermissionRole(fileId: String, permissionId: String, role: String) {
        requireWrite(fileId)
        server.permissions[fileId]?.replaceAll { if (it.id == permissionId) it.copy(role = role) else it }
    }
}

/** One simulated phone: its own Room database, identity and keystore, sharing the fake Drive. */
class Device(server: FakeDriveServer, email: String, name: String, val time: FakeTime = FakeTime()) {
    val db: AppDatabase = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java)
        .allowMainThreadQueries().build()
    val identity = FakeIdentity(AccountInfo(email, name, "sub-$email"))
    val listener = CountingListener()
    val drive = FakeDriveRemote(server, email)
    val crypto = HouseholdCrypto()
    val keys = HouseholdKeyStore(db.sync(), FakeKeyWrapper())
    val writer = LocalChangeWriter(db, identity, time, listener)
    val households = HouseholdRepositoryImpl(db, identity, writer, time)
    val finance = FinanceRepositoryImpl(db, households, writer, identity, time)
    val engine = SyncEngine(db, drive, crypto, keys, identity, time, listener)
    val cloud = HouseholdCloudService(db, drive, crypto, keys, identity, households, engine, time)
    val backup = BackupService(db, drive, crypto, keys, engine, identity, time)

    suspend fun hh() = households.currentContext()!!.householdId
    fun close() = db.close()
}
