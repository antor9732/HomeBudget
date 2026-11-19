package com.homebudget.presentation.transactions

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.homebudget.core.common.Money
import com.homebudget.core.common.TimeProvider
import com.homebudget.domain.model.Category
import com.homebudget.domain.model.ItemListParser
import com.homebudget.domain.model.TransactionItem
import com.homebudget.domain.model.Transaction
import com.homebudget.domain.model.TransactionDraft
import com.homebudget.domain.model.TransactionType
import com.homebudget.domain.repository.FinanceRepository
import com.homebudget.domain.repository.HouseholdContext
import com.homebudget.domain.repository.HouseholdRepository
import com.homebudget.presentation.common.BaseViewModel
import com.homebudget.presentation.common.MonthStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth
import javax.inject.Inject

enum class SortOrder(val label: String) { NEWEST("Newest"), OLDEST("Oldest"), LARGEST("Largest"), SMALLEST("Smallest") }

data class TxFilter(val query: String = "", val type: TransactionType? = null, val categoryId: String? = null, val sort: SortOrder = SortOrder.NEWEST)

data class TxListState(
    val context: HouseholdContext,
    val month: YearMonth,
    val items: List<Transaction>,
    val categories: Map<String, Category>,
    val filter: TxFilter,
    val incomeMinor: Long,
    val expenseMinor: Long,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class TransactionsViewModel @Inject constructor(
    households: HouseholdRepository,
    finance: FinanceRepository,
    val months: MonthStore,
) : BaseViewModel() {
    private val filter = MutableStateFlow(TxFilter())

    val state: StateFlow<TxListState?> = combine(households.context.filterNotNull(), months.month) { c, m -> c to m }
        .flatMapLatest { (ctx, month) ->
            combine(finance.observeTransactions(ctx.householdId, month), finance.observeCategories(ctx.householdId), filter) { txs, cats, f ->
                val byId = cats.associateBy { it.id }
                val q = f.query.trim().lowercase()
                val filtered = txs.filter { t ->
                    (f.type == null || t.type == f.type) &&
                        (f.categoryId == null || t.categoryId == f.categoryId) &&
                        (q.isEmpty() || t.title.lowercase().contains(q) || (t.description?.lowercase()?.contains(q) == true) ||
                            t.items.any { it.name.lowercase().contains(q) } ||
                            (byId[t.categoryId]?.name?.lowercase()?.contains(q) == true))
                }.let { l ->
                    when (f.sort) {
                        SortOrder.NEWEST -> l.sortedWith(compareByDescending<Transaction> { it.date }.thenByDescending { it.createdAt })
                        SortOrder.OLDEST -> l.sortedWith(compareBy<Transaction> { it.date }.thenBy { it.createdAt })
                        SortOrder.LARGEST -> l.sortedByDescending { it.amountMinor }
                        SortOrder.SMALLEST -> l.sortedBy { it.amountMinor }
                    }
                }
                TxListState(ctx, month, filtered, byId, f,
                    filtered.filter { it.type == TransactionType.INCOME }.sumOf { it.amountMinor },
                    filtered.filter { it.type == TransactionType.EXPENSE }.sumOf { it.amountMinor })
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun setQuery(q: String) { filter.value = filter.value.copy(query = q) }
    fun setType(t: TransactionType?) { filter.value = filter.value.copy(type = t, categoryId = null) }
    fun setCategory(id: String?) { filter.value = filter.value.copy(categoryId = id) }
    fun setSort(s: SortOrder) { filter.value = filter.value.copy(sort = s) }
}

/** One editable line of an itemized transaction. */
data class ItemRow(val key: Long, val name: String = "", val amount: String = "")

data class EditState(
    val loaded: Boolean = false,
    val isNew: Boolean = true,
    val type: TransactionType = TransactionType.EXPENSE,
    val title: String = "",
    val amount: String = "",
    val categoryId: String? = null,
    val date: LocalDate = LocalDate.now(),
    val description: String = "",
    val categories: List<Category> = emptyList(),
    val currency: String = "BDT",
    val canDelete: Boolean = false,
    val saved: Boolean = false,
    val itemized: Boolean = false,
    val items: List<ItemRow> = emptyList(),
) {
    /** Live total of valid item prices (minor units). */
    val itemsTotalMinor: Long get() = items.sumOf { Money.parseToMinor(it.amount) ?: 0L }
    val filledItemCount: Int get() = items.count { it.name.isNotBlank() || it.amount.isNotBlank() }
}

@HiltViewModel
class TransactionEditViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val finance: FinanceRepository,
    private val households: HouseholdRepository,
    time: TimeProvider,
    months: MonthStore,
) : BaseViewModel() {
    private val id: String? = savedState.get<String>("id")?.takeIf { it.isNotBlank() }
    private val initialType = savedState.get<String>("type")?.let { runCatching { TransactionType.valueOf(it) }.getOrNull() } ?: TransactionType.EXPENSE
    private var nextKey = 1L
    val state = MutableStateFlow(EditState())

    init {
        viewModelScope.launch {
            val ctx = households.context.filterNotNull().first()
            val cats = finance.observeCategories(ctx.householdId).first()
            val selectedMonth = months.month.value
            val defaultDate = if (YearMonth.from(time.today()) == selectedMonth) time.today() else selectedMonth.atDay(1)
            val existing = id?.let { finance.observeTransaction(it).first() }
            state.value = if (existing != null) EditState(
                loaded = true, isNew = false, type = existing.type, title = existing.title,
                amount = Money.toInputString(existing.amountMinor), categoryId = existing.categoryId, date = existing.date,
                description = existing.description.orEmpty(), categories = cats, currency = ctx.household.currencyCode, canDelete = ctx.canEditTransactions,
                itemized = existing.items.isNotEmpty(),
                items = existing.items.map { ItemRow(nextKey++, it.name, Money.toInputString(it.amountMinor)) },
            ) else EditState(loaded = true, type = initialType, date = defaultDate, categories = cats, currency = ctx.household.currencyCode)
        }
    }

    fun update(transform: (EditState) -> EditState) { state.value = transform(state.value) }

    fun setItemized(on: Boolean) = update { s ->
        if (on) {
            // Seed with the current single amount so nothing typed is lost, plus an empty row.
            val seed = if (s.items.isEmpty() && Money.parseToMinor(s.amount) != null)
                listOf(ItemRow(nextKey++, s.title.ifBlank { "Item 1" }, s.amount)) else s.items
            s.copy(itemized = true, items = seed + ItemRow(nextKey++))
        } else {
            s.copy(itemized = false, amount = if (s.itemsTotalMinor > 0) Money.toInputString(s.itemsTotalMinor) else s.amount)
        }
    }

    fun addItem() = update { it.copy(items = it.items + ItemRow(nextKey++)) }
    fun removeItem(key: Long) = update { it.copy(items = it.items.filterNot { r -> r.key == key }) }
    fun editItem(key: Long, name: String? = null, amount: String? = null) = update { s ->
        s.copy(items = s.items.map { r ->
            if (r.key != key) r else r.copy(
                name = name?.take(ItemListParser.MAX_NAME) ?: r.name,
                amount = amount?.filter { c -> c.isDigit() || c == '.' || c == ',' } ?: r.amount,
            )
        })
    }

    /** Appends items parsed from a pasted list ("Rice 450" per line). */
    fun pasteItems(text: String) {
        val parsed = ItemListParser.parse(text)
        update { s ->
            val kept = s.items.filter { it.name.isNotBlank() || it.amount.isNotBlank() }
            s.copy(itemized = true, items = kept + parsed.items.map { ItemRow(nextKey++, it.name, Money.toInputString(it.amountMinor)) } + ItemRow(nextKey++))
        }
        message(
            "Added ${parsed.items.size} items" +
                if (parsed.invalidLines.isEmpty()) "" else " · ${parsed.invalidLines.size} lines skipped (no price): " + parsed.invalidLines.take(2).joinToString()
        )
    }

    fun save() = launchSafely {
        val s = state.value
        val cat = s.categoryId ?: return@launchSafely message("Choose a category")
        val items: List<TransactionItem>
        val amount: Long
        if (s.itemized) {
            val rows = s.items.filter { it.name.isNotBlank() || it.amount.isNotBlank() }
            if (rows.isEmpty()) return@launchSafely message("Add at least one item")
            items = rows.mapIndexed { i, r ->
                val a = Money.parseToMinor(r.amount) ?: return@launchSafely message("Item ${i + 1}: enter a valid price")
                if (r.name.isBlank()) return@launchSafely message("Item ${i + 1}: enter a name")
                TransactionItem(r.name.trim(), a)
            }
            amount = items.sumOf { it.amountMinor }
        } else {
            items = emptyList()
            amount = Money.parseToMinor(s.amount) ?: return@launchSafely message("Enter a valid amount (up to 2 decimals)")
        }
        val title = s.title.ifBlank { s.categories.firstOrNull { it.id == cat }?.name.orEmpty() }
        val draft = TransactionDraft(s.type, title, amount, cat, s.date, s.description, items)
        if (id == null) finance.addTransaction(draft) else finance.updateTransaction(id, draft)
        state.value = s.copy(saved = true)
    }

    fun delete() = launchSafely {
        if (id != null) finance.deleteTransaction(id)
        state.value = state.value.copy(saved = true)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class TransactionDetailViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val finance: FinanceRepository,
    households: HouseholdRepository,
) : BaseViewModel() {
    private val id: String = checkNotNull(savedState["id"])
    val deleted = MutableStateFlow(false)
    val state = households.context.filterNotNull().flatMapLatest { ctx ->
        combine(finance.observeTransaction(id), finance.observeCategories(ctx.householdId)) { t, cats ->
            Triple(ctx, t, t?.let { tx -> cats.firstOrNull { it.id == tx.categoryId } })
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun delete() = launchSafely {
        finance.deleteTransaction(id)
        deleted.value = true
    }
}
