package com.homebudget.domain.model

import java.time.LocalDate
import java.time.YearMonth

enum class TransactionType { INCOME, EXPENSE }

enum class Role {
    OWNER, ADMIN, MEMBER, VIEWER;

    val canEditTransactions get() = this != VIEWER
    val canManageFinanceSetup get() = this == OWNER || this == ADMIN // categories, budgets, opening balances
    val canManageMembers get() = this == OWNER || this == ADMIN
    val canManageHousehold get() = this == OWNER
}

enum class MemberStatus { INVITED, ACTIVE, REMOVED }

data class Household(
    val id: String,
    val name: String,
    val ownerMemberId: String,
    val currencyCode: String,
    val driveFolderId: String?,
    val createdAt: Long,
)

data class Member(
    val id: String,
    val householdId: String,
    val email: String?,
    val displayName: String,
    val role: Role,
    val status: MemberStatus,
    val joinedAt: Long,
)

data class Category(
    val id: String,
    val householdId: String,
    val name: String,
    val type: TransactionType,
    val iconKey: String,
    val colorHex: String,
    val isActive: Boolean,
)

data class Transaction(
    val id: String,
    val householdId: String,
    val categoryId: String,
    val type: TransactionType,
    val title: String,
    val amountMinor: Long,
    val currencyCode: String,
    val date: LocalDate,
    val description: String?,
    val createdBy: String,
    val deviceId: String,
    val createdAt: Long,
    val updatedAt: Long,
    val revision: Long,
    val items: List<TransactionItem> = emptyList(),
)

/** One line of an itemized transaction, e.g. "Rice 5kg – ৳450". */
data class TransactionItem(val name: String, val amountMinor: Long)

data class TransactionWithCategory(val transaction: Transaction, val category: Category?)

data class Budget(val householdId: String, val month: YearMonth, val amountMinor: Long, val revision: Long)

data class OpeningBalance(val householdId: String, val month: YearMonth, val amountMinor: Long)

/** Input for creating/editing a transaction. */
data class TransactionDraft(
    val type: TransactionType,
    val title: String,
    val amountMinor: Long,
    val categoryId: String,
    val date: LocalDate,
    val description: String?,
    /** When non-empty the amount must equal the sum of item amounts. */
    val items: List<TransactionItem> = emptyList(),
)

data class CategoryTotal(val category: Category?, val totalMinor: Long, val percentBasisPoints: Int)

data class MonthTotals(val month: YearMonth, val incomeMinor: Long, val expenseMinor: Long)

enum class SyncHealth { LOCAL_ONLY, IDLE, SYNCING, ERROR, AUTH_REQUIRED, ACCESS_REVOKED }

data class SyncStatus(
    val health: SyncHealth,
    val lastSuccessfulSyncAt: Long?,
    val pendingCount: Int,
    val lastError: String?,
    val openConflicts: Int,
)

enum class ShoppingStatus { OPEN, DONE }

data class ShoppingItem(
    val id: String,
    val listId: String,
    val name: String,
    val quantity: String?,
    val categoryId: String,
    val position: Int,
    val isChecked: Boolean,
    val priceMinor: Long?,
    val checkedBy: String?,
)

data class ShoppingList(
    val id: String,
    val householdId: String,
    val title: String,
    val status: ShoppingStatus,
    val createdBy: String,
    val createdAt: Long,
    val updatedAt: Long,
    val completedAt: Long?,
    val items: List<ShoppingItem> = emptyList(),
) {
    val boughtCount get() = items.count { it.isChecked }
    val boughtTotalMinor get() = items.filter { it.isChecked }.sumOf { it.priceMinor ?: 0L }
}

/** What "Finish bazar" did. */
data class ShoppingFinishResult(val expenses: Int, val totalMinor: Long, val carriedOver: Int, val newListId: String?)
