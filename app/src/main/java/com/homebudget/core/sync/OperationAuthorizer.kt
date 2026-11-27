package com.homebudget.core.sync

import com.homebudget.core.common.Ids
import com.homebudget.core.database.MemberEntity
import com.homebudget.data.remote.EntityType
import com.homebudget.data.remote.MemberDto
import com.homebudget.data.remote.OperationEnvelope
import com.homebudget.data.remote.OperationType
import com.homebudget.data.remote.SCHEMA_VERSION
import com.homebudget.data.remote.SyncJson
import com.homebudget.data.remote.TransactionDto
import com.homebudget.data.remote.BudgetDto
import com.homebudget.data.remote.CategoryDto
import com.homebudget.data.remote.HouseholdDto
import com.homebudget.data.remote.ShoppingItemDto
import com.homebudget.data.remote.ShoppingListDto
import com.homebudget.domain.model.ShoppingStatus
import com.homebudget.domain.model.MemberStatus
import com.homebudget.domain.model.Role

sealed interface AuthDecision {
    data object Allowed : AuthDecision
    data class Rejected(val reason: String) : AuthDecision
}

/**
 * Application-level authorization for remote operations. Drive sharing alone is not trusted:
 * the author identity is the Drive-attested file owner email (not a value inside the payload),
 * and it must map to an authorized household member whose role permits the change.
 */
object OperationAuthorizer {

    fun validateShape(env: OperationEnvelope, expectedHouseholdId: String): String? {
        if (env.householdId != expectedHouseholdId) return "household mismatch"
        if (env.schemaVersion < 1 || env.schemaVersion > SCHEMA_VERSION) return "unsupported schema ${env.schemaVersion}"
        val type = runCatching { EntityType.valueOf(env.entityType) }.getOrNull() ?: return "unknown entity type"
        runCatching { OperationType.valueOf(env.operationType) }.getOrNull() ?: return "unknown operation type"
        return try {
            when (type) {
                EntityType.HOUSEHOLD -> SyncJson.decodeFromString<HouseholdDto>(env.payload).let { check(it.id == env.entityId && it.id == env.householdId) }
                EntityType.MEMBER -> SyncJson.decodeFromString<MemberDto>(env.payload).let {
                    check(it.id == env.entityId && it.householdId == env.householdId)
                    Role.valueOf(it.role); MemberStatus.valueOf(it.status)
                }
                EntityType.CATEGORY -> SyncJson.decodeFromString<CategoryDto>(env.payload).let {
                    check(it.id == env.entityId && it.householdId == env.householdId && it.name.isNotBlank())
                }
                EntityType.BUDGET, EntityType.OPENING_BALANCE -> SyncJson.decodeFromString<BudgetDto>(env.payload).let {
                    check(it.id == env.entityId && it.householdId == env.householdId && it.month in 1..12)
                    if (type == EntityType.BUDGET) check(it.amountMinor >= 0)
                }
                EntityType.TRANSACTION -> SyncJson.decodeFromString<TransactionDto>(env.payload).let {
                    check(it.id == env.entityId && it.householdId == env.householdId && it.amountMinor > 0 && it.title.isNotBlank())
                    if (it.items.isNotEmpty()) {
                        check(it.items.size <= 200 && it.items.all { i -> i.amountMinor > 0 && i.name.isNotBlank() })
                        check(it.items.sumOf { i -> i.amountMinor } == it.amountMinor)
                    }
                }
                EntityType.SHOPPING_LIST -> SyncJson.decodeFromString<ShoppingListDto>(env.payload).let {
                    check(it.id == env.entityId && it.householdId == env.householdId && it.title.isNotBlank() && it.title.length <= 80)
                    ShoppingStatus.valueOf(it.status)
                }
                EntityType.SHOPPING_ITEM -> SyncJson.decodeFromString<ShoppingItemDto>(env.payload).let {
                    check(it.id == env.entityId && it.householdId == env.householdId && it.name.isNotBlank() && it.name.length <= 60)
                    check((it.quantity?.length ?: 0) <= 30 && it.position >= 0 && (it.priceMinor == null || it.priceMinor >= 0))
                }
            }
            null
        } catch (e: Exception) {
            "invalid payload: ${e.javaClass.simpleName}"
        }
    }

    /**
     * @param authorEmail Drive-attested creator of the operation file.
     * @param ownerEmail Drive-attested owner of the household folder.
     * @param members current local member table.
     * @param existingTransactionCreator createdBy of the existing transaction (for MEMBER-role edits).
     */
    fun authorize(
        env: OperationEnvelope,
        authorEmail: String?,
        ownerEmail: String,
        members: List<MemberEntity>,
        remoteCreatedAt: Long,
        existingTransactionCreator: String?,
    ): AuthDecision {
        if (authorEmail.isNullOrBlank()) return AuthDecision.Rejected("author not attested by Drive")
        val type = EntityType.valueOf(env.entityType)

        if (authorEmail.equals(ownerEmail, ignoreCase = true)) {
            // The household owner may perform any change except demoting/removing themselves through MEMBER ops.
            return AuthDecision.Allowed
        }

        val member = members.firstOrNull { it.email.equals(authorEmail, ignoreCase = true) && !it.isDeleted }
            ?: return AuthDecision.Rejected("author is not a household member")
        val removedBefore = member.status == MemberStatus.REMOVED && (member.removedAt == null || remoteCreatedAt >= member.removedAt)
        if (removedBefore) return AuthDecision.Rejected("author was removed from household")
        if (env.actorMemberId != member.id) return AuthDecision.Rejected("actor does not match Drive author")

        return when (type) {
            EntityType.HOUSEHOLD -> AuthDecision.Rejected("only owner can change household")
            EntityType.MEMBER -> authorizeMemberChange(env, member, authorEmail, members)
            EntityType.CATEGORY, EntityType.BUDGET, EntityType.OPENING_BALANCE ->
                if (member.role.canManageFinanceSetup) AuthDecision.Allowed else AuthDecision.Rejected("role ${member.role} cannot change ${type.name}")
            EntityType.TRANSACTION -> when {
                member.role == Role.ADMIN || member.role == Role.OWNER -> AuthDecision.Allowed
                member.role == Role.MEMBER -> {
                    val dto = SyncJson.decodeFromString<TransactionDto>(env.payload)
                    val creator = existingTransactionCreator ?: dto.createdBy
                    if (creator == member.id && dto.createdBy == member.id) AuthDecision.Allowed
                    else AuthDecision.Rejected("members may only change their own transactions")
                }
                else -> AuthDecision.Rejected("viewer cannot change transactions")
            }
            // Bazar lists are shared work: anyone who can add transactions may edit any list or item.
            EntityType.SHOPPING_LIST, EntityType.SHOPPING_ITEM ->
                if (member.role.canEditTransactions) AuthDecision.Allowed else AuthDecision.Rejected("viewer cannot change bazar lists")
        }
    }

    private fun authorizeMemberChange(env: OperationEnvelope, actor: MemberEntity, authorEmail: String, members: List<MemberEntity>): AuthDecision {
        val dto = SyncJson.decodeFromString<MemberDto>(env.payload)
        // Self-activation: an invited member records their own join (same role, not removed).
        val isSelf = dto.email.equals(authorEmail, ignoreCase = true) && dto.id == actor.id &&
            dto.id == Ids.member(env.householdId, authorEmail)
        if (isSelf && dto.role == actor.role.name && dto.status != MemberStatus.REMOVED.name && !dto.isDeleted) {
            return AuthDecision.Allowed
        }
        if (!actor.role.canManageMembers) return AuthDecision.Rejected("role ${actor.role} cannot manage members")
        if (dto.role == Role.OWNER.name) return AuthDecision.Rejected("only owner can assign owner role")
        val target = members.firstOrNull { it.id == dto.id }
        if (target?.role == Role.OWNER) return AuthDecision.Rejected("only owner can change the owner record")
        return AuthDecision.Allowed
    }
}
