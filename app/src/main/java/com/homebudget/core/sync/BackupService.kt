package com.homebudget.core.sync

import androidx.room.withTransaction
import com.homebudget.core.common.LocalIdentity
import com.homebudget.core.common.TimeProvider
import com.homebudget.core.database.AppDatabase
import com.homebudget.core.database.HouseholdEntity
import com.homebudget.core.drive.DriveRemote
import com.homebudget.core.security.HouseholdCrypto
import com.homebudget.core.security.HouseholdKeyStore
import com.homebudget.data.mapper.toDto
import com.homebudget.data.remote.SnapshotContent
import com.homebudget.data.remote.SnapshotFile
import com.homebudget.data.remote.SyncJson
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

data class BackupVerification(
    val remoteOperations: Int,
    val readable: Int,
    val corrupted: Int,
    val invalid: Int,
    val localPending: Int,
    /** Operations this device created and marked synced, but which are missing from Drive. */
    val missingFromDrive: Int,
) {
    val healthy: Boolean get() = corrupted == 0 && invalid == 0 && missingFromDrive == 0
}

/**
 * Backup verification, verified rebuild and encrypted snapshots.
 * The operation log in Drive is the authoritative history; snapshots are recovery aids only and
 * the log is never compacted in v1.0 (see docs/SYNC-ARCHITECTURE.md).
 */
@Singleton
class BackupService @Inject constructor(
    private val db: AppDatabase,
    private val remote: DriveRemote,
    private val crypto: HouseholdCrypto,
    private val keys: HouseholdKeyStore,
    private val engine: SyncEngine,
    private val identity: LocalIdentity,
    private val time: TimeProvider,
) {
    private suspend fun readAll(hh: String): Pair<List<RemoteOp>, BackupVerification> {
        val cursor = db.sync().cursor(hh) ?: throw IllegalStateException("Not connected to Google Drive")
        val key = keys.load(hh)?.first ?: throw IllegalStateException("Household key missing")
        val ops = ArrayList<RemoteOp>()
        var total = 0; var corrupted = 0; var invalid = 0
        val remoteIds = HashSet<String>()
        for (entry in engine.readLedger(cursor).entries) {
            if (!remoteIds.add(entry.op.operationId)) continue
            total++
            when (val r = engine.decodeEntry(entry, hh, key, cursor.driveFolderId)) {
                is SyncEngine.ReadResult.Ok -> ops += r.op
                SyncEngine.ReadResult.Corrupt -> corrupted++
                SyncEngine.ReadResult.Invalid -> invalid++
            }
        }
        val local = db.sync().allOperations(hh)
        val missing = local.count { it.syncStatus.name == "SYNCED" && it.operationId !in remoteIds }
        val pending = local.count { it.syncStatus.name != "SYNCED" }
        return ops to BackupVerification(total, ops.size, corrupted, invalid, pending, missing)
    }

    suspend fun verify(hh: String): BackupVerification = readAll(hh).second

    /**
     * Rebuilds local data from the Drive operation log. Refuses if the remote copy is not fully
     * readable or if this device still has unsynchronized changes, so healthy local data is never
     * replaced by an incomplete remote copy. The replacement happens in one database transaction.
     */
    suspend fun rebuildFromDrive(hh: String): SyncReport {
        val (ops, v) = readAll(hh)
        if (v.localPending > 0) throw IllegalStateException("Sync pending changes before rebuilding")
        if (!v.healthy) throw IllegalStateException("Remote backup failed verification; local data was kept")
        val cursor = db.sync().cursor(hh)!!
        val household = db.households().get(hh)!!
        var report = SyncReport()
        db.withTransaction {
            db.openHelper.writableDatabase.apply {
                execSQL("DELETE FROM applied_operations WHERE householdId = ?", arrayOf(hh))
                execSQL("DELETE FROM deferred_operations WHERE householdId = ?", arrayOf(hh))
                execSQL("DELETE FROM transactions WHERE householdId = ?", arrayOf(hh))
                execSQL("DELETE FROM budgets WHERE householdId = ?", arrayOf(hh))
                execSQL("DELETE FROM opening_balances WHERE householdId = ?", arrayOf(hh))
                execSQL("DELETE FROM categories WHERE householdId = ?", arrayOf(hh))
                execSQL("DELETE FROM members WHERE householdId = ?", arrayOf(hh))
            }
            db.households().upsert(
                HouseholdEntity(hh, household.name, household.ownerMemberId, household.currencyCode, household.driveFolderId,
                    household.createdAt, household.updatedAt, 0, "rebuild")
            )
            report = engine.applyAll(hh, cursor.ownerEmail, ops)
            if (report.deferred > 0 || report.rejected > 0) {
                throw IllegalStateException("Rebuild could not apply ${report.deferred + report.rejected} operations; local data was kept")
            }
        }
        return report
    }

    /** Writes an encrypted, hash-verified snapshot of current local state to `snapshots/`. */
    suspend fun createSnapshot(hh: String): String {
        db.sync().cursor(hh) ?: throw IllegalStateException("Not connected to Google Drive")
        val (key, version) = keys.load(hh) ?: throw IllegalStateException("Household key missing")
        val content = db.withTransaction {
            SnapshotContent(
                household = db.households().get(hh)!!.toDto(),
                members = db.members().all(hh).map { it.toDto() },
                categories = db.categories().all(hh).map { it.toDto() },
                budgets = db.budgets().all(hh).map { it.toDto() },
                openingBalances = db.openingBalances().all(hh).map { it.toDto() },
                transactions = db.transactions().allIncludingDeleted(hh).map { it.toDto(db.transactionItems().forTransaction(it.id)) },
                appliedOperationIds = db.sync().appliedIds(hh).sorted(),
            )
        }
        val plain = SyncJson.encodeToString(SnapshotContent.serializer(), content).toByteArray()
        val now = time.now().toEpochMilli()
        val aad = "homebudget-snapshot|$hh|$now".toByteArray()
        val sealed = crypto.encrypt(key, plain, aad)
        val file = SnapshotFile(
            householdId = hh, createdAt = now, createdByDevice = identity.deviceId(), keyVersion = version,
            nonce = HouseholdCrypto.b64(sealed.nonce), ciphertext = HouseholdCrypto.b64(sealed.ciphertext), sha256 = sha256(plain),
        )
        // Snapshots go to this account's own My Drive (recovery aid only; the ledger is authoritative).
        val name = "HomeBudget snapshot_${now}_${identity.deviceId()}.json"
        val created = remote.createTextFile(null, name, SyncJson.encodeToString(SnapshotFile.serializer(), file))
        // Read back and verify integrity.
        val back = SyncJson.decodeFromString<SnapshotFile>(remote.download(created.id))
        val decrypted = crypto.decrypt(key, com.homebudget.core.security.Sealed(HouseholdCrypto.unb64(back.nonce), HouseholdCrypto.unb64(back.ciphertext)),
            "homebudget-snapshot|$hh|${back.createdAt}".toByteArray())
        check(sha256(decrypted) == back.sha256) { "Snapshot verification failed" }
        return name
    }

    private fun sha256(b: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
}
