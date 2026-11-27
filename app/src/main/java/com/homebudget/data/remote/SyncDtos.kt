package com.homebudget.data.remote

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

const val SCHEMA_VERSION = 1

enum class EntityType(val applyOrder: Int) {
    HOUSEHOLD(0), MEMBER(1), CATEGORY(2), BUDGET(3), OPENING_BALANCE(4), TRANSACTION(5), SHOPPING_LIST(6), SHOPPING_ITEM(7)
}

enum class OperationType { CREATE, UPDATE, DELETE }

val SyncJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
}

/* Remote DTOs: full entity state after the operation (state-based operations with revisions). */

@Serializable
data class HouseholdDto(
    val id: String, val name: String, val ownerMemberId: String, val currencyCode: String,
    val driveFolderId: String? = null, val createdAt: Long, val updatedAt: Long, val revision: Long, val isDeleted: Boolean = false,
)

@Serializable
data class MemberDto(
    val id: String, val householdId: String, val email: String? = null, val googleSubject: String? = null,
    val displayName: String, val role: String, val status: String, val joinedAt: Long, val removedAt: Long? = null,
    val updatedAt: Long, val revision: Long, val isDeleted: Boolean = false,
)

@Serializable
data class CategoryDto(
    val id: String, val householdId: String, val name: String, val type: String, val iconKey: String,
    val colorHex: String, val isActive: Boolean, val sortOrder: Int, val updatedAt: Long, val revision: Long,
    val isDeleted: Boolean = false,
)

@Serializable
data class BudgetDto(
    val id: String, val householdId: String, val year: Int, val month: Int, val amountMinor: Long,
    val updatedAt: Long, val revision: Long, val isDeleted: Boolean = false, val isSet: Boolean = true,
)

@Serializable
data class TransactionItemDto(val name: String, val amountMinor: Long)

@Serializable
data class TransactionDto(
    val id: String, val householdId: String, val categoryId: String, val type: String, val title: String,
    val amountMinor: Long, val currencyCode: String, val transactionDate: Long, val description: String? = null,
    val createdBy: String, val deviceId: String, val createdAt: Long, val updatedAt: Long, val revision: Long,
    val isDeleted: Boolean = false,
    /** Optional line items; when present, amountMinor == sum of item amounts. Older apps ignore it. */
    val items: List<TransactionItemDto> = emptyList(),
)

@Serializable
data class ShoppingListDto(
    val id: String, val householdId: String, val title: String, val status: String, val createdBy: String,
    val createdAt: Long, val updatedAt: Long, val completedAt: Long? = null, val revision: Long, val isDeleted: Boolean = false,
)

@Serializable
data class ShoppingItemDto(
    val id: String, val householdId: String, val listId: String, val name: String, val quantity: String? = null,
    val categoryId: String, val position: Int, val isChecked: Boolean, val priceMinor: Long? = null, val checkedBy: String? = null,
    val updatedAt: Long, val revision: Long, val isDeleted: Boolean = false,
)

/** Decrypted logical operation (matches the envelope in the plan, Section 9). */
@Serializable
data class OperationEnvelope(
    val operationId: String,
    val householdId: String,
    val entityType: String,
    val entityId: String,
    val operationType: String,
    val deviceId: String,
    val actorMemberId: String,
    val baseOperationId: String? = null,
    val schemaVersion: Int,
    val createdAt: Long,
    /** JSON string of the entity DTO. */
    val payload: String,
)

/** What is actually stored in Drive: routing metadata in clear, payload AES-256-GCM encrypted. */
@Serializable
data class RemoteOperationFile(
    val format: String = "homebudget-op",
    val operationId: String,
    val householdId: String,
    val entityType: String,
    val entityId: String,
    val operationType: String,
    val deviceId: String,
    val actorMemberId: String,
    val baseOperationId: String? = null,
    val schemaVersion: Int,
    val createdAt: Long,
    val keyVersion: Int,
    val nonce: String,
    val ciphertext: String,
) {
    /** Associated data binds the clear metadata to the ciphertext so it cannot be tampered with. */
    fun aad(): ByteArray = listOf(operationId, householdId, entityType, entityId, operationType, deviceId, actorMemberId, baseOperationId ?: "-", schemaVersion.toString(), createdAt.toString())
        .joinToString("|").toByteArray(Charsets.UTF_8)
}

@Serializable
data class HouseholdManifest(
    val format: String = "homebudget-household",
    val householdId: String,
    val name: String,
    val ownerEmail: String,
    val currencyCode: String,
    val schemaVersion: Int,
    val createdAt: Long,
    val operationsFolderId: String,
    val snapshotsFolderId: String,
    val metadataFolderId: String,
)

/** Household key protected by a recovery passphrase (PBKDF2-HMAC-SHA256 -> AES-256-GCM key wrap). */
@Serializable
data class KeyEnvelope(
    val format: String = "homebudget-key",
    val householdId: String,
    val keyVersion: Int,
    val kdf: String = "PBKDF2WithHmacSHA256",
    val iterations: Int,
    val salt: String,
    val nonce: String,
    val wrappedKey: String,
)

@Serializable
data class SnapshotFile(
    val format: String = "homebudget-snapshot",
    val householdId: String,
    val createdAt: Long,
    val createdByDevice: String,
    val keyVersion: Int,
    val nonce: String,
    val ciphertext: String,
    /** SHA-256 of the plaintext snapshot, verified on read. */
    val sha256: String,
)

@Serializable
data class SnapshotContent(
    val household: HouseholdDto,
    val members: List<MemberDto>,
    val categories: List<CategoryDto>,
    val budgets: List<BudgetDto>,
    val openingBalances: List<BudgetDto>,
    val transactions: List<TransactionDto>,
    val appliedOperationIds: List<String>,
)

/** One operation inside the household ledger. [author] is the Google account that added it. */
@Serializable
data class LedgerEntry(val author: String, val op: RemoteOperationFile)

/**
 * The whole household in one shared, encrypted Drive file (v1.2+). Members get access by picking
 * this single file in the Google Picker, which the `drive.file` scope supports.
 * Entries are an append-only, grow-only set keyed by operation id: every device re-adds its own
 * operations if a concurrent upload dropped them, so the file converges without locking.
 */
@Serializable
data class HouseholdLedger(
    val format: String = LEDGER_FORMAT,
    val schemaVersion: Int,
    val householdId: String,
    val name: String,
    val ownerEmail: String,
    val currencyCode: String,
    val createdAt: Long,
    /** Household key protected by a recovery passphrase (optional, "extra lock"). */
    val keyEnvelope: KeyEnvelope? = null,
    /**
     * Household key in base64 when no passphrase is set (default, zero-setup). The file is then
     * protected by Google Drive sharing only: just the owner and invited accounts can open it.
     */
    val openKey: String? = null,
    val entries: List<LedgerEntry> = emptyList(),
) {
    val needsPassphrase: Boolean get() = openKey == null
}

const val LEDGER_FORMAT = "homebudget-ledger"
