package com.homebudget.presentation.dashboard

import androidx.lifecycle.viewModelScope
import com.homebudget.domain.model.BudgetCalculator
import com.homebudget.domain.model.Category
import com.homebudget.domain.model.CategoryTotal
import com.homebudget.domain.model.MonthSummary
import com.homebudget.domain.model.MonthTotals
import com.homebudget.domain.model.SyncStatus
import com.homebudget.domain.model.Transaction
import com.homebudget.domain.model.TransactionType
import com.homebudget.domain.repository.FinanceRepository
import com.homebudget.domain.repository.HouseholdContext
import com.homebudget.domain.repository.HouseholdRepository
import com.homebudget.domain.repository.SyncRepository
import com.homebudget.presentation.common.BaseViewModel
import com.homebudget.presentation.common.MonthStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import java.time.YearMonth
import javax.inject.Inject

data class DashboardState(
    val context: HouseholdContext,
    val month: YearMonth,
    val summary: MonthSummary,
    val categoryTotals: List<CategoryTotal>,
    val series: List<MonthTotals>,
    val recent: List<Transaction>,
    val categories: Map<String, Category>,
    val sync: SyncStatus,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class DashboardViewModel @Inject constructor(
    households: HouseholdRepository,
    private val finance: FinanceRepository,
    private val syncRepo: SyncRepository,
    val months: MonthStore,
) : BaseViewModel() {

    val state: StateFlow<DashboardState?> = combine(households.context.filterNotNull(), months.month) { c, m -> c to m }
        .flatMapLatest { (ctx, month) ->
            val hh = ctx.householdId
            val summary = combine(
                finance.observeBudget(hh, month), finance.observeOpeningBalance(hh, month), finance.observeMonthTotals(hh, month),
            ) { budget, opening, totals -> BudgetCalculator.fromTotals(budget?.amountMinor, opening?.amountMinor, totals.first, totals.second) }
            val details = combine(
                finance.observeCategoryTotals(hh, TransactionType.EXPENSE, month, month),
                finance.observeMonthlySeries(hh, month.minusMonths(5), month),
                finance.observeRecent(hh, 6),
                finance.observeCategories(hh),
            ) { cats, series, recent, allCats -> Triple(cats, series, recent) to allCats.associateBy { it.id } }
            combine(summary, details, syncRepo.observeStatus(hh)) { s, d, sync ->
                DashboardState(ctx, month, s, d.first.first, d.first.second, d.first.third, d.second, sync)
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun syncNow() = syncRepo.requestSync()
}
