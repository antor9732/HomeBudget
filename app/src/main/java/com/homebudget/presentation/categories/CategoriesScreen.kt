package com.homebudget.presentation.categories

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.homebudget.domain.model.Category
import com.homebudget.domain.model.TransactionType
import com.homebudget.domain.repository.FinanceRepository
import com.homebudget.domain.repository.HouseholdRepository
import com.homebudget.presentation.common.BaseViewModel
import com.homebudget.presentation.common.CategoryBadge
import com.homebudget.presentation.common.CategoryIcons
import com.homebudget.presentation.common.parseColor
import com.homebudget.presentation.transactions.CollectMessages
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class CategoriesViewModel @Inject constructor(
    households: HouseholdRepository,
    private val finance: FinanceRepository,
) : BaseViewModel() {
    val state = households.context.filterNotNull().flatMapLatest { ctx ->
        finance.observeCategories(ctx.householdId).map { ctx to it }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun save(existing: Category?, name: String, type: TransactionType, icon: String, color: String) = launchSafely {
        if (existing == null) finance.createCategory(name, type, icon, color) else finance.updateCategory(existing.id, name, icon, color)
        message("Category saved")
    }

    fun toggleArchive(c: Category) = launchSafely {
        finance.setCategoryActive(c.id, !c.isActive)
        message(if (c.isActive) "${c.name} archived. Past transactions keep it." else "${c.name} restored")
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoriesScreen(vm: CategoriesViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val (ctx, cats) = state ?: return
    val host = remember { SnackbarHostState() }
    CollectMessages(vm, host)
    var tab by remember { mutableStateOf(0) }
    var editing by remember { mutableStateOf<Category?>(null) }
    var creating by remember { mutableStateOf(false) }
    val type = if (tab == 0) TransactionType.EXPENSE else TransactionType.INCOME
    Box(Modifier.fillMaxSize()) {
        Column {
            Text("Categories", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(16.dp, 8.dp))
            PrimaryTabRow(selectedTabIndex = tab) {
                Tab(tab == 0, { tab = 0 }, text = { Text("Expense") })
                Tab(tab == 1, { tab = 1 }, text = { Text("Income") })
            }
            LazyColumn(contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp)) {
                items(cats.filter { it.type == type }, key = { it.id }) { c ->
                    Row(
                        Modifier.fillMaxWidth().clickable(enabled = ctx.canManageSetup) { editing = c }.padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CategoryBadge(c.iconKey, c.colorHex, label = null)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(c.name, style = MaterialTheme.typography.bodyLarge, color = if (c.isActive) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline)
                            if (!c.isActive) Text("Archived", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                        }
                        if (ctx.canManageSetup) IconButton(onClick = { vm.toggleArchive(c) }) {
                            Icon(if (c.isActive) Icons.Filled.Archive else Icons.Filled.Unarchive, contentDescription = if (c.isActive) "Archive ${c.name}" else "Restore ${c.name}")
                        }
                    }
                }
            }
        }
        if (ctx.canManageSetup) {
            SmallFloatingActionButton(onClick = { creating = true }, Modifier.align(Alignment.BottomEnd).padding(16.dp)) {
                Icon(Icons.Filled.Add, contentDescription = "New category")
            }
        }
        SnackbarHost(host, Modifier.align(Alignment.BottomCenter))
    }
    if (creating || editing != null) {
        CategoryDialog(editing, type, onDismiss = { creating = false; editing = null }) { name, icon, color ->
            vm.save(editing, name, editing?.type ?: type, icon, color); creating = false; editing = null
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CategoryDialog(existing: Category?, type: TransactionType, onDismiss: () -> Unit, onSave: (String, String, String) -> Unit) {
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var icon by remember { mutableStateOf(existing?.iconKey ?: "more") }
    var color by remember { mutableStateOf(existing?.colorHex ?: CategoryIcons.colors.first()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "New ${type.name.lowercase()} category" else "Edit category") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(name, { name = it.take(40) }, label = { Text("Name") }, singleLine = true)
                Text("Icon", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    CategoryIcons.all.keys.forEach { k ->
                        Box(Modifier.clip(CircleShape).border(if (k == icon) 2.dp else 0.dp, MaterialTheme.colorScheme.primary, CircleShape)
                            .clickable { icon = k }.semantics { contentDescription = "Icon $k" }) { CategoryBadge(k, color, 40.dp) }
                    }
                }
                Text("Color", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    CategoryIcons.colors.forEach { hex ->
                        Box(Modifier.size(32.dp).clip(CircleShape).background(parseColor(hex))
                            .border(if (hex == color) 3.dp else 0.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                            .clickable { color = hex }.semantics { contentDescription = "Color $hex" })
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(name, icon, color) }, enabled = name.isNotBlank()) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
