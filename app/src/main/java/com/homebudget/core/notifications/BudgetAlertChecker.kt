package com.homebudget.core.notifications

import com.homebudget.core.common.AppPreferences
import com.homebudget.core.common.TimeProvider
import com.homebudget.domain.model.BudgetCalculator
import com.homebudget.domain.repository.FinanceRepository
import com.homebudget.domain.repository.HouseholdRepository
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/** Recalculates from Room (never cached totals) and shows de-duplicated alerts. */
@Singleton
class BudgetAlertChecker @Inject constructor(
    private val households: HouseholdRepository,
    private val finance: FinanceRepository,
    private val prefs: AppPreferences,
    private val notifier: Notifier,
    private val time: TimeProvider,
) {
    /** @return whether the daily reminder should remain scheduled. */
    suspend fun checkAfterChange(): Boolean {
        val ctx = households.currentContext() ?: return false
        val month = time.currentMonth()
        val summary = summary(ctx.householdId, month)
        val settings = prefs.notificationSettings.first()
        val plan = BudgetAlertEvaluator.evaluateChange(ctx.householdId, month, summary, settings, prefs.notifiedKeys())
        for (level in plan.show) {
            if (notifier.show(level, ctx.household.name, summary, ctx.household.currencyCode)) {
                prefs.addNotifiedKey(BudgetAlertEvaluator.key(ctx.householdId, month, level))
            }
        }
        return plan.scheduleDailyReminder
    }

    suspend fun runDaily(): Boolean {
        val ctx = households.currentContext() ?: return false
        val month = time.currentMonth()
        val today = time.today()
        val summary = summary(ctx.householdId, month)
        val settings = prefs.notificationSettings.first()
        val plan = BudgetAlertEvaluator.evaluateDaily(ctx.householdId, month, today, summary, settings, prefs.notifiedKeys())
        for (level in plan.show) {
            if (notifier.show(level, ctx.household.name, summary, ctx.household.currencyCode)) {
                prefs.addNotifiedKey(BudgetAlertEvaluator.key(ctx.householdId, month, level, today))
            }
        }
        return plan.scheduleDailyReminder
    }

    private suspend fun summary(hh: String, month: java.time.YearMonth) = finance.monthTotals(hh, month).let { (income, expense) ->
        BudgetCalculator.fromTotals(finance.getBudget(hh, month)?.amountMinor, finance.observeOpeningBalance(hh, month).first()?.amountMinor, income, expense)
    }
}
