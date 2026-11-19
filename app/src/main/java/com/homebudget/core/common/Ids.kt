package com.homebudget.core.common

import java.util.UUID

object Ids {
    fun random(): String = UUID.randomUUID().toString()

    /** Deterministic IDs so two devices creating the same logical record converge on one entity. */
    fun budget(householdId: String, year: Int, month: Int): String =
        nameUuid("budget:$householdId:$year-$month")

    fun openingBalance(householdId: String, year: Int, month: Int): String =
        nameUuid("opening:$householdId:$year-$month")

    fun member(householdId: String, email: String): String =
        nameUuid("member:$householdId:${email.trim().lowercase()}")

    fun defaultCategory(householdId: String, key: String): String =
        nameUuid("category:$householdId:default:$key")

    private fun nameUuid(s: String): String = UUID.nameUUIDFromBytes(s.toByteArray(Charsets.UTF_8)).toString()
}
