package com.homebudget.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import com.github.takahirom.roborazzi.captureRoboImage
import com.homebudget.HomeBudgetApp
import com.homebudget.MainActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * End-to-end UI smoke test of the real app (real Hilt graph, Room, DataStore) under Robolectric,
 * fully offline. Screenshots are written to app/build/screenshots for visual review.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = HomeBudgetApp::class, qualifiers = "w411dp-h891dp-xxhdpi")
class UiSmokeTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    private fun shot(name: String) = rule.onRoot().captureRoboImage("build/screenshots/$name.png")

    @Test
    fun offlineHouseholdFlow() {
        rule.waitUntilAtLeastOneExists(hasText("Use without Google (this phone only)"), 10_000)
        shot("01_welcome")
        rule.onNodeWithText("Use without Google (this phone only)").performClick()

        rule.waitUntilAtLeastOneExists(hasText("Household name"), 5_000)
        rule.onNode(hasSetTextAction() and hasText("Household name")).performTextInput("Rahman Family")
        rule.onNode(hasSetTextAction() and hasText("Your name")).performTextInput("Antor")
        rule.onNode(hasSetTextAction() and hasText("This month's budget (optional, ৳)")).performTextInput("30000")
        shot("02_create")
        rule.onNode(hasText("Create household") and hasClickAction()).performScrollTo().performClick()

        rule.waitUntilAtLeastOneExists(hasText("Monthly budget"), 10_000)
        rule.waitForIdle()
        shot("03_dashboard_empty")

        // Add an expense through the dashboard quick action.
        rule.onNode(hasText("Add expense") and hasClickAction()).performScrollTo().performClick()
        rule.waitUntilAtLeastOneExists(hasText("Amount (BDT)"), 5_000)
        rule.onNode(hasSetTextAction() and hasText("Amount (BDT)")).performTextInput("35000")
        rule.onNodeWithText("Grocery").performClick()
        shot("04_add_expense")
        rule.onNode(hasText("Save") and hasClickAction()).performScrollTo().performClick()

        rule.waitUntilAtLeastOneExists(hasText("Over budget by"), 10_000)
        rule.waitForIdle()
        shot("05_dashboard_over_budget")

        // Add income.
        rule.onNode(hasText("Add income") and hasClickAction()).performScrollTo().performClick()
        rule.waitUntilAtLeastOneExists(hasText("Amount (BDT)"), 5_000)
        rule.onNode(hasSetTextAction() and hasText("Amount (BDT)")).performTextInput("40000")
        rule.onNodeWithText("Salary").performClick()
        rule.onNode(hasText("Save") and hasClickAction()).performScrollTo().performClick()
        rule.waitUntilAtLeastOneExists(hasText("Monthly net income"), 10_000)
        rule.waitForIdle()

        val tabs = listOf(
            Triple("History", "06_transactions", "2 items"), Triple("Bazar", "07_bazar", "New bazar list"),
            Triple("Reports", "08_reports", "Totals"), Triple("Settings", "09_settings", "Account & sync"),
        )
        for ((tab, name, marker) in tabs) {
            rule.onAllNodesWithText(tab)[0].performClick()
            rule.waitUntilAtLeastOneExists(hasText(marker, substring = true), 10_000)
            rule.waitForIdle()
            shot(name)
        }
        rule.onNodeWithText("Members", useUnmergedTree = true).performClick()
        rule.waitUntilAtLeastOneExists(hasText("(you)", substring = true), 10_000)
        rule.waitForIdle(); shot("10_members")
        rule.onNodeWithContentDescription("Back").performClick()
        rule.onAllNodesWithText("History")[0].performClick()
        rule.waitUntilAtLeastOneExists(hasText("2 items", substring = true), 10_000)
        rule.onNodeWithText("Grocery", useUnmergedTree = true).performClick()
        rule.waitUntilAtLeastOneExists(hasText("Revision"), 10_000)
        rule.waitForIdle(); shot("11_detail")
    }

    @Test
    fun itemizedBazarFlow() {
        // App state (DataStore) may survive from another test in the same process.
        rule.waitUntil(10_000) {
            rule.onAllNodes(hasText("Use without Google (this phone only)")).fetchSemanticsNodes().isNotEmpty() ||
                rule.onAllNodes(hasText("Monthly budget")).fetchSemanticsNodes().isNotEmpty()
        }
        if (rule.onAllNodes(hasText("Use without Google (this phone only)")).fetchSemanticsNodes().isNotEmpty()) {
            rule.onNodeWithText("Use without Google (this phone only)").performClick()
            rule.waitUntilAtLeastOneExists(hasText("Household name"), 5_000)
            rule.onNode(hasSetTextAction() and hasText("Household name")).performTextInput("Rahman Family")
            rule.onNode(hasText("Create household") and hasClickAction()).performScrollTo().performClick()
        }
        rule.waitUntilAtLeastOneExists(hasText("Monthly budget"), 10_000)

        rule.onNode(hasText("Add expense") and hasClickAction()).performScrollTo().performClick()
        rule.waitUntilAtLeastOneExists(hasText("Itemized list"), 5_000)
        rule.onNodeWithText("Grocery").performClick()
        rule.onNode(androidx.compose.ui.test.isToggleable()).performClick()
        rule.waitUntilAtLeastOneExists(hasText("Paste list"), 5_000)
        rule.onAllNodes(hasSetTextAction() and hasText("Item"))[0].performTextInput("Rice 5kg")
        rule.onAllNodes(hasSetTextAction() and hasText("Price"))[0].performTextInput("450")
        // (Paste-list dialog parsing is covered by ItemListParserTest; dialogs with focused text
        // fields do not idle under Robolectric.)
        for ((name, price) in listOf("Oil 2L" to "380", "Eggs" to "150", "Onion 2kg" to "120")) {
            rule.onNode(hasText("Add item") and hasClickAction()).performScrollTo().performClick()
            rule.onAllNodes(hasSetTextAction() and hasText("Item"))[0].performTextInput(name)
            rule.onAllNodes(hasSetTextAction() and hasText("Price"))[0].performTextInput(price)
        }
        rule.waitUntilAtLeastOneExists(hasText("Save 4 items", substring = true), 5_000)
        rule.waitForIdle(); shot("12_itemized_edit")
        rule.onNode(hasText("Save 4 items", substring = true) and hasClickAction()).performScrollTo().performClick()

        rule.waitUntilAtLeastOneExists(hasText("Spending by category"), 10_000)
        rule.waitForIdle(); shot("12b_dashboard_after_items")
        rule.onAllNodesWithText("History")[0].performClick()
        rule.waitUntilAtLeastOneExists(hasText("4 items", substring = true), 10_000)
        rule.onAllNodes(hasText("4 items", substring = true), useUnmergedTree = true)[0].performClick()
        rule.waitUntilAtLeastOneExists(hasText("Revision"), 10_000)
        rule.waitForIdle(); shot("13_itemized_detail")
    }
}
