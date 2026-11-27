package com.homebudget.data.mapper

import com.homebudget.core.database.BudgetEntity
import com.homebudget.core.database.CategoryEntity
import com.homebudget.core.database.HouseholdEntity
import com.homebudget.core.database.MemberEntity
import com.homebudget.core.database.OpeningBalanceEntity
import com.homebudget.core.database.TransactionEntity
import com.homebudget.core.database.TransactionItemEntity
import com.homebudget.core.database.ShoppingItemEntity
import com.homebudget.core.database.ShoppingListEntity
import com.homebudget.data.remote.ShoppingItemDto
import com.homebudget.data.remote.ShoppingListDto
import com.homebudget.domain.model.ShoppingItem
import com.homebudget.domain.model.ShoppingList
import com.homebudget.domain.model.ShoppingStatus
import com.homebudget.data.remote.TransactionItemDto
import com.homebudget.domain.model.TransactionItem
import com.homebudget.data.remote.BudgetDto
import com.homebudget.data.remote.CategoryDto
import com.homebudget.data.remote.HouseholdDto
import com.homebudget.data.remote.MemberDto
import com.homebudget.data.remote.TransactionDto
import com.homebudget.domain.model.Budget
import com.homebudget.domain.model.Category
import com.homebudget.domain.model.Household
import com.homebudget.domain.model.Member
import com.homebudget.domain.model.MemberStatus
import com.homebudget.domain.model.OpeningBalance
import com.homebudget.domain.model.Role
import com.homebudget.domain.model.Transaction
import com.homebudget.domain.model.TransactionType
import java.time.LocalDate
import java.time.YearMonth

// ---- Entity -> Domain
fun HouseholdEntity.toDomain() = Household(id, name, ownerMemberId, currencyCode, driveFolderId, createdAt)
fun MemberEntity.toDomain() = Member(id, householdId, email, displayName, role, status, joinedAt)
fun CategoryEntity.toDomain() = Category(id, householdId, name, type, iconKey, colorHex, isActive)
fun TransactionEntity.toDomain(items: List<TransactionItem> = emptyList()) = Transaction(
    id, householdId, categoryId, type, title, amountMinor, currencyCode, LocalDate.ofEpochDay(transactionDate),
    description, createdBy, deviceId, createdAt, updatedAt, revision, items,
)
fun BudgetEntity.toDomain() = Budget(householdId, YearMonth.of(year, month), amountMinor, revision)
fun OpeningBalanceEntity.toDomain() = OpeningBalance(householdId, YearMonth.of(year, month), amountMinor)

// ---- Entity <-> DTO (explicit mapping between DB and remote formats)
fun HouseholdEntity.toDto() = HouseholdDto(id, name, ownerMemberId, currencyCode, driveFolderId, createdAt, updatedAt, revision, isDeleted)
fun HouseholdDto.toEntity(opId: String) = HouseholdEntity(id, name, ownerMemberId, currencyCode, driveFolderId, createdAt, updatedAt, revision, opId, isDeleted)

fun MemberEntity.toDto() = MemberDto(id, householdId, email, googleSubject, displayName, role.name, status.name, joinedAt, removedAt, updatedAt, revision, isDeleted)
fun MemberDto.toEntity(opId: String) = MemberEntity(
    id, householdId, email, googleSubject, displayName, Role.valueOf(role), MemberStatus.valueOf(status),
    joinedAt, removedAt, updatedAt, revision, opId, isDeleted,
)

fun CategoryEntity.toDto() = CategoryDto(id, householdId, name, type.name, iconKey, colorHex, isActive, sortOrder, updatedAt, revision, isDeleted)
fun CategoryDto.toEntity(opId: String) = CategoryEntity(
    id, householdId, name, TransactionType.valueOf(type), iconKey, colorHex, isActive, sortOrder, updatedAt, revision, opId, isDeleted,
)

fun BudgetEntity.toDto() = BudgetDto(id, householdId, year, month, amountMinor, updatedAt, revision, isDeleted)
fun BudgetDto.toBudgetEntity(opId: String) = BudgetEntity(id, householdId, year, month, amountMinor, revision, updatedAt, opId, isDeleted)

fun OpeningBalanceEntity.toDto() = BudgetDto(id, householdId, year, month, amountMinor, updatedAt, revision, isDeleted, isSet)
fun BudgetDto.toOpeningEntity(opId: String) = OpeningBalanceEntity(id, householdId, year, month, amountMinor, revision, updatedAt, opId, isSet, isDeleted)

fun TransactionEntity.toDto(items: List<TransactionItemEntity> = emptyList()) = TransactionDto(
    id, householdId, categoryId, type.name, title, amountMinor, currencyCode, transactionDate, description,
    createdBy, deviceId, createdAt, updatedAt, revision, isDeleted,
    items.sortedBy { it.position }.map { TransactionItemDto(it.name, it.amountMinor) },
)

fun TransactionDto.itemEntities(): List<TransactionItemEntity> =
    items.mapIndexed { i, it -> TransactionItemEntity("$id:$i", id, i, it.name, it.amountMinor) }

fun TransactionItemEntity.toDomain() = TransactionItem(name, amountMinor)
fun TransactionDto.toEntity(opId: String) = TransactionEntity(
    id, householdId, categoryId, TransactionType.valueOf(type), title, amountMinor, currencyCode, transactionDate,
    description, createdBy, deviceId, createdAt, updatedAt, revision, opId, isDeleted,
)

// ---- Bazar lists
fun ShoppingListEntity.toDto() = ShoppingListDto(id, householdId, title, status.name, createdBy, createdAt, updatedAt, completedAt, revision, isDeleted)
fun ShoppingListDto.toEntity(opId: String) = ShoppingListEntity(id, householdId, title, ShoppingStatus.valueOf(status), createdBy, createdAt, updatedAt, completedAt, revision, opId, isDeleted)
fun ShoppingItemEntity.toDto() = ShoppingItemDto(id, householdId, listId, name, quantity, categoryId, position, isChecked, priceMinor, checkedBy, updatedAt, revision, isDeleted)
fun ShoppingItemDto.toEntity(opId: String) = ShoppingItemEntity(id, householdId, listId, name, quantity, categoryId, position, isChecked, priceMinor, checkedBy, updatedAt, revision, opId, isDeleted)
fun ShoppingItemEntity.toDomain() = ShoppingItem(id, listId, name, quantity, categoryId, position, isChecked, priceMinor, checkedBy)
fun ShoppingListEntity.toDomain(items: List<ShoppingItem>) = ShoppingList(id, householdId, title, status, createdBy, createdAt, updatedAt, completedAt, items)
