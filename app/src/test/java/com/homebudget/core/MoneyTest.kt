package com.homebudget.core

import com.homebudget.core.common.Money
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MoneyTest {
    @Test fun parse() {
        assertEquals(500_000L, Money.parseToMinor("5000"))
        assertEquals(125_050L, Money.parseToMinor("1,250.50"))
        assertEquals(125_050L, Money.parseToMinor("1250.5"))
        assertEquals(1L, Money.parseToMinor("0.01"))
        assertEquals(100_000L, Money.parseToMinor("৳1,000"))
        assertNull(Money.parseToMinor("-5"))
        assertNull(Money.parseToMinor("1.234"))
        assertNull(Money.parseToMinor("abc"))
        assertNull(Money.parseToMinor(""))
    }

    @Test fun format() {
        assertEquals("৳1,25,000", Money.format(12_500_000))
        assertEquals("৳1,000.50", Money.format(100_050))
        assertEquals("-৳5,000", Money.format(-500_000))
        assertEquals("+৳10", Money.format(1_000, showSign = true))
        assertEquals("৳0.05", Money.format(5))
        assertEquals("1250.5", Money.toInputString(125_050))
        assertEquals("1,00,00,000", Money.groupIndian(10_000_000))
    }
}
