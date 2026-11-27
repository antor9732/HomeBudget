package com.homebudget.presentation.bazar

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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.homebudget.core.common.Money
import com.homebudget.domain.model.Category
import com.homebudget.domain.model.ShoppingItem
import com.homebudget.domain.model.ShoppingList
import com.homebudget.domain.model.ShoppingStatus
import com.homebudget.presentation.common.CategoryBadge
import com.homebudget.presentation.common.EmptyState
import com.homebudget.presentation.common.MoneyText
import com.homebudget.presentation.common.SectionCard
import com.homebudget.presentation.transactions.CollectMessages
import java.text.DateFormat
import java.util.Date

// ------------------------------------------------------------------------------------- home tab

@Composable
fun BazarHomeScreen(onOpen: (String) -> Unit, vm: BazarHomeViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val created by vm.created.collectAsStateWithLifecycle()
    val host = remember { SnackbarHostState() }
    CollectMessages(vm, host)
    LaunchedEffect(created) { created?.let { vm.consumeCreated(); onOpen(it) } }
    val s = state ?: return
    val cur = s.context.household.currencyCode
    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text("Bazar list", style = MaterialTheme.typography.headlineMedium)
                Text(
                    "Write what to buy, tick items with their price at the market. When you finish, the bazar is added to expenses by category.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (s.context.canEditTransactions) {
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { vm.create(vm.defaultTitle()) }, Modifier.fillMaxWidth().height(52.dp)) {
                        Icon(Icons.Filled.Add, contentDescription = null); Spacer(Modifier.width(8.dp)); Text("New bazar list")
                    }
                }
            }
            if (s.open.isEmpty() && s.done.isEmpty()) {
                item { EmptyState(Icons.Filled.ShoppingCart, "No bazar lists yet", "Make a list at home, everyone in the family sees it on their phone.") }
            }
            if (s.open.isNotEmpty()) {
                item { Text("To buy", style = MaterialTheme.typography.titleMedium) }
                items(s.open, key = { it.id }) { l -> ListCard(l, cur) { onOpen(l.id) } }
            }
            if (s.done.isNotEmpty()) {
                item { Text("Finished", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp)) }
                items(s.done.take(20), key = { it.id }) { l -> ListCard(l, cur) { onOpen(l.id) } }
            }
        }
        SnackbarHost(host, Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
private fun ListCard(l: ShoppingList, cur: String, onClick: () -> Unit) {
    val done = l.status == ShoppingStatus.DONE
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick), colors = CardDefaults.cardColors(
        containerColor = if (done) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f) else MaterialTheme.colorScheme.surfaceContainerLow,
    )) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(if (done) Icons.Filled.CheckCircle else Icons.Filled.ShoppingCart, contentDescription = null,
                    tint = if (done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(l.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val sub = if (done) "Finished ${l.completedAt?.let { DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(it)) } ?: ""}"
                    else "${l.boughtCount} of ${l.items.size} bought"
                    Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (l.boughtTotalMinor > 0) MoneyText(l.boughtTotalMinor, cur, style = MaterialTheme.typography.titleMedium)
            }
            if (!done && l.items.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                LinearProgressIndicator(progress = { l.boughtCount.toFloat() / l.items.size }, Modifier.fillMaxWidth())
            }
        }
    }
}

// ------------------------------------------------------------------------------------- one list

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BazarListScreen(onBack: () -> Unit, onOpenList: (String) -> Unit, vm: BazarListViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val closed by vm.closed.collectAsStateWithLifecycle()
    val finished by vm.finished.collectAsStateWithLifecycle()
    val nextCat by vm.nextCategory.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val host = remember { SnackbarHostState() }
    CollectMessages(vm, host)
    LaunchedEffect(closed) { if (closed) onBack() }
    LaunchedEffect(finished) { finished?.let { if (it.isNotEmpty()) onOpenList(it) } }

    var menu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    val pricingId by vm.pricingId.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<ShoppingItem?>(null) }
    var finishing by remember { mutableStateOf(false) }
    val s = state
    val list = s?.list
    val cur = s?.context?.household?.currencyCode ?: Money.DEFAULT_CURRENCY

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(list?.title ?: "Bazar list", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                actions = {
                    if (s?.context?.canEditTransactions == true && list != null) Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "More") }
                        DropdownMenu(menu, { menu = false }) {
                            if (s.editable) DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; renaming = true })
                            DropdownMenuItem(text = { Text("Delete list") }, onClick = { menu = false; deleting = true })
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(host) },
        bottomBar = {
            if (s != null && list != null && list.items.isNotEmpty()) Surface(tonalElevation = 3.dp) {
                Row(Modifier.fillMaxWidth().padding(16.dp, 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("${list.boughtCount} of ${list.items.size} bought", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        MoneyText(list.boughtTotalMinor, cur, style = MaterialTheme.typography.titleLarge)
                    }
                    if (s.editable) Button(onClick = { finishing = true }, enabled = list.boughtCount > 0 && !busy) { Text("Finish bazar") }
                }
            }
        },
    ) { pad ->
        if (s == null) return@Scaffold
        if (list == null) {
            EmptyState(Icons.Filled.ShoppingCart, "List not found", "It may have been deleted on another phone.", Modifier.padding(pad))
            return@Scaffold
        }
        val toBuy = list.items.filter { !it.isChecked }
        val bought = list.items.filter { it.isChecked }
        LazyColumn(
            Modifier.fillMaxSize().padding(pad).imePadding(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (list.status == ShoppingStatus.DONE) item {
                Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.medium) {
                    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                        Spacer(Modifier.width(10.dp))
                        Text("Bazar finished. Bought items were added to expenses.", color = MaterialTheme.colorScheme.onPrimaryContainer, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
            if (s.editable) item { AddItemBar(s.categories, nextCat ?: defaultCategoryId(s.categories), { vm.nextCategory.value = it }) { n, q -> vm.add(n, q) } }
            if (list.items.isEmpty()) item {
                EmptyState(Icons.Filled.ShoppingCart, "Empty list", "Add what you need: rice, oil, fish… One item per line works too.")
            }
            if (toBuy.isNotEmpty()) {
                item { SectionLabel((if (list.status == ShoppingStatus.DONE) "Not bought · " else "To buy · ") + toBuy.size) }
                items(toBuy, key = { it.id }) { item ->
                    ItemRow(item, s.category(item.categoryId), cur, s.editable,
                        onCheck = { vm.pricingId.value = item.id }, onClick = { if (s.editable) editing = item })
                    if (pricingId == item.id) PriceRow(item, onCancel = { vm.pricingId.value = null }) { price -> vm.tick(item.id, price); vm.pricingId.value = null }
                }
            }
            if (bought.isNotEmpty()) {
                item { SectionLabel("Bought · ${bought.size}") }
                items(bought, key = { it.id }) { item ->
                    ItemRow(item, s.category(item.categoryId), cur, s.editable,
                        onCheck = { vm.untick(item.id) }, onClick = { if (s.editable) editing = item })
                }
            }
        }
    }

    editing?.let { item ->
        EditItemDialog(item, s?.categories.orEmpty(), onDismiss = { editing = null },
            onDelete = { vm.delete(item.id); editing = null }) { n, q, c, p -> vm.update(item.id, n, q, c, item.isChecked, p); editing = null }
    }
    if (renaming && list != null) TitleDialog("Rename list", list.title, "Save", { renaming = false }) { vm.rename(it); renaming = false }
    if (deleting) AlertDialog(
        onDismissRequest = { deleting = false },
        title = { Text("Delete this list?") },
        text = { Text("It is removed from every phone in the household. Expenses already added stay.") },
        confirmButton = { TextButton(onClick = { deleting = false; vm.deleteList() }) { Text("Delete") } },
        dismissButton = { TextButton(onClick = { deleting = false }) { Text("Cancel") } },
    )
    if (finishing && s != null && list != null) FinishDialog(list, s.categories, cur, onDismiss = { finishing = false }) { carry ->
        finishing = false; vm.finish(carry)
    }
}

private fun defaultCategoryId(cats: List<Category>): String? =
    (cats.firstOrNull { it.iconKey == "grocery" && it.isActive } ?: cats.firstOrNull { it.isActive })?.id

@Composable
private fun SectionLabel(text: String) =
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))

@Composable
private fun AddItemBar(categories: List<Category>, categoryId: String?, onCategory: (String) -> Unit, onAdd: (String, String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var qty by remember { mutableStateOf("") }
    var catMenu by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val submit = { if (name.isNotBlank()) { onAdd(name, qty); name = ""; qty = ""; focus.requestFocus() } }
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = name, onValueChange = { name = it.take(600) }, label = { Text("Item") }, placeholder = { Text("e.g. Rice") },
                modifier = Modifier.weight(1f).focusRequester(focus), singleLine = false, maxLines = 4,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            )
            Spacer(Modifier.width(8.dp))
            OutlinedTextField(
                value = qty, onValueChange = { qty = it.take(30) }, label = { Text("Qty") }, placeholder = { Text("5 kg") },
                modifier = Modifier.width(96.dp), singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { submit() }),
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                val c = categories.firstOrNull { it.id == categoryId }
                AssistChip(
                    onClick = { catMenu = true },
                    label = { Text(c?.name ?: "Category") },
                    leadingIcon = { CategoryBadge(c?.iconKey, c?.colorHex, size = 22.dp) },
                    trailingIcon = { Icon(Icons.Filled.ArrowDropDown, contentDescription = null) },
                )
                DropdownMenu(catMenu, { catMenu = false }) {
                    categories.filter { it.isActive }.forEach { cat ->
                        DropdownMenuItem(text = { Text(cat.name) }, leadingIcon = { CategoryBadge(cat.iconKey, cat.colorHex, size = 24.dp) },
                            onClick = { onCategory(cat.id); catMenu = false })
                    }
                }
            }
            Button(onClick = submit, enabled = name.isNotBlank()) { Icon(Icons.Filled.Add, contentDescription = null); Spacer(Modifier.width(4.dp)); Text("Add") }
        }
    }
}

@Composable
private fun ItemRow(item: ShoppingItem, category: Category?, cur: String, editable: Boolean, onCheck: () -> Unit, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = editable, onClick = onClick).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = item.isChecked, onCheckedChange = { onCheck() }, enabled = editable)
        Column(Modifier.weight(1f)) {
            Text(
                item.name + (item.quantity?.let { "  ·  $it" } ?: ""),
                style = MaterialTheme.typography.bodyLarge,
                textDecoration = if (item.isChecked) TextDecoration.LineThrough else null,
                color = if (item.isChecked) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            Text(category?.name ?: "Other", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (item.isChecked) {
            if (item.priceMinor != null) MoneyText(item.priceMinor, cur, style = MaterialTheme.typography.titleSmall)
            else Text("Add price", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium)
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
}

/** Shown under an item when it is ticked: type the price and confirm, right there in the list. */
@Composable
private fun PriceRow(item: ShoppingItem, onCancel: () -> Unit, onSave: (String) -> Unit) {
    var price by remember { mutableStateOf(item.priceMinor?.let { Money.toInputString(it) } ?: "") }
    val focus = remember { FocusRequester() }
    val valid = parsePrice(price)?.let { it > 0 } == true
    Surface(color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f), shape = MaterialTheme.shapes.medium) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Text("How much did it cost?", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = price, onValueChange = { price = it.take(16) }, label = { Text("Price (${Money.TAKA_SYMBOL})") }, singleLine = true,
                    modifier = Modifier.weight(1f).focusRequester(focus),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (valid) onSave(price) }),
                )
                Spacer(Modifier.width(8.dp))
                Button(onClick = { onSave(price) }, enabled = valid) { Text("Bought") }
            }
            TextButton(onClick = onCancel) { Text("Cancel") }
        }
    }
}

@Composable
private fun EditItemDialog(
    item: ShoppingItem, categories: List<Category>, onDismiss: () -> Unit, onDelete: () -> Unit,
    onSave: (String, String, String, String) -> Unit,
) {
    var name by remember { mutableStateOf(item.name) }
    var qty by remember { mutableStateOf(item.quantity ?: "") }
    var cat by remember { mutableStateOf(item.categoryId) }
    var price by remember { mutableStateOf(item.priceMinor?.let { Money.toInputString(it) } ?: "") }
    var catMenu by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit item") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it.take(60) }, label = { Text("Item") }, singleLine = true)
                OutlinedTextField(qty, { qty = it.take(30) }, label = { Text("Quantity") }, singleLine = true)
                if (item.isChecked) OutlinedTextField(price, { price = it.take(16) }, label = { Text("Price (${Money.TAKA_SYMBOL})") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                Box {
                    val c = categories.firstOrNull { it.id == cat }
                    AssistChip(onClick = { catMenu = true }, label = { Text(c?.name ?: "Category") },
                        leadingIcon = { CategoryBadge(c?.iconKey, c?.colorHex, size = 22.dp) },
                        trailingIcon = { Icon(Icons.Filled.ArrowDropDown, contentDescription = null) })
                    DropdownMenu(catMenu, { catMenu = false }) {
                        categories.filter { it.isActive }.forEach { x ->
                            DropdownMenuItem(text = { Text(x.name) }, onClick = { cat = x.id; catMenu = false })
                        }
                    }
                }
                TextButton(onClick = onDelete) { Icon(Icons.Filled.Delete, contentDescription = null); Spacer(Modifier.width(6.dp)); Text("Remove from list") }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(name, qty, cat, price) }, enabled = name.isNotBlank()) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun FinishDialog(list: ShoppingList, categories: List<Category>, cur: String, onDismiss: () -> Unit, onConfirm: (Boolean) -> Unit) {
    val bought = list.items.filter { it.isChecked }
    val missing = bought.count { (it.priceMinor ?: 0) <= 0 }
    val left = list.items.size - bought.size
    var carry by remember { mutableStateOf(true) }
    val byCat = bought.groupBy { it.categoryId }.map { (id, its) -> (categories.firstOrNull { it.id == id }) to its.sumOf { it.priceMinor ?: 0 } }
        .sortedByDescending { it.second }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Finish bazar?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (missing > 0) {
                    Text("$missing ticked item${if (missing == 1) " has" else "s have"} no price yet. Add the price first.", color = MaterialTheme.colorScheme.error)
                } else {
                    Text("These expenses will be added for today:", style = MaterialTheme.typography.bodyMedium)
                    byCat.forEach { (c, sum) ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CategoryBadge(c?.iconKey, c?.colorHex, size = 24.dp)
                            Text("  ${c?.name ?: "Other"}", Modifier.weight(1f))
                            MoneyText(sum, cur, style = MaterialTheme.typography.titleSmall)
                        }
                    }
                    HorizontalDivider()
                    Row {
                        Text("Total", Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                        MoneyText(bought.sumOf { it.priceMinor ?: 0 }, cur, style = MaterialTheme.typography.titleMedium)
                    }
                    if (left > 0) Row(Modifier.clickable { carry = !carry }, verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(carry, { carry = it })
                        Text("Move $left item${if (left == 1) "" else "s"} not bought to a new list", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(carry) }, enabled = missing == 0) { Text("Add expenses") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun TitleDialog(title: String, initial: String, confirm: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(text, { text = it.take(80) }, label = { Text("Name") }, singleLine = true) },
        confirmButton = { TextButton(onClick = { onSave(text) }, enabled = text.isNotBlank()) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
