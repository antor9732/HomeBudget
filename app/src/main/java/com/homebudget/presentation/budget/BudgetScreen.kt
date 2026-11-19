package com.homebudget.presentation.budget

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.homebudget.core.common.Money
import com.homebudget.domain.model.Budget
import com.homebudget.domain.model.OpeningBalance
import com.homebudget.domain.repository.FinanceRepository
import com.homebudget.domain.repository.HouseholdContext
import com.homebudget.domain.repository.HouseholdRepository
import com.homebudget.presentation.common.BaseViewModel
import com.homebudget.presentation.common.MoneyText
import com.homebudget.presentation.common.MonthFmt
import com.homebudget.presentation.common.MonthSelector
import com.homebudget.presentation.common.MonthStore
import com.homebudget.presentation.common.SectionCard
import com.homebudget.presentation.transactions.CollectMessages
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import java.time.YearMonth
import javax.inject.Inject

data class BudgetState(val ctx: HouseholdContext, val month: YearMonth, val budget: Budget?, val opening: OpeningBalance?, val history: List<Budget>)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class BudgetViewModel @Inject constructor(
    private val households: HouseholdRepository,
    private val finance: FinanceRepository,
    val months: MonthStore,
) : BaseViewModel() {
    val state = combine(households.context.filterNotNull(), months.month) { c, m -> c to m }.flatMapLatest { (ctx, m) ->
        combine(finance.observeBudget(ctx.householdId, m), finance.observeOpeningBalance(ctx.householdId, m), finance.observeBudgetHistory(ctx.householdId)) { b, o, h ->
            BudgetState(ctx, m, b, o, h)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun saveBudget(text: String) = launchSafely {
        val v = Money.parseToMinor(text) ?: return@launchSafely message("Enter a valid amount")
        finance.setBudget(months.month.value, v); message("Budget saved")
    }

    fun copyPrevious() = launchSafely {
        val ctx = households.context.filterNotNull().first()
        val prev = finance.getBudget(ctx.householdId, months.month.value.minusMonths(1))
            ?: return@launchSafely message("No budget in ${months.month.value.minusMonths(1).format(MonthFmt)}")
        finance.setBudget(months.month.value, prev.amountMinor); message("Copied previous month's budget")
    }

    fun saveOpening(text: String) = launchSafely {
        if (text.isBlank()) { finance.setOpeningBalance(months.month.value, null); message("Opening balance cleared"); return@launchSafely }
        val v = Money.parseToMinor(text) ?: return@launchSafely message("Enter a valid amount")
        finance.setOpeningBalance(months.month.value, v); message("Opening balance saved")
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BudgetScreen(onBack: () -> Unit, vm: BudgetViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val host = remember { SnackbarHostState() }
    CollectMessages(vm, host)
    Scaffold(
        topBar = { TopAppBar(title = { Text("Monthly budget") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } }) },
        snackbarHost = { SnackbarHost(host) },
    ) { pad ->
        val s = state ?: return@Scaffold
        var budgetText by remember(s.month, s.budget?.amountMinor) { mutableStateOf(s.budget?.let { Money.toInputString(it.amountMinor) } ?: "") }
        var openingText by remember(s.month, s.opening?.amountMinor) { mutableStateOf(s.opening?.let { Money.toInputString(it.amountMinor) } ?: "") }
        LaunchedEffect(s.month) {}
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { MonthSelector(s.month, vm.months::previous, vm.months::next) }
            item {
                SectionCard(title = "Spending budget") {
                    Text("One household spending limit for ${s.month.format(MonthFmt)}. Income does not increase it.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedTextField(budgetText, { budgetText = it }, Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        label = { Text("Budget (${s.ctx.household.currencyCode})") }, enabled = s.ctx.canManageSetup, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                    if (s.ctx.canManageSetup) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { vm.saveBudget(budgetText) }) { Text("Save budget") }
                        OutlinedButton(onClick = vm::copyPrevious) { Text("Copy last month") }
                    } else Text("Only the owner or an admin can change budgets.", style = MaterialTheme.typography.bodySmall)
                }
            }
            item {
                SectionCard(title = "Opening cash balance") {
                    Text("Optional. Cash on hand at the start of the month; enables the 'Available cash' figure (opening + income − expenses). Not carried forward automatically.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedTextField(openingText, { openingText = it }, Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        label = { Text("Opening balance (leave empty to clear)") }, enabled = s.ctx.canManageSetup, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                    if (s.ctx.canManageSetup) Button(onClick = { vm.saveOpening(openingText) }) { Text("Save opening balance") }
                }
            }
            item { Text("Budget history", style = MaterialTheme.typography.titleMedium) }
            items(s.history, key = { it.month.toString() }) { b ->
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                    TextButton(onClick = { vm.months.set(b.month) }, Modifier.weight(1f), contentPadding = PaddingValues(0.dp)) {
                        Text(b.month.format(MonthFmt), Modifier.fillMaxWidth())
                    }
                    MoneyText(b.amountMinor, s.ctx.household.currencyCode)
                }
                HorizontalDivider()
            }
        }
    }
}
