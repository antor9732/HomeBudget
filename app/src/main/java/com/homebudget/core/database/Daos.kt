package com.homebudget.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import com.homebudget.domain.model.TransactionType
import kotlinx.coroutines.flow.Flow

@Dao
interface HouseholdDao {
    @Query("SELECT * FROM households WHERE id = :id")
    suspend fun get(id: String): HouseholdEntity?

    @Query("SELECT * FROM households WHERE id = :id")
    fun observe(id: String): Flow<HouseholdEntity?>

    @Query("SELECT * FROM households WHERE isDeleted = 0 ORDER BY createdAt LIMIT 1")
    suspend fun first(): HouseholdEntity?

    @Upsert
    suspend fun upsert(e: HouseholdEntity)

    @Query("DELETE FROM households WHERE id = :id")
    suspend fun hardDelete(id: String)
}

@Dao
interface MemberDao {
    @Query("SELECT * FROM members WHERE id = :id")
    suspend fun get(id: String): MemberEntity?

    @Query("SELECT * FROM members WHERE householdId = :hh AND isDeleted = 0 ORDER BY role, displayName")
    fun observeAll(hh: String): Flow<List<MemberEntity>>

    @Query("SELECT * FROM members WHERE householdId = :hh AND isDeleted = 0")
    suspend fun all(hh: String): List<MemberEntity>

    @Query("SELECT * FROM members WHERE householdId = :hh AND lower(email) = lower(:email) AND isDeleted = 0 LIMIT 1")
    suspend fun byEmail(hh: String, email: String): MemberEntity?

    @Query("SELECT * FROM members WHERE id = :id")
    fun observe(id: String): Flow<MemberEntity?>

    @Upsert
    suspend fun upsert(e: MemberEntity)
}

@Dao
interface CategoryDao {
    @Query("SELECT * FROM categories WHERE id = :id")
    suspend fun get(id: String): CategoryEntity?

    @Query("SELECT * FROM categories WHERE householdId = :hh AND isDeleted = 0 ORDER BY type, isActive DESC, sortOrder, name")
    fun observeAll(hh: String): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM categories WHERE householdId = :hh AND isDeleted = 0")
    suspend fun all(hh: String): List<CategoryEntity>

    @Query("SELECT COALESCE(MAX(sortOrder), 0) FROM categories WHERE householdId = :hh")
    suspend fun maxSortOrder(hh: String): Int

    @Upsert
    suspend fun upsert(e: CategoryEntity)
}

@Dao
interface BudgetDao {
    @Query("SELECT * FROM budgets WHERE id = :id")
    suspend fun get(id: String): BudgetEntity?

    @Query("SELECT * FROM budgets WHERE householdId = :hh AND year = :year AND month = :month AND isDeleted = 0")
    fun observe(hh: String, year: Int, month: Int): Flow<BudgetEntity?>

    @Query("SELECT * FROM budgets WHERE householdId = :hh AND year = :year AND month = :month AND isDeleted = 0")
    suspend fun find(hh: String, year: Int, month: Int): BudgetEntity?

    @Query("SELECT * FROM budgets WHERE householdId = :hh AND isDeleted = 0 ORDER BY year DESC, month DESC")
    fun observeHistory(hh: String): Flow<List<BudgetEntity>>

    @Query("SELECT * FROM budgets WHERE householdId = :hh AND isDeleted = 0")
    suspend fun all(hh: String): List<BudgetEntity>

    @Upsert
    suspend fun upsert(e: BudgetEntity)
}

@Dao
interface OpeningBalanceDao {
    @Query("SELECT * FROM opening_balances WHERE id = :id")
    suspend fun get(id: String): OpeningBalanceEntity?

    @Query("SELECT * FROM opening_balances WHERE householdId = :hh AND year = :year AND month = :month AND isDeleted = 0 AND isSet = 1")
    fun observe(hh: String, year: Int, month: Int): Flow<OpeningBalanceEntity?>

    @Query("SELECT * FROM opening_balances WHERE householdId = :hh AND year = :year AND month = :month AND isDeleted = 0 AND isSet = 1")
    suspend fun find(hh: String, year: Int, month: Int): OpeningBalanceEntity?

    @Query("SELECT * FROM opening_balances WHERE householdId = :hh AND isDeleted = 0")
    suspend fun all(hh: String): List<OpeningBalanceEntity>

    @Upsert
    suspend fun upsert(e: OpeningBalanceEntity)
}

data class TypeTotal(val type: TransactionType, val total: Long)
data class CategorySum(val categoryId: String, val total: Long)
data class MonthTypeTotal(val monthKey: Int, val type: TransactionType, val total: Long)

@Dao
interface TransactionDao {
    @Query("SELECT * FROM transactions WHERE id = :id")
    suspend fun get(id: String): TransactionEntity?

    @Query("SELECT * FROM transactions WHERE id = :id")
    fun observe(id: String): Flow<TransactionEntity?>

    @Upsert
    suspend fun upsert(e: TransactionEntity)

    @Query(
        """SELECT type, SUM(amountMinor) AS total FROM transactions
           WHERE householdId = :hh AND isDeleted = 0 AND transactionDate BETWEEN :fromDay AND :toDay
           GROUP BY type"""
    )
    fun observeTypeTotals(hh: String, fromDay: Long, toDay: Long): Flow<List<TypeTotal>>

    @Query(
        """SELECT type, SUM(amountMinor) AS total FROM transactions
           WHERE householdId = :hh AND isDeleted = 0 AND transactionDate BETWEEN :fromDay AND :toDay
           GROUP BY type"""
    )
    suspend fun typeTotals(hh: String, fromDay: Long, toDay: Long): List<TypeTotal>

    @Query(
        """SELECT categoryId, SUM(amountMinor) AS total FROM transactions
           WHERE householdId = :hh AND isDeleted = 0 AND type = :type AND transactionDate BETWEEN :fromDay AND :toDay
           GROUP BY categoryId ORDER BY total DESC"""
    )
    fun observeCategoryTotals(hh: String, type: TransactionType, fromDay: Long, toDay: Long): Flow<List<CategorySum>>

    @Query(
        """SELECT CAST(strftime('%Y%m', transactionDate * 86400, 'unixepoch') AS INTEGER) AS monthKey,
                  type, SUM(amountMinor) AS total
           FROM transactions
           WHERE householdId = :hh AND isDeleted = 0 AND transactionDate BETWEEN :fromDay AND :toDay
           GROUP BY monthKey, type ORDER BY monthKey"""
    )
    fun observeMonthlyTotals(hh: String, fromDay: Long, toDay: Long): Flow<List<MonthTypeTotal>>

    @Query(
        """SELECT * FROM transactions
           WHERE householdId = :hh AND isDeleted = 0 AND transactionDate BETWEEN :fromDay AND :toDay
           ORDER BY transactionDate DESC, createdAt DESC"""
    )
    fun observeInRange(hh: String, fromDay: Long, toDay: Long): Flow<List<TransactionEntity>>

    @Query(
        """SELECT * FROM transactions WHERE householdId = :hh AND isDeleted = 0
           ORDER BY transactionDate DESC, createdAt DESC LIMIT :limit"""
    )
    fun observeRecent(hh: String, limit: Int): Flow<List<TransactionEntity>>

    @Query("SELECT * FROM transactions WHERE householdId = :hh")
    suspend fun allIncludingDeleted(hh: String): List<TransactionEntity>

    @Query("SELECT COUNT(*) FROM transactions WHERE householdId = :hh AND isDeleted = 0")
    suspend fun countActive(hh: String): Int
}

@Dao
interface TransactionItemDao {
    @Query("SELECT * FROM transaction_items WHERE transactionId = :txId ORDER BY position")
    suspend fun forTransaction(txId: String): List<TransactionItemEntity>

    @Query("SELECT * FROM transaction_items WHERE transactionId = :txId ORDER BY position")
    fun observeForTransaction(txId: String): Flow<List<TransactionItemEntity>>

    @Query(
        """SELECT i.* FROM transaction_items i JOIN transactions t ON t.id = i.transactionId
           WHERE t.householdId = :hh AND t.isDeleted = 0 AND t.transactionDate BETWEEN :fromDay AND :toDay
           ORDER BY i.transactionId, i.position"""
    )
    fun observeInRange(hh: String, fromDay: Long, toDay: Long): Flow<List<TransactionItemEntity>>

    @Query("DELETE FROM transaction_items WHERE transactionId = :txId")
    suspend fun deleteFor(txId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<TransactionItemEntity>)
}

@Dao
interface SyncDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertOperation(op: SyncOperationEntity)

    @Query("SELECT * FROM sync_operations WHERE householdId = :hh AND syncStatus != 'SYNCED' ORDER BY createdAt LIMIT :limit")
    suspend fun pending(hh: String, limit: Int = 200): List<SyncOperationEntity>

    @Query("SELECT COUNT(*) FROM sync_operations WHERE householdId = :hh AND syncStatus != 'SYNCED'")
    fun observePendingCount(hh: String): Flow<Int>

    @Query("SELECT COUNT(*) FROM sync_operations WHERE householdId = :hh AND syncStatus != 'SYNCED'")
    suspend fun pendingCount(hh: String): Int

    @Query("UPDATE sync_operations SET syncStatus = 'SYNCED', driveFileId = :fileId, lastError = NULL WHERE operationId = :opId")
    suspend fun markSynced(opId: String, fileId: String)

    @Query("UPDATE sync_operations SET syncStatus = 'FAILED', retryCount = retryCount + 1, lastError = :error WHERE operationId = :opId")
    suspend fun markFailed(opId: String, error: String)

    @Query("SELECT * FROM sync_operations WHERE householdId = :hh ORDER BY createdAt")
    suspend fun allOperations(hh: String): List<SyncOperationEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertApplied(a: AppliedOperationEntity): Long

    @Query("SELECT EXISTS(SELECT 1 FROM applied_operations WHERE operationId = :opId)")
    suspend fun isApplied(opId: String): Boolean

    @Query("SELECT baseOperationId FROM applied_operations WHERE operationId = :opId")
    suspend fun appliedBase(opId: String): String?

    @Query("SELECT operationId FROM applied_operations WHERE householdId = :hh")
    suspend fun appliedIds(hh: String): List<String>

    @Upsert
    suspend fun upsertDeferred(d: DeferredOperationEntity)

    @Query("SELECT * FROM deferred_operations WHERE householdId = :hh ORDER BY remoteCreatedAt")
    suspend fun deferred(hh: String): List<DeferredOperationEntity>

    @Query("DELETE FROM deferred_operations WHERE operationId = :opId")
    suspend fun deleteDeferred(opId: String)

    @Query("SELECT * FROM sync_cursors WHERE householdId = :hh")
    suspend fun cursor(hh: String): SyncCursorEntity?

    @Query("SELECT * FROM sync_cursors WHERE householdId = :hh")
    fun observeCursor(hh: String): Flow<SyncCursorEntity?>

    @Upsert
    suspend fun upsertCursor(c: SyncCursorEntity)

    @Query("SELECT * FROM household_keys WHERE householdId = :hh")
    suspend fun key(hh: String): HouseholdKeyEntity?

    @Upsert
    suspend fun upsertKey(k: HouseholdKeyEntity)
}

@Dao
interface ConflictDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(c: ConflictEntity)

    @Query("SELECT * FROM conflicts WHERE householdId = :hh ORDER BY detectedAt DESC")
    fun observeAll(hh: String): Flow<List<ConflictEntity>>

    @Query("SELECT COUNT(*) FROM conflicts WHERE householdId = :hh AND resolutionStatus = 'OPEN'")
    fun observeOpenCount(hh: String): Flow<Int>

    @Query("SELECT * FROM conflicts WHERE id = :id")
    suspend fun get(id: String): ConflictEntity?

    @Query("UPDATE conflicts SET resolutionStatus = :status, resolvedAt = :at WHERE id = :id")
    suspend fun resolve(id: String, status: ConflictResolution, at: Long)
}
