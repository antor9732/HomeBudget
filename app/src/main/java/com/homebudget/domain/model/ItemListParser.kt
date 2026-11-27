package com.homebudget.domain.model

import com.homebudget.core.common.Money

/**
 * Parses a pasted/typed shopping list, one item per line, price last:
 *   "Rice 5kg 450", "তেল ২ লিটার - ৩৮০", "Eggs: 150.50", "Fish ৳ 600"
 * Bangla digits are accepted. Lines without a price are reported back.
 */
object ItemListParser {
    data class Result(val items: List<TransactionItem>, val invalidLines: List<String>)

    private val bnDigits = mapOf('০' to '0', '১' to '1', '২' to '2', '৩' to '3', '৪' to '4', '৫' to '5', '৬' to '6', '৭' to '7', '৮' to '8', '৯' to '9')
    private val trailingPrice = Regex("""^(.*?)[\s:=\-–—৳]*(?:tk\.?|taka|টাকা)?\s*([0-9][0-9,]*(?:\.[0-9]{1,2})?)\s*(?:tk\.?|taka|টাকা|/-|৳)?\s*$""", RegexOption.IGNORE_CASE)

    fun parse(text: String): Result {
        val items = ArrayList<TransactionItem>()
        val invalid = ArrayList<String>()
        text.lines().map { it.trim() }.filter { it.isNotEmpty() }.forEach { raw ->
            val line = raw.map { bnDigits[it] ?: it }.joinToString("")
                .trimStart('-', '*', '•', ' ').replace(Regex("""^\d+[.)]\s+"""), "")
            val m = trailingPrice.matchEntire(line)
            val name = m?.groupValues?.get(1)?.trim()?.trimEnd(':', '-', '=', '৳')?.trim()
            val amount = m?.groupValues?.get(2)?.let { Money.parseToMinor(it) }
            if (m == null || name.isNullOrBlank() || amount == null || amount <= 0) invalid += raw
            else items += TransactionItem(name.take(MAX_NAME), amount)
        }
        return Result(items, invalid)
    }

    /** Converts Bangla digits (০-৯) to ASCII digits. */
    fun normalizeDigits(s: String): String = s.map { bnDigits[it] ?: it }.joinToString("")

    const val MAX_NAME = 60
    const val MAX_ITEMS = 200
}
