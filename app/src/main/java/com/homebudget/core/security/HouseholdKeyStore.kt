package com.homebudget.core.security

import com.homebudget.core.database.HouseholdKeyEntity
import com.homebudget.core.database.SyncDao
import javax.inject.Inject
import javax.inject.Singleton

/** Stores/loads the household data key wrapped with the device key. */
@Singleton
class HouseholdKeyStore @Inject constructor(
    private val syncDao: SyncDao,
    private val wrapper: DeviceKeyWrapper,
) {
    suspend fun save(householdId: String, key: ByteArray, keyVersion: Int) {
        syncDao.upsertKey(HouseholdKeyEntity(householdId, wrapper.wrap(key), keyVersion))
    }

    suspend fun load(householdId: String): Pair<ByteArray, Int>? =
        syncDao.key(householdId)?.let { wrapper.unwrap(it.wrappedKey) to it.keyVersion }
}
