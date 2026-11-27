package com.homebudget.core.database

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.homebudget.domain.model.MemberStatus
import com.homebudget.domain.model.Role
import com.homebudget.domain.model.ShoppingStatus
import com.homebudget.domain.model.TransactionType

/*
 * Every shared (synchronized) record has: stable UUID id, revision (per-entity logical counter),
 * lastOperationId (operation that produced the current state; deterministic tie-breaker),
 * updatedAt and isDeleted (tombstone). Tombstones are never purged in v1.
 */

@Entity(tableName = "households")
data class HouseholdEntity(
    @PrimaryKey val id: String,
    val name: String,
    val ownerMemberId: String,
    val currencyCode: String,
    val driveFolderId: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val revision: Long,
    val lastOperationId: String,
    val isDeleted: Boolean = false,
)

@Entity(
    tableName = "members",
    foreignKeys = [ForeignKey(entity = HouseholdEntity::class, parentColumns = ["id"], childColumns = ["householdId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("householdId"), Index("email")],
)
data class MemberEntity(
    @PrimaryKey val id: String,
    val householdId: String,
    val email: String?,
    val googleSubject: String?,
    val displayName: String,
    val role: Role,
    val status: MemberStatus,
    val joinedAt: Long,
    val removedAt: Long?,
    val updatedAt: Long,
    val revision: Long,
    val lastOperationId: String,
    val isDeleted: Boolean = false,
)

@Entity(
    tableName = "budgets",
    foreignKeys = [ForeignKey(entity = HouseholdEntity::class, parentColumns = ["id"], childColumns = ["householdId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["householdId", "year", "month"], unique = true)],
)
data class BudgetEntity(
    @PrimaryKey val id: String,
    val householdId: String,
    val year: Int,
    val month: Int,
    val amountMinor: Long,
    val revision: Long,
    val updatedAt: Long,
    val lastOperationId: String,
    val isDeleted: Boolean = false,
)

@Entity(
    tableName = "opening_balances",
    foreignKeys = [ForeignKey(entity = HouseholdEntity::class, parentColumns = ["id"], childColumns = ["householdId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["householdId", "year", "month"], unique = true)],
)
data class OpeningBalanceEntity(
    @PrimaryKey val id: String,
    val householdId: String,
    val year: Int,
    val month: Int,
    val amountMinor: Long,
    val revision: Long,
    val updatedAt: Long,
    val lastOperationId: String,
    /** false = cleared by the user (kept as a normal revision, not a tombstone, so it can be set again). */
    val isSet: Boolean = true,
    val isDeleted: Boolean = false,
)

@Entity(
    tableName = "categories",
    foreignKeys = [ForeignKey(entity = HouseholdEntity::class, parentColumns = ["id"], childColumns = ["householdId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("householdId"), Index("type")],
)
data class CategoryEntity(
    @PrimaryKey val id: String,
    val householdId: String,
    val name: String,
    val type: TransactionType,
    val iconKey: String,
    val colorHex: String,
    val isActive: Boolean,
    val sortOrder: Int,
    val updatedAt: Long,
    val revision: Long,
    val lastOperationId: String,
    val isDeleted: Boolean = false,
)

@Entity(
    tableName = "transactions",
    foreignKeys = [
        ForeignKey(entity = HouseholdEntity::class, parentColumns = ["id"], childColumns = ["householdId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = CategoryEntity::class, parentColumns = ["id"], childColumns = ["categoryId"], onDelete = ForeignKey.RESTRICT),
    ],
    indices = [Index("householdId"), Index("transactionDate"), Index("categoryId"), Index("type")],
)
data class TransactionEntity(
    @PrimaryKey val id: String,
    val householdId: String,
    val categoryId: String,
    val type: TransactionType,
    val title: String,
    val amountMinor: Long,
    val currencyCode: String,
    /** LocalDate.toEpochDay() */
    val transactionDate: Long,
    val description: String?,
    val createdBy: String,
    val deviceId: String,
    val createdAt: Long,
    val updatedAt: Long,
    val revision: Long,
    val lastOperationId: String,
    val isDeleted: Boolean = false,
)

/** Optional line items of a transaction (e.g. each grocery item). Amount of the parent = sum of items. */
@Entity(
    tableName = "transaction_items",
    foreignKeys = [ForeignKey(entity = TransactionEntity::class, parentColumns = ["id"], childColumns = ["transactionId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("transactionId")],
)
data class TransactionItemEntity(
    @PrimaryKey val id: String,
    val transactionId: String,
    val position: Int,
    val name: String,
    val amountMinor: Long,
)

enum class SyncState { PENDING, SYNCED, FAILED }

/** Outbound operation log. Written in the same Room transaction as the business change. */
@Entity(tableName = "sync_operations", indices = [Index(value = ["householdId", "syncStatus"])])
data class SyncOperationEntity(
    @PrimaryKey val operationId: String,
    val householdId: String,
    val entityType: String,
    val entityId: String,
    val operationType: String,
    val deviceId: String,
    val actorMemberId: String,
    /** lastOperationId of the entity before this change (causal parent); null for creates. */
    val baseOperationId: String?,
    /** JSON entity snapshot (plaintext locally; encrypted before upload). */
    val payload: String,
    val schemaVersion: Int,
    val createdAt: Long,
    val syncStatus: SyncState,
    val retryCount: Int = 0,
    val lastError: String? = null,
    val driveFileId: String? = null,
)

/** Idempotency ledger: every operation (local or remote) applied to this device. */
@Entity(tableName = "applied_operations", indices = [Index("householdId")])
data class AppliedOperationEntity(
    @PrimaryKey val operationId: String,
    val householdId: String,
    val source: String, // LOCAL | REMOTE
    val entityId: String,
    /** Causal parent; used to recognise late-arriving ancestors (not conflicts). */
    val baseOperationId: String?,
    val driveFileId: String?,
    val appliedAt: Long,
)

/** Remote operations that passed validation but could not be applied yet (e.g. missing category). */
@Entity(tableName = "deferred_operations", indices = [Index("householdId")])
data class DeferredOperationEntity(
    @PrimaryKey val operationId: String,
    val householdId: String,
    val driveFileId: String,
    val authorEmail: String?,
    val remoteCreatedAt: Long,
    val decryptedJson: String,
    val reason: String,
    val attempts: Int,
)

enum class AccessState { OK, AUTH_REQUIRED, ACCESS_REVOKED }

@Entity(tableName = "sync_cursors")
data class SyncCursorEntity(
    @PrimaryKey val householdId: String,
    val driveFolderId: String,
    val operationsFolderId: String,
    val snapshotsFolderId: String?,
    val metadataFolderId: String?,
    /** Folder owner's email as attested by Drive; the household owner. */
    val ownerEmail: String,
    /** Drive createdTime (epoch millis) of the newest remote op processed. */
    val lastRemoteCursor: Long,
    val lastSuccessfulSyncAt: Long?,
    val lastAttemptAt: Long?,
    val lastError: String?,
    val accessState: AccessState,
)

/** Household data key, wrapped by the device Android Keystore key. Never leaves the device in this form. */
@Entity(tableName = "household_keys")
data class HouseholdKeyEntity(
    @PrimaryKey val householdId: String,
    val wrappedKey: String,
    val keyVersion: Int,
)

enum class ConflictResolution { OPEN, KEPT_CURRENT, APPLIED_OTHER }

@Entity(tableName = "conflicts", indices = [Index("householdId"), Index("entityId")])
data class ConflictEntity(
    @PrimaryKey val id: String,
    val householdId: String,
    val entityType: String,
    val entityId: String,
    /** JSON of the version that is currently applied. */
    val localVersion: String,
    /** JSON of the concurrent version that did not win. */
    val remoteVersion: String,
    val localRevision: Long,
    val remoteRevision: Long,
    val detectedAt: Long,
    val resolutionStatus: ConflictResolution,
    val resolvedAt: Long? = null,
)

/** A shared bazar (shopping) list. Synced as its own entity; items are synced separately. */
@Entity(
    tableName = "shopping_lists",
    foreignKeys = [ForeignKey(entity = HouseholdEntity::class, parentColumns = ["id"], childColumns = ["householdId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("householdId")],
)
data class ShoppingListEntity(
    @PrimaryKey val id: String,
    val householdId: String,
    val title: String,
    val status: ShoppingStatus,
    val createdBy: String,
    val createdAt: Long,
    val updatedAt: Long,
    val completedAt: Long?,
    val revision: Long,
    val lastOperationId: String,
    val isDeleted: Boolean = false,
)

/** One line of a bazar list. Each item is its own synced entity so two phones can edit different items at once. */
@Entity(
    tableName = "shopping_items",
    foreignKeys = [ForeignKey(entity = HouseholdEntity::class, parentColumns = ["id"], childColumns = ["householdId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("householdId"), Index("listId")],
)
data class ShoppingItemEntity(
    @PrimaryKey val id: String,
    val householdId: String,
    val listId: String,
    val name: String,
    val quantity: String?,
    val categoryId: String,
    val position: Int,
    val isChecked: Boolean,
    val priceMinor: Long?,
    val checkedBy: String?,
    val updatedAt: Long,
    val revision: Long,
    val lastOperationId: String,
    val isDeleted: Boolean = false,
)
