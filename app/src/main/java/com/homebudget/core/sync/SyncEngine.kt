package com.homebudget.core.sync

import androidx.room.withTransaction
import com.homebudget.core.common.LocalIdentity
import com.homebudget.core.common.TimeProvider
import com.homebudget.core.database.AccessState
import com.homebudget.core.database.AppDatabase
import com.homebudget.core.database.AppliedOperationEntity
import com.homebudget.core.database.DeferredOperationEntity
import com.homebudget.core.database.SyncCursorEntity
import com.homebudget.core.database.SyncOperationEntity
import com.homebudget.core.database.SyncState
import com.homebudget.core.drive.HOUSEHOLD_FOLDER_PREFIX
import com.homebudget.core.drive.KEY_ENVELOPE_NAME
import com.homebudget.core.drive.LEDGER_SUFFIX
import com.homebudget.data.remote.HouseholdLedger
import com.homebudget.data.remote.KeyEnvelope
import com.homebudget.data.remote.LEDGER_FORMAT
import com.homebudget.data.remote.LedgerEntry
import com.homebudget.data.remote.SCHEMA_VERSION
import com.homebudget.core.drive.DriveAccessDeniedException
import com.homebudget.core.drive.DriveAuthException
import com.homebudget.core.drive.DriveRemote
import com.homebudget.core.drive.DriveTransientException
import com.homebudget.core.security.CryptoException
import com.homebudget.core.security.HouseholdCrypto
import com.homebudget.core.security.HouseholdKeyStore
import com.homebudget.core.security.Sealed
import com.homebudget.data.remote.EntityType
import com.homebudget.data.remote.OperationEnvelope
import com.homebudget.data.remote.RemoteOperationFile
import com.homebudget.data.remote.SyncJson
import com.homebudget.domain.model.MemberStatus
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

data class SyncReport(
    val applied: Int = 0,
    val conflicts: Int = 0,
    val rejected: Int = 0,
    val deferred: Int = 0,
    val uploaded: Int = 0,
    val corrupted: Int = 0,
)

sealed interface SyncOutcome {
    data class Success(val report: SyncReport) : SyncOutcome
    data object LocalOnly : SyncOutcome
    data class AuthRequired(val message: String) : SyncOutcome
    data class AccessRevoked(val message: String) : SyncOutcome
    data class Failed(val message: String, val retryable: Boolean) : SyncOutcome
}

/** A decrypted, not-yet-applied remote operation. */
data class RemoteOp(val envelope: OperationEnvelope, val fileId: String, val authorEmail: String?, val createdTime: Long)

/**
 * Operation-based synchronization over one shared, encrypted Drive file (the household ledger).
 * Each local change is an immutable encrypted operation; the ledger is a grow-only set of them,
 * so a concurrent overwrite can only drop entries temporarily: their author re-adds them.
 */
@Singleton
class SyncEngine @Inject constructor(
    private val db: AppDatabase,
    private val remote: DriveRemote,
    private val crypto: HouseholdCrypto,
    private val keys: HouseholdKeyStore,
    private val identity: LocalIdentity,
    private val time: TimeProvider,
    private val listener: ChangeListener,
) {
    private val mutex = Mutex()
    private val applier = OperationApplier(db) { time.now().toEpochMilli() }

    suspend fun sync(householdId: String): SyncOutcome = mutex.withLock {
        var cursor = db.sync().cursor(householdId) ?: return SyncOutcome.LocalOnly
        val myEmail = identity.currentSession().account?.email
            ?: return markState(cursor, AccessState.AUTH_REQUIRED, "Sign in with Google to sync").let { SyncOutcome.AuthRequired("Not signed in") }
        val key = keys.load(householdId) ?: return SyncOutcome.Failed("Household key missing on this device", retryable = false)
        db.sync().upsertCursor(cursor.copy(lastAttemptAt = now()))

        try {
            if (cursor.isLegacyFolderLayout()) {
                if (!myEmail.equals(cursor.ownerEmail, ignoreCase = true)) {
                    markState(cursor, null, "The owner must update HomeBudget; then join the household again")
                    return SyncOutcome.Failed("Household storage needs the owner's update", retryable = false)
                }
                cursor = migrateLegacyFolder(cursor, key.first)
            }
            val ledger = readLedger(cursor)
            var report = pull(cursor, ledger, key.first)

            if (!myEmail.equals(cursor.ownerEmail, ignoreCase = true)) {
                val me = db.members().byEmail(householdId, myEmail)
                if (me == null || me.status == MemberStatus.REMOVED) {
                    markState(cursor, AccessState.ACCESS_REVOKED, "This account is not an active household member")
                    return SyncOutcome.AccessRevoked("Not an active member")
                }
            }
            report = report.copy(uploaded = push(cursor, ledger, myEmail, key.first, key.second))
            val latest = db.sync().cursor(householdId)!!
            db.sync().upsertCursor(latest.copy(lastSuccessfulSyncAt = now(), lastError = null, accessState = AccessState.OK))
            if (report.applied > 0 || report.conflicts > 0) listener.onDataChanged(householdId)
            return SyncOutcome.Success(report)
        } catch (e: DriveAuthException) {
            markState(cursor, AccessState.AUTH_REQUIRED, e.message)
            return SyncOutcome.AuthRequired(e.message ?: "Authorization required")
        } catch (e: DriveAccessDeniedException) {
            markState(cursor, AccessState.ACCESS_REVOKED, "Access to the household file was revoked")
            return SyncOutcome.AccessRevoked(e.message ?: "Access revoked")
        } catch (e: DriveTransientException) {
            markState(cursor, null, "Temporary network problem; will retry")
            return SyncOutcome.Failed(e.message ?: "Transient", retryable = true)
        } catch (e: Exception) {
            markState(cursor, null, "Sync error: ${e.javaClass.simpleName}")
            return SyncOutcome.Failed(e.javaClass.simpleName, retryable = true)
        }
    }

    private suspend fun markState(cursor: SyncCursorEntity, state: AccessState?, error: String?) {
        val latest = db.sync().cursor(cursor.householdId) ?: cursor
        db.sync().upsertCursor(latest.copy(accessState = state ?: latest.accessState, lastError = error))
    }

    // -------------------------------------------------------------------------------------- ledger

    /** Downloads and validates the household ledger file. */
    suspend fun readLedger(cursor: SyncCursorEntity): HouseholdLedger {
        val text = remote.download(cursor.driveFolderId)
        val ledger = try {
            SyncJson.decodeFromString<HouseholdLedger>(text)
        } catch (e: Exception) {
            throw IllegalStateException("Household file is unreadable", e)
        }
        check(ledger.format == LEDGER_FORMAT && ledger.householdId == cursor.householdId) { "Household file does not match" }
        return ledger
    }

    // ---------------------------------------------------------------------------------------- pull

    private suspend fun pull(cursor: SyncCursorEntity, ledger: HouseholdLedger, key: ByteArray): SyncReport {
        val hh = cursor.householdId
        val fetched = ArrayList<RemoteOp>()
        var corrupted = 0
        var rejected = 0
        for (entry in ledger.entries) {
            val opId = entry.op.operationId
            if (db.sync().isApplied(opId)) continue
            when (val r = decodeEntry(entry, hh, key, cursor.driveFolderId)) {
                is ReadResult.Ok -> fetched += r.op
                is ReadResult.Corrupt -> corrupted++
                is ReadResult.Invalid -> { rejected++; if (OP_ID.matches(opId)) markRejected(hh, opId, cursor.driveFolderId) }
            }
        }
        val outcome = applyAll(hh, cursor.ownerEmail, fetched + loadDeferred(hh))
        return outcome.copy(corrupted = corrupted, rejected = outcome.rejected + rejected)
    }

    sealed interface ReadResult {
        data class Ok(val op: RemoteOp) : ReadResult
        data object Corrupt : ReadResult
        data object Invalid : ReadResult
    }

    fun decodeEntry(entry: LedgerEntry, householdId: String, key: ByteArray, ledgerId: String): ReadResult {
        val file = entry.op
        if (file.format != "homebudget-op" || !OP_ID.matches(file.operationId) || file.householdId != householdId || entry.author.isBlank()) {
            return ReadResult.Invalid
        }
        val plain = try {
            crypto.decrypt(key, Sealed(HouseholdCrypto.unb64(file.nonce), HouseholdCrypto.unb64(file.ciphertext)), file.aad())
        } catch (e: CryptoException) {
            return ReadResult.Corrupt
        } catch (e: IllegalArgumentException) {
            return ReadResult.Corrupt
        }
        val env = OperationEnvelope(
            operationId = file.operationId, householdId = file.householdId, entityType = file.entityType,
            entityId = file.entityId, operationType = file.operationType, deviceId = file.deviceId,
            actorMemberId = file.actorMemberId, baseOperationId = file.baseOperationId,
            schemaVersion = file.schemaVersion, createdAt = file.createdAt, payload = plain.toString(Charsets.UTF_8),
        )
        return ReadResult.Ok(RemoteOp(env, ledgerId, entry.author.lowercase(), file.createdAt))
    }

    private suspend fun loadDeferred(hh: String): List<RemoteOp> = db.sync().deferred(hh).mapNotNull { d ->
        runCatching { SyncJson.decodeFromString<OperationEnvelope>(d.decryptedJson) }.getOrNull()
            ?.let { RemoteOp(it, d.driveFileId, d.authorEmail, d.remoteCreatedAt) }
    }

    /** Validates, authorizes and applies operations; dependency-blocked ones are deferred. */
    suspend fun applyAll(hh: String, ownerEmail: String, ops: List<RemoteOp>): SyncReport {
        var applied = 0; var conflicts = 0; var rejected = 0; var deferred = 0
        val ordered = ops.distinctBy { it.envelope.operationId }.sortedWith(
            compareBy<RemoteOp>({ entityOrder(it.envelope.entityType) }, { it.createdTime }, { it.envelope.operationId })
        )
        // Two passes so a dependency that arrives later in the same batch can unblock earlier items.
        var pending = ordered
        repeat(2) { pass ->
            val stillBlocked = ArrayList<Pair<RemoteOp, String>>()
            for (op in pending) {
                val env = op.envelope
                if (db.sync().isApplied(env.operationId)) continue
                val shapeError = OperationAuthorizer.validateShape(env, hh)
                if (shapeError != null) {
                    rejected++; markRejected(hh, env.operationId, op.fileId); continue
                }

                val members = db.members().all(hh)
                val existingCreator = if (env.entityType == EntityType.TRANSACTION.name) db.transactions().get(env.entityId)?.createdBy else null
                when (val auth = OperationAuthorizer.authorize(env, op.authorEmail, ownerEmail, members, op.createdTime, existingCreator)) {
                    is AuthDecision.Rejected -> {
                        if (auth.reason == "author is not a household member") stillBlocked += op to auth.reason
                        else { rejected++; markRejected(hh, env.operationId, op.fileId) }
                        continue
                    }
                    AuthDecision.Allowed -> Unit
                }
                when (val r = db.withTransaction { applier.apply(env, op.fileId) }) {
                    ApplyResult.Applied -> applied++
                    is ApplyResult.Conflict -> { conflicts++; if (r.incomingApplied) applied++ }
                    ApplyResult.Ignored -> Unit
                    is ApplyResult.Deferred -> stillBlocked += op to r.reason
                }
            }
            pending = stillBlocked.map { it.first }
            if (pass == 1 || pending.isEmpty()) {
                for ((op, reason) in stillBlocked) {
                    val prev = db.sync().deferred(hh).firstOrNull { it.operationId == op.envelope.operationId }
                    val attempts = (prev?.attempts ?: 0) + 1
                    if (attempts > MAX_DEFER_ATTEMPTS) {
                        rejected++; markRejected(hh, op.envelope.operationId, op.fileId)
                    } else {
                        deferred++
                        db.sync().upsertDeferred(
                            DeferredOperationEntity(
                                op.envelope.operationId, hh, op.fileId, op.authorEmail, op.createdTime,
                                SyncJson.encodeToString(OperationEnvelope.serializer(), op.envelope), reason, attempts,
                            )
                        )
                    }
                }
                return SyncReport(applied = applied, conflicts = conflicts, rejected = rejected, deferred = deferred)
            }
        }
        return SyncReport(applied, conflicts, rejected, deferred)
    }

    /** Rejected operations are recorded so they are not downloaded again; they are never applied. */
    private suspend fun markRejected(hh: String, opId: String, fileId: String) {
        db.sync().deleteDeferred(opId)
        db.sync().insertApplied(AppliedOperationEntity(opId, hh, "REJECTED", "", null, fileId, now()))
    }

    // ---------------------------------------------------------------------------------------- push

    /**
     * Adds this device's operations that the ledger does not contain yet (new ones, and any that a
     * concurrent upload from another phone overwrote), then reads the file back. An operation is
     * marked synced only after it has been seen in a downloaded copy.
     */
    private suspend fun push(cursor: SyncCursorEntity, first: HouseholdLedger, myEmail: String, key: ByteArray, keyVersion: Int): Int {
        val mine = db.sync().allOperations(cursor.householdId)
        if (mine.isEmpty()) return 0
        var ledger = first
        var uploaded = 0
        repeat(MAX_UPLOAD_ROUNDS) {
            val present = ledger.entries.mapTo(HashSet()) { it.op.operationId }
            mine.filter { it.operationId in present && it.syncStatus != SyncState.SYNCED }
                .forEach { db.sync().markSynced(it.operationId, cursor.driveFolderId) }
            val missing = mine.filter { it.operationId !in present }
            if (missing.isEmpty()) return uploaded
            val added = missing.map { LedgerEntry(myEmail.lowercase(), toRemoteFile(it, key, keyVersion)) }
            try {
                remote.updateTextFile(cursor.driveFolderId, SyncJson.encodeToString(HouseholdLedger.serializer(), ledger.copy(entries = ledger.entries + added)))
            } catch (e: Exception) {
                missing.forEach { db.sync().markFailed(it.operationId, if (e is DriveTransientException) "Network error" else e.javaClass.simpleName) }
                throw e
            }
            uploaded += missing.count { it.syncStatus != SyncState.SYNCED }
            ledger = readLedger(cursor)
        }
        return uploaded
    }

    /**
     * One-time upgrade for households connected with v1.0/v1.1 (one Drive file per operation).
     * Only the owner runs it: their app can read every file it created in the old folder.
     */
    private suspend fun migrateLegacyFolder(cursor: SyncCursorEntity, key: ByteArray): SyncCursorEntity {
        val envFile = remote.findChildByName(cursor.metadataFolderId ?: error("metadata folder unknown"), KEY_ENVELOPE_NAME)
            ?: error("key envelope missing")
        val envelope = SyncJson.decodeFromString<KeyEnvelope>(remote.download(envFile.id))
        val entries = ArrayList<LedgerEntry>()
        var page: String? = null
        do {
            val p = remote.listChildren(cursor.operationsFolderId, null, page)
            for (f in p.files) {
                parseOperationId(f.name) ?: continue
                val op = runCatching { SyncJson.decodeFromString<RemoteOperationFile>(remote.download(f.id)) }.getOrNull() ?: continue
                entries += LedgerEntry((f.ownerEmail ?: cursor.ownerEmail).lowercase(), op)
            }
            page = p.nextPageToken
        } while (page != null)
        val household = db.households().get(cursor.householdId) ?: error("household missing")
        val ledger = HouseholdLedger(
            schemaVersion = SCHEMA_VERSION, householdId = cursor.householdId, name = household.name,
            ownerEmail = cursor.ownerEmail, currencyCode = household.currencyCode, createdAt = household.createdAt,
            keyEnvelope = envelope, openKey = HouseholdCrypto.b64(key), // v1.2: members join without a passphrase
            entries = entries.distinctBy { it.op.operationId },
        )
        val file = remote.createTextFile(null, ledgerFileName(household.name), SyncJson.encodeToString(HouseholdLedger.serializer(), ledger))
        // Carry existing sharing over to the new file.
        runCatching { remote.listPermissions(cursor.driveFolderId) }.getOrDefault(emptyList())
            .filter { it.type == "user" && it.role != "owner" && it.email != null }
            .forEach { p -> runCatching { remote.share(file.id, p.email!!, p.role, null) } }
        val migrated = cursor.copy(driveFolderId = file.id, operationsFolderId = "", snapshotsFolderId = null, metadataFolderId = null, lastRemoteCursor = 0)
        db.sync().upsertCursor(migrated)
        return migrated
    }

    private fun toRemoteFile(op: SyncOperationEntity, key: ByteArray, keyVersion: Int): RemoteOperationFile {
        val unsigned = RemoteOperationFile(
            operationId = op.operationId, householdId = op.householdId, entityType = op.entityType,
            entityId = op.entityId, operationType = op.operationType, deviceId = op.deviceId,
            actorMemberId = op.actorMemberId, baseOperationId = op.baseOperationId, schemaVersion = op.schemaVersion,
            createdAt = op.createdAt, keyVersion = keyVersion, nonce = "", ciphertext = "",
        )
        val sealed = crypto.encrypt(key, op.payload.toByteArray(Charsets.UTF_8), unsigned.aad())
        return unsigned.copy(nonce = HouseholdCrypto.b64(sealed.nonce), ciphertext = HouseholdCrypto.b64(sealed.ciphertext))
    }

    private fun now() = time.now().toEpochMilli()

    companion object {
        /** Re-list a window of already-seen files to tolerate Drive listing eventual consistency. */
        const val LISTING_OVERLAP_MS = 10 * 60 * 1000L
        const val MAX_DEFER_ATTEMPTS = 50
        const val MAX_UPLOAD_ROUNDS = 4
        private val OP_ID = Regex("""^[0-9a-fA-F-]{36}$""")

        fun ledgerFileName(householdName: String) = "$HOUSEHOLD_FOLDER_PREFIX$householdName$LEDGER_SUFFIX"
        private val OP_NAME = Regex("""^op_(\d+)_([0-9a-fA-F-]{36})\.json$""")

        fun operationFileName(createdAt: Long, operationId: String) = "op_${createdAt}_$operationId.json"
        fun parseOperationId(name: String): String? = OP_NAME.matchEntire(name)?.groupValues?.get(2)
        fun entityOrder(type: String): Int = runCatching { EntityType.valueOf(type).applyOrder }.getOrDefault(99)
    }
}

/** v1.0/v1.1 households stored one file per operation inside a shared folder. */
fun SyncCursorEntity.isLegacyFolderLayout(): Boolean = operationsFolderId.isNotEmpty()
