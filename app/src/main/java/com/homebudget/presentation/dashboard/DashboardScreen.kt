package com.homebudget.presentation.dashboard

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.homebudget.domain.model.Category
import com.homebudget.domain.model.SyncHealth
import com.homebudget.domain.model.Transaction
import com.homebudget.domain.model.TransactionType
import com.homebudget.presentation.common.BudgetBar
import com.homebudget.presentation.common.CategoryBadge
import com.homebudget.presentation.common.DayFmt
import com.homebudget.presentation.common.DonutChart
import com.homebudget.presentation.common.DonutSlice
import com.homebudget.presentation.common.EmptyState
import com.homebudget.presentation.common.IncomeExpenseBars
import com.homebudget.presentation.common.LabeledRow
import com.homebudget.presentation.common.LegendDot
import com.homebudget.presentation.common.MoneyText
import com.homebudget.presentation.common.MonthSelector
import com.homebudget.presentation.common.Pill
import com.homebudget.presentation.common.SectionCard
import com.homebudget.presentation.common.parseColor
import com.homebudget.presentation.theme.expenseColor
import com.homebudget.presentation.theme.incomeColor
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    onAdd: (TransactionType) -> Unit,
    onOpenTransaction: (String) -> Unit,
    onOpenBudget: () -> Unit,
    onOpenSync: () -> Unit,
    onOpenMembers: () -> Unit,
    onSeeAll: () -> Unit,
    vm: DashboardViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val s = state ?: return Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
    val cur = s.context.household.currencyCode
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(s.context.household.name, style = MaterialTheme.typography.headlineMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("Signed in as ${s.context.member.displayName} · ${s.context.role.name.lowercase()}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                androidx.compose.material3.IconButton(onClick = onOpenMembers) { Icon(Icons.Filled.Group, contentDescription = "Household members") }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                MonthSelector(s.month, vm.months::previous, vm.months::next, Modifier.weight(1f))
                SyncChip(s.sync.health, s.sync.pendingCount, onOpenSync)
            }
            if (s.context.readOnly) {
                Pill("Read-only: household access was revoked", MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer)
            }
        }
        item { BudgetCard(s, cur, onOpenBudget) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MetricCard("Income", s.summary.incomeMinor, cur, incomeColor(), Modifier.weight(1f))
                MetricCard("Expenses", s.summary.expenseMinor, cur, expenseColor(), Modifier.weight(1f))
            }
        }
        item {
            SectionCard(title = "Cash flow") {
                LabeledRow("Monthly net income") { MoneyText(s.summary.netIncomeMinor, cur, showSign = true, color = if (s.summary.netIncomeMinor >= 0) incomeColor() else expenseColor()) }
                if (s.summary.availableCashMinor != null) {
                    LabeledRow("Opening balance") { MoneyText(s.summary.openingBalanceMinor ?: 0, cur) }
                    LabeledRow("Available cash") { MoneyText(s.summary.availableCashMinor!!, cur, style = MaterialTheme.typography.titleMedium) }
                } else {
                    TextButton(onClick = onOpenBudget, contentPadding = PaddingValues(0.dp)) { Text("Set an opening balance to see available cash") }
                }
            }
        }
        if (s.context.canEditTransactions) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    FilledTonalButton(onClick = { onAdd(TransactionType.EXPENSE) }, Modifier.weight(1f).height(52.dp)) {
                        Icon(Icons.Filled.Remove, contentDescription = null); Spacer(Modifier.width(8.dp)); Text("Add expense")
                    }
                    FilledTonalButton(onClick = { onAdd(TransactionType.INCOME) }, Modifier.weight(1f).height(52.dp)) {
                        Icon(Icons.Filled.Add, contentDescription = null); Spacer(Modifier.width(8.dp)); Text("Add income")
                    }
                }
            }
        }
        item {
            SectionCard(title = "Spending by category") {
                if (s.categoryTotals.isEmpty()) {
                    Text("No expenses this month yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    val slices = s.categoryTotals.map { DonutSlice(it.category?.name ?: "Unknown", it.totalMinor, parseColor(it.category?.colorHex ?: "#9E9E9E")) }
                    DonutChart(slices) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Spent", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            MoneyText(s.summary.expenseMinor, cur, style = MaterialTheme.typography.titleLarge)
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    s.categoryTotals.take(6).forEach { ct ->
                        Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            LegendDot(parseColor(ct.category?.colorHex ?: "#9E9E9E"))
                            Text("  ${ct.category?.name ?: "Unknown"}", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                            Text("${ct.percentBasisPoints / 100}%  ", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            MoneyText(ct.totalMinor, cur, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
        item { SectionCard(title = "Income vs expense (6 months)") { IncomeExpenseBars(s.series) } }
        item {
            SectionCard(title = "Recent activity", action = { TextButton(onClick = onSeeAll) { Text("See all") } }) {
                if (s.recent.isEmpty()) EmptyState(Icons.Filled.ReceiptLong, "No transactions", "Add your first income or expense.")
                s.recent.forEach { TransactionRow(it, s.categories[it.categoryId], cur) { onOpenTransaction(it.id) } }
            }
        }
    }
}

@Composable
private fun BudgetCard(s: DashboardState, cur: String, onOpenBudget: () -> Unit) {
    SectionCard(title = "Monthly budget", action = { TextButton(onClick = onOpenBudget) { Text(if (s.summary.budgetMinor == null) "Set budget" else "Edit") } }) {
        val sum = s.summary
        if (sum.budgetMinor == null) {
            Text("No budget set for this month.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@SectionCard
        }
        Row(verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f)) {
                Text(if (sum.isOverBudget) "Over budget by" else "Remaining", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                MoneyText(if (sum.isOverBudget) sum.overrunMinor else sum.remainingBudgetMinor ?: 0, cur,
                    style = MaterialTheme.typography.displaySmall, color = if (sum.isOverBudget) expenseColor() else MaterialTheme.colorScheme.onSurface)
            }
            Text("${(sum.utilizationBasisPoints ?: 0) / 100}% used", style = MaterialTheme.typography.labelLarge)
        }
        Spacer(Modifier.height(12.dp))
        BudgetBar(sum.utilizationBasisPoints)
        Spacer(Modifier.height(8.dp))
        LabeledRow("Spent of budget") {
            MoneyText(sum.expenseMinor, cur, style = MaterialTheme.typography.bodyMedium)
            Text(" / ", style = MaterialTheme.typography.bodyMedium)
            MoneyText(sum.budgetMinor, cur, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun MetricCard(label: String, minor: Long, cur: String, color: androidx.compose.ui.graphics.Color, modifier: Modifier) {
    SectionCard(modifier) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        MoneyText(minor, cur, style = MaterialTheme.typography.titleLarge, color = color)
    }
}

@Composable
private fun SyncChip(health: SyncHealth, pending: Int, onClick: () -> Unit) {
    val (icon, label) = when (health) {
        SyncHealth.LOCAL_ONLY -> Icons.Filled.CloudOff to "On this phone"
        SyncHealth.IDLE -> if (pending > 0) Icons.Filled.CloudSync to "$pending to sync" else Icons.Filled.CloudDone to "Synced"
        SyncHealth.SYNCING -> Icons.Filled.CloudSync to "Syncing"
        SyncHealth.ERROR -> Icons.Filled.ErrorOutline to "Sync issue"
        SyncHealth.AUTH_REQUIRED -> Icons.Filled.Lock to "Authorize"
        SyncHealth.ACCESS_REVOKED -> Icons.Filled.Lock to "No access"
    }
    AssistChip(onClick = onClick, label = { Text(label) }, leadingIcon = { Icon(icon, contentDescription = null, Modifier.size(18.dp)) })
}

@Composable
fun TransactionRow(t: Transaction, category: Category?, currency: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        CategoryBadge(category?.iconKey, category?.colorHex, label = category?.name)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(t.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${category?.name ?: "—"} · ${t.date.format(DayFmt)}" + (if (t.items.isNotEmpty()) " · ${t.items.size} items" else ""), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        val income = t.type == TransactionType.INCOME
        MoneyText(if (income) t.amountMinor else -t.amountMinor, currency, showSign = true,
            color = if (income) incomeColor() else MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyLarge)
    }
}

fun formatTimestamp(millis: Long?): String = millis?.let { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it)) } ?: "Never"
