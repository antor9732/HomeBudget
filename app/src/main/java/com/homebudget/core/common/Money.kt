package com.homebudget.core.common

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale
import kotlin.math.abs

/**
 * All money is stored as integer minor units (poisha for BDT; 1 taka = 100 poisha).
 * Float/Double are never used for stored or calculated money.
 */
object Money {
    const val DEFAULT_CURRENCY = "BDT"
    const val TAKA_SYMBOL = "৳"

    fun symbolFor(currencyCode: String): String = when (currencyCode) {
        "BDT" -> TAKA_SYMBOL
        else -> "$currencyCode "
    }

    /** Parses user input like "1,250.50" into minor units. Returns null for invalid/negative input. */
    fun parseToMinor(input: String): Long? {
        val cleaned = input.trim().replace(",", "").replace(TAKA_SYMBOL, "").trim()
        if (cleaned.isEmpty()) return null
        if (!Regex("""^\d{1,13}(\.\d{0,2})?$""").matches(cleaned)) return null
        return try {
            BigDecimal(cleaned).setScale(2, RoundingMode.UNNECESSARY).movePointRight(2).longValueExact()
        } catch (e: ArithmeticException) {
            null
        } catch (e: NumberFormatException) {
            null
        }
    }

    /** Formats minor units using South-Asian digit grouping (e.g. ৳1,25,000.50). */
    fun format(minor: Long, currencyCode: String = DEFAULT_CURRENCY, showSign: Boolean = false): String {
        val sign = when {
            minor < 0 -> "-"
            showSign && minor > 0 -> "+"
            else -> ""
        }
        val absMinor = abs(minor)
        val major = absMinor / 100
        val fraction = absMinor % 100
        val grouped = groupIndian(major)
        val frac = if (fraction == 0L) "" else "." + fraction.toString().padStart(2, '0')
        return sign + symbolFor(currencyCode) + grouped + frac
    }

    /** Plain decimal string for edit fields, e.g. 125050 -> "1250.50". */
    fun toInputString(minor: Long): String {
        val bd = BigDecimal.valueOf(minor).movePointLeft(2)
        val df = DecimalFormat("0.##", DecimalFormatSymbols(Locale.US))
        return df.format(bd)
    }

    internal fun groupIndian(value: Long): String {
        val s = value.toString()
        if (s.length <= 3) return s
        val last3 = s.takeLast(3)
        var rest = s.dropLast(3)
        val parts = ArrayList<String>()
        while (rest.length > 2) {
            parts.add(0, rest.takeLast(2))
            rest = rest.dropLast(2)
        }
        if (rest.isNotEmpty()) parts.add(0, rest)
        return parts.joinToString(",") + "," + last3
    }
}
