package com.homebudget.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        HouseholdEntity::class, MemberEntity::class, BudgetEntity::class, OpeningBalanceEntity::class,
        CategoryEntity::class, TransactionEntity::class, TransactionItemEntity::class, SyncOperationEntity::class, AppliedOperationEntity::class,
        DeferredOperationEntity::class, SyncCursorEntity::class, HouseholdKeyEntity::class, ConflictEntity::class,
        ShoppingListEntity::class, ShoppingItemEntity::class,
    ],
    version = AppDatabase.VERSION,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun households(): HouseholdDao
    abstract fun members(): MemberDao
    abstract fun categories(): CategoryDao
    abstract fun budgets(): BudgetDao
    abstract fun openingBalances(): OpeningBalanceDao
    abstract fun transactions(): TransactionDao
    abstract fun transactionItems(): TransactionItemDao
    abstract fun sync(): SyncDao
    abstract fun conflicts(): ConflictDao
    abstract fun shopping(): ShoppingDao

    companion object {
        const val VERSION = 3
        const val NAME = "homebudget.db"

        /** Explicit migrations only; destructive migration is never enabled (financial data). */
        /** v2: itemized transactions. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `transaction_items` (`id` TEXT NOT NULL, `transactionId` TEXT NOT NULL, " +
                        "`position` INTEGER NOT NULL, `name` TEXT NOT NULL, `amountMinor` INTEGER NOT NULL, PRIMARY KEY(`id`), " +
                        "FOREIGN KEY(`transactionId`) REFERENCES `transactions`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_transaction_items_transactionId` ON `transaction_items` (`transactionId`)")
            }
        }

        /**
         * v3: shared bazar lists. Operations that older versions rejected as an unknown type are
         * forgotten so they are read again (and applied) on the next sync.
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `shopping_lists` (`id` TEXT NOT NULL, `householdId` TEXT NOT NULL, `title` TEXT NOT NULL, " +
                        "`status` TEXT NOT NULL, `createdBy` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, " +
                        "`completedAt` INTEGER, `revision` INTEGER NOT NULL, `lastOperationId` TEXT NOT NULL, `isDeleted` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`id`), FOREIGN KEY(`householdId`) REFERENCES `households`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_shopping_lists_householdId` ON `shopping_lists` (`householdId`)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `shopping_items` (`id` TEXT NOT NULL, `householdId` TEXT NOT NULL, `listId` TEXT NOT NULL, " +
                        "`name` TEXT NOT NULL, `quantity` TEXT, `categoryId` TEXT NOT NULL, `position` INTEGER NOT NULL, `isChecked` INTEGER NOT NULL, " +
                        "`priceMinor` INTEGER, `checkedBy` TEXT, `updatedAt` INTEGER NOT NULL, `revision` INTEGER NOT NULL, " +
                        "`lastOperationId` TEXT NOT NULL, `isDeleted` INTEGER NOT NULL, PRIMARY KEY(`id`), " +
                        "FOREIGN KEY(`householdId`) REFERENCES `households`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_shopping_items_householdId` ON `shopping_items` (`householdId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_shopping_items_listId` ON `shopping_items` (`listId`)")
                db.execSQL("DELETE FROM applied_operations WHERE source = 'REJECTED'")
            }
        }

        val MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3)

        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, NAME)
                .addMigrations(*MIGRATIONS)
                .build()
    }
}
