package com.homebudget.sync

import android.app.Application
import com.homebudget.core.common.Ids
import com.homebudget.core.sync.SyncOutcome
import com.homebudget.domain.model.Role
import com.homebudget.domain.model.ShoppingStatus
import com.homebudget.domain.model.TransactionType
import com.homebudget.domain.repository.PermissionDeniedException
import com.homebudget.domain.repository.ValidationException
import com.homebudget.testing.Device
import com.homebudget.testing.FakeDriveServer
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.YearMonth

private fun tk(t: Long) = t * 100

/** Shared bazar lists: one phone writes the list, another ticks items with prices and finishes it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class BazarListTest {
    private val server = FakeDriveServer()
    private lateinit var a: Device
    private lateinit var b: Device
    private val nov = YearMonth.of(2026, 11)

    @Before fun setUp() = runBlocking {
        a = Device(server, "owner.a@gmail.com", "Owner A")
        b = Device(server, "member.b@gmail.com", "Member B")
        a.households.createLocalHousehold("Test Family", "Owner A", "owner.a@gmail.com", "BDT")
        assertTrue(a.cloud.connectCurrentHousehold() is SyncOutcome.Success)
        a.cloud.invite("member.b@gmail.com", Role.MEMBER)
        val id = b.cloud.listJoinableFolders().single().folderId
        assertTrue(b.cloud.join(id, null) is SyncOutcome.Success)
        a.engine.sync(a.hh())
        Unit
    }

    @After fun tearDown() { a.close(); b.close() }

    private suspend fun Device.cat(name: String) = finance.observeCategories(hh()).first().first { it.name == name }.id
    private suspend fun Device.sync() = engine.sync(hh())

    @Test fun `list written on one phone is ticked on another and becomes expenses per category`(): Unit = runBlocking {
        val list = a.shopping.createList("Bazar 28 Nov")
        a.shopping.addItem(list, "Rice", "5 kg", null)
        a.shopping.addItem(list, "Oil", "2 L", null)
        a.shopping.addItem(list, "Paracetamol", null, a.cat("Medical"))
        a.shopping.addItem(list, "Fish", null, null)
        a.sync(); b.sync()

        val onB = b.shopping.observeList(list).first()!!
        assertEquals(4, onB.items.size)
        assertEquals(b.cat("Grocery"), onB.items.first { it.name == "Rice" }.categoryId)
        onB.items.forEach {
            when (it.name) {
                "Rice" -> b.shopping.setBought(it.id, true, tk(450))
                "Oil" -> b.shopping.setBought(it.id, true, tk(380))
                "Paracetamol" -> b.shopping.setBought(it.id, true, tk(25))
            }
        }
        val r = b.shopping.finish(list, carryOver = true)
        assertEquals(2, r.expenses)
        assertEquals(tk(855), r.totalMinor)
        assertEquals(1, r.carriedOver)

        val txs = b.finance.observeTransactions(b.hh(), nov).first()
        val grocery = txs.single { it.categoryId == b.cat("Grocery") }
        assertEquals(tk(830), grocery.amountMinor)
        assertEquals(listOf("Rice (5 kg)", "Oil (2 L)"), grocery.items.map { it.name })
        assertEquals(TransactionType.EXPENSE, grocery.type)
        assertEquals(tk(25), txs.single { it.categoryId == b.cat("Medical") }.amountMinor)

        b.sync(); a.sync()
        assertEquals(tk(855), a.finance.monthTotals(a.hh(), nov).second)
        assertEquals(ShoppingStatus.DONE, a.shopping.observeList(list).first()!!.status)
        val remaining = a.shopping.observeList(r.newListId!!).first()!!
        assertEquals(listOf("Fish"), remaining.items.map { it.name })
        assertEquals(ShoppingStatus.OPEN, remaining.status)
    }

    @Test fun `finishing on two phones at the same time does not count the bazar twice`(): Unit = runBlocking {
        val list = a.shopping.createList("Bazar")
        val rice = a.shopping.addItem(list, "Rice", null, null)
        a.shopping.setBought(rice, true, tk(450))
        a.sync(); b.sync()
        a.shopping.finish(list, carryOver = false)
        b.shopping.finish(list, carryOver = false)
        a.sync(); b.sync(); a.sync()
        assertEquals(tk(450), a.finance.monthTotals(a.hh(), nov).second)
        assertEquals(tk(450), b.finance.monthTotals(b.hh(), nov).second)
        assertNotNull(a.db.transactions().get(Ids.bazarExpense(list, a.cat("Grocery"))))
    }

    @Test fun `edits to different items on two phones both survive`(): Unit = runBlocking {
        val list = a.shopping.createList("Bazar")
        val rice = a.shopping.addItem(list, "Rice", null, null)
        a.sync(); b.sync()
        a.shopping.addItem(list, "Eggs", "12", null)
        b.shopping.setBought(rice, true, tk(450))
        a.sync(); b.sync(); a.sync()
        for (d in listOf(a, b)) {
            val items = d.shopping.observeList(list).first()!!.items
            assertEquals(setOf("Rice", "Eggs"), items.map { it.name }.toSet())
            assertTrue(items.single { it.name == "Rice" }.isChecked)
        }
    }

    @Test fun `finish needs a price for every ticked item and a finished list is locked`(): Unit = runBlocking {
        val list = a.shopping.createList("Bazar")
        val rice = a.shopping.addItem(list, "Rice", null, null)
        assertThrows(ValidationException::class.java) { runBlocking { a.shopping.finish(list, false) } }
        a.shopping.setBought(rice, true, null)
        assertThrows(ValidationException::class.java) { runBlocking { a.shopping.finish(list, false) } }
        a.shopping.setBought(rice, true, tk(100))
        a.shopping.finish(list, false)
        assertThrows(ValidationException::class.java) { runBlocking { a.shopping.addItem(list, "Late", null, null) } }
        assertThrows(ValidationException::class.java) { runBlocking { a.shopping.setBought(rice, false, null) } }
    }

    @Test fun `viewer cannot change lists and deleted lists disappear everywhere`(): Unit = runBlocking {
        val list = a.shopping.createList("Bazar")
        a.sync(); b.sync()
        a.households.changeRole(b.households.currentContext()!!.member.id, Role.VIEWER)
        a.sync(); b.sync()
        assertThrows(PermissionDeniedException::class.java) { runBlocking { b.shopping.addItem(list, "Rice", null, null) } }
        a.shopping.deleteList(list)
        a.sync(); b.sync()
        assertNull(b.shopping.observeList(list).first())
        assertTrue(b.shopping.observeLists(b.hh()).first().isEmpty())
    }
}
