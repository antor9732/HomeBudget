package com.homebudget.core.sync

import com.homebudget.core.common.Ids
import com.homebudget.core.common.Money
import com.homebudget.core.common.TimeProvider
import com.homebudget.core.database.AccessState
import com.homebudget.core.database.AppDatabase
import com.homebudget.core.database.ConflictEntity
import com.homebudget.core.database.ConflictResolution
import com.homebudget.core.notifications.WorkScheduler
import com.homebudget.data.mapper.toBudgetEntity
import com.homebudget.data.mapper.toDto
import com.homebudget.data.mapper.toEntity
import com.homebudget.data.mapper.toOpeningEntity
import com.homebudget.data.mapper.itemEntities
import com.homebudget.data.remote.BudgetDto
import com.homebudget.data.remote.CategoryDto
import com.homebudget.data.remote.EntityType
import com.homebudget.data.remote.MemberDto
import com.homebudget.data.remote.OperationType
import com.homebudget.data.remote.SyncJson
import com.homebudget.data.remote.TransactionDto
import com.homebudget.domain.model.SyncHealth
import com.homebudget.domain.model.SyncStatus
import com.homebudget.domain.repository.ConflictItem
import com.homebudget.domain.repository.HouseholdRepository
import com.homebudget.domain.repository.PermissionDeniedException
import com.homebudget.domain.repository.SyncRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SyncRepositoryImpl @Inject constructor(
    private val db: AppDatabase,
    private val scheduler: WorkScheduler,
    private val households: HouseholdRepository,
    private val writer: LocalChangeWriter,
    private val time: TimeProvider,
) : SyncRepository {

    override fun observeStatus(householdId: String): Flow<SyncStatus> = combine(
        db.sync().observeCursor(householdId), db.sync().observePendingCount(householdId), db.conflicts().observeOpenCount(householdId),
    ) { cursor, pending, conflicts ->
        val health = when {
            cursor == null -> SyncHealth.LOCAL_ONLY
            cursor.accessState == AccessState.ACCESS_REVOKED -> SyncHealth.ACCESS_REVOKED
            cursor.accessState == AccessState.AUTH_REQUIRED -> SyncHealth.AUTH_REQUIRED
            cursor.lastError != null -> SyncHealth.ERROR
            else -> SyncHealth.IDLE
        }
        SyncStatus(health, cursor?.lastSuccessfulSyncAt, pending, cursor?.lastError, conflicts)
    }

    override fun observeConflicts(householdId: String): Flow<List<ConflictItem>> =
        db.conflicts().observeAll(householdId).map { list -> list.map { it.toItem() } }

    override suspend fun keepCurrent(conflictId: String) {
        val ctx = households.currentContext() ?: return
        if (ctx.readOnly) throw PermissionDeniedException("Read-only")
        db.conflicts().resolve(conflictId, ConflictResolution.KEPT_CURRENT, time.now().toEpochMilli())
    }

    /** Re-applies the losing version as a NEW edit (higher revision) so every device converges on it. */
    override suspend fun applyOtherVersion(conflictId: String) {
        val ctx = households.currentContext() ?: return
        val c = db.conflicts().get(conflictId) ?: return
        val type = EntityType.valueOf(c.entityType)
        val needsSetup = type in setOf(EntityType.BUDGET, EntityType.OPENING_BALANCE, EntityType.CATEGORY, EntityType.MEMBER, EntityType.HOUSEHOLD)
        if (ctx.readOnly || (needsSetup && !ctx.canManageSetup) || (type == EntityType.TRANSACTION && !ctx.canEditTransactions)) {
            throw PermissionDeniedException("Your role cannot resolve this conflict")
        }
        val opId = Ids.random()
        val now = time.now().toEpochMilli()
        val hh = ctx.householdId
        when (type) {
            EntityType.TRANSACTION -> {
                val cur = db.transactions().get(c.entityId) ?: return
                if (cur.isDeleted) throw PermissionDeniedException("Deleted transactions cannot be restored")
                val other = SyncJson.decodeFromString<TransactionDto>(c.remoteVersion)
                val e = other.toEntity(opId).copy(revision = cur.revision + 1, updatedAt = now, isDeleted = false)
                val items = other.itemEntities()
                writer.record(hh, type, c.entityId, OperationType.UPDATE, opId, cur.lastOperationId, ctx.member.id,
                    SyncJson.encodeToString(TransactionDto.serializer(), e.toDto(items))) {
                    db.transactions().upsert(e)
                    db.transactionItems().deleteFor(e.id)
                    if (items.isNotEmpty()) db.transactionItems().insertAll(items)
                }
            }
            EntityType.BUDGET -> {
                val cur = db.budgets().get(c.entityId) ?: return
                val other = SyncJson.decodeFromString<BudgetDto>(c.remoteVersion)
                val e = other.toBudgetEntity(opId).copy(revision = cur.revision + 1, updatedAt = now, isDeleted = false)
                writer.record(hh, type, c.entityId, OperationType.UPDATE, opId, cur.lastOperationId, ctx.member.id,
                    SyncJson.encodeToString(BudgetDto.serializer(), e.toDto())) { db.budgets().upsert(e) }
            }
            EntityType.OPENING_BALANCE -> {
                val cur = db.openingBalances().get(c.entityId) ?: return
                val other = SyncJson.decodeFromString<BudgetDto>(c.remoteVersion)
                val e = other.toOpeningEntity(opId).copy(revision = cur.revision + 1, updatedAt = now, isDeleted = false)
                writer.record(hh, type, c.entityId, OperationType.UPDATE, opId, cur.lastOperationId, ctx.member.id,
                    SyncJson.encodeToString(BudgetDto.serializer(), e.toDto())) { db.openingBalances().upsert(e) }
            }
            EntityType.CATEGORY -> {
                val cur = db.categories().get(c.entityId) ?: return
                val other = SyncJson.decodeFromString<CategoryDto>(c.remoteVersion)
                val e = other.toEntity(opId).copy(revision = cur.revision + 1, updatedAt = now, isDeleted = false)
                writer.record(hh, type, c.entityId, OperationType.UPDATE, opId, cur.lastOperationId, ctx.member.id,
                    SyncJson.encodeToString(CategoryDto.serializer(), e.toDto())) { db.categories().upsert(e) }
            }
            else -> throw PermissionDeniedException("Resolve membership conflicts from the Members screen")
        }
        db.conflicts().resolve(conflictId, ConflictResolution.APPLIED_OTHER, now)
    }

    override fun requestSync() = scheduler.requestSync()

    private fun ConflictEntity.toItem() = ConflictItem(
        id, entityType, entityId, summarize(entityType, localVersion), summarize(entityType, remoteVersion), detectedAt,
        resolutionStatus == ConflictResolution.OPEN,
    )

    private fun summarize(type: String, json: String): String = runCatching {
        when (EntityType.valueOf(type)) {
            EntityType.TRANSACTION -> SyncJson.decodeFromString<TransactionDto>(json).let {
                if (it.isDeleted) "Deleted: ${it.title}" else "${it.title} · ${Money.format(it.amountMinor, it.currencyCode)} · ${LocalDate.ofEpochDay(it.transactionDate)}" +
                    (if (it.items.isNotEmpty()) " · ${it.items.size} items: " + it.items.take(4).joinToString { i -> i.name } else "")
            }
            EntityType.BUDGET -> SyncJson.decodeFromString<BudgetDto>(json).let { "Budget ${it.year}-${"%02d".format(it.month)}: ${Money.format(it.amountMinor)}" }
            EntityType.OPENING_BALANCE -> SyncJson.decodeFromString<BudgetDto>(json).let { "Opening ${it.year}-${"%02d".format(it.month)}: ${Money.format(it.amountMinor)}" }
            EntityType.CATEGORY -> SyncJson.decodeFromString<CategoryDto>(json).let { "${it.name}${if (it.isActive) "" else " (archived)"}" }
            EntityType.MEMBER -> SyncJson.decodeFromString<MemberDto>(json).let { "${it.displayName}: ${it.role} ${it.status}" }
            EntityType.HOUSEHOLD -> "Household settings"
            EntityType.SHOPPING_LIST -> "Bazar list"
            EntityType.SHOPPING_ITEM -> "Bazar list item"
        }
    }.getOrDefault("Unreadable version")
}
