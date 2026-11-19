package com.homebudget.presentation.reports

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.homebudget.domain.model.CategoryTotal
import com.homebudget.domain.model.MonthTotals
import com.homebudget.domain.model.TransactionType
import com.homebudget.domain.repository.FinanceRepository
import com.homebudget.domain.repository.HouseholdContext
import com.homebudget.domain.repository.HouseholdRepository
import com.homebudget.presentation.common.BaseViewModel
import com.homebudget.presentation.common.CategoryBadge
import com.homebudget.presentation.common.IncomeExpenseBars
import com.homebudget.presentation.common.LabeledRow
import com.homebudget.presentation.common.MoneyText
import com.homebudget.presentation.common.MonthFmt
import com.homebudget.presentation.common.MonthSelector
import com.homebudget.presentation.common.MonthStore
import com.homebudget.presentation.common.SectionCard
import com.homebudget.presentation.common.ShortMonthFmt
import com.homebudget.presentation.common.parseColor
import com.homebudget.presentation.theme.expenseColor
import com.homebudget.presentation.theme.incomeColor
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import java.time.YearMonth
import javax.inject.Inject

data class ReportsState(
    val ctx: HouseholdContext,
    val month: YearMonth,
    val current: MonthTotals,
    val previous: MonthTotals,
    val expenseByCategory: List<CategoryTotal>,
    val incomeByCategory: List<CategoryTotal>,
    val year: Int,
    val yearSeries: List<MonthTotals>,
    val yearExpenseByCategory: List<CategoryTotal>,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ReportsViewModel @Inject constructor(
    households: HouseholdRepository,
    finance: FinanceRepository,
    val months: MonthStore,
) : BaseViewModel() {
    val year = MutableStateFlow(months.month.value.year)

    val state = combine(households.context.filterNotNull(), months.month, year) { c, m, y -> Triple(c, m, y) }.flatMapLatest { (ctx, m, y) ->
        val hh = ctx.householdId
        combine(
            finance.observeMonthlySeries(hh, m.minusMonths(1), m),
            finance.observeCategoryTotals(hh, TransactionType.EXPENSE, m, m),
            finance.observeCategoryTotals(hh, TransactionType.INCOME, m, m),
            finance.observeMonthlySeries(hh, YearMonth.of(y, 1), YearMonth.of(y, 12)),
            finance.observeCategoryTotals(hh, TransactionType.EXPENSE, YearMonth.of(y, 1), YearMonth.of(y, 12)),
        ) { two, exp, inc, ys, yexp -> ReportsState(ctx, m, two[1], two[0], exp, inc, y, ys, yexp) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}

@Composable
fun ReportsScreen(vm: ReportsViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val s = state ?: return
    var tab by remember { mutableStateOf(0) }
    val cur = s.ctx.household.currencyCode
    Column(Modifier.fillMaxSize()) {
        Text("Reports", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(16.dp, 8.dp))
        PrimaryTabRow(selectedTabIndex = tab) {
            Tab(tab == 0, { tab = 0 }, text = { Text("Monthly") })
            Tab(tab == 1, { tab = 1 }, text = { Text("Yearly") })
        }
        LazyColumn(contentPadding = PaddingValues(16.dp, 12.dp, 16.dp, 96.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (tab == 0) {
                item { MonthSelector(s.month, vm.months::previous, vm.months::next) }
                item {
                    SectionCard(title = "Totals") {
                        LabeledRow("Income") { MoneyText(s.current.incomeMinor, cur, color = incomeColor()) }
                        LabeledRow("Expenses") { MoneyText(s.current.expenseMinor, cur, color = expenseColor()) }
                        HorizontalDivider()
                        LabeledRow("Net income") { MoneyText(s.current.incomeMinor - s.current.expenseMinor, cur, showSign = true) }
                    }
                }
                item {
                    SectionCard(title = "Compared with ${s.previous.month.format(MonthFmt)}") {
                        ComparisonRow("Income", s.current.incomeMinor, s.previous.incomeMinor, cur, higherIsGood = true)
                        ComparisonRow("Expenses", s.current.expenseMinor, s.previous.expenseMinor, cur, higherIsGood = false)
                    }
                }
                item { CategoryBreakdown("Expenses by category", s.expenseByCategory, cur) }
                item { CategoryBreakdown("Income by category", s.incomeByCategory, cur) }
            } else {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { vm.year.value-- }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Previous year") }
                        Text("${s.year}", style = MaterialTheme.typography.titleMedium)
                        IconButton(onClick = { vm.year.value++ }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Next year") }
                    }
                }
                item {
                    val inc = s.yearSeries.sumOf { it.incomeMinor }
                    val exp = s.yearSeries.sumOf { it.expenseMinor }
                    SectionCard(title = "Year summary") {
                        LabeledRow("Income") { MoneyText(inc, cur, color = incomeColor()) }
                        LabeledRow("Expenses") { MoneyText(exp, cur, color = expenseColor()) }
                        LabeledRow("Net income") { MoneyText(inc - exp, cur, showSign = true) }
                        LabeledRow("Average monthly expense") { MoneyText(exp / 12, cur) }
                    }
                }
                item { SectionCard(title = "Month by month") { IncomeExpenseBars(s.yearSeries) } }
                item {
                    SectionCard(title = "Monthly table") {
                        s.yearSeries.forEach { m ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                                Text(m.month.format(ShortMonthFmt), Modifier.weight(0.6f))
                                MoneyText(m.incomeMinor, cur, Modifier.weight(1f), MaterialTheme.typography.bodySmall, incomeColor())
                                MoneyText(m.expenseMinor, cur, Modifier.weight(1f), MaterialTheme.typography.bodySmall, expenseColor())
                                MoneyText(m.incomeMinor - m.expenseMinor, cur, Modifier.weight(1f), MaterialTheme.typography.bodySmall, showSign = true)
                            }
                        }
                    }
                }
                item { CategoryBreakdown("Yearly expenses by category", s.yearExpenseByCategory, cur) }
            }
        }
    }
}

@Composable
private fun ComparisonRow(label: String, now: Long, before: Long, cur: String, higherIsGood: Boolean) {
    val diff = now - before
    val pct = if (before > 0) (diff * 100 / before) else null
    val good = if (higherIsGood) diff >= 0 else diff <= 0
    LabeledRow(label) {
        Column(horizontalAlignment = Alignment.End) {
            MoneyText(now, cur)
            Row {
                MoneyText(diff, cur, style = MaterialTheme.typography.bodySmall, showSign = true, color = if (good) incomeColor() else expenseColor())
                if (pct != null) Text("  (${if (pct > 0) "+" else ""}$pct%)", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun CategoryBreakdown(title: String, totals: List<CategoryTotal>, cur: String) {
    SectionCard(title = title) {
        if (totals.isEmpty()) Text("No data", color = MaterialTheme.colorScheme.onSurfaceVariant)
        totals.forEach { t ->
            Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                CategoryBadge(t.category?.iconKey, t.category?.colorHex, 32.dp, t.category?.name)
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Row { Text(t.category?.name ?: "Unknown", Modifier.weight(1f)); Text("${t.percentBasisPoints / 100}.${(t.percentBasisPoints % 100) / 10}%", style = MaterialTheme.typography.bodySmall) }
                    Spacer(Modifier.height(4.dp))
                    LinearProgressIndicator(progress = { t.percentBasisPoints / 10_000f }, Modifier.fillMaxWidth(), color = parseColor(t.category?.colorHex ?: "#9E9E9E"), drawStopIndicator = {})
                }
                MoneyText(t.totalMinor, cur, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
