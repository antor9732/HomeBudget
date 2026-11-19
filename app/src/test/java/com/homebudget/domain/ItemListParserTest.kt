package com.homebudget.domain

import com.homebudget.domain.model.ItemListParser
import com.homebudget.domain.model.TransactionItem
import org.junit.Assert.assertEquals
import org.junit.Test

class ItemListParserTest {
    @Test fun `parses english bangla and mixed formats`() {
        val r = ItemListParser.parse(
            """
            Rice 5kg 450
            তেল ২ লিটার - ৩৮০
            Eggs: 150.50
            Fish ৳ 600
            1. Onion 2kg 120 tk
            - Salt 40
            no price here
            Sugar 1,250
            """.trimIndent()
        )
        assertEquals(
            listOf(
                TransactionItem("Rice 5kg", 45_000), TransactionItem("তেল 2 লিটার", 38_000), TransactionItem("Eggs", 15_050),
                TransactionItem("Fish", 60_000), TransactionItem("Onion 2kg", 12_000), TransactionItem("Salt", 4_000),
                TransactionItem("Sugar", 125_000),
            ),
            r.items,
        )
        assertEquals(listOf("no price here"), r.invalidLines)
    }

    @Test fun `price only line is invalid`() {
        assertEquals(listOf("450"), ItemListParser.parse("450").invalidLines)
    }
}
