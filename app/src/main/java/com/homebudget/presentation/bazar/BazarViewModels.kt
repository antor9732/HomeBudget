package com.homebudget.presentation.bazar

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.homebudget.core.common.Money
import com.homebudget.core.common.TimeProvider
import com.homebudget.domain.model.Category
import com.homebudget.domain.model.ItemListParser
import com.homebudget.domain.model.ShoppingList
import com.homebudget.domain.model.TransactionType
import com.homebudget.domain.repository.FinanceRepository
import com.homebudget.domain.repository.HouseholdContext
import com.homebudget.domain.repository.HouseholdRepository
import com.homebudget.domain.repository.ShoppingRepository
import com.homebudget.domain.repository.ValidationException
import com.homebudget.presentation.common.BaseViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject

/** Accepts "450", "৪৫০", "1,250.50", "৳ 380". */
fun parsePrice(input: String): Long? = Money.parseToMinor(ItemListParser.normalizeDigits(input).replace("৳", "").trim())

data class BazarHomeState(val context: HouseholdContext, val open: List<ShoppingList>, val done: List<ShoppingList>)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class BazarHomeViewModel @Inject constructor(
    households: HouseholdRepository,
    private val shopping: ShoppingRepository,
    private val time: TimeProvider,
) : BaseViewModel() {
    val state = households.context.filterNotNull().flatMapLatest { ctx ->
        shopping.observeLists(ctx.householdId).map { lists ->
            BazarHomeState(
                ctx,
                lists.filter { it.status == com.homebudget.domain.model.ShoppingStatus.OPEN }.sortedByDescending { it.updatedAt },
                lists.filter { it.status == com.homebudget.domain.model.ShoppingStatus.DONE }.sortedByDescending { it.completedAt ?: it.updatedAt },
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Emits the id of a list that was just created, so the screen can open it. */
    val created = MutableStateFlow<String?>(null)

    fun defaultTitle(): String = "Bazar " + time.today().format(DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH))

    fun create(title: String) = launchSafely { created.value = shopping.createList(title) }
    fun consumeCreated() { created.value = null }
}

data class BazarListState(
    val context: HouseholdContext,
    val list: ShoppingList?,
    val categories: List<Category>,
) {
    val editable get() = list != null && context.canEditTransactions && list.status == com.homebudget.domain.model.ShoppingStatus.OPEN
    fun category(id: String) = categories.firstOrNull { it.id == id }
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class BazarListViewModel @Inject constructor(
    savedState: SavedStateHandle,
    households: HouseholdRepository,
    finance: FinanceRepository,
    private val shopping: ShoppingRepository,
) : BaseViewModel() {
    val listId: String = checkNotNull(savedState["id"])
    val state = households.context.filterNotNull().flatMapLatest { ctx ->
        combine(shopping.observeList(listId), finance.observeCategories(ctx.householdId)) { list, cats ->
            BazarListState(ctx, list, cats.filter { it.type == TransactionType.EXPENSE })
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Category chosen for the next added item (sticky, like a store aisle). */
    val nextCategory = MutableStateFlow<String?>(null)
    val closed = MutableStateFlow(false)
    /** Item whose price is being entered (kept here so it survives recomposition). */
    val pricingId = MutableStateFlow<String?>(null)
    val finished = MutableStateFlow<String?>(null)

    /** Adds one item, or several when the text has one item per line. */
    fun add(name: String, quantity: String) = launchSafely(showBusy = false) {
        val lines = name.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) throw ValidationException("Write an item name")
        for (l in lines) shopping.addItem(listId, l, quantity.takeIf { lines.size == 1 }, nextCategory.value)
    }

    fun tick(itemId: String, price: String?) = launchSafely(showBusy = false) {
        val minor = price?.takeIf { it.isNotBlank() }?.let { parsePrice(it) ?: throw ValidationException("Enter a valid price") }
        shopping.setBought(itemId, true, minor)
    }

    fun untick(itemId: String) = launchSafely(showBusy = false) { shopping.setBought(itemId, false, null) }

    fun update(itemId: String, name: String, quantity: String, categoryId: String, bought: Boolean, price: String) = launchSafely(showBusy = false) {
        shopping.updateItem(itemId, name, quantity, categoryId)
        if (bought) {
            val minor = price.takeIf { it.isNotBlank() }?.let { parsePrice(it) ?: throw ValidationException("Enter a valid price") }
            shopping.setBought(itemId, true, minor)
        }
    }

    fun delete(itemId: String) = launchSafely(showBusy = false) { shopping.deleteItem(itemId) }
    fun rename(title: String) = launchSafely { shopping.renameList(listId, title) }
    fun deleteList() = launchSafely { shopping.deleteList(listId); closed.value = true }

    fun finish(carryOver: Boolean) = launchSafely {
        val r = shopping.finish(listId, carryOver)
        message(
            "Added ${r.expenses} expense${if (r.expenses == 1) "" else "s"} · ${Money.format(r.totalMinor)}" +
                if (r.carriedOver > 0) " · ${r.carriedOver} item${if (r.carriedOver == 1) "" else "s"} moved to a new list" else ""
        )
        finished.value = r.newListId ?: ""
    }
}
