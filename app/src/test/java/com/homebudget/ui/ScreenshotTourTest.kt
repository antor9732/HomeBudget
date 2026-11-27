package com.homebudget.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextInput
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.homebudget.HomeBudgetApp
import com.homebudget.MainActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Walks the real app with demo data and saves screenshots for the tutorial video (build/screenshots/tour_*). */
@OptIn(ExperimentalTestApi::class, com.github.takahirom.roborazzi.ExperimentalRoborazziApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = HomeBudgetApp::class, qualifiers = "w411dp-h891dp-xxhdpi")
class ScreenshotTourTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    private fun shot(name: String) { rule.waitForIdle(); rule.onRoot().captureRoboImage("build/screenshots/tour_$name.png") }
    private fun waitText(s: String, sub: Boolean = false) = rule.waitUntilAtLeastOneExists(hasText(s, substring = sub), 10_000)
    private fun field(label: String) = rule.onNode(hasSetTextAction() and hasText(label))
    private fun click(label: String) = rule.onNode(hasText(label) and hasClickAction()).performScrollTo().performClick()
    private fun settingsList() = rule.onNode(androidx.compose.ui.test.hasTestTag("settings_list"))
    private fun back() = rule.onNodeWithContentDescription("Back").performClick()

    private fun expense(category: String, title: String, amount: String, income: Boolean = false) {
        click(if (income) "Add income" else "Add expense")
        waitText("Itemized list")
        field("Amount (BDT)").performTextInput(amount)
        rule.onNodeWithText(category).performClick()
        rule.onNode(hasSetTextAction() and hasText(category)).performTextInput("")
        click("Save")
        waitText("Spending by category")
    }

    @Test
    fun tour() {
        rule.waitUntil(10_000) {
            rule.onAllNodes(hasText("Use without Google (this phone only)")).fetchSemanticsNodes().isNotEmpty() ||
                rule.onAllNodes(hasText("Monthly budget")).fetchSemanticsNodes().isNotEmpty()
        }
        if (rule.onAllNodes(hasText("Use without Google (this phone only)")).fetchSemanticsNodes().isEmpty()) return
        shot("01_welcome")
        waitText("Use without Google (this phone only)"); click("Use without Google (this phone only)"); waitText("Household name")
        field("Household name").performTextInput("Rahman Family")
        field("Your name").performTextInput("Antor")
        field("This month's budget (optional, ৳)").performTextInput("30000")
        shot("03_create")
        click("Create household"); waitText("Monthly budget"); shot("04_dashboard_new")

        click("Edit"); waitText("Opening cash balance")
        field("Opening balance (leave empty to clear)").performTextInput("5000")
        click("Save opening balance"); shot("05_budget"); back(); waitText("Monthly budget")

        expense("Salary", "Salary", "40000", income = true)
        expense("House Rent", "House Rent", "25000")
        expense("Electricity", "Electricity", "1800")
        expense("Medical", "Medical", "1200")

        // Itemized monthly bazar
        click("Add expense"); waitText("Itemized list")
        rule.onNodeWithText("Grocery").performClick()
        rule.onNode(isToggleable()).performClick(); waitText("Paste list")
        val items = listOf("Rice 10kg" to "780", "Soybean oil 2L" to "390", "Lentils 2kg" to "280", "Eggs (24)" to "300", "Fish" to "650", "Vegetables" to "420")
        items.forEachIndexed { i, (n, p) ->
            if (i > 0) click("Add item")
            rule.onAllNodes(hasSetTextAction() and hasText("Item"))[0].performTextInput(n)
            rule.onAllNodes(hasSetTextAction() and hasText("Price"))[0].performTextInput(p)
        }
        rule.onNode(hasSetTextAction() and hasText("Grocery")).performTextInput("")
        shot("06_itemized")
        rule.onNode(hasText("Save 6 items", substring = true) and hasClickAction()).performScrollTo().performClick(); waitText("Spending by category")
        shot("07_dashboard_over")
        rule.onNodeWithText("Spending by category").performScrollTo(); shot("08_dashboard_charts")

        rule.onAllNodesWithText("History")[0].performClick(); waitText("5 items", sub = true); shot("09_history")
        field("Search title, note or category").performTextInput("oil"); waitText("1 items", sub = true); shot("10_search")
        rule.onAllNodes(hasText("6 items", substring = true), useUnmergedTree = true)[0].performClick()
        waitText("Revision"); shot("11_detail"); back()

        // Bazar list: write the list, tick items with prices, finish -> expenses by category
        rule.onAllNodesWithText("Bazar")[0].performClick(); waitText("New bazar list"); shot("20_bazar_home")
        click("New bazar list")
        waitText("Empty list")
        fun addItem(name: String, qty: String) {
            field("Item").performTextInput(name)
            if (qty.isNotEmpty()) field("Qty").performTextInput(qty)
            click("Add"); waitText(if (qty.isEmpty()) name else "$name  ·  $qty")
        }
        addItem("Rice", "5 kg"); addItem("Soybean oil", "2 L"); addItem("Potato", "3 kg"); addItem("Eggs", "12"); addItem("Fish", "1 kg")
        rule.onAllNodes(hasText("Grocery") and hasClickAction())[0].performClick(); rule.onAllNodesWithText("Medical").onFirst().performClick()
        addItem("Paracetamol", "1 strip")
        shot("21_bazar_list")
        var boughtCount = 0
        fun tick(price: String, index: Int = 0) {
            rule.onAllNodes(isToggleable())[index].performClick(); waitText("How much did it cost?")
            field("Price (৳)").performTextInput(price); click("Bought")
            boughtCount++; waitText("$boughtCount of 6 bought")
        }
        tick("450"); tick("380")
        rule.onAllNodes(isToggleable())[0].performClick(); waitText("How much did it cost?")
        field("Price (৳)").performTextInput("120"); shot("21b_bazar_price")
        click("Bought"); boughtCount++; waitText("$boughtCount of 6 bought")
        tick("150"); tick("20", index = 1)
        shot("22_bazar_bought")
        rule.onNode(hasText("Finish bazar") and hasClickAction()).performClick(); waitText("Finish bazar?"); rule.waitForIdle(); captureScreenRoboImage("build/screenshots/tour_23_bazar_finish.png")
        rule.onNode(hasText("Add expenses") and hasClickAction()).performClick(); waitText("(remaining)", sub = true); shot("24_bazar_remaining")
        back(); waitText("New bazar list"); shot("26_bazar_home_after")
        rule.onAllNodes(hasText("Finished ", substring = true) and hasClickAction())[0].performScrollTo().performClick()
        waitText("Bazar finished", sub = true); shot("25_bazar_done"); back(); waitText("New bazar list")
        rule.onAllNodesWithText("Dashboard")[0].performClick(); waitText("Monthly budget")
        shot("27a_dashboard_after_bazar"); rule.onNode(androidx.compose.ui.test.hasScrollAction()).performScrollToNode(hasText("Recent activity")); shot("27_dashboard_after_bazar")
        rule.onAllNodesWithText("Reports")[0].performClick(); waitText("Totals"); shot("13_reports")
        rule.onAllNodesWithText("Settings")[0].performClick(); waitText("Account & sync"); shot("14_settings")
        settingsList().performScrollToIndex(0); rule.onNodeWithText("Categories", useUnmergedTree = true).performClick(); waitText("House Rent"); shot("12_categories"); back()
        waitText("Account & sync")
        rule.onNodeWithText("Connect Google Drive", useUnmergedTree = true).performClick(); waitText("Sign in & connect"); shot("15_connect"); back()
        waitText("Account & sync")
        rule.onNodeWithText("Members", useUnmergedTree = true).performClick(); waitText("(you)", sub = true); shot("16_members"); back()
        waitText("Account & sync")
        settingsList().performScrollToIndex(2); rule.onNodeWithText("Budget notifications", useUnmergedTree = true).performClick(); waitText("Reminder time"); shot("17_notifications"); back()
        waitText("Account & sync")
        settingsList().performScrollToIndex(1); rule.onNodeWithText("Sync center", useUnmergedTree = true).performClick(); waitText("Pending changes"); shot("18_sync"); back()
        waitText("Account & sync")
        settingsList().performScrollToIndex(1); rule.onNodeWithText("Backup & restore", useUnmergedTree = true).performClick(); waitText("Cloud backup"); shot("19_backup")
    }

    @Suppress("unused") private val unused: SemanticsMatcher? = null
}
