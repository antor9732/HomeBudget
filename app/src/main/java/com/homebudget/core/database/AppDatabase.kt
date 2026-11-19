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

    companion object {
        const val VERSION = 2
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

        val MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_1_2)

        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, NAME)
                .addMigrations(*MIGRATIONS)
                .build()
    }
}
