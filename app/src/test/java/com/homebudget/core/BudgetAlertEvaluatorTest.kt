package com.homebudget.core

import com.homebudget.core.common.NotificationSettings
import com.homebudget.core.notifications.AlertLevel
import com.homebudget.core.notifications.BudgetAlertEvaluator
import com.homebudget.domain.model.BudgetCalculator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

class BudgetAlertEvaluatorTest {
    private val m = YearMonth.of(2026, 11)
    private val on = NotificationSettings()
    private fun s(budget: Long, expense: Long) = BudgetCalculator.fromTotals(budget, null, 0, expense)

    @Test fun `80 and 90 percent warnings`() {
        assertEquals(listOf(AlertLevel.WARN_80), BudgetAlertEvaluator.evaluateChange("h", m, s(100, 80), on, emptySet()).show)
        assertEquals(listOf(AlertLevel.WARN_90), BudgetAlertEvaluator.evaluateChange("h", m, s(100, 95), on, emptySet()).show)
        assertEquals(emptyList<AlertLevel>(), BudgetAlertEvaluator.evaluateChange("h", m, s(100, 79), on, emptySet()).show)
    }

    @Test fun `thresholds are configurable`() {
        val no80 = on.copy(warn80 = false)
        assertTrue(BudgetAlertEvaluator.evaluateChange("h", m, s(100, 85), no80, emptySet()).show.isEmpty())
    }

    @Test fun `exceeding budget alerts and schedules daily reminder`() {
        val p = BudgetAlertEvaluator.evaluateChange("h", m, s(30_000, 35_000), on, emptySet())
        assertEquals(listOf(AlertLevel.OVER_BUDGET), p.show)
        assertTrue(p.scheduleDailyReminder)
    }

    @Test fun `budget no longer exceeded cancels reminders`() {
        assertFalse(BudgetAlertEvaluator.evaluateChange("h", m, s(30_000, 20_000), on, emptySet()).scheduleDailyReminder)
        assertFalse(BudgetAlertEvaluator.evaluateDaily("h", m, LocalDate.of(2026, 11, 16), s(30_000, 20_000), on, emptySet()).scheduleDailyReminder)
    }

    @Test fun `disabled notifications show and schedule nothing`() {
        val off = on.copy(enabled = false)
        val p = BudgetAlertEvaluator.evaluateChange("h", m, s(100, 500), off, emptySet())
        assertTrue(p.show.isEmpty()); assertFalse(p.scheduleDailyReminder)
        val d = BudgetAlertEvaluator.evaluateDaily("h", m, LocalDate.of(2026, 11, 16), s(100, 500), off, emptySet())
        assertTrue(d.show.isEmpty()); assertFalse(d.scheduleDailyReminder)
    }

    @Test fun `no duplicate alert for same household month and day`() {
        val day = LocalDate.of(2026, 11, 16)
        val already = setOf(BudgetAlertEvaluator.key("h", m, AlertLevel.OVER_BUDGET), BudgetAlertEvaluator.key("h", m, AlertLevel.DAILY_OVER_BUDGET, day))
        assertTrue(BudgetAlertEvaluator.evaluateChange("h", m, s(100, 500), on, already).show.isEmpty())
        assertTrue(BudgetAlertEvaluator.evaluateDaily("h", m, day, s(100, 500), on, already).show.isEmpty())
        assertEquals(listOf(AlertLevel.DAILY_OVER_BUDGET), BudgetAlertEvaluator.evaluateDaily("h", m, day.plusDays(1), s(100, 500), on, already).show)
    }
}
