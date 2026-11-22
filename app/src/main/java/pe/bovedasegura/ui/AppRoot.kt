package pe.bovedasegura.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import pe.bovedasegura.data.Prefs
import pe.bovedasegura.data.Vault

@Composable
fun AppRoot() {
    val data by Vault.data.collectAsState()
    var setupDone by remember { mutableStateOf(Vault.isSetUp && Prefs.setupComplete) }
    when {
        !Vault.isSetUp -> SetupScreen(onDone = { setupDone = true })
        data == null -> UnlockScreen(onUnlocked = {}, onWiped = { setupDone = false })
        !setupDone -> SetupScreen(onDone = { setupDone = true })
        else -> MainScaffold()
    }
}

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab("home", "Inicio", Icons.Filled.Shield),
    Tab("vault", "Bóveda", Icons.Filled.Key),
    Tab("files", "Archivos", Icons.Filled.Folder),
    Tab("totp", "2FA", Icons.Filled.Timer),
    Tab("more", "Más", Icons.Filled.MoreHoriz),
)

fun NavHostController.go(route: String) = navigate(route) { launchSingleTop = true }

@Composable
fun MainScaffold() {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val current = entry?.destination?.route
    val showBar = tabs.any { it.route == current }
    Scaffold(
        bottomBar = {
            if (showBar) NavigationBar {
                tabs.forEach { t ->
                    NavigationBarItem(
                        selected = current == t.route,
                        onClick = {
                            nav.navigate(t.route) {
                                popUpTo("home") { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(t.icon, null) },
                        label = { Text(t.label) }
                    )
                }
            }
        }
    ) { pad ->
        NavHost(nav, startDestination = "home", modifier = Modifier.padding(bottom = if (showBar) pad.calculateBottomPadding() else androidx.compose.ui.unit.Dp(0f))) {
            composable("home") { HomeScreen(nav) }
            composable("vault") { VaultListScreen(nav) }
            composable("files") { FilesScreen(nav) }
            composable("totp") { TotpScreen(nav) }
            composable("more") { MoreScreen(nav) }
            composable("item/{id}", listOf(navArgument("id") { type = NavType.StringType })) { ItemDetailScreen(nav, it.arguments?.getString("id") ?: "") }
            composable("edit/{id}?type={type}", listOf(
                navArgument("id") { type = NavType.StringType },
                navArgument("type") { type = NavType.StringType; defaultValue = "LOGIN" }
            )) { ItemEditScreen(nav, it.arguments?.getString("id") ?: "new", it.arguments?.getString("type") ?: "LOGIN") }
            composable("file/{id}", listOf(navArgument("id") { type = NavType.StringType })) { FileViewerScreen(nav, it.arguments?.getString("id") ?: "") }
            composable("camera?itemId={itemId}", listOf(navArgument("itemId") { type = NavType.StringType; nullable = true; defaultValue = null })) { CameraScreen(nav, it.arguments?.getString("itemId")) }
            composable("generator") { GeneratorScreen(nav) }
            composable("backups") { BackupsScreen(nav) }
            composable("travel") { TravelScreen(nav) }
            composable("decoy") { DecoyScreen(nav) }
            composable("autofill") { AutofillSetupScreen(nav) }
            composable("importexport") { ImportExportScreen(nav) }
            composable("kit") { KitScreen(nav) }
            composable("security") { SecuritySettingsScreen(nav) }
            composable("password") { ChangePasswordScreen(nav) }
            composable("trash") { TrashScreen(nav) }
            composable("health/{kind}", listOf(navArgument("kind") { type = NavType.StringType })) { HealthListScreen(nav, it.arguments?.getString("kind") ?: "weak") }
        }
    }
    PendingShareImport()
}
