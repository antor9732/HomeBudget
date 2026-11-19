package com.homebudget.presentation.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.homebudget.core.common.AppPreferences
import com.homebudget.core.common.ThemeMode
import com.homebudget.domain.model.TransactionType
import com.homebudget.domain.repository.HouseholdRepository
import com.homebudget.presentation.budget.BudgetScreen
import com.homebudget.presentation.categories.CategoriesScreen
import com.homebudget.presentation.common.BaseViewModel
import com.homebudget.presentation.dashboard.DashboardScreen
import com.homebudget.presentation.household.ConnectDriveScreen
import com.homebudget.presentation.household.CreateHouseholdScreen
import com.homebudget.presentation.household.JoinHouseholdScreen
import com.homebudget.presentation.household.WelcomeScreen
import com.homebudget.presentation.members.MembersScreen
import com.homebudget.presentation.reports.ReportsScreen
import com.homebudget.presentation.settings.BackupScreen
import com.homebudget.presentation.settings.NotificationSettingsScreen
import com.homebudget.presentation.settings.PrivacyScreen
import com.homebudget.presentation.settings.SettingsScreen
import com.homebudget.presentation.sync.SyncCenterScreen
import com.homebudget.presentation.theme.HomeBudgetTheme
import com.homebudget.presentation.transactions.TransactionDetailScreen
import com.homebudget.presentation.transactions.TransactionEditScreen
import com.homebudget.presentation.transactions.TransactionsScreen
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

sealed interface RootState {
    data object Loading : RootState
    data object NoHousehold : RootState
    data class Ready(val canEdit: Boolean) : RootState
}

@HiltViewModel
class AppViewModel @Inject constructor(prefs: AppPreferences, households: HouseholdRepository) : BaseViewModel() {
    init { launchSafely(showBusy = false) { households.repairSession() } }
    val theme = prefs.theme.stateIn(viewModelScope, SharingStarted.Eagerly, ThemeMode.SYSTEM)
    val root = combine(prefs.session, households.context) { session, ctx ->
        when {
            session.householdId == null -> RootState.NoHousehold
            ctx == null -> RootState.Loading
            else -> RootState.Ready(ctx.canEditTransactions)
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, RootState.Loading)
}

private data class Tab(val route: String, val label: String, val icon: ImageVector)
private val tabs = listOf(
    Tab("dashboard", "Dashboard", Icons.Filled.Dashboard),
    Tab("transactions", "History", Icons.AutoMirrored.Filled.ReceiptLong),
    Tab("categories", "Categories", Icons.Filled.Category),
    Tab("reports", "Reports", Icons.Filled.BarChart),
    Tab("settings", "Settings", Icons.Filled.Settings),
)

@Composable
fun HomeBudgetRoot(vm: AppViewModel = hiltViewModel()) {
    val theme by vm.theme.collectAsStateWithLifecycle()
    val root by vm.root.collectAsStateWithLifecycle()
    HomeBudgetTheme(theme) {
        androidx.compose.material3.Surface(Modifier.fillMaxSize()) {
            when (val r = root) {
                RootState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                RootState.NoHousehold -> OnboardingNav()
                is RootState.Ready -> MainNav(r.canEdit)
            }
        }
    }
}

@Composable
private fun OnboardingNav() {
    val nav = rememberNavController()
    NavHost(nav, startDestination = "welcome") {
        composable("welcome") { WelcomeScreen(onCreate = { nav.navigate("create") }, onJoin = { nav.navigate("join") }) }
        composable("create") { CreateHouseholdScreen(onBack = { nav.popBackStack() }, onDone = {}) }
        composable("join") { JoinHouseholdScreen(onBack = { nav.popBackStack() }, onDone = {}) }
    }
}

private fun NavHostController.addTx(type: TransactionType) = navigate("edit?type=${type.name}")

@Composable
private fun MainNav(canEdit: Boolean) {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    val isTab = tabs.any { it.route == route }
    var addMenu by remember { mutableStateOf(false) }
    Scaffold(
        bottomBar = {
            if (isTab) NavigationBar {
                tabs.forEach { t ->
                    NavigationBarItem(
                        selected = route == t.route,
                        onClick = {
                            nav.navigate(t.route) {
                                popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true; restoreState = true
                            }
                        },
                        icon = { Icon(t.icon, contentDescription = null) },
                        label = { Text(t.label, maxLines = 1, style = androidx.compose.material3.MaterialTheme.typography.labelSmall) },
                    )
                }
            }
        },
        floatingActionButton = {
            if (isTab && canEdit && route in setOf("dashboard", "transactions")) Box {
                FloatingActionButton(onClick = { addMenu = true }) { Icon(Icons.Filled.Add, contentDescription = "Add transaction") }
                DropdownMenu(addMenu, { addMenu = false }) {
                    DropdownMenuItem(text = { Text("Add expense") }, onClick = { addMenu = false; nav.addTx(TransactionType.EXPENSE) })
                    DropdownMenuItem(text = { Text("Add income") }, onClick = { addMenu = false; nav.addTx(TransactionType.INCOME) })
                }
            }
        },
    ) { pad ->
        NavHost(nav, startDestination = "dashboard", modifier = Modifier.padding(pad)) {
            composable("dashboard") {
                DashboardScreen(
                    onAdd = nav::addTx, onOpenTransaction = { nav.navigate("tx/$it") }, onOpenBudget = { nav.navigate("budget") },
                    onOpenSync = { nav.navigate("sync") }, onOpenMembers = { nav.navigate("members") }, onSeeAll = { nav.navigate("transactions") },
                )
            }
            composable("transactions") { TransactionsScreen(onOpen = { nav.navigate("tx/$it") }) }
            composable("categories") { CategoriesScreen() }
            composable("reports") { ReportsScreen() }
            composable("settings") {
                SettingsScreen(
                    onMembers = { nav.navigate("members") }, onNotifications = { nav.navigate("notifications") }, onSync = { nav.navigate("sync") },
                    onBackup = { nav.navigate("backup") }, onPrivacy = { nav.navigate("privacy") }, onBudget = { nav.navigate("budget") },
                    onConnectDrive = { nav.navigate("connect") },
                )
            }
            composable(
                "edit?type={type}&id={id}",
                arguments = listOf(
                    navArgument("type") { type = NavType.StringType; defaultValue = TransactionType.EXPENSE.name },
                    navArgument("id") { type = NavType.StringType; defaultValue = "" },
                ),
            ) { TransactionEditScreen(onDone = { nav.popBackStack() }) }
            composable("tx/{id}", arguments = listOf(navArgument("id") { type = NavType.StringType })) {
                TransactionDetailScreen(onBack = { nav.popBackStack() }, onEdit = { id -> nav.navigate("edit?id=$id") })
            }
            composable("budget") { BudgetScreen(onBack = { nav.popBackStack() }) }
            composable("members") { MembersScreen(onBack = { nav.popBackStack() }, onConnectDrive = { nav.navigate("connect") }) }
            composable("notifications") { NotificationSettingsScreen(onBack = { nav.popBackStack() }) }
            composable("sync") { SyncCenterScreen(onBack = { nav.popBackStack() }) }
            composable("backup") { BackupScreen(onBack = { nav.popBackStack() }) }
            composable("privacy") { PrivacyScreen(onBack = { nav.popBackStack() }) }
            composable("connect") { ConnectDriveScreen(onBack = { nav.popBackStack() }) }
        }
    }
}
