package com.homebudget.data.repository

import com.homebudget.core.common.Ids
import com.homebudget.core.common.TimeProvider
import com.homebudget.core.database.AppDatabase
import com.homebudget.core.database.ShoppingItemEntity
import com.homebudget.core.database.ShoppingListEntity
import com.homebudget.core.sync.LocalChangeWriter
import com.homebudget.data.mapper.toDomain
import com.homebudget.data.mapper.toDto
import com.homebudget.data.remote.EntityType
import com.homebudget.data.remote.OperationType
import com.homebudget.data.remote.ShoppingItemDto
import com.homebudget.data.remote.ShoppingListDto
import com.homebudget.data.remote.SyncJson
import com.homebudget.domain.model.ItemListParser
import com.homebudget.domain.model.ShoppingFinishResult
import com.homebudget.domain.model.ShoppingList
import com.homebudget.domain.model.ShoppingStatus
import com.homebudget.domain.model.TransactionDraft
import com.homebudget.domain.model.TransactionItem
import com.homebudget.domain.model.TransactionType
import com.homebudget.domain.repository.FinanceRepository
import com.homebudget.domain.repository.HouseholdContext
import com.homebudget.domain.repository.HouseholdRepository
import com.homebudget.domain.repository.PermissionDeniedException
import com.homebudget.domain.repository.ShoppingRepository
import com.homebudget.domain.repository.ValidationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ShoppingRepositoryImpl @Inject constructor(
    private val db: AppDatabase,
    private val households: HouseholdRepository,
    private val finance: FinanceRepository,
    private val writer: LocalChangeWriter,
    private val time: TimeProvider,
) : ShoppingRepository {

    private fun now() = time.now().toEpochMilli()

    private suspend fun editor(): HouseholdContext {
        val ctx = households.currentContext() ?: throw IllegalStateException("No household")
        if (!ctx.canEditTransactions) throw PermissionDeniedException("Your role cannot change bazar lists")
        return ctx
    }

    // ------------------------------------------------------------------------------ reading

    override fun observeLists(householdId: String): Flow<List<ShoppingList>> =
        combine(db.shopping().observeLists(householdId), db.shopping().observeAllItems(householdId)) { lists, items ->
            val byList = items.groupBy { it.listId }
            lists.map { l -> l.toDomain(byList[l.id].orEmpty().sortedBy { it.position }.map { it.toDomain() }) }
        }

    override fun observeList(listId: String): Flow<ShoppingList?> =
        combine(db.shopping().observeList(listId), db.shopping().observeItems(listId)) { l, items ->
            l?.takeIf { !it.isDeleted }?.toDomain(items.map { it.toDomain() })
        }

    // ------------------------------------------------------------------------------ lists

    private suspend fun saveList(ctx: HouseholdContext, e: ShoppingListEntity, type: OperationType, base: String?) {
        writer.record(ctx.householdId, EntityType.SHOPPING_LIST, e.id, type, e.lastOperationId, base, ctx.member.id,
            SyncJson.encodeToString(ShoppingListDto.serializer(), e.toDto())) { db.shopping().upsertList(e) }
    }

    private fun cleanTitle(title: String): String {
        val t = title.trim()
        if (t.isEmpty()) throw ValidationException("Give the list a name")
        if (t.length > 80) throw ValidationException("List name is too long")
        return t
    }

    override suspend fun createList(title: String): String {
        val ctx = editor()
        val now = now()
        val e = ShoppingListEntity(Ids.random(), ctx.householdId, cleanTitle(title), ShoppingStatus.OPEN, ctx.member.id, now, now, null, 1, Ids.random())
        saveList(ctx, e, OperationType.CREATE, null)
        return e.id
    }

    private suspend fun openList(ctx: HouseholdContext, listId: String): ShoppingListEntity {
        val l = db.shopping().getList(listId) ?: throw ValidationException("List not found")
        if (l.householdId != ctx.householdId || l.isDeleted) throw ValidationException("List not found")
        return l
    }

    override suspend fun renameList(listId: String, title: String) {
        val ctx = editor()
        val old = openList(ctx, listId)
        val e = old.copy(title = cleanTitle(title), updatedAt = now(), revision = old.revision + 1, lastOperationId = Ids.random())
        saveList(ctx, e, OperationType.UPDATE, old.lastOperationId)
    }

    override suspend fun deleteList(listId: String) {
        val ctx = editor()
        val old = openList(ctx, listId)
        val e = old.copy(isDeleted = true, updatedAt = now(), revision = old.revision + 1, lastOperationId = Ids.random())
        saveList(ctx, e, OperationType.DELETE, old.lastOperationId)
    }

    // ------------------------------------------------------------------------------ items

    private suspend fun saveItem(ctx: HouseholdContext, e: ShoppingItemEntity, type: OperationType, base: String?) {
        writer.record(ctx.householdId, EntityType.SHOPPING_ITEM, e.id, type, e.lastOperationId, base, ctx.member.id,
            SyncJson.encodeToString(ShoppingItemDto.serializer(), e.toDto())) { db.shopping().upsertItem(e) }
        touchList(e.listId)
    }

    /** Keeps "recently changed" ordering without a sync operation (updatedAt of the list is device-local here). */
    private suspend fun touchList(listId: String) {
        db.shopping().getList(listId)?.let { db.shopping().upsertList(it.copy(updatedAt = maxOf(it.updatedAt, now()))) }
    }

    private suspend fun editableItem(ctx: HouseholdContext, itemId: String): ShoppingItemEntity {
        val item = db.shopping().getItem(itemId) ?: throw ValidationException("Item not found")
        if (item.householdId != ctx.householdId || item.isDeleted) throw ValidationException("Item not found")
        val list = openList(ctx, item.listId)
        if (list.status == ShoppingStatus.DONE) throw ValidationException("This bazar is already finished")
        return item
    }

    private fun cleanName(name: String): String {
        val n = name.trim()
        if (n.isEmpty()) throw ValidationException("Item name is required")
        if (n.length > ItemListParser.MAX_NAME) throw ValidationException("Item name is too long")
        return n
    }

    private fun cleanQty(q: String?): String? {
        val t = q?.trim()?.ifEmpty { null } ?: return null
        if (t.length > 30) throw ValidationException("Quantity is too long")
        return t
    }

    private suspend fun expenseCategoryOrDefault(ctx: HouseholdContext, categoryId: String?): String {
        if (categoryId != null) {
            val c = db.categories().get(categoryId)
            if (c != null && c.householdId == ctx.householdId && !c.isDeleted && c.type == TransactionType.EXPENSE) return c.id
            throw ValidationException("Choose an expense category")
        }
        return defaultCategory(ctx)
    }

    /** Grocery if it exists and is active, otherwise the first active expense category. */
    private suspend fun defaultCategory(ctx: HouseholdContext): String {
        val grocery = db.categories().get(Ids.defaultCategory(ctx.householdId, "grocery"))
        if (grocery != null && grocery.isActive && !grocery.isDeleted) return grocery.id
        return db.categories().all(ctx.householdId).filter { it.type == TransactionType.EXPENSE && it.isActive && !it.isDeleted }
            .minByOrNull { it.sortOrder }?.id ?: throw ValidationException("Create an expense category first")
    }

    override suspend fun addItem(listId: String, name: String, quantity: String?, categoryId: String?): String {
        val ctx = editor()
        val list = openList(ctx, listId)
        if (list.status == ShoppingStatus.DONE) throw ValidationException("This bazar is already finished")
        if (db.shopping().items(listId).size >= ItemListParser.MAX_ITEMS) throw ValidationException("A list can hold ${ItemListParser.MAX_ITEMS} items")
        val e = ShoppingItemEntity(
            id = Ids.random(), householdId = ctx.householdId, listId = listId, name = cleanName(name), quantity = cleanQty(quantity),
            categoryId = expenseCategoryOrDefault(ctx, categoryId), position = db.shopping().maxPosition(listId) + 1,
            isChecked = false, priceMinor = null, checkedBy = null, updatedAt = now(), revision = 1, lastOperationId = Ids.random(),
        )
        saveItem(ctx, e, OperationType.CREATE, null)
        return e.id
    }

    override suspend fun updateItem(itemId: String, name: String, quantity: String?, categoryId: String) {
        val ctx = editor()
        val old = editableItem(ctx, itemId)
        val e = old.copy(name = cleanName(name), quantity = cleanQty(quantity), categoryId = expenseCategoryOrDefault(ctx, categoryId),
            updatedAt = now(), revision = old.revision + 1, lastOperationId = Ids.random())
        saveItem(ctx, e, OperationType.UPDATE, old.lastOperationId)
    }

    override suspend fun setBought(itemId: String, bought: Boolean, priceMinor: Long?) {
        val ctx = editor()
        val old = editableItem(ctx, itemId)
        if (priceMinor != null && priceMinor <= 0) throw ValidationException("Price must be greater than zero")
        val e = old.copy(
            isChecked = bought, priceMinor = if (bought) priceMinor ?: old.priceMinor else old.priceMinor,
            checkedBy = if (bought) ctx.member.id else null,
            updatedAt = now(), revision = old.revision + 1, lastOperationId = Ids.random(),
        )
        saveItem(ctx, e, OperationType.UPDATE, old.lastOperationId)
    }

    override suspend fun deleteItem(itemId: String) {
        val ctx = editor()
        val old = editableItem(ctx, itemId)
        val e = old.copy(isDeleted = true, updatedAt = now(), revision = old.revision + 1, lastOperationId = Ids.random())
        saveItem(ctx, e, OperationType.DELETE, old.lastOperationId)
    }

    // ------------------------------------------------------------------------------ finish

    override suspend fun finish(listId: String, carryOver: Boolean): ShoppingFinishResult {
        val ctx = editor()
        val list = openList(ctx, listId)
        if (list.status == ShoppingStatus.DONE) throw ValidationException("This bazar is already finished")
        val items = db.shopping().items(listId)
        val bought = items.filter { it.isChecked }
        if (bought.isEmpty()) throw ValidationException("Tick the items you bought first")
        val noPrice = bought.filter { (it.priceMinor ?: 0L) <= 0L }
        if (noPrice.isNotEmpty()) throw ValidationException("Enter the price for: " + noPrice.take(3).joinToString { it.name } + if (noPrice.size > 3) "…" else "")

        // Items whose category was archived or removed meanwhile go to the default category.
        var fallback: String? = null
        val resolved = ArrayList<Pair<ShoppingItemEntity, String>>()
        for (item in bought) {
            val c = db.categories().get(item.categoryId)
            val ok = c != null && c.householdId == ctx.householdId && !c.isDeleted && c.isActive && c.type == TransactionType.EXPENSE
            resolved += item to (if (ok) item.categoryId else fallback ?: defaultCategory(ctx).also { fallback = it })
        }
        val groups = resolved.groupBy({ it.second }, { it.first })
        val today = time.today()
        var total = 0L
        var created = 0
        for ((categoryId, group) in groups) {
            if (group.size > ItemListParser.MAX_ITEMS) throw ValidationException("Too many items in one category")
            val txId = Ids.bazarExpense(listId, categoryId)
            val sum = group.sumOf { it.priceMinor!! }
            total += sum
            val existing = db.transactions().get(txId)
            if (existing != null && !existing.isDeleted) continue // already recorded (finished earlier on this or another phone)
            val catName = db.categories().get(categoryId)?.name
            val title = (if (groups.size > 1 && catName != null) "${list.title} · $catName" else list.title).take(120)
            val lines = group.sortedBy { it.position }.map { TransactionItem(label(it), it.priceMinor!!) }
            finance.addTransaction(
                TransactionDraft(TransactionType.EXPENSE, title, sum, categoryId, today, "From bazar list", lines),
                fixedId = txId,
            )
            created++
        }

        val done = list.copy(status = ShoppingStatus.DONE, completedAt = now(), updatedAt = now(), revision = list.revision + 1, lastOperationId = Ids.random())
        saveList(ctx, done, OperationType.UPDATE, list.lastOperationId)

        val left = items.filter { !it.isChecked }
        var newListId: String? = null
        if (carryOver && left.isNotEmpty()) {
            newListId = createList(remainingTitle(list.title))
            left.sortedBy { it.position }.forEach { addItem(newListId, it.name, it.quantity, it.categoryId.takeIf { id -> db.categories().get(id)?.let { c -> c.isActive && !c.isDeleted } == true }) }
        }
        return ShoppingFinishResult(created, total, if (newListId != null) left.size else 0, newListId)
    }

    private fun label(i: ShoppingItemEntity): String {
        val q = i.quantity?.let { " ($it)" } ?: ""
        val full = i.name + q
        return if (full.length <= ItemListParser.MAX_NAME) full else i.name.take(ItemListParser.MAX_NAME)
    }

    private fun remainingTitle(title: String): String {
        val base = title.removeSuffix(" (remaining)")
        return (base.take(80 - " (remaining)".length) + " (remaining)")
    }
}
