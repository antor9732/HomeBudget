package com.homebudget.data.repository

import com.homebudget.domain.model.TransactionType

data class CategorySeed(val key: String, val name: String, val type: TransactionType, val icon: String, val color: String)

/** Section 5.3 / 5.4 default categories. */
object DefaultCategories {
    val all = listOf(
        CategorySeed("house_rent", "House Rent", TransactionType.EXPENSE, "home", "#8D6E63"),
        CategorySeed("grocery", "Grocery", TransactionType.EXPENSE, "grocery", "#43A047"),
        CategorySeed("electricity", "Electricity", TransactionType.EXPENSE, "bolt", "#FBC02D"),
        CategorySeed("gas", "Gas", TransactionType.EXPENSE, "fire", "#F4511E"),
        CategorySeed("water", "Water", TransactionType.EXPENSE, "water", "#039BE5"),
        CategorySeed("internet", "Internet", TransactionType.EXPENSE, "wifi", "#5E35B1"),
        CategorySeed("medical", "Medical", TransactionType.EXPENSE, "medical", "#E53935"),
        CategorySeed("education", "Education", TransactionType.EXPENSE, "school", "#3949AB"),
        CategorySeed("transport", "Transport", TransactionType.EXPENSE, "bus", "#00897B"),
        CategorySeed("shopping", "Shopping", TransactionType.EXPENSE, "shopping", "#D81B60"),
        CategorySeed("entertainment", "Entertainment", TransactionType.EXPENSE, "movie", "#8E24AA"),
        CategorySeed("other_expense", "Other Expenses", TransactionType.EXPENSE, "more", "#757575"),
        CategorySeed("salary", "Salary", TransactionType.INCOME, "salary", "#2E7D32"),
        CategorySeed("business", "Business Income", TransactionType.INCOME, "business", "#1565C0"),
        CategorySeed("freelancing", "Freelancing", TransactionType.INCOME, "laptop", "#6A1B9A"),
        CategorySeed("rental", "Rental Income", TransactionType.INCOME, "key", "#EF6C00"),
        CategorySeed("other_income", "Other Income", TransactionType.INCOME, "savings", "#546E7A"),
    )
}
