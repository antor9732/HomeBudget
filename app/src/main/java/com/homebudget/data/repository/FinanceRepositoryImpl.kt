package com.homebudget.data.repository

import com.homebudget.core.common.Ids
import com.homebudget.core.common.LocalIdentity
import com.homebudget.core.common.MonthKey
import com.homebudget.core.common.TimeProvider
import com.homebudget.core.database.AppDatabase
import com.homebudget.core.database.BudgetEntity
import com.homebudget.core.database.CategoryEntity
import com.homebudget.core.database.OpeningBalanceEntity
import com.homebudget.core.database.TransactionEntity
import com.homebudget.core.database.TransactionItemEntity
import com.homebudget.domain.model.ItemListParser
import com.homebudget.domain.model.TransactionItem
import com.homebudget.core.sync.LocalChangeWriter
import com.homebudget.data.mapper.toDomain
import com.homebudget.data.mapper.toDto
import com.homebudget.data.remote.BudgetDto
import com.homebudget.data.remote.CategoryDto
import com.homebudget.data.remote.EntityType
import com.homebudget.data.remote.OperationType
import com.homebudget.data.remote.SyncJson
import com.homebudget.data.remote.TransactionDto
import com.homebudget.domain.model.Budget
import com.homebudget.domain.model.BudgetCalculator
import com.homebudget.domain.model.Category
import com.homebudget.domain.model.CategoryTotal
import com.homebudget.domain.model.MonthTotals
import com.homebudget.domain.model.OpeningBalance
import com.homebudget.domain.model.Role
import com.homebudget.domain.model.Transaction
import com.homebudget.domain.model.TransactionDraft
import com.homebudget.domain.model.TransactionType
import com.homebudget.domain.repository.FinanceRepository
import com.homebudget.domain.repository.HouseholdContext
import com.homebudget.domain.repository.HouseholdRepository
import com.homebudget.domain.repository.PermissionDeniedException
import com.homebudget.domain.repository.ValidationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import java.time.YearMonth
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FinanceRepositoryImpl @Inject constructor(
    private val db: AppDatabase,
    private val households: HouseholdRepository,
    private val writer: LocalChangeWriter,
    private val identity: LocalIdentity,
    private val time: TimeProvider,
) : FinanceRepository {

    private suspend fun ctx(): HouseholdContext = households.currentContext() ?: throw IllegalStateException("No household")
    private fun now() = time.now().toEpochMilli()

    // ------------------------------------------------------------------------------ transactions

    private suspend fun validate(ctx: HouseholdContext, draft: TransactionDraft) {
        if (draft.title.isBlank()) throw ValidationException("Title is required")
        if (draft.title.length > 120) throw ValidationException("Title is too long")
        if (draft.amountMinor <= 0) throw ValidationException("Amount must be greater than zero")
        if (draft.amountMinor > 100_000_000_000L * 100) throw ValidationException("Amount is too large")
        val cat = db.categories().get(draft.categoryId) ?: throw ValidationException("Choose a category")
        if (cat.householdId != ctx.householdId || cat.isDeleted) throw ValidationException("Invalid category")
        if (cat.type != draft.type) throw ValidationException("Category type does not match transaction type")
        if (draft.items.isNotEmpty()) {
            if (draft.items.size > ItemListParser.MAX_ITEMS) throw ValidationException("Too many items (max ${ItemListParser.MAX_ITEMS})")
            if (draft.items.any { it.name.isBlank() || it.name.length > ItemListParser.MAX_NAME }) throw ValidationException("Every item needs a name")
            if (draft.items.any { it.amountMinor <= 0 }) throw ValidationException("Every item needs a price above zero")
            if (draft.items.sumOf { it.amountMinor } != draft.amountMinor) throw ValidationException("Total must equal the sum of the items")
        }
    }

    private fun itemEntities(txId: String, items: List<TransactionItem>) =
        items.mapIndexed { i, it -> TransactionItemEntity("$txId:$i", txId, i, it.name.trim(), it.amountMinor) }

    private suspend fun saveWithItems(e: TransactionEntity, items: List<TransactionItemEntity>) {
        db.transactions().upsert(e)
        db.transactionItems().deleteFor(e.id)
        if (items.isNotEmpty()) db.transactionItems().insertAll(items)
    }

    override suspend fun addTransaction(draft: TransactionDraft, fixedId: String?): String {
        val ctx = ctx()
        if (!ctx.canEditTransactions) throw PermissionDeniedException("Your role cannot add transactions")
        validate(ctx, draft)
        val cat = db.categories().get(draft.categoryId)!!
        if (!cat.isActive) throw ValidationException("Category is archived")
        val now = now()
        val opId = Ids.random()
        val e = TransactionEntity(
            id = fixedId ?: Ids.random(), householdId = ctx.householdId, categoryId = draft.categoryId, type = draft.type,
            title = draft.title.trim(), amountMinor = draft.amountMinor, currencyCode = ctx.household.currencyCode,
            transactionDate = draft.date.toEpochDay(), description = draft.description?.trim()?.ifBlank { null },
            createdBy = ctx.member.id, deviceId = identity.deviceId(), createdAt = now, updatedAt = now,
            revision = 1, lastOperationId = opId,
        )
        val items = itemEntities(e.id, draft.items)
        writer.record(ctx.householdId, EntityType.TRANSACTION, e.id, OperationType.CREATE, opId, null, ctx.member.id,
            SyncJson.encodeToString(TransactionDto.serializer(), e.toDto(items))) { saveWithItems(e, items) }
        return e.id
    }

    private fun checkCanModify(ctx: HouseholdContext, e: TransactionEntity) {
        if (!ctx.canEditTransactions) throw PermissionDeniedException("Your role cannot change transactions")
        if (ctx.role == Role.MEMBER && e.createdBy != ctx.member.id) throw PermissionDeniedException("Members can only change their own transactions")
        if (e.householdId != ctx.householdId) throw PermissionDeniedException("Wrong household")
        if (e.isDeleted) throw ValidationException("Transaction was deleted")
    }

    override suspend fun updateTransaction(id: String, draft: TransactionDraft) {
        val ctx = ctx()
        val old = db.transactions().get(id) ?: throw ValidationException("Transaction not found")
        checkCanModify(ctx, old)
        validate(ctx, draft)
        val opId = Ids.random()
        val e = old.copy(
            categoryId = draft.categoryId, type = draft.type, title = draft.title.trim(), amountMinor = draft.amountMinor,
            transactionDate = draft.date.toEpochDay(), description = draft.description?.trim()?.ifBlank { null },
            updatedAt = now(), revision = old.revision + 1, lastOperationId = opId,
        )
        val items = itemEntities(id, draft.items)
        writer.record(ctx.householdId, EntityType.TRANSACTION, id, OperationType.UPDATE, opId, old.lastOperationId, ctx.member.id,
            SyncJson.encodeToString(TransactionDto.serializer(), e.toDto(items))) { saveWithItems(e, items) }
    }

    override suspend fun deleteTransaction(id: String) {
        val ctx = ctx()
        val old = db.transactions().get(id) ?: throw ValidationException("Transaction not found")
        checkCanModify(ctx, old)
        val opId = Ids.random()
        val e = old.copy(isDeleted = true, updatedAt = now(), revision = old.revision + 1, lastOperationId = opId)
        val items = db.transactionItems().forTransaction(id)
        writer.record(ctx.householdId, EntityType.TRANSACTION, id, OperationType.DELETE, opId, old.lastOperationId, ctx.member.id,
            SyncJson.encodeToString(TransactionDto.serializer(), e.toDto(items))) { db.transactions().upsert(e) }
    }

    override fun observeTransaction(id: String): Flow<Transaction?> =
        combine(db.transactions().observe(id), db.transactionItems().observeForTransaction(id)) { t, items ->
            t?.takeUnless { e -> e.isDeleted }?.toDomain(items.map { it.toDomain() })
        }

    override fun observeTransactions(householdId: String, month: YearMonth): Flow<List<Transaction>> {
        val r = MonthKey.epochDayRange(month)
        return combine(
            db.transactions().observeInRange(householdId, r.first, r.last),
            db.transactionItems().observeInRange(householdId, r.first, r.last),
        ) { txs, items ->
            val byTx = items.groupBy { it.transactionId }
            txs.map { t -> t.toDomain(byTx[t.id].orEmpty().map { it.toDomain() }) }
        }
    }

    override fun observeRecent(householdId: String, limit: Int): Flow<List<Transaction>> =
        db.transactions().observeRecent(householdId, limit).map { l -> l.map { it.toDomain() } }

    // ------------------------------------------------------------------------------ categories

    override fun observeCategories(householdId: String): Flow<List<Category>> =
        db.categories().observeAll(householdId).map { l -> l.map { it.toDomain() } }

    override suspend fun createCategory(name: String, type: TransactionType, iconKey: String, colorHex: String): String {
        val ctx = ctx()
        if (!ctx.canManageSetup) throw PermissionDeniedException("Only owner/admin can manage categories")
        val clean = name.trim()
        if (clean.isEmpty() || clean.length > 40) throw ValidationException("Category name must be 1–40 characters")
        if (db.categories().all(ctx.householdId).any { it.type == type && it.name.equals(clean, ignoreCase = true) }) {
            throw ValidationException("A category with this name already exists")
        }
        val opId = Ids.random()
        val e = CategoryEntity(
            id = Ids.random(), householdId = ctx.householdId, name = clean, type = type, iconKey = iconKey,
            colorHex = colorHex, isActive = true, sortOrder = db.categories().maxSortOrder(ctx.householdId) + 1,
            updatedAt = now(), revision = 1, lastOperationId = opId,
        )
        writer.record(ctx.householdId, EntityType.CATEGORY, e.id, OperationType.CREATE, opId, null, ctx.member.id,
            SyncJson.encodeToString(CategoryDto.serializer(), e.toDto())) { db.categories().upsert(e) }
        return e.id
    }

    override suspend fun updateCategory(id: String, name: String, iconKey: String, colorHex: String) {
        val ctx = ctx()
        if (!ctx.canManageSetup) throw PermissionDeniedException("Only owner/admin can manage categories")
        val old = db.categories().get(id) ?: throw ValidationException("Category not found")
        val clean = name.trim()
        if (clean.isEmpty() || clean.length > 40) throw ValidationException("Category name must be 1–40 characters")
        val opId = Ids.random()
        val e = old.copy(name = clean, iconKey = iconKey, colorHex = colorHex, updatedAt = now(), revision = old.revision + 1, lastOperationId = opId)
        writer.record(ctx.householdId, EntityType.CATEGORY, id, OperationType.UPDATE, opId, old.lastOperationId, ctx.member.id,
            SyncJson.encodeToString(CategoryDto.serializer(), e.toDto())) { db.categories().upsert(e) }
    }

    /** Categories are archived, never deleted, so historical transactions keep their reference. */
    override suspend fun setCategoryActive(id: String, active: Boolean) {
        val ctx = ctx()
        if (!ctx.canManageSetup) throw PermissionDeniedException("Only owner/admin can manage categories")
        val old = db.categories().get(id) ?: throw ValidationException("Category not found")
        if (old.isActive == active) return
        val opId = Ids.random()
        val e = old.copy(isActive = active, updatedAt = now(), revision = old.revision + 1, lastOperationId = opId)
        writer.record(ctx.householdId, EntityType.CATEGORY, id, OperationType.UPDATE, opId, old.lastOperationId, ctx.member.id,
            SyncJson.encodeToString(CategoryDto.serializer(), e.toDto())) { db.categories().upsert(e) }
    }

    // ------------------------------------------------------------------------------ budgets

    override fun observeBudget(householdId: String, month: YearMonth): Flow<Budget?> =
        db.budgets().observe(householdId, month.year, month.monthValue).map { it?.toDomain() }

    override fun observeBudgetHistory(householdId: String): Flow<List<Budget>> =
        db.budgets().observeHistory(householdId).map { l -> l.map { it.toDomain() } }

    override suspend fun getBudget(householdId: String, month: YearMonth): Budget? =
        db.budgets().find(householdId, month.year, month.monthValue)?.toDomain()

    /** Budgets are per (household, year, month) with a deterministic id; transactions are never touched. */
    override suspend fun setBudget(month: YearMonth, amountMinor: Long) {
        val ctx = ctx()
        if (!ctx.canManageSetup) throw PermissionDeniedException("Only owner/admin can set budgets")
        if (amountMinor < 0) throw ValidationException("Budget cannot be negative")
        val id = Ids.budget(ctx.householdId, month.year, month.monthValue)
        val old = db.budgets().get(id)
        if (old != null && !old.isDeleted && old.amountMinor == amountMinor) return
        val opId = Ids.random()
        val e = BudgetEntity(id, ctx.householdId, month.year, month.monthValue, amountMinor, (old?.revision ?: 0) + 1, now(), opId)
        writer.record(ctx.householdId, EntityType.BUDGET, id, if (old == null) OperationType.CREATE else OperationType.UPDATE, opId,
            old?.lastOperationId, ctx.member.id, SyncJson.encodeToString(BudgetDto.serializer(), e.toDto())) { db.budgets().upsert(e) }
    }

    override fun observeOpeningBalance(householdId: String, month: YearMonth): Flow<OpeningBalance?> =
        db.openingBalances().observe(householdId, month.year, month.monthValue).map { it?.toDomain() }

    /** Pass null to clear the opening balance (available cash is then hidden). */
    override suspend fun setOpeningBalance(month: YearMonth, amountMinor: Long?) {
        val ctx = ctx()
        if (!ctx.canManageSetup) throw PermissionDeniedException("Only owner/admin can set opening balances")
        val id = Ids.openingBalance(ctx.householdId, month.year, month.monthValue)
        val old = db.openingBalances().get(id)
        if (amountMinor == null && (old == null || !old.isSet)) return
        if (amountMinor != null && old != null && old.isSet && old.amountMinor == amountMinor) return
        val opId = Ids.random()
        val e = OpeningBalanceEntity(
            id, ctx.householdId, month.year, month.monthValue, amountMinor ?: old!!.amountMinor,
            (old?.revision ?: 0) + 1, now(), opId, isSet = amountMinor != null,
        )
        writer.record(ctx.householdId, EntityType.OPENING_BALANCE, id, if (old == null) OperationType.CREATE else OperationType.UPDATE,
            opId, old?.lastOperationId, ctx.member.id, SyncJson.encodeToString(BudgetDto.serializer(), e.toDto())) { db.openingBalances().upsert(e) }
    }

    // ------------------------------------------------------------------------------ aggregates

    override fun observeMonthTotals(householdId: String, month: YearMonth): Flow<Pair<Long, Long>> {
        val r = MonthKey.epochDayRange(month)
        return db.transactions().observeTypeTotals(householdId, r.first, r.last).map { totals ->
            (totals.firstOrNull { it.type == TransactionType.INCOME }?.total ?: 0L) to
                (totals.firstOrNull { it.type == TransactionType.EXPENSE }?.total ?: 0L)
        }
    }

    override suspend fun monthTotals(householdId: String, month: YearMonth): Pair<Long, Long> {
        val r = MonthKey.epochDayRange(month)
        val totals = db.transactions().typeTotals(householdId, r.first, r.last)
        return (totals.firstOrNull { it.type == TransactionType.INCOME }?.total ?: 0L) to
            (totals.firstOrNull { it.type == TransactionType.EXPENSE }?.total ?: 0L)
    }

    override fun observeCategoryTotals(householdId: String, type: TransactionType, from: YearMonth, to: YearMonth): Flow<List<CategoryTotal>> {
        val fromDay = from.atDay(1).toEpochDay()
        val toDay = to.atEndOfMonth().toEpochDay()
        return combine(db.transactions().observeCategoryTotals(householdId, type, fromDay, toDay), db.categories().observeAll(householdId)) { sums, cats ->
            val total = sums.sumOf { it.total }
            val byId = cats.associateBy { it.id }
            sums.map { CategoryTotal(byId[it.categoryId]?.toDomain(), it.total, BudgetCalculator.percentBasisPoints(it.total, total)) }
        }
    }

    override fun observeMonthlySeries(householdId: String, from: YearMonth, to: YearMonth): Flow<List<MonthTotals>> =
        db.transactions().observeMonthlyTotals(householdId, from.atDay(1).toEpochDay(), to.atEndOfMonth().toEpochDay()).map { rows ->
            val months = generateSequence(from) { it.plusMonths(1) }.takeWhile { !it.isAfter(to) }.toList()
            months.map { m ->
                val key = MonthKey.of(m)
                MonthTotals(
                    m,
                    rows.filter { it.monthKey == key && it.type == TransactionType.INCOME }.sumOf { it.total },
                    rows.filter { it.monthKey == key && it.type == TransactionType.EXPENSE }.sumOf { it.total },
                )
            }
        }
}
