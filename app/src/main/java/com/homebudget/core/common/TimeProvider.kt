package com.homebudget.core.common

import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

interface TimeProvider {
    fun now(): Instant
    fun zone(): ZoneId
    fun today(): LocalDate = now().atZone(zone()).toLocalDate()
    fun currentMonth(): YearMonth = YearMonth.from(today())
}

@Singleton
class SystemTimeProvider @Inject constructor() : TimeProvider {
    override fun now(): Instant = Instant.now()
    // Resolved on every call so device timezone changes are respected.
    override fun zone(): ZoneId = ZoneId.systemDefault()
}

/** Household month boundary = calendar month in the device's local timezone (Decision D-01). */
object MonthKey {
    fun of(ym: YearMonth): Int = ym.year * 100 + ym.monthValue
    fun toYearMonth(key: Int): YearMonth = YearMonth.of(key / 100, key % 100)
    /** Inclusive epochDay range for a month; transactionDate is stored as LocalDate.toEpochDay(). */
    fun epochDayRange(ym: YearMonth): LongRange = ym.atDay(1).toEpochDay()..ym.atEndOfMonth().toEpochDay()
}
