package com.homebudget.domain

import com.homebudget.domain.model.AmountLine
import com.homebudget.domain.model.BudgetCalculator
import com.homebudget.domain.model.TransactionType.EXPENSE
import com.homebudget.domain.model.TransactionType.INCOME
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private fun tk(taka: Long) = taka * 100 // taka -> poisha

class BudgetCalculatorTest {

    @Test
    fun `plan section 6 example`() {
        val s = BudgetCalculator.summarize(
            budgetMinor = tk(30_000), openingMinor = tk(5_000),
            lines = listOf(AmountLine(INCOME, tk(40_000)), AmountLine(EXPENSE, tk(35_000))),
        )
        assertEquals(tk(30_000), s.budgetMinor)
        assertEquals(tk(40_000), s.incomeMinor)
        assertEquals(tk(35_000), s.expenseMinor)
        assertEquals(-tk(5_000), s.remainingBudgetMinor)   // R = B - E
        assertEquals(tk(5_000), s.overrunMinor)            // N = max(0, E - B)
        assertEquals(tk(5_000), s.netIncomeMinor)          // M = I - E
        assertEquals(tk(10_000), s.availableCashMinor)     // C = O + I - E
        assertTrue(s.isOverBudget)
    }

    @Test
    fun `remaining budget can be negative while available cash is positive`() {
        val s = BudgetCalculator.fromTotals(tk(10_000), tk(50_000), income = 0, expense = tk(12_000))
        assertEquals(-tk(2_000), s.remainingBudgetMinor)
        assertEquals(tk(38_000), s.availableCashMinor)
    }

    @Test
    fun `income does not reduce budget utilization and expenses are not income`() {
        val s = BudgetCalculator.summarize(tk(30_000), null, listOf(AmountLine(INCOME, tk(100_000)), AmountLine(EXPENSE, tk(15_000))))
        assertEquals(5_000, s.utilizationBasisPoints) // 50.00%
        assertEquals(tk(100_000), s.incomeMinor)
        assertEquals(tk(15_000), s.remainingBudgetMinor)
        assertEquals(0L, s.overrunMinor)
    }

    @Test
    fun `soft deleted transactions are excluded`() {
        val s = BudgetCalculator.summarize(tk(1_000), null, listOf(AmountLine(EXPENSE, tk(500)), AmountLine(EXPENSE, tk(300), isDeleted = true)))
        assertEquals(tk(500), s.expenseMinor)
    }

    @Test
    fun `available cash hidden without opening balance`() {
        val s = BudgetCalculator.fromTotals(tk(1_000), null, tk(10), tk(5))
        assertNull(s.availableCashMinor)
    }

    @Test
    fun `no budget gives no remaining or utilization`() {
        val s = BudgetCalculator.fromTotals(null, null, 0, tk(5))
        assertNull(s.remainingBudgetMinor)
        assertNull(s.utilizationBasisPoints)
        assertEquals(0L, s.overrunMinor)
        assertFalse(s.isOverBudget)
    }

    @Test
    fun `poisha precision is exact`() {
        val s = BudgetCalculator.summarize(10, null, listOf(AmountLine(EXPENSE, 1), AmountLine(EXPENSE, 2)))
        assertEquals(3L, s.expenseMinor)
        assertEquals(7L, s.remainingBudgetMinor)
        assertEquals(3_000, s.utilizationBasisPoints)
    }

    @Test
    fun `percent basis points`() {
        assertEquals(2_500, BudgetCalculator.percentBasisPoints(25, 100))
        assertEquals(0, BudgetCalculator.percentBasisPoints(5, 0))
    }
}
