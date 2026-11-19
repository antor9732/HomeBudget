package com.homebudget.data.repository

import androidx.room.withTransaction
import com.homebudget.core.common.Ids
import com.homebudget.core.common.LocalIdentity
import com.homebudget.core.common.TimeProvider
import com.homebudget.core.database.AccessState
import com.homebudget.core.database.AppDatabase
import com.homebudget.core.database.CategoryEntity
import com.homebudget.core.database.HouseholdEntity
import com.homebudget.core.database.MemberEntity
import com.homebudget.core.sync.LocalChangeWriter
import com.homebudget.data.mapper.toDomain
import com.homebudget.data.mapper.toDto
import com.homebudget.data.remote.CategoryDto
import com.homebudget.data.remote.EntityType
import com.homebudget.data.remote.HouseholdDto
import com.homebudget.data.remote.MemberDto
import com.homebudget.data.remote.OperationType
import com.homebudget.data.remote.SyncJson
import com.homebudget.domain.model.Household
import com.homebudget.domain.model.Member
import com.homebudget.domain.model.MemberStatus
import com.homebudget.domain.model.Role
import com.homebudget.domain.repository.HouseholdContext
import com.homebudget.domain.repository.HouseholdRepository
import com.homebudget.domain.repository.PermissionDeniedException
import com.homebudget.domain.repository.ValidationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class HouseholdRepositoryImpl @Inject constructor(
    private val db: AppDatabase,
    private val identity: LocalIdentity,
    private val writer: LocalChangeWriter,
    private val time: TimeProvider,
) : HouseholdRepository {

    override val context: Flow<HouseholdContext?> = identity.session
        .map { it.householdId to it.memberId }
        .distinctUntilChanged()
        .flatMapLatest { (hh, memberId) ->
            if (hh == null || memberId == null) flowOf(null)
            else combine(db.households().observe(hh), db.members().observe(memberId), db.sync().observeCursor(hh)) { h, m, c ->
                if (h == null || m == null) null
                else HouseholdContext(h.toDomain(), m.toDomain(), readOnly = c?.accessState == AccessState.ACCESS_REVOKED || m.status == MemberStatus.REMOVED)
            }
        }

    override suspend fun currentContext(): HouseholdContext? = context.first()

    override suspend fun repairSession() {
        val s = identity.currentSession()
        val hh = s.householdId ?: return
        val ok = db.households().get(hh) != null && s.memberId != null && db.members().get(s.memberId) != null
        if (!ok) identity.setHousehold(null, null)
    }

    private suspend fun requireContext(): HouseholdContext = currentContext() ?: throw IllegalStateException("No household")

    override suspend fun createLocalHousehold(name: String, ownerDisplayName: String, ownerEmail: String?, currencyCode: String): Household {
        if (name.isBlank()) throw ValidationException("Household name is required")
        val now = time.now().toEpochMilli()
        val hhId = Ids.random()
        val memberId = if (ownerEmail != null) Ids.member(hhId, ownerEmail) else Ids.random()
        val household = HouseholdEntity(hhId, name.trim(), memberId, currencyCode, null, now, now, 1, Ids.random())
        val owner = MemberEntity(
            id = memberId, householdId = hhId, email = ownerEmail?.lowercase(), googleSubject = null,
            displayName = ownerDisplayName.ifBlank { "Owner" }, role = Role.OWNER, status = MemberStatus.ACTIVE,
            joinedAt = now, removedAt = null, updatedAt = now, revision = 1, lastOperationId = Ids.random(),
        )
        db.withTransaction {
            writer.record(hhId, EntityType.HOUSEHOLD, hhId, OperationType.CREATE, household.lastOperationId, null, memberId,
                SyncJson.encodeToString(HouseholdDto.serializer(), household.toDto())) { db.households().upsert(household) }
            writer.record(hhId, EntityType.MEMBER, memberId, OperationType.CREATE, owner.lastOperationId, null, memberId,
                SyncJson.encodeToString(MemberDto.serializer(), owner.toDto())) { db.members().upsert(owner) }
            DefaultCategories.all.forEachIndexed { index, seed ->
                val c = CategoryEntity(
                    id = Ids.defaultCategory(hhId, seed.key), householdId = hhId, name = seed.name, type = seed.type,
                    iconKey = seed.icon, colorHex = seed.color, isActive = true, sortOrder = index, updatedAt = now,
                    revision = 1, lastOperationId = Ids.random(),
                )
                writer.record(hhId, EntityType.CATEGORY, c.id, OperationType.CREATE, c.lastOperationId, null, memberId,
                    SyncJson.encodeToString(CategoryDto.serializer(), c.toDto())) { db.categories().upsert(c) }
            }
        }
        identity.setHousehold(hhId, memberId)
        return household.toDomain()
    }

    override fun observeMembers(householdId: String): Flow<List<Member>> =
        db.members().observeAll(householdId).map { list -> list.map { it.toDomain() } }

    override suspend fun recordInvitedMember(email: String, role: Role) {
        val ctx = requireContext()
        if (!ctx.canManageMembers) throw PermissionDeniedException("Only owner/admin can invite members")
        if (role == Role.OWNER) throw ValidationException("A household has a single owner")
        val clean = email.trim().lowercase()
        if (!Regex("""^[^@\s]+@[^@\s]+\.[^@\s]+$""").matches(clean)) throw ValidationException("Enter a valid Google account email")
        val id = Ids.member(ctx.householdId, clean)
        val existing = db.members().get(id)
        val now = time.now().toEpochMilli()
        val opId = Ids.random()
        val entity = MemberEntity(
            id = id, householdId = ctx.householdId, email = clean, googleSubject = existing?.googleSubject,
            displayName = existing?.displayName ?: clean.substringBefore('@'), role = role, status = MemberStatus.INVITED,
            joinedAt = existing?.joinedAt ?: now, removedAt = null, updatedAt = now,
            revision = (existing?.revision ?: 0) + 1, lastOperationId = opId,
        )
        writeMember(ctx, entity, if (existing == null) OperationType.CREATE else OperationType.UPDATE, existing?.lastOperationId)
    }

    override suspend fun changeRole(memberId: String, role: Role) {
        val ctx = requireContext()
        if (!ctx.canManageMembers) throw PermissionDeniedException("Only owner/admin can change roles")
        val m = db.members().get(memberId) ?: throw ValidationException("Member not found")
        if (m.role == Role.OWNER || role == Role.OWNER) throw PermissionDeniedException("Owner role cannot be changed in v1.0")
        val opId = Ids.random()
        writeMember(ctx, m.copy(role = role, revision = m.revision + 1, lastOperationId = opId, updatedAt = time.now().toEpochMilli()), OperationType.UPDATE, m.lastOperationId)
    }

    override suspend fun markMemberRemoved(memberId: String) {
        val ctx = requireContext()
        if (!ctx.canManageMembers) throw PermissionDeniedException("Only owner/admin can remove members")
        val m = db.members().get(memberId) ?: throw ValidationException("Member not found")
        if (m.role == Role.OWNER) throw PermissionDeniedException("The owner cannot be removed")
        val now = time.now().toEpochMilli()
        val opId = Ids.random()
        writeMember(ctx, m.copy(status = MemberStatus.REMOVED, removedAt = now, revision = m.revision + 1, lastOperationId = opId, updatedAt = now), OperationType.UPDATE, m.lastOperationId)
    }

    /** Used by the cloud service: owner attaches their Google identity / invited member activates. */
    suspend fun updateOwnIdentity(email: String, subject: String?, displayName: String) {
        val ctx = requireContext()
        val m = db.members().get(ctx.member.id) ?: return
        val clean = email.lowercase()
        val newStatus = if (m.status == MemberStatus.INVITED) MemberStatus.ACTIVE else m.status
        if (m.email == clean && m.googleSubject == subject && m.status == newStatus && m.displayName == displayName) return
        val opId = Ids.random()
        val updated = m.copy(email = clean, googleSubject = subject, displayName = displayName, status = newStatus,
            revision = m.revision + 1, lastOperationId = opId, updatedAt = time.now().toEpochMilli())
        writer.record(ctx.householdId, EntityType.MEMBER, m.id, OperationType.UPDATE, opId, m.lastOperationId, m.id,
            SyncJson.encodeToString(MemberDto.serializer(), updated.toDto())) { db.members().upsert(updated) }
    }

    suspend fun setDriveFolder(folderId: String) {
        val ctx = requireContext()
        val h = db.households().get(ctx.householdId) ?: return
        if (h.driveFolderId == folderId) return
        val opId = Ids.random()
        val updated = h.copy(driveFolderId = folderId, revision = h.revision + 1, lastOperationId = opId, updatedAt = time.now().toEpochMilli())
        writer.record(h.id, EntityType.HOUSEHOLD, h.id, OperationType.UPDATE, opId, h.lastOperationId, ctx.member.id,
            SyncJson.encodeToString(HouseholdDto.serializer(), updated.toDto())) { db.households().upsert(updated) }
    }

    override suspend fun renameHousehold(name: String) {
        val ctx = requireContext()
        if (ctx.readOnly || !ctx.role.canManageHousehold) throw PermissionDeniedException("Only the owner can rename the household")
        if (name.isBlank()) throw ValidationException("Name is required")
        val h = db.households().get(ctx.householdId) ?: return
        val opId = Ids.random()
        val updated = h.copy(name = name.trim(), revision = h.revision + 1, lastOperationId = opId, updatedAt = time.now().toEpochMilli())
        writer.record(h.id, EntityType.HOUSEHOLD, h.id, OperationType.UPDATE, opId, h.lastOperationId, ctx.member.id,
            SyncJson.encodeToString(HouseholdDto.serializer(), updated.toDto())) { db.households().upsert(updated) }
    }

    private suspend fun writeMember(ctx: HouseholdContext, entity: MemberEntity, type: OperationType, base: String?) {
        writer.record(ctx.householdId, EntityType.MEMBER, entity.id, type, entity.lastOperationId, base, ctx.member.id,
            SyncJson.encodeToString(MemberDto.serializer(), entity.toDto())) { db.members().upsert(entity) }
    }
}
