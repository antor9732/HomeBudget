package com.homebudget.presentation.transactions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Switch
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.homebudget.core.common.Money
import com.homebudget.domain.model.TransactionType
import com.homebudget.presentation.common.BaseViewModel
import com.homebudget.presentation.common.CategoryBadge
import com.homebudget.presentation.common.DayFmt
import com.homebudget.presentation.common.EmptyState
import com.homebudget.presentation.common.LabeledRow
import com.homebudget.presentation.common.MoneyText
import com.homebudget.presentation.common.MonthSelector
import com.homebudget.presentation.common.SectionCard
import com.homebudget.presentation.dashboard.TransactionRow
import com.homebudget.presentation.dashboard.formatTimestamp
import com.homebudget.presentation.theme.expenseColor
import com.homebudget.presentation.theme.incomeColor
import java.time.Instant
import java.time.ZoneOffset

@Composable
fun CollectMessages(vm: BaseViewModel, host: SnackbarHostState) {
    LaunchedEffect(vm) { vm.messages.collect { host.showSnackbar(it) } }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransactionsScreen(onOpen: (String) -> Unit, vm: TransactionsViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val s = state ?: return
    var sortOpen by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            Text("Transactions", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(top = 8.dp))
            MonthSelector(s.month, vm.months::previous, vm.months::next)
            OutlinedTextField(
                value = s.filter.query, onValueChange = vm::setQuery, modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Search title, note or category") }, singleLine = true,
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    Box {
                        IconButton(onClick = { sortOpen = true }) { Icon(Icons.Filled.Sort, contentDescription = "Sort: ${s.filter.sort.label}") }
                        DropdownMenu(sortOpen, { sortOpen = false }) {
                            SortOrder.entries.forEach { o -> DropdownMenuItem(text = { Text(o.label) }, onClick = { vm.setSort(o); sortOpen = false }) }
                        }
                    }
                },
            )
            LazyRow(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item { FilterChip(s.filter.type == null, { vm.setType(null) }, { Text("All") }) }
                item { FilterChip(s.filter.type == TransactionType.EXPENSE, { vm.setType(TransactionType.EXPENSE) }, { Text("Expenses") }) }
                item { FilterChip(s.filter.type == TransactionType.INCOME, { vm.setType(TransactionType.INCOME) }, { Text("Income") }) }
                val cats = s.categories.values.filter { s.filter.type == null || it.type == s.filter.type }.sortedBy { it.name }
                items(cats, key = { it.id }) { c ->
                    FilterChip(s.filter.categoryId == c.id, { vm.setCategory(if (s.filter.categoryId == c.id) null else c.id) }, { Text(c.name) })
                }
            }
            Row(Modifier.padding(bottom = 4.dp)) {
                Text("In ", style = MaterialTheme.typography.bodySmall); MoneyText(s.incomeMinor, s.context.household.currencyCode, style = MaterialTheme.typography.bodySmall, color = incomeColor())
                Text("   Out ", style = MaterialTheme.typography.bodySmall); MoneyText(s.expenseMinor, s.context.household.currencyCode, style = MaterialTheme.typography.bodySmall, color = expenseColor())
                Text("   ${s.items.size} items", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (s.items.isEmpty()) {
            EmptyState(Icons.Filled.ReceiptLong, "Nothing here", "No transactions match this month and filter.")
        } else {
            LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 96.dp)) {
                items(s.items, key = { it.id }) { t -> TransactionRow(t, s.categories[t.categoryId], s.context.household.currencyCode) { onOpen(t.id) } }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TransactionEditScreen(onDone: () -> Unit, vm: TransactionEditViewModel = hiltViewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val host = remember { SnackbarHostState() }
    CollectMessages(vm, host)
    LaunchedEffect(s.saved) { if (s.saved) onDone() }
    var datePicker by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var pasteOpen by remember { mutableStateOf(false) }
    val title = when {
        !s.isNew -> "Edit transaction"
        s.type == TransactionType.INCOME -> "Add income"
        else -> "Add expense"
    }
    Scaffold(
        topBar = {
            TopAppBar(title = { Text(title) },
                navigationIcon = { IconButton(onClick = onDone) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                actions = { if (!s.isNew && s.canDelete) IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Filled.Delete, contentDescription = "Delete") } })
        },
        snackbarHost = { SnackbarHost(host) },
    ) { pad ->
        if (!s.loaded) { Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) { CircularProgressIndicator() }; return@Scaffold }
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf(TransactionType.EXPENSE, TransactionType.INCOME).forEachIndexed { i, t ->
                    SegmentedButton(selected = s.type == t, onClick = { vm.update { it.copy(type = t, categoryId = null) } },
                        shape = SegmentedButtonDefaults.itemShape(i, 2)) { Text(if (t == TransactionType.EXPENSE) "Expense" else "Income") }
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Itemized list", style = MaterialTheme.typography.bodyLarge)
                    Text("Add many items (e.g. monthly bazar) in one entry", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = s.itemized, onCheckedChange = vm::setItemized)
            }
            if (!s.itemized) {
                OutlinedTextField(s.amount, { v -> vm.update { it.copy(amount = v.filter { c -> c.isDigit() || c == '.' || c == ',' }) } },
                    Modifier.fillMaxWidth(), label = { Text("Amount (${s.currency})") }, singleLine = true,
                    textStyle = MaterialTheme.typography.headlineMedium,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next))
            } else {
                ItemsEditor(s, vm, onPaste = { pasteOpen = true })
            }
            OutlinedTextField(s.title, { v -> vm.update { it.copy(title = v.take(120)) } }, Modifier.fillMaxWidth(),
                label = { Text("Title") }, singleLine = true, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next))
            Text("Category", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                s.categories.filter { it.type == s.type && (it.isActive || it.id == s.categoryId) }.forEach { c ->
                    FilterChip(s.categoryId == c.id, { vm.update { it.copy(categoryId = c.id, title = it.title.ifBlank { c.name }) } },
                        label = { Text(c.name) }, leadingIcon = { CategoryBadge(c.iconKey, c.colorHex, size = 22.dp) })
                }
            }
            OutlinedButton(onClick = { datePicker = true }, Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.CalendarMonth, contentDescription = null); Text("  ${s.date.format(DayFmt)}")
            }
            OutlinedTextField(s.description, { v -> vm.update { it.copy(description = v.take(500)) } }, Modifier.fillMaxWidth(),
                label = { Text("Note (optional)") }, minLines = 2)
            Button(onClick = vm::save, enabled = !busy, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                Text(if (s.itemized) "Save ${s.filledItemCount} items · ${Money.format(s.itemsTotalMinor, s.currency)}" else if (s.isNew) "Save" else "Save changes")
            }
            Spacer(Modifier.height(24.dp))
        }
    }
    if (datePicker) {
        val st = rememberDatePickerState(initialSelectedDateMillis = s.date.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli())
        DatePickerDialog(onDismissRequest = { datePicker = false }, confirmButton = {
            TextButton(onClick = {
                st.selectedDateMillis?.let { ms -> vm.update { it.copy(date = Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate()) } }
                datePicker = false
            }) { Text("OK") }
        }, dismissButton = { TextButton(onClick = { datePicker = false }) { Text("Cancel") } }) { DatePicker(st) }
    }
    if (confirmDelete) {
        AlertDialog(onDismissRequest = { confirmDelete = false }, title = { Text("Delete transaction?") },
            text = { Text("It will be removed from totals on all household phones.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; vm.delete() }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } })
    }
    if (pasteOpen) {
        var text by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { pasteOpen = false },
            title = { Text("Paste a list") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("One item per line, price at the end:", style = MaterialTheme.typography.bodySmall)
                    Text("চাল ৫ কেজি ৪৫০\nতেল ২ লিটার - 380\nDim 150", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth().height(200.dp), placeholder = { Text("Item price") })
                }
            },
            confirmButton = { TextButton(onClick = { pasteOpen = false; vm.pasteItems(text) }, enabled = text.isNotBlank()) { Text("Add items") } },
            dismissButton = { TextButton(onClick = { pasteOpen = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ItemsEditor(s: EditState, vm: TransactionEditViewModel, onPaste: () -> Unit) {
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Total", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            MoneyText(s.itemsTotalMinor, s.currency, style = MaterialTheme.typography.headlineMedium)
        }
        Spacer(Modifier.height(8.dp))
        s.items.forEachIndexed { index, row ->
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${index + 1}.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(24.dp))
                OutlinedTextField(row.name, { vm.editItem(row.key, name = it) }, Modifier.weight(1f),
                    placeholder = { Text("Item") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next))
                Spacer(Modifier.width(6.dp))
                OutlinedTextField(row.amount, { vm.editItem(row.key, amount = it) }, Modifier.width(110.dp),
                    placeholder = { Text("Price") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next))
                IconButton(onClick = { vm.removeItem(row.key) }) { Icon(Icons.Filled.Close, contentDescription = "Remove item ${index + 1}") }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
            OutlinedButton(onClick = vm::addItem) { Icon(Icons.Filled.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Add item") }
            OutlinedButton(onClick = onPaste) { Icon(Icons.Filled.ContentPaste, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Paste list") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransactionDetailScreen(onBack: () -> Unit, onEdit: (String) -> Unit, vm: TransactionDetailViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val deleted by vm.deleted.collectAsStateWithLifecycle()
    val host = remember { SnackbarHostState() }
    CollectMessages(vm, host)
    LaunchedEffect(deleted) { if (deleted) onBack() }
    var confirmDelete by remember { mutableStateOf(false) }
    Scaffold(
        topBar = { TopAppBar(title = { Text("Transaction") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } }) },
        snackbarHost = { SnackbarHost(host) },
    ) { pad ->
        val (ctx, t, cat) = state ?: return@Scaffold
        if (t == null) { EmptyState(Icons.Filled.ReceiptLong, "Not found", "This transaction was deleted.", Modifier.padding(pad)); return@Scaffold }
        val canModify = ctx.canEditTransactions && (ctx.role != com.homebudget.domain.model.Role.MEMBER || t.createdBy == ctx.member.id)
        Column(Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CategoryBadge(cat?.iconKey, cat?.colorHex, 52.dp, cat?.name)
                    Column(Modifier.padding(start = 12.dp).weight(1f)) {
                        Text(t.title, style = MaterialTheme.typography.titleLarge)
                        Text(cat?.name ?: "—", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Spacer(Modifier.height(12.dp))
                val income = t.type == TransactionType.INCOME
                MoneyText(if (income) t.amountMinor else -t.amountMinor, t.currencyCode, showSign = true,
                    style = MaterialTheme.typography.displaySmall, color = if (income) incomeColor() else expenseColor())
            }
            if (t.items.isNotEmpty()) SectionCard(title = "${t.items.size} items") {
                t.items.forEachIndexed { i, item ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Text("${i + 1}. ${item.name}", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        MoneyText(item.amountMinor, t.currencyCode, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                androidx.compose.material3.HorizontalDivider(Modifier.padding(vertical = 6.dp))
                LabeledRow("Total") { MoneyText(t.amountMinor, t.currencyCode, style = MaterialTheme.typography.titleMedium) }
            }
            SectionCard {
                LabeledRow("Type") { Text(if (t.type == TransactionType.INCOME) "Income" else "Expense") }
                LabeledRow("Date") { Text(t.date.format(DayFmt)) }
                if (!t.description.isNullOrBlank()) LabeledRow("Note") { Text(t.description, Modifier.padding(start = 16.dp)) }
                LabeledRow("Last updated") { Text(formatTimestamp(t.updatedAt)) }
                LabeledRow("Revision") { Text(t.revision.toString()) }
            }
            if (canModify) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = { confirmDelete = true }, Modifier.weight(1f)) { Icon(Icons.Filled.Delete, null, Modifier.size(18.dp)); Text("  Delete") }
                    Button(onClick = { onEdit(t.id) }, Modifier.weight(1f)) { Icon(Icons.Filled.Edit, null, Modifier.size(18.dp)); Text("  Edit") }
                }
            }
        }
    }
    if (confirmDelete) {
        AlertDialog(onDismissRequest = { confirmDelete = false }, title = { Text("Delete transaction?") },
            text = { Text("It will be removed from totals on all household phones.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; vm.delete() }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } })
    }
}

