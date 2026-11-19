package com.homebudget.presentation.common

import com.homebudget.core.common.TimeProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.time.YearMonth
import javax.inject.Inject
import javax.inject.Singleton

/** Month selected across Dashboard, Transactions, Budget and Reports. */
@Singleton
class MonthStore @Inject constructor(time: TimeProvider) {
    private val _month = MutableStateFlow(time.currentMonth())
    val month: StateFlow<YearMonth> = _month
    fun set(m: YearMonth) { _month.value = m }
    fun previous() { _month.value = _month.value.minusMonths(1) }
    fun next() { _month.value = _month.value.plusMonths(1) }
}
