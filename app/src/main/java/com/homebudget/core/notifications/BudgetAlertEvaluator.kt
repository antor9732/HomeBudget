package com.homebudget.core.notifications

import com.homebudget.core.common.NotificationSettings
import com.homebudget.domain.model.MonthSummary
import java.time.LocalDate
import java.time.YearMonth

enum class AlertLevel { WARN_80, WARN_90, OVER_BUDGET, DAILY_OVER_BUDGET }

data class AlertPlan(
    /** Alerts to display now (already de-duplicated). */
    val show: List<AlertLevel>,
    /** Whether the daily over-budget reminder should be scheduled (true) or cancelled (false). */
    val scheduleDailyReminder: Boolean,
)

/** Pure notification rules from Section 7, tested in BudgetAlertEvaluatorTest. */
object BudgetAlertEvaluator {

    fun key(householdId: String, month: YearMonth, level: AlertLevel, day: LocalDate? = null): String =
        "$householdId:$month:${level.name}" + (day?.let { ":$it" } ?: "")

    /** Evaluation after any data change (threshold crossings). */
    fun evaluateChange(
        householdId: String,
        month: YearMonth,
        summary: MonthSummary,
        settings: NotificationSettings,
        alreadyNotified: Set<String>,
    ): AlertPlan {
        if (!settings.enabled) return AlertPlan(emptyList(), scheduleDailyReminder = false)
        val util = summary.utilizationBasisPoints ?: return AlertPlan(emptyList(), false)
        val show = ArrayList<AlertLevel>()
        // Show only the highest newly-crossed level to avoid a burst of notifications.
        when {
            summary.isOverBudget -> AlertLevel.OVER_BUDGET
            util >= 9_000 && settings.warn90 -> AlertLevel.WARN_90
            util >= 8_000 && settings.warn80 -> AlertLevel.WARN_80
            else -> null
        }?.let { level ->
            if (key(householdId, month, level) !in alreadyNotified) show += level
        }
        return AlertPlan(show, scheduleDailyReminder = summary.isOverBudget && settings.dailyOverBudgetReminder)
    }

    /** Evaluation when the daily reminder fires (always recalculated from Room by the caller). */
    fun evaluateDaily(
        householdId: String,
        month: YearMonth,
        today: LocalDate,
        summary: MonthSummary,
        settings: NotificationSettings,
        alreadyNotified: Set<String>,
    ): AlertPlan {
        if (!settings.enabled || !settings.dailyOverBudgetReminder) return AlertPlan(emptyList(), false)
        if (!summary.isOverBudget) return AlertPlan(emptyList(), scheduleDailyReminder = false)
        val k = key(householdId, month, AlertLevel.DAILY_OVER_BUDGET, today)
        return AlertPlan(if (k in alreadyNotified) emptyList() else listOf(AlertLevel.DAILY_OVER_BUDGET), scheduleDailyReminder = true)
    }
}
