package com.homebudget.data

import android.app.Application
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import com.homebudget.core.database.AppDatabase
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Validates the exported Room schema and every registered migration up to the current version.
 * Add a `migrate(N -> N+1)` assertion here for each new migration.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class MigrationTest {
    private val name = "migration-test.db"

    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java)

    @Test
    fun migrate1To2KeepsDataAndAddsItemsTable() {
        helper.createDatabase(name, 1).apply {
            execSQL("INSERT INTO households (id,name,ownerMemberId,currencyCode,driveFolderId,createdAt,updatedAt,revision,lastOperationId,isDeleted) VALUES ('h','Home','m','BDT',NULL,1,1,1,'o',0)")
            execSQL("INSERT INTO categories (id,householdId,name,type,iconKey,colorHex,isActive,sortOrder,updatedAt,revision,lastOperationId,isDeleted) VALUES ('c','h','Grocery','EXPENSE','grocery','#000000',1,0,1,1,'o',0)")
            execSQL("INSERT INTO transactions (id,householdId,categoryId,type,title,amountMinor,currencyCode,transactionDate,description,createdBy,deviceId,createdAt,updatedAt,revision,lastOperationId,isDeleted) VALUES ('t','h','c','EXPENSE','Bazar',500000,'BDT',20000,NULL,'m','d',1,1,1,'o',0)")
            close()
        }
        val db = helper.runMigrationsAndValidate(name, 2, true, AppDatabase.MIGRATION_1_2)
        db.query("SELECT amountMinor FROM transactions WHERE id = 't'").use { c -> c.moveToFirst(); assertEquals(500000L, c.getLong(0)) }
        db.execSQL("INSERT INTO transaction_items (id,transactionId,position,name,amountMinor) VALUES ('t:0','t',0,'Rice',45000)")
        db.close()
    }

    @Test
    fun exportedSchemaMatchesEntitiesAndOpensWithMigrations() {
        helper.createDatabase("fresh-$name", 1).close()
        val db = Room.databaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, AppDatabase::class.java, "fresh-$name")
            .addMigrations(*AppDatabase.MIGRATIONS).build()
        // Opening validates the on-disk schema against the compiled entities (throws on mismatch).
        assertEquals(AppDatabase.VERSION, db.openHelper.writableDatabase.version)
        db.close()
    }
}
