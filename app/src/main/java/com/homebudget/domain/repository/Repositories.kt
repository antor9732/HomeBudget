package com.homebudget.domain.repository

import com.homebudget.domain.model.Budget
import com.homebudget.domain.model.Category
import com.homebudget.domain.model.CategoryTotal
import com.homebudget.domain.model.Household
import com.homebudget.domain.model.Member
import com.homebudget.domain.model.MonthTotals
import com.homebudget.domain.model.OpeningBalance
import com.homebudget.domain.model.Role
import com.homebudget.domain.model.ShoppingFinishResult
import com.homebudget.domain.model.ShoppingList
import com.homebudget.domain.model.SyncStatus
import com.homebudget.domain.model.Transaction
import com.homebudget.domain.model.TransactionDraft
import com.homebudget.domain.model.TransactionType
import kotlinx.coroutines.flow.Flow
import java.time.YearMonth

class PermissionDeniedException(message: String) : Exception(message)
class ValidationException(message: String) : Exception(message)

/** Who is acting, in which household, with which role. */
data class HouseholdContext(
    val household: Household,
    val member: Member,
    /** True when Drive access was revoked: data stays readable but cannot be changed. */
    val readOnly: Boolean,
) {
    val householdId get() = household.id
    val role: Role get() = member.role
    val canEditTransactions get() = !readOnly && role.canEditTransactions
    val canManageSetup get() = !readOnly && role.canManageFinanceSetup
    val canManageMembers get() = !readOnly && role.canManageMembers
}

interface HouseholdRepository {
    /** Emits null until a household exists on this device. */
    val context: Flow<HouseholdContext?>
    suspend fun currentContext(): HouseholdContext?
    /** Clears a stale session that points to a household/member missing from the database. */
    suspend fun repairSession()
    suspend fun createLocalHousehold(name: String, ownerDisplayName: String, ownerEmail: String?, currencyCode: String): Household
    fun observeMembers(householdId: String): Flow<List<Member>>
    suspend fun recordInvitedMember(email: String, role: Role)
    suspend fun changeRole(memberId: String, role: Role)
    suspend fun markMemberRemoved(memberId: String)
    suspend fun renameHousehold(name: String)
}

interface FinanceRepository {
    // Transactions
    /** @param fixedId a deterministic id (e.g. a bazar expense); null for a new random id. */
    suspend fun addTransaction(draft: TransactionDraft, fixedId: String? = null): String
    suspend fun updateTransaction(id: String, draft: TransactionDraft)
    suspend fun deleteTransaction(id: String)
    fun observeTransaction(id: String): Flow<Transaction?>
    fun observeTransactions(householdId: String, month: YearMonth): Flow<List<Transaction>>
    fun observeRecent(householdId: String, limit: Int): Flow<List<Transaction>>

    // Categories
    fun observeCategories(householdId: String): Flow<List<Category>>
    suspend fun createCategory(name: String, type: TransactionType, iconKey: String, colorHex: String): String
    suspend fun updateCategory(id: String, name: String, iconKey: String, colorHex: String)
    suspend fun setCategoryActive(id: String, active: Boolean)

    // Budgets & opening balances
    fun observeBudget(householdId: String, month: YearMonth): Flow<Budget?>
    fun observeBudgetHistory(householdId: String): Flow<List<Budget>>
    suspend fun setBudget(month: YearMonth, amountMinor: Long)
    suspend fun getBudget(householdId: String, month: YearMonth): Budget?
    fun observeOpeningBalance(householdId: String, month: YearMonth): Flow<OpeningBalance?>
    suspend fun setOpeningBalance(month: YearMonth, amountMinor: Long?)

    // Aggregates
    fun observeMonthTotals(householdId: String, month: YearMonth): Flow<Pair<Long, Long>>
    suspend fun monthTotals(householdId: String, month: YearMonth): Pair<Long, Long>
    fun observeCategoryTotals(householdId: String, type: TransactionType, from: YearMonth, to: YearMonth): Flow<List<CategoryTotal>>
    fun observeMonthlySeries(householdId: String, from: YearMonth, to: YearMonth): Flow<List<MonthTotals>>
}

/** Shared bazar (shopping) lists: tick items with their price, then turn them into expenses per category. */
interface ShoppingRepository {
    fun observeLists(householdId: String): Flow<List<ShoppingList>>
    fun observeList(listId: String): Flow<ShoppingList?>
    suspend fun createList(title: String): String
    suspend fun renameList(listId: String, title: String)
    suspend fun deleteList(listId: String)
    suspend fun addItem(listId: String, name: String, quantity: String?, categoryId: String?): String
    suspend fun updateItem(itemId: String, name: String, quantity: String?, categoryId: String)
    /** Ticks an item as bought with its price (null keeps the price empty), or unticks it. */
    suspend fun setBought(itemId: String, bought: Boolean, priceMinor: Long?)
    suspend fun deleteItem(itemId: String)
    /**
     * Adds one itemized expense per category for the ticked items, marks the list done and, if
     * [carryOver], moves unticked items to a new list.
     */
    suspend fun finish(listId: String, carryOver: Boolean): ShoppingFinishResult
}

interface SyncRepository {
    fun observeStatus(householdId: String): Flow<SyncStatus>
    fun observeConflicts(householdId: String): Flow<List<ConflictItem>>
    suspend fun keepCurrent(conflictId: String)
    suspend fun applyOtherVersion(conflictId: String)
    fun requestSync()
}

data class ConflictItem(
    val id: String,
    val entityType: String,
    val entityId: String,
    val currentSummary: String,
    val otherSummary: String,
    val detectedAt: Long,
    val open: Boolean,
)
