package com.homebudget.core.sync

import androidx.room.withTransaction
import com.homebudget.core.common.LocalIdentity
import com.homebudget.core.common.TimeProvider
import com.homebudget.core.database.AccessState
import com.homebudget.core.database.AppDatabase
import com.homebudget.core.database.HouseholdEntity
import com.homebudget.core.database.SyncCursorEntity
import com.homebudget.core.drive.DriveFile
import com.homebudget.core.drive.DriveRemote
import com.homebudget.core.drive.HOUSEHOLD_FOLDER_PREFIX
import com.homebudget.core.drive.KEY_ENVELOPE_NAME
import com.homebudget.core.drive.LEDGER_SUFFIX
import com.homebudget.core.drive.DriveAccessDeniedException
import com.homebudget.core.drive.MANIFEST_NAME
import com.homebudget.core.security.HouseholdCrypto
import com.homebudget.core.security.HouseholdKeyStore
import com.homebudget.data.repository.HouseholdRepositoryImpl
import com.homebudget.data.remote.HouseholdLedger
import com.homebudget.data.remote.LEDGER_FORMAT
import com.homebudget.data.remote.KeyEnvelope
import com.homebudget.data.remote.SCHEMA_VERSION
import com.homebudget.data.remote.SyncJson
import com.homebudget.domain.model.MemberStatus
import com.homebudget.domain.model.Role
import com.homebudget.domain.repository.PermissionDeniedException
import com.homebudget.domain.repository.ValidationException
import javax.inject.Inject
import javax.inject.Singleton

open class JoinException(message: String) : Exception(message)

/** This household was protected with an optional recovery passphrase; ask the owner for it. */
class PassphraseRequiredException : JoinException("This household uses a recovery passphrase. Ask the owner for it.")

private const val NOT_HOUSEHOLD_HINT =
    "This is not a household file. In the picker open 'Shared with me' and tap the file named \"HomeBudget - … .homebudget.json\"."

data class AccessCheck(val step: String, val ok: Boolean, val detail: String)

data class JoinableFolder(val folderId: String, val name: String, val ownerEmail: String?, val ownedByMe: Boolean)

/**
 * Household lifecycle against Google Drive: create shared folder, invite/remove members (Drive
 * permissions + app-level membership), join/restore on another phone, change recovery passphrase.
 */
@Singleton
class HouseholdCloudService @Inject constructor(
    private val db: AppDatabase,
    private val remote: DriveRemote,
    private val crypto: HouseholdCrypto,
    private val keys: HouseholdKeyStore,
    private val identity: LocalIdentity,
    private val households: HouseholdRepositoryImpl,
    private val engine: SyncEngine,
    private val time: TimeProvider,
) {
    /** Owner: create the shared household file for the current (local) household and enable sync. */
    suspend fun connectCurrentHousehold(passphrase: CharArray? = null): SyncOutcome {
        passphrase?.let { HouseholdCrypto.validatePassphrase(it) }
        val ctx = households.currentContext() ?: throw IllegalStateException("No household")
        if (ctx.role != Role.OWNER) throw PermissionDeniedException("Only the owner can connect Google Drive")
        val account = identity.currentSession().account ?: throw IllegalStateException("Sign in with Google first")
        if (db.sync().cursor(ctx.householdId) != null) throw IllegalStateException("Already connected")

        val ownerEmail = account.email.lowercase()
        val key = crypto.generateHouseholdKey()
        val ledger = HouseholdLedger(
            schemaVersion = SCHEMA_VERSION, householdId = ctx.householdId, name = ctx.household.name, ownerEmail = ownerEmail,
            currencyCode = ctx.household.currencyCode, createdAt = ctx.household.createdAt,
            keyEnvelope = passphrase?.let { crypto.wrapWithPassphrase(ctx.householdId, 1, key, it) },
            openKey = if (passphrase == null) HouseholdCrypto.b64(key) else null,
        )
        val file = remote.createTextFile(null, SyncEngine.ledgerFileName(ctx.household.name), SyncJson.encodeToString(HouseholdLedger.serializer(), ledger))
        val attestedOwner = (file.ownerEmail ?: ownerEmail).lowercase()

        keys.save(ctx.householdId, key, 1)
        key.fill(0)
        db.sync().upsertCursor(
            SyncCursorEntity(ctx.householdId, file.id, "", null, null, attestedOwner, 0, null, null, null, AccessState.OK)
        )
        households.updateOwnIdentity(account.email, account.subject, account.displayName)
        households.setDriveFolder(file.id)
        return engine.sync(ctx.householdId)
    }

    suspend fun listJoinableFolders(): List<JoinableFolder> =
        remote.findHouseholdLedgers().filter { it.name.endsWith(LEDGER_SUFFIX) }.map { it.toJoinable() }

    private fun DriveFile.toJoinable() =
        JoinableFolder(id, name.removePrefix(HOUSEHOLD_FOLDER_PREFIX).removeSuffix(LEDGER_SUFFIX), ownerEmail, ownedByMe)

    /**
     * Member join, or owner restore on a new phone / after reinstall. Validates the manifest, the
     * Drive-attested folder owner, the recovery passphrase and finally app-level membership.
     */
    suspend fun join(folderId: String, passphrase: CharArray? = null): SyncOutcome {
        val account = identity.currentSession().account ?: throw JoinException("Sign in with Google first")
        val file: DriveFile = remote.getFile(folderId)
        val ledger = readLedgerFile(folderId)
        if (ledger.schemaVersion > SCHEMA_VERSION) throw JoinException("Please update the app to open this household")
        val attestedOwner = file.ownerEmail?.lowercase()
        if (attestedOwner == null || !attestedOwner.equals(ledger.ownerEmail, ignoreCase = true)) {
            throw JoinException("Household file ownership could not be verified")
        }
        val keyVersion: Int
        val key: ByteArray = if (ledger.openKey != null) {
            keyVersion = ledger.keyEnvelope?.keyVersion ?: 1
            runCatching { HouseholdCrypto.unb64(ledger.openKey) }.getOrElse { throw JoinException("Household key is damaged") }
        } else {
            val envelope = ledger.keyEnvelope ?: throw JoinException("Household key is missing")
            if (envelope.householdId != ledger.householdId) throw JoinException("Household key does not match")
            if (passphrase == null) throw PassphraseRequiredException()
            keyVersion = envelope.keyVersion
            try {
                crypto.unwrapWithPassphrase(envelope, passphrase)
            } catch (e: Exception) {
                throw JoinException("Incorrect recovery passphrase")
            }
        }

        val hh = ledger.householdId
        val existed = db.households().get(hh) != null
        val now = time.now().toEpochMilli()
        db.withTransaction {
            if (!existed) {
                // Placeholder (revision 0) is replaced by the owner's HOUSEHOLD operation during sync.
                db.households().upsert(HouseholdEntity(hh, ledger.name, "", ledger.currencyCode, folderId, ledger.createdAt, now, 0, "manifest"))
            }
            db.sync().upsertCursor(
                SyncCursorEntity(hh, folderId, "", null, null, attestedOwner, 0, null, null, null, AccessState.OK)
            )
        }
        keys.save(hh, key, keyVersion)
        key.fill(0)

        val first = engine.sync(hh)
        val isOwner = account.email.equals(attestedOwner, ignoreCase = true)
        val me = db.members().byEmail(hh, account.email)
            ?: if (isOwner) db.members().all(hh).firstOrNull { it.role == Role.OWNER } else null
        if (me == null || me.status == MemberStatus.REMOVED) {
            if (!existed) wipeLocalHousehold(hh)
            throw JoinException("This Google account has not been invited to the household")
        }
        identity.setHousehold(hh, me.id)
        if (first !is SyncOutcome.Success) return first
        // Viewers have read-only Drive access, so they cannot publish their own activation record.
        if (me.role == Role.VIEWER) return first
        households.updateOwnIdentity(account.email, account.subject, account.displayName)
        return engine.sync(hh)
    }

    private suspend fun wipeLocalHousehold(hh: String) {
        db.withTransaction {
            db.openHelper.writableDatabase.apply {
                execSQL("DELETE FROM sync_operations WHERE householdId = ?", arrayOf(hh))
                execSQL("DELETE FROM applied_operations WHERE householdId = ?", arrayOf(hh))
                execSQL("DELETE FROM deferred_operations WHERE householdId = ?", arrayOf(hh))
                execSQL("DELETE FROM conflicts WHERE householdId = ?", arrayOf(hh))
                execSQL("DELETE FROM sync_cursors WHERE householdId = ?", arrayOf(hh))
                execSQL("DELETE FROM household_keys WHERE householdId = ?", arrayOf(hh))
                execSQL("DELETE FROM transactions WHERE householdId = ?", arrayOf(hh))
            }
            db.households().hardDelete(hh) // cascades members, categories, budgets, opening balances
        }
    }

    private suspend fun readLedgerFile(fileId: String): HouseholdLedger {
        val text = try {
            remote.download(fileId)
        } catch (e: DriveAccessDeniedException) {
            throw JoinException("This Google account cannot open the household file. Ask the owner to invite this email.")
        }
        val ledger = runCatching { SyncJson.decodeFromString<HouseholdLedger>(text) }.getOrNull()
        if (ledger == null || ledger.format != LEDGER_FORMAT) throw JoinException(NOT_HOUSEHOLD_HINT)
        return ledger
    }

    /** Households this Google account created with HomeBudget (for automatic restore on a new phone). */
    suspend fun findMyHouseholds(): List<JoinableFolder> = listJoinableFolders().filter { it.ownedByMe }

    suspend fun needsPassphrase(fileId: String): Boolean = readLedgerFile(fileId).needsPassphrase

    /** The Picker returns the file the member tapped; make sure it is a household file. */
    suspend fun resolvePickedFolder(pickedId: String): JoinableFolder {
        val file = remote.getFile(pickedId)
        readLedgerFile(pickedId)
        return file.toJoinable()
    }

    /** Reports exactly which Drive operations this account can perform on a household file. */
    suspend fun diagnoseFolder(folderId: String): List<AccessCheck> {
        val me = identity.currentSession().account?.email.orEmpty()
        val out = ArrayList<AccessCheck>()
        suspend fun check(step: String, block: suspend () -> String): Boolean = try {
            out += AccessCheck(step, true, block()); true
        } catch (e: Exception) {
            out += AccessCheck(step, false, "${e.javaClass.simpleName}: ${e.message ?: ""}".take(160)); false
        }
        check("Open household file") { remote.getFile(folderId).let { "${it.name} · owner ${it.ownerEmail ?: "?"}" } }
        var ledger: HouseholdLedger? = null
        check("Read household data") {
            ledger = SyncJson.decodeFromString<HouseholdLedger>(remote.download(folderId))
            check(ledger!!.format == LEDGER_FORMAT) { "not a HomeBudget household file" }
            "household ${ledger!!.name}"
        }
        val l = ledger ?: return out
        check("Household key present") { if (l.needsPassphrase) "protected by recovery passphrase" else "ready (no passphrase needed)" }
        check("Changes in the file") {
            val others = l.entries.count { !it.author.equals(me, ignoreCase = true) }
            "${l.entries.size} changes, $others by other accounts"
        }
        return out
    }

    /** Owner/admin: share the household file and record the app-level invitation. Requires internet. */
    suspend fun invite(email: String, role: Role) {
        val ctx = households.currentContext() ?: throw IllegalStateException("No household")
        if (!ctx.canManageMembers) throw PermissionDeniedException("Only owner/admin can invite")
        var cursor = db.sync().cursor(ctx.householdId) ?: throw ValidationException("Connect Google Drive before inviting members")
        if (cursor.isLegacyFolderLayout()) {
            engine.sync(ctx.householdId) // upgrades storage to the single household file first
            cursor = db.sync().cursor(ctx.householdId)!!
            if (cursor.isLegacyFolderLayout()) throw ValidationException("Could not upgrade Drive storage; check the internet and try again")
        }
        val clean = email.trim().lowercase()
        if (role == Role.OWNER) throw ValidationException("A household has a single owner")
        // Validate input and record membership first (atomic + queued), then grant Drive access.
        households.recordInvitedMember(clean, role)
        val driveRole = if (role == Role.VIEWER) "reader" else "writer"
        val existing = remote.listPermissions(cursor.driveFolderId).firstOrNull { it.email.equals(clean, ignoreCase = true) }
        if (existing == null) {
            remote.share(cursor.driveFolderId, clean, driveRole,
                "You were invited to the HomeBudget household \"${ctx.household.name}\". Install HomeBudget, tap 'Continue with Google' with this Gmail, then 'I was invited' and tap this file. That's all.")
        } else if (existing.role != driveRole && existing.role != "owner") {
            remote.updatePermissionRole(cursor.driveFolderId, existing.id, driveRole)
        }
        engine.sync(ctx.householdId)
    }

    suspend fun changeRole(memberId: String, role: Role) {
        val ctx = households.currentContext() ?: throw IllegalStateException("No household")
        households.changeRole(memberId, role)
        val cursor = db.sync().cursor(ctx.householdId) ?: return
        val member = db.members().get(memberId) ?: return
        val email = member.email ?: return
        val driveRole = if (role == Role.VIEWER) "reader" else "writer"
        remote.listPermissions(cursor.driveFolderId).firstOrNull { it.email.equals(email, ignoreCase = true) }?.let {
            if (it.role != driveRole) remote.updatePermissionRole(cursor.driveFolderId, it.id, driveRole)
        }
        engine.sync(ctx.householdId)
    }

    /** Revokes Drive access AND app-level membership. Already-downloaded offline data cannot be erased remotely. */
    suspend fun remove(memberId: String) {
        val ctx = households.currentContext() ?: throw IllegalStateException("No household")
        households.markMemberRemoved(memberId)
        val cursor = db.sync().cursor(ctx.householdId) ?: return
        val email = db.members().get(memberId)?.email ?: return
        remote.listPermissions(cursor.driveFolderId).filter { it.email.equals(email, ignoreCase = true) && it.role != "owner" }
            .forEach { remote.deletePermission(cursor.driveFolderId, it.id) }
        engine.sync(ctx.householdId)
    }

    /** Owner: re-wrap the household key with a new recovery passphrase. */
    suspend fun changePassphrase(newPassphrase: CharArray) {
        HouseholdCrypto.validatePassphrase(newPassphrase)
        val ctx = households.currentContext() ?: throw IllegalStateException("No household")
        if (ctx.role != Role.OWNER) throw PermissionDeniedException("Only the owner can change the passphrase")
        val cursor = db.sync().cursor(ctx.householdId) ?: throw ValidationException("Not connected to Google Drive")
        val (key, version) = keys.load(ctx.householdId) ?: throw IllegalStateException("Key missing")
        val envelope = crypto.wrapWithPassphrase(ctx.householdId, version, key, newPassphrase)
        key.fill(0)
        if (cursor.isLegacyFolderLayout()) engine.sync(ctx.householdId) // upgrades storage first
        val current = db.sync().cursor(ctx.householdId)!!
        val ledger = engine.readLedger(current)
        remote.updateTextFile(current.driveFolderId, SyncJson.encodeToString(HouseholdLedger.serializer(), ledger.copy(keyEnvelope = envelope, openKey = null)))
        check(engine.readLedger(current).keyEnvelope == envelope) { "Passphrase change was not saved; try again" }
    }

    /**
     * Owner: switch to zero-setup joining. The household key is stored in the shared file, so the
     * file is protected by Google Drive sharing only (invited accounts can open it, nobody else).
     */
    suspend fun removePassphrase() {
        val ctx = households.currentContext() ?: throw IllegalStateException("No household")
        if (ctx.role != Role.OWNER) throw PermissionDeniedException("Only the owner can change this")
        val cursor = db.sync().cursor(ctx.householdId) ?: throw ValidationException("Not connected to Google Drive")
        if (cursor.isLegacyFolderLayout()) engine.sync(ctx.householdId)
        val current = db.sync().cursor(ctx.householdId)!!
        val (key, _) = keys.load(ctx.householdId) ?: throw IllegalStateException("Key missing")
        val ledger = engine.readLedger(current)
        val open = HouseholdCrypto.b64(key)
        key.fill(0)
        remote.updateTextFile(current.driveFolderId, SyncJson.encodeToString(HouseholdLedger.serializer(), ledger.copy(openKey = open)))
        check(!engine.readLedger(current).needsPassphrase) { "Change was not saved; try again" }
    }

    suspend fun currentNeedsPassphrase(): Boolean? {
        val ctx = households.currentContext() ?: return null
        val cursor = db.sync().cursor(ctx.householdId) ?: return null
        if (cursor.isLegacyFolderLayout()) return true
        return runCatching { engine.readLedger(cursor).needsPassphrase }.getOrNull()
    }

    /** Leave the household on this device only (local data is removed; cloud data is untouched). */
    suspend fun leaveOnThisDevice() {
        val ctx = households.currentContext() ?: return
        wipeLocalHousehold(ctx.householdId)
        identity.setHousehold(null, null)
    }

}
