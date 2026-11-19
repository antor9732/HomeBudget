package com.homebudget.data

import android.app.Application
import com.homebudget.core.database.SyncState
import com.homebudget.core.sync.SyncOutcome
import com.homebudget.domain.model.BudgetCalculator
import com.homebudget.domain.model.Role
import com.homebudget.domain.model.TransactionDraft
import com.homebudget.domain.model.TransactionType.EXPENSE
import com.homebudget.domain.model.TransactionType.INCOME
import com.homebudget.domain.repository.PermissionDeniedException
import com.homebudget.domain.repository.ValidationException
import com.homebudget.testing.Device
import com.homebudget.testing.FakeDriveServer
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

/** Section 14 acceptance matrix (single device, fully offline). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class FinanceRepositoryTest {
    private lateinit var d: Device
    private val nov = YearMonth.of(2026, 11)

    @Before fun setUp() = runBlocking {
        d = Device(FakeDriveServer(), "owner@example.com", "Owner")
        d.identity.setAccount(null) // never signed in: purely offline
        d.households.createLocalHousehold("Rahman Family", "Owner", null, "BDT")
        Unit
    }

    @After fun tearDown() = d.close()

    private suspend fun cat(name: String) = d.finance.observeCategories(d.hh()).first().first { it.name == name }.id
    private suspend fun expense(amount: Long, category: String = "Grocery", date: LocalDate = LocalDate.of(2026, 11, 10)) =
        d.finance.addTransaction(TransactionDraft(EXPENSE, category, amount, cat(category), date, null))
    private suspend fun summary(m: YearMonth = nov) = d.finance.monthTotals(d.hh(), m).let { (i, e) ->
        BudgetCalculator.fromTotals(d.finance.getBudget(d.hh(), m)?.amountMinor, d.finance.observeOpeningBalance(d.hh(), m).first()?.amountMinor, i, e)
    }

    @Test fun `default categories are created`(): Unit = runBlocking {
        val cats = d.finance.observeCategories(d.hh()).first()
        assertEquals(12, cats.count { it.type == EXPENSE })
        assertEquals(5, cats.count { it.type == INCOME })
    }

    @Test fun `acceptance matrix`(): Unit = runBlocking {
        expense(tk(5_000))
        assertEquals(tk(5_000), summary().expenseMinor)                       // Add ৳5,000 expense

        d.finance.addTransaction(TransactionDraft(INCOME, "Salary", tk(10_000), cat("Salary"), LocalDate.of(2026, 11, 1), null))
        assertEquals(tk(10_000), summary().incomeMinor)                       // Add ৳10,000 income

        d.finance.setBudget(nov, tk(30_000))
        assertEquals(tk(30_000), summary().budgetMinor)                       // Set ৳30,000 budget

        val big = expense(tk(30_000), "House Rent")                           // total spend = ৳35,000
        assertEquals(tk(5_000), summary().overrunMinor)                       // Spend ৳35,000 -> overrun ৳5,000

        val five = d.finance.observeTransactions(d.hh(), nov).first().first { it.amountMinor == tk(5_000) }.id
        d.finance.deleteTransaction(five)
        assertEquals(tk(30_000), summary().expenseMinor)                      // Delete ৳5,000 expense

        d.finance.setBudget(nov, tk(40_000))
        assertEquals(tk(10_000), summary().remainingBudgetMinor)              // Edit budget -> recalculates
        assertEquals(7_500, summary().utilizationBasisPoints)

        expense(tk(777), date = LocalDate.of(2026, 10, 31))
        assertEquals(tk(30_000), summary().expenseMinor)                      // Switch month: November unaffected
        assertEquals(tk(777), summary(YearMonth.of(2026, 10)).expenseMinor)
        assertNull(summary(YearMonth.of(2026, 10)).budgetMinor)
        assertTrue(d.finance.observeTransactions(d.hh(), YearMonth.of(2026, 10)).first().all { it.date.monthValue == 10 })
        assertTrue(big.isNotEmpty())
    }

    @Test fun `opening balance gives available cash and can be cleared and set again`(): Unit = runBlocking {
        d.finance.setOpeningBalance(nov, tk(5_000))
        d.finance.addTransaction(TransactionDraft(INCOME, "Salary", tk(40_000), cat("Salary"), LocalDate.of(2026, 11, 1), null))
        expense(tk(35_000))
        assertEquals(tk(10_000), summary().availableCashMinor)
        d.finance.setOpeningBalance(nov, null)
        assertNull(summary().availableCashMinor)
        d.finance.setOpeningBalance(nov, tk(1_000))
        assertEquals(tk(6_000), summary().availableCashMinor)
    }

    @Test fun `every change is saved with a pending sync operation`(): Unit = runBlocking {
        val before = d.db.sync().pendingCount(d.hh())
        expense(tk(100))
        val ops = d.db.sync().allOperations(d.hh())
        assertEquals(before + 1, d.db.sync().pendingCount(d.hh()))
        assertTrue(ops.all { it.syncStatus == SyncState.PENDING })
        assertEquals(ops.size, ops.map { it.operationId }.toSet().size)
    }

    @Test fun `no internet - sync is a no-op and data remains usable`(): Unit = runBlocking {
        expense(tk(100))
        assertEquals(SyncOutcome.LocalOnly, d.engine.sync(d.hh()))
        assertEquals(tk(100), summary().expenseMinor)
    }

    @Test fun `archived category keeps historical transactions`(): Unit = runBlocking {
        val grocery = cat("Grocery")
        expense(tk(250))
        d.finance.setCategoryActive(grocery, false)
        assertEquals(tk(250), summary().expenseMinor)
        assertThrows(ValidationException::class.java) { runBlocking { expense(tk(1)) } }
    }

    @Test fun `validation rules`(): Unit = runBlocking {
        assertThrows(ValidationException::class.java) { runBlocking { expense(0) } }
        assertThrows(ValidationException::class.java) {
            runBlocking { d.finance.addTransaction(TransactionDraft(EXPENSE, "x", 100, cat("Salary"), LocalDate.now(), null)) }
        }
        assertThrows(ValidationException::class.java) { runBlocking { d.finance.createCategory("Grocery", EXPENSE, "more", "#000000") } }
    }

    @Test fun `edit transaction updates totals and revision`(): Unit = runBlocking {
        val id = expense(tk(100))
        d.finance.updateTransaction(id, TransactionDraft(EXPENSE, "Grocery big", tk(400), cat("Grocery"), LocalDate.of(2026, 11, 12), "weekly"))
        val t = d.finance.observeTransaction(id).first()!!
        assertEquals(2L, t.revision)
        assertEquals(tk(400), summary().expenseMinor)
    }

    @Test fun `viewer role cannot change data`(): Unit = runBlocking {
        val me = d.households.currentContext()!!.member
        d.db.members().upsert(d.db.members().get(me.id)!!.copy(role = Role.VIEWER))
        assertThrows(PermissionDeniedException::class.java) { runBlocking { expense(tk(1)) } }
        assertThrows(PermissionDeniedException::class.java) { runBlocking { d.finance.setBudget(nov, 1) } }
        Unit
    }

    @Test fun `itemized expense stores items and total equals sum`(): Unit = runBlocking {
        val items = listOf(
            com.homebudget.domain.model.TransactionItem("Rice", tk(450)),
            com.homebudget.domain.model.TransactionItem("Oil", tk(380)),
            com.homebudget.domain.model.TransactionItem("Eggs", tk(150)),
        )
        val id = d.finance.addTransaction(TransactionDraft(EXPENSE, "Monthly bazar", tk(980), cat("Grocery"), LocalDate.of(2026, 11, 5), null, items))
        val t = d.finance.observeTransaction(id).first()!!
        assertEquals(items, t.items)
        assertEquals(tk(980), summary().expenseMinor)
        // Payload queued for sync carries the items.
        assertTrue(d.db.sync().allOperations(d.hh()).last().payload.contains("\"Oil\""))
        // Edit replaces the item list.
        d.finance.updateTransaction(id, TransactionDraft(EXPENSE, "Monthly bazar", tk(450), cat("Grocery"), LocalDate.of(2026, 11, 5), null, items.take(1)))
        assertEquals(1, d.finance.observeTransaction(id).first()!!.items.size)
        assertEquals(tk(450), summary().expenseMinor)
        // Month list exposes items (used for search).
        assertEquals(listOf("Rice"), d.finance.observeTransactions(d.hh(), nov).first().single().items.map { it.name })
        // Delete removes it from totals.
        d.finance.deleteTransaction(id)
        assertEquals(0L, summary().expenseMinor)
    }

    @Test fun `itemized total mismatch is rejected`() {
        assertThrows(ValidationException::class.java) {
            runBlocking {
                d.finance.addTransaction(TransactionDraft(EXPENSE, "Bazar", tk(100), cat("Grocery"), LocalDate.of(2026, 11, 5), null,
                    listOf(com.homebudget.domain.model.TransactionItem("Rice", tk(450)))))
            }
        }
    }
}
