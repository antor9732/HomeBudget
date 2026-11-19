package com.homebudget.core.sync

import androidx.room.withTransaction
import com.homebudget.core.common.LocalIdentity
import com.homebudget.core.common.TimeProvider
import com.homebudget.core.database.AppDatabase
import com.homebudget.core.database.AppliedOperationEntity
import com.homebudget.core.database.SyncOperationEntity
import com.homebudget.core.database.SyncState
import com.homebudget.data.remote.EntityType
import com.homebudget.data.remote.OperationType
import com.homebudget.data.remote.SCHEMA_VERSION
import javax.inject.Inject
import javax.inject.Singleton

/** Notified after local or remote data changes (schedules sync and budget checks). */
interface ChangeListener {
    fun onLocalChange(householdId: String)
    fun onDataChanged(householdId: String)
}

object NoopChangeListener : ChangeListener {
    override fun onLocalChange(householdId: String) {}
    override fun onDataChanged(householdId: String) {}
}

/**
 * Persists a business record and its pending sync operation in ONE Room transaction, so a change is
 * never saved without being queued for sync (and vice versa).
 */
@Singleton
class LocalChangeWriter @Inject constructor(
    private val db: AppDatabase,
    private val identity: LocalIdentity,
    private val time: TimeProvider,
    private val listener: ChangeListener,
) {
    suspend fun record(
        householdId: String,
        entityType: EntityType,
        entityId: String,
        operationType: OperationType,
        operationId: String,
        baseOperationId: String?,
        actorMemberId: String,
        payloadJson: String,
        upsertEntity: suspend () -> Unit,
    ) {
        val deviceId = identity.deviceId()
        val now = time.now().toEpochMilli()
        db.withTransaction {
            upsertEntity()
            db.sync().insertOperation(
                SyncOperationEntity(
                    operationId = operationId,
                    householdId = householdId,
                    entityType = entityType.name,
                    entityId = entityId,
                    operationType = operationType.name,
                    deviceId = deviceId,
                    actorMemberId = actorMemberId,
                    baseOperationId = baseOperationId,
                    payload = payloadJson,
                    schemaVersion = SCHEMA_VERSION,
                    createdAt = now,
                    syncStatus = SyncState.PENDING,
                )
            )
            db.sync().insertApplied(AppliedOperationEntity(operationId, householdId, "LOCAL", entityId, baseOperationId, null, now))
        }
        listener.onLocalChange(householdId)
    }
}
