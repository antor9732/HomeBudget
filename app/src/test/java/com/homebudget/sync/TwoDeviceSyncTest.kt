package com.homebudget.sync

import android.app.Application
import com.homebudget.core.common.Ids
import com.homebudget.core.database.AccessState
import com.homebudget.core.database.ConflictResolution
import com.homebudget.core.sync.JoinException
import com.homebudget.core.sync.SyncEngine
import com.homebudget.core.sync.SyncOutcome
import com.homebudget.data.mapper.toDto
import com.homebudget.data.remote.EntityType
import com.homebudget.data.remote.HouseholdLedger
import com.homebudget.data.remote.OperationType
import com.homebudget.data.remote.SyncJson
import com.homebudget.data.remote.TransactionDto
import com.homebudget.domain.model.MemberStatus
import com.homebudget.domain.model.Role
import com.homebudget.domain.model.TransactionDraft
import com.homebudget.domain.model.TransactionType.EXPENSE
import com.homebudget.domain.repository.PermissionDeniedException
import com.homebudget.testing.Device
import com.homebudget.testing.FakeDriveServer
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.YearMonth

private fun tk(t: Long) = t * 100
private const val PASS = "family-secret-2026"

/**
 * Multi-device synchronization tests against an in-memory Google Drive (Section 14).
 * Each Device has its own Room database, identity and key store; only the fake Drive is shared.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class TwoDeviceSyncTest {
    private val server = FakeDriveServer()
    private lateinit var a: Device
    private lateinit var b: Device
    private val nov = YearMonth.of(2026, 11)
    private val day = LocalDate.of(2026, 11, 15)

    @Before fun setUp() = runBlocking {
        a = Device(server, "owner.a@gmail.com", "Owner A")
        b = Device(server, "member.b@gmail.com", "Member B")
        a.households.createLocalHousehold("Test Family", "Owner A", "owner.a@gmail.com", "BDT")
        assertTrue(a.cloud.connectCurrentHousehold() is SyncOutcome.Success)
        a.cloud.invite("member.b@gmail.com", Role.ADMIN)
        assertTrue(b.cloud.listJoinableFolders().isNotEmpty())
        val folder = b.cloud.listJoinableFolders().single().folderId
        assertTrue(b.cloud.join(folder, PASS.toCharArray()) is SyncOutcome.Success)
        a.engine.sync(a.hh())
        Unit
    }

    @After fun tearDown() { a.close(); b.close() }

    private suspend fun Device.cat(name: String) = finance.observeCategories(hh()).first().first { it.name == name }.id
    private suspend fun Device.spend(title: String, amount: Long) =
        finance.addTransaction(TransactionDraft(EXPENSE, title, amount, cat(title), day, null))
    private suspend fun Device.expenseTotal() = finance.monthTotals(hh(), nov).second
    private suspend fun Device.txs() = finance.observeTransactions(hh(), nov).first()

    private fun ledgerNode() = server.nodes.values.single { it.name.endsWith(".homebudget.json") }
    private fun ledger() = SyncJson.decodeFromString<HouseholdLedger>(ledgerNode().content)
    private fun saveLedger(l: HouseholdLedger) { ledgerNode().content = SyncJson.encodeToString(HouseholdLedger.serializer(), l) }

    @Test fun `picked household file is accepted and other files are rejected`(): Unit = runBlocking {
        val id = b.cloud.listJoinableFolders().single().folderId
        assertEquals(id, b.cloud.resolvePickedFolder(id).folderId)
        assertEquals("Test Family", b.cloud.resolvePickedFolder(id).name)
        val other = b.drive.createTextFile(null, "notes.json", "{\"x\":1}")
        assertThrows(JoinException::class.java) { runBlocking { b.cloud.resolvePickedFolder(other.id) } }
    }

    @Test fun `zero setup - new member joins without any passphrase`(): Unit = runBlocking {
        a.cloud.invite("viewer.z@gmail.com", Role.MEMBER)
        val z = com.homebudget.testing.Device(server, "viewer.z@gmail.com", "Zed")
        try {
            val id = z.cloud.listJoinableFolders().single().folderId
            assertFalse(z.cloud.needsPassphrase(id))
            assertTrue(z.cloud.join(id, null) is SyncOutcome.Success)
            assertEquals(Role.MEMBER, z.households.currentContext()!!.role)
        } finally { z.close() }
    }

    @Test fun `optional passphrase is enforced once set and can be turned off`(): Unit = runBlocking {
        a.cloud.changePassphrase("brand-new-secret".toCharArray())
        a.cloud.invite("late.l@gmail.com", Role.MEMBER)
        val l = com.homebudget.testing.Device(server, "late.l@gmail.com", "Late")
        try {
            val id = l.cloud.listJoinableFolders().single().folderId
            assertTrue(l.cloud.needsPassphrase(id))
            assertThrows(com.homebudget.core.sync.PassphraseRequiredException::class.java) { runBlocking { l.cloud.join(id, null) } }
            assertThrows(JoinException::class.java) { runBlocking { l.cloud.join(id, "wrong-secret-1".toCharArray()) } }
            a.cloud.removePassphrase()
            assertFalse(l.cloud.needsPassphrase(id))
            assertTrue(l.cloud.join(id, null) is SyncOutcome.Success)
        } finally { l.close() }
    }

    @Test fun `owner on a new phone finds the household automatically`(): Unit = runBlocking {
        val a2 = com.homebudget.testing.Device(server, "owner.a@gmail.com", "Owner A")
        try {
            val mine = a2.cloud.findMyHouseholds()
            assertEquals(1, mine.size)
            assertTrue(a2.cloud.join(mine.single().folderId, null) is SyncOutcome.Success)
            assertEquals(Role.OWNER, a2.households.currentContext()!!.role)
        } finally { a2.close() }
    }

    @Test fun `whole household lives in one shared file`(): Unit = runBlocking {
        assertEquals(1, server.nodes.size)
        assertEquals(0, server.nodes.values.count { it.mime == "application/vnd.google-apps.folder" })
    }

    @Test fun `operation dropped by a stale overwrite is re-added by its author`(): Unit = runBlocking {
        val before = ledgerNode().content
        a.spend("Grocery", tk(1_000))
        a.engine.sync(a.hh())
        assertEquals(0, a.db.sync().pendingCount(a.hh()))
        ledgerNode().content = before // another phone uploaded an older copy
        b.spend("Medical", tk(2_000)); b.engine.sync(b.hh())
        assertEquals(tk(2_000), b.expenseTotal())
        a.engine.sync(a.hh()) // A notices its change is missing and adds it back
        b.engine.sync(b.hh())
        for (d in listOf(a, b)) assertEquals(tk(3_000), d.expenseTotal())
    }

    @Test fun `concurrent upload right after ours is detected and repaired`(): Unit = runBlocking {
        a.spend("Grocery", tk(1_000))
        val stale = ledgerNode().content
        var once = true
        server.afterUpdate = { if (once) { once = false; ledgerNode().content = stale } }
        assertTrue(a.engine.sync(a.hh()) is SyncOutcome.Success)
        server.afterUpdate = null
        assertEquals(0, a.db.sync().pendingCount(a.hh()))
        assertTrue(ledger().entries.any { it.author == "owner.a@gmail.com" })
        b.engine.sync(b.hh())
        assertEquals(tk(1_000), b.expenseTotal())
    }

    @Test fun `owner upgrade moves an old folder household into one file`(): Unit = runBlocking {
        val o = Device(server, "legacy.o@gmail.com", "Legacy O")
        val m = Device(server, "legacy.m@gmail.com", "Legacy M")
        try {
            o.households.createLocalHousehold("Old Family", "Legacy O", "legacy.o@gmail.com", "BDT")
            val hh = o.hh()
            o.spend("Grocery", tk(1_000))
            // v1.1 layout: folder + operations/snapshots/metadata + key envelope, shared with a member.
            val root = o.drive.createFolder("HomeBudget - Old Family", null)
            val ops = o.drive.createFolder("operations", root.id)
            val snaps = o.drive.createFolder("snapshots", root.id)
            val meta = o.drive.createFolder("metadata", root.id)
            val key = o.crypto.generateHouseholdKey()
            val env = o.crypto.wrapWithPassphrase(hh, 1, key, PASS.toCharArray())
            o.drive.createTextFile(meta.id, "key-envelope.json", SyncJson.encodeToString(com.homebudget.data.remote.KeyEnvelope.serializer(), env))
            o.keys.save(hh, key, 1)
            o.db.sync().upsertCursor(com.homebudget.core.database.SyncCursorEntity(hh, root.id, ops.id, snaps.id, meta.id,
                "legacy.o@gmail.com", 0, null, null, null, AccessState.OK))
            o.households.recordInvitedMember("legacy.m@gmail.com", Role.MEMBER)
            o.drive.share(root.id, "legacy.m@gmail.com", "writer", null)

            assertTrue(o.engine.sync(hh) is SyncOutcome.Success)
            val cursor = o.db.sync().cursor(hh)!!
            assertEquals("", cursor.operationsFolderId)
            val file = server.nodes[cursor.driveFolderId]!!
            assertTrue(file.name.endsWith(".homebudget.json"))
            assertTrue(server.permissions[file.id].orEmpty().any { it.email == "legacy.m@gmail.com" && it.role == "writer" })
            assertEquals(0, o.db.sync().pendingCount(hh))

            assertTrue(m.cloud.join(file.id, PASS.toCharArray()) is SyncOutcome.Success)
            assertEquals(tk(1_000), m.expenseTotal())
        } finally { o.close(); m.close() }
    }

    @Test fun `member joined with correct role and household data`(): Unit = runBlocking {
        val ctx = b.households.currentContext()!!
        assertEquals(a.hh(), ctx.householdId)
        assertEquals(Role.ADMIN, ctx.role)
        assertEquals(MemberStatus.ACTIVE, ctx.member.status)
        assertEquals(17, b.finance.observeCategories(b.hh()).first().size)
        assertEquals("Test Family", ctx.household.name)
        // Owner sees member as ACTIVE after B's self-activation syncs.
        assertEquals(MemberStatus.ACTIVE, a.db.members().byEmail(a.hh(), "member.b@gmail.com")!!.status)
    }

    @Test fun `mandatory two-device offline test`(): Unit = runBlocking {
        // Both phones offline
        a.spend("Grocery", tk(1_000)); a.spend("Electricity", tk(500))
        b.spend("Medical", tk(2_000)); b.spend("Transport", tk(700))
        // Reconnect both
        a.engine.sync(a.hh()); b.engine.sync(b.hh()); a.engine.sync(a.hh())
        for (d in listOf(a, b)) {
            assertEquals(4, d.txs().size)
            assertEquals(tk(4_200), d.expenseTotal())
            assertEquals(4, d.txs().map { it.id }.toSet().size)
            assertEquals(0, d.db.sync().pendingCount(d.hh()))
        }
    }

    @Test fun `duplicate operation file is applied only once`(): Unit = runBlocking {
        a.spend("Grocery", tk(1_000))
        a.engine.sync(a.hh())
        val l = ledger()
        saveLedger(l.copy(entries = l.entries + l.entries.last())) // same operation delivered twice
        b.engine.sync(b.hh()); b.engine.sync(b.hh())
        assertEquals(1, b.txs().size)
        assertEquals(tk(1_000), b.expenseTotal())
    }

    @Test fun `lost upload response does not duplicate on retry`(): Unit = runBlocking {
        a.spend("Grocery", tk(1_000))
        server.failUploadsAfterWrite = 1
        assertTrue(a.engine.sync(a.hh()) is SyncOutcome.Failed)
        assertEquals(1, a.db.sync().pendingCount(a.hh()))
        assertTrue(a.engine.sync(a.hh()) is SyncOutcome.Success)
        assertEquals(0, a.db.sync().pendingCount(a.hh()))
        b.engine.sync(b.hh())
        assertEquals(1, b.txs().size)
    }

    @Test fun `concurrent edit converges and preserves conflict`(): Unit = runBlocking {
        val id = a.spend("Grocery", tk(1_000))
        a.engine.sync(a.hh()); b.engine.sync(b.hh())
        val g = a.cat("Grocery")
        a.finance.updateTransaction(id, TransactionDraft(EXPENSE, "Grocery A", tk(1_100), g, day, null))
        b.finance.updateTransaction(id, TransactionDraft(EXPENSE, "Grocery B", tk(1_200), g, day, null))
        a.engine.sync(a.hh()); b.engine.sync(b.hh()); a.engine.sync(a.hh())
        val ta = a.finance.observeTransaction(id).first()!!
        val tb = b.finance.observeTransaction(id).first()!!
        assertEquals(ta.title, tb.title)
        assertEquals(ta.amountMinor, tb.amountMinor)
        val conflictsA = a.db.conflicts().observeAll(a.hh()).first()
        val conflictsB = b.db.conflicts().observeAll(b.hh()).first()
        assertTrue(conflictsA.isNotEmpty() || conflictsB.isNotEmpty())
        val all = conflictsA + conflictsB
        assertTrue(all.all { it.resolutionStatus == ConflictResolution.OPEN })
        assertTrue(all.any { it.remoteVersion.contains("Grocery A") || it.remoteVersion.contains("Grocery B") })
    }

    @Test fun `delete while other device offline is not resurrected`(): Unit = runBlocking {
        val id = a.spend("Grocery", tk(1_000))
        a.engine.sync(a.hh()); b.engine.sync(b.hh())
        a.finance.deleteTransaction(id)
        b.finance.updateTransaction(id, TransactionDraft(EXPENSE, "Grocery edited", tk(9_999), b.cat("Grocery"), day, null))
        a.engine.sync(a.hh()); b.engine.sync(b.hh()); a.engine.sync(a.hh())
        assertEquals(0, a.txs().size)
        assertEquals(0, b.txs().size)
        assertEquals(0L, a.expenseTotal()); assertEquals(0L, b.expenseTotal())
    }

    @Test fun `concurrent budget edit converges`(): Unit = runBlocking {
        a.finance.setBudget(nov, tk(30_000))
        b.finance.setBudget(nov, tk(25_000))
        a.engine.sync(a.hh()); b.engine.sync(b.hh()); a.engine.sync(a.hh())
        val ba = a.finance.getBudget(a.hh(), nov)!!.amountMinor
        assertEquals(ba, b.finance.getBudget(b.hh(), nov)!!.amountMinor)
        assertTrue(a.db.conflicts().observeAll(a.hh()).first().size + b.db.conflicts().observeAll(b.hh()).first().size > 0)
    }

    @Test fun `resolving conflict with other version propagates`(): Unit = runBlocking {
        a.finance.setBudget(nov, tk(30_000))
        b.finance.setBudget(nov, tk(25_000))
        a.engine.sync(a.hh()); b.engine.sync(b.hh()); a.engine.sync(a.hh())
        val (dev, c) = listOf(a, b).firstNotNullOf { d -> d.db.conflicts().observeAll(d.hh()).first().firstOrNull()?.let { d to it } }
        val scheduler = com.homebudget.core.notifications.WorkScheduler(androidx.test.core.app.ApplicationProvider.getApplicationContext())
        val repo = com.homebudget.core.sync.SyncRepositoryImpl(dev.db, scheduler, dev.households, dev.writer, dev.time)
        repo.applyOtherVersion(c.id)
        a.engine.sync(a.hh()); b.engine.sync(b.hh()); a.engine.sync(a.hh())
        val loser = SyncJson.decodeFromString<com.homebudget.data.remote.BudgetDto>(c.remoteVersion).amountMinor
        assertEquals(loser, a.finance.getBudget(a.hh(), nov)!!.amountMinor)
        assertEquals(loser, b.finance.getBudget(b.hh(), nov)!!.amountMinor)
    }

    @Test fun `removed member is blocked and becomes read-only`(): Unit = runBlocking {
        val bId = a.db.members().byEmail(a.hh(), "member.b@gmail.com")!!.id
        a.cloud.remove(bId)
        val out = b.engine.sync(b.hh())
        assertTrue(out is SyncOutcome.AccessRevoked)
        assertEquals(AccessState.ACCESS_REVOKED, b.db.sync().cursor(b.hh())!!.accessState)
        assertTrue(b.households.currentContext()!!.readOnly)
        assertThrows(PermissionDeniedException::class.java) { runBlocking { b.spend("Grocery", tk(1)) } }
    }

    @Test fun `uninvited account cannot join`(): Unit = runBlocking {
        val c = Device(server, "stranger@gmail.com", "Stranger")
        try {
            assertTrue(c.cloud.listJoinableFolders().isEmpty())
            // Even with Drive access but no app-level invitation, joining is refused.
            val folder = a.db.sync().cursor(a.hh())!!.driveFolderId
            a.drive.share(folder, "stranger@gmail.com", "writer", null)
            assertThrows(JoinException::class.java) { runBlocking { c.cloud.join(folder, PASS.toCharArray()) } }
            assertEquals(null, c.households.currentContext())
        } finally { c.close() }
    }

    @Test fun `wrong passphrase is rejected`(): Unit = runBlocking {
        a.cloud.changePassphrase(PASS.toCharArray())
        val c = Device(server, "owner.a@gmail.com", "Owner A new phone")
        try {
            val folder = c.cloud.listJoinableFolders().single().folderId
            assertThrows(JoinException::class.java) { runBlocking { c.cloud.join(folder, "not-the-passphrase".toCharArray()) } }
        } finally { c.close() }
    }

    @Test fun `restore on new phone`(): Unit = runBlocking {
        a.spend("Grocery", tk(1_000)); b.spend("Medical", tk(2_000))
        a.finance.setBudget(nov, tk(30_000))
        a.engine.sync(a.hh()); b.engine.sync(b.hh())
        val a2 = Device(server, "owner.a@gmail.com", "Owner A new phone")
        try {
            val folder = a2.cloud.listJoinableFolders().single().folderId
            assertTrue(a2.cloud.join(folder, PASS.toCharArray()) is SyncOutcome.Success)
            assertEquals(Role.OWNER, a2.households.currentContext()!!.role)
            assertEquals(2, a2.txs().size)
            assertEquals(tk(3_000), a2.expenseTotal())
            assertEquals(tk(30_000), a2.finance.getBudget(a2.hh(), nov)!!.amountMinor)
        } finally { a2.close() }
    }

    @Test fun `forged operation from a member editing someone else's transaction is rejected`(): Unit = runBlocking {
        val id = a.spend("Grocery", tk(1_000))
        a.engine.sync(a.hh())
        // Downgrade B to MEMBER (members may only change their own transactions).
        a.cloud.changeRole(a.db.members().byEmail(a.hh(), "member.b@gmail.com")!!.id, Role.MEMBER)
        b.engine.sync(b.hh())
        // B bypasses the repository and writes an edit directly into its op log.
        val ctx = b.households.currentContext()!!
        val cur = b.db.transactions().get(id)!!
        val opId = Ids.random()
        val forged = cur.copy(amountMinor = 1, revision = cur.revision + 1, lastOperationId = opId)
        b.writer.record(ctx.householdId, EntityType.TRANSACTION, id, OperationType.UPDATE, opId, cur.lastOperationId, ctx.member.id,
            SyncJson.encodeToString(TransactionDto.serializer(), forged.toDto())) { b.db.transactions().upsert(forged) }
        b.engine.sync(b.hh())
        a.engine.sync(a.hh())
        assertEquals(tk(1_000), a.finance.observeTransaction(id).first()!!.amountMinor)
    }

    @Test fun `backup verification and verified rebuild`(): Unit = runBlocking {
        a.spend("Grocery", tk(1_000)); b.spend("Medical", tk(2_000))
        a.engine.sync(a.hh()); b.engine.sync(b.hh()); a.engine.sync(a.hh())
        val v = a.backup.verify(a.hh())
        assertTrue(v.healthy)
        assertEquals(v.remoteOperations, v.readable)
        a.backup.rebuildFromDrive(a.hh())
        assertEquals(tk(3_000), a.expenseTotal())
        val name = a.backup.createSnapshot(a.hh())
        assertTrue(name.startsWith("HomeBudget snapshot_"))
    }

    @Test fun `rebuild refuses when remote copy is corrupted`(): Unit = runBlocking {
        a.spend("Grocery", tk(1_000))
        a.engine.sync(a.hh())
        val l = ledger()
        val last = l.entries.last()
        saveLedger(l.copy(entries = l.entries.dropLast(1) + last.copy(op = last.op.copy(ciphertext = "AAAA" + last.op.ciphertext.drop(4)))))
        assertFalse(a.backup.verify(a.hh()).healthy)
        assertThrows(IllegalStateException::class.java) { runBlocking { a.backup.rebuildFromDrive(a.hh()) } }
        assertEquals(tk(1_000), a.expenseTotal()) // healthy local data kept
    }

    @Test fun `cloud payloads are encrypted`(): Unit = runBlocking {
        a.finance.addTransaction(TransactionDraft(EXPENSE, "SecretPharmacy", tk(1_234), a.cat("Medical"), day, "private note"))
        a.engine.sync(a.hh())
        assertTrue(server.nodes.values.none { it.content.contains("SecretPharmacy") || it.content.contains("private note") })
    }

    @Test fun `viewer joins read-only and sees data`(): Unit = runBlocking {
        a.spend("Grocery", tk(1_000)); a.engine.sync(a.hh())
        a.cloud.invite("viewer.c@gmail.com", Role.VIEWER)
        val c = Device(server, "viewer.c@gmail.com", "Viewer C")
        try {
            val folder = c.cloud.listJoinableFolders().single().folderId
            assertTrue(c.cloud.join(folder, PASS.toCharArray()) is SyncOutcome.Success)
            assertEquals(Role.VIEWER, c.households.currentContext()!!.role)
            assertEquals(tk(1_000), c.expenseTotal())
            assertThrows(PermissionDeniedException::class.java) { runBlocking { c.spend("Grocery", tk(1)) } }
            assertTrue(c.engine.sync(c.hh()) is SyncOutcome.Success)
        } finally { c.close() }
    }

    @Test fun `itemized transaction syncs with its items`(): Unit = runBlocking {
        val items = listOf(com.homebudget.domain.model.TransactionItem("Rice", tk(450)), com.homebudget.domain.model.TransactionItem("Oil", tk(380)))
        val id = a.finance.addTransaction(TransactionDraft(EXPENSE, "Bazar", tk(830), a.cat("Grocery"), day, null, items))
        a.engine.sync(a.hh()); b.engine.sync(b.hh())
        assertEquals(items, b.finance.observeTransaction(id).first()!!.items)
        b.finance.updateTransaction(id, TransactionDraft(EXPENSE, "Bazar", tk(450), b.cat("Grocery"), day, null, items.take(1)))
        b.engine.sync(b.hh()); a.engine.sync(a.hh())
        assertEquals(items.take(1), a.finance.observeTransaction(id).first()!!.items)
        assertEquals(tk(450), a.expenseTotal())
    }
}
