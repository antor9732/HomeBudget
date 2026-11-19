package com.homebudget.core.sync

import com.homebudget.core.common.Ids
import com.homebudget.core.database.AppDatabase
import com.homebudget.core.database.AppliedOperationEntity
import com.homebudget.core.database.ConflictEntity
import com.homebudget.core.database.ConflictResolution
import com.homebudget.data.mapper.toBudgetEntity
import com.homebudget.data.mapper.toDto
import com.homebudget.data.mapper.toEntity
import com.homebudget.data.mapper.toOpeningEntity
import com.homebudget.data.mapper.itemEntities
import com.homebudget.data.remote.BudgetDto
import com.homebudget.data.remote.CategoryDto
import com.homebudget.data.remote.EntityType
import com.homebudget.data.remote.HouseholdDto
import com.homebudget.data.remote.MemberDto
import com.homebudget.data.remote.OperationEnvelope
import com.homebudget.data.remote.SyncJson
import com.homebudget.data.remote.TransactionDto

sealed interface ApplyResult {
    data object Applied : ApplyResult
    data object Ignored : ApplyResult
    data class Conflict(val incomingApplied: Boolean) : ApplyResult
    data class Deferred(val reason: String) : ApplyResult
}

/**
 * Applies a validated remote operation idempotently. Must be called inside a Room transaction.
 */
class OperationApplier(private val db: AppDatabase, private val nowMillis: () -> Long) {

    suspend fun apply(env: OperationEnvelope, driveFileId: String?): ApplyResult {
        if (db.sync().isApplied(env.operationId)) return ApplyResult.Ignored
        val type = EntityType.valueOf(env.entityType)
        if (type != EntityType.HOUSEHOLD && db.households().get(env.householdId) == null) {
            return ApplyResult.Deferred("household not yet known")
        }
        val result = when (type) {
            EntityType.HOUSEHOLD -> {
                val dto = SyncJson.decodeFromString<HouseholdDto>(env.payload)
                val existing = db.households().get(dto.id)
                merge(env, existing?.let { LocalVersion(it.revision, it.lastOperationId, it.isDeleted) }, dto.revision, dto.isDeleted,
                    currentJson = { existing?.let { SyncJson.encodeToString(HouseholdDto.serializer(), it.toDto()) } }) {
                    // Device-local Drive folder id is preserved if the payload omits it.
                    db.households().upsert(dto.toEntity(env.operationId).copy(driveFolderId = dto.driveFolderId ?: existing?.driveFolderId))
                }
            }
            EntityType.MEMBER -> {
                val dto = SyncJson.decodeFromString<MemberDto>(env.payload)
                val existing = db.members().get(dto.id)
                merge(env, existing?.let { LocalVersion(it.revision, it.lastOperationId, it.isDeleted) }, dto.revision, dto.isDeleted,
                    currentJson = { existing?.let { SyncJson.encodeToString(MemberDto.serializer(), it.toDto()) } }) {
                    db.members().upsert(dto.toEntity(env.operationId))
                }
            }
            EntityType.CATEGORY -> {
                val dto = SyncJson.decodeFromString<CategoryDto>(env.payload)
                val existing = db.categories().get(dto.id)
                merge(env, existing?.let { LocalVersion(it.revision, it.lastOperationId, it.isDeleted) }, dto.revision, dto.isDeleted,
                    currentJson = { existing?.let { SyncJson.encodeToString(CategoryDto.serializer(), it.toDto()) } }) {
                    db.categories().upsert(dto.toEntity(env.operationId))
                }
            }
            EntityType.BUDGET -> {
                val dto = SyncJson.decodeFromString<BudgetDto>(env.payload)
                if (dto.id != Ids.budget(dto.householdId, dto.year, dto.month)) return ApplyResult.Deferred("non-canonical budget id")
                val existing = db.budgets().get(dto.id)
                merge(env, existing?.let { LocalVersion(it.revision, it.lastOperationId, it.isDeleted) }, dto.revision, dto.isDeleted,
                    currentJson = { existing?.let { SyncJson.encodeToString(BudgetDto.serializer(), it.toDto()) } }) {
                    db.budgets().upsert(dto.toBudgetEntity(env.operationId))
                }
            }
            EntityType.OPENING_BALANCE -> {
                val dto = SyncJson.decodeFromString<BudgetDto>(env.payload)
                if (dto.id != Ids.openingBalance(dto.householdId, dto.year, dto.month)) return ApplyResult.Deferred("non-canonical opening id")
                val existing = db.openingBalances().get(dto.id)
                merge(env, existing?.let { LocalVersion(it.revision, it.lastOperationId, it.isDeleted) }, dto.revision, dto.isDeleted,
                    currentJson = { existing?.let { SyncJson.encodeToString(BudgetDto.serializer(), it.toDto()) } }) {
                    db.openingBalances().upsert(dto.toOpeningEntity(env.operationId))
                }
            }
            EntityType.TRANSACTION -> {
                val dto = SyncJson.decodeFromString<TransactionDto>(env.payload)
                if (db.categories().get(dto.categoryId) == null) return ApplyResult.Deferred("category ${dto.categoryId} not yet received")
                val existing = db.transactions().get(dto.id)
                merge(env, existing?.let { LocalVersion(it.revision, it.lastOperationId, it.isDeleted) }, dto.revision, dto.isDeleted,
                    currentJson = { existing?.let { SyncJson.encodeToString(TransactionDto.serializer(), it.toDto(db.transactionItems().forTransaction(it.id))) } }) {
                    db.transactions().upsert(dto.toEntity(env.operationId))
                    db.transactionItems().deleteFor(dto.id)
                    if (dto.items.isNotEmpty()) db.transactionItems().insertAll(dto.itemEntities())
                }
            }
        }
        db.sync().insertApplied(AppliedOperationEntity(env.operationId, env.householdId, "REMOTE", env.entityId, env.baseOperationId, driveFileId, nowMillis()))
        db.sync().deleteDeferred(env.operationId)
        return result
    }

    private suspend fun merge(
        env: OperationEnvelope,
        local: LocalVersion?,
        incomingRevision: Long,
        incomingDeleted: Boolean,
        currentJson: suspend () -> String?,
        write: suspend () -> Unit,
    ): ApplyResult {
        val incoming = IncomingVersion(incomingRevision, env.operationId, env.baseOperationId, incomingDeleted)
        val ancestor = local != null && isAncestor(env.operationId, local.operationId)
        val decision = MergePolicy.decide(local, incoming, ancestor)
        if (decision.conflict && local != null) {
            val current = currentJson() ?: "{}"
            val keptIsIncoming = decision.apply
            db.conflicts().insert(
                ConflictEntity(
                    id = "${env.entityId}:${minOf(env.operationId, local.operationId)}:${maxOf(env.operationId, local.operationId)}",
                    householdId = env.householdId,
                    entityType = env.entityType,
                    entityId = env.entityId,
                    localVersion = if (keptIsIncoming) env.payload else current,
                    remoteVersion = if (keptIsIncoming) current else env.payload,
                    localRevision = if (keptIsIncoming) incomingRevision else local.revision,
                    remoteRevision = if (keptIsIncoming) local.revision else incomingRevision,
                    detectedAt = nowMillis(),
                    resolutionStatus = ConflictResolution.OPEN,
                )
            )
        }
        if (decision.apply) write()
        return when {
            decision.conflict -> ApplyResult.Conflict(decision.apply)
            decision.apply -> ApplyResult.Applied
            else -> ApplyResult.Ignored
        }
    }

    /** Walks the causal chain of the local version looking for [candidateOpId]. */
    private suspend fun isAncestor(candidateOpId: String, localOpId: String): Boolean {
        var cursor: String? = localOpId
        var steps = 0
        while (cursor != null && steps < 64) {
            val base = db.sync().appliedBase(cursor) ?: return false
            if (base == candidateOpId) return true
            cursor = base
            steps++
        }
        return false
    }
}
