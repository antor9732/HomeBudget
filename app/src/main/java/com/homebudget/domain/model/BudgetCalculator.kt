package com.homebudget.domain.model

import kotlin.math.max

/**
 * Section 6 financial formulas. Pure function; all values are integer minor units.
 *  B budget, I income, E expenses, R = B - E, N = max(0, E - B), M = I - E, O opening, C = O + I - E.
 */
data class MonthSummary(
    val budgetMinor: Long?,            // B (null = no budget set)
    val incomeMinor: Long,             // I
    val expenseMinor: Long,            // E
    val remainingBudgetMinor: Long?,   // R
    val overrunMinor: Long,            // N
    val netIncomeMinor: Long,          // M
    val openingBalanceMinor: Long?,    // O
    val availableCashMinor: Long?,     // C (only when O configured)
    /** Budget utilization in basis points (10000 = 100%). Null if no budget or budget is 0. */
    val utilizationBasisPoints: Int?,
) {
    val isOverBudget: Boolean get() = overrunMinor > 0
}

data class AmountLine(val type: TransactionType, val amountMinor: Long, val isDeleted: Boolean = false)

object BudgetCalculator {
    fun summarize(budgetMinor: Long?, openingMinor: Long?, lines: List<AmountLine>): MonthSummary {
        val active = lines.filterNot { it.isDeleted }
        val income = active.filter { it.type == TransactionType.INCOME }.sumOf { it.amountMinor }
        val expense = active.filter { it.type == TransactionType.EXPENSE }.sumOf { it.amountMinor }
        return fromTotals(budgetMinor, openingMinor, income, expense)
    }

    fun fromTotals(budgetMinor: Long?, openingMinor: Long?, income: Long, expense: Long): MonthSummary {
        val remaining = budgetMinor?.let { it - expense }
        val overrun = budgetMinor?.let { max(0L, expense - it) } ?: 0L
        val utilization = budgetMinor?.takeIf { it > 0 }?.let { b ->
            // Integer arithmetic; clamp to Int range for absurd values.
            val bp = (expense * 10_000L) / b
            bp.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        }
        return MonthSummary(
            budgetMinor = budgetMinor,
            incomeMinor = income,
            expenseMinor = expense,
            remainingBudgetMinor = remaining,
            overrunMinor = overrun,
            netIncomeMinor = income - expense,
            openingBalanceMinor = openingMinor,
            availableCashMinor = openingMinor?.let { it + income - expense },
            utilizationBasisPoints = utilization,
        )
    }

    fun percentBasisPoints(part: Long, total: Long): Int = if (total <= 0) 0 else ((part * 10_000L) / total).toInt()
}
