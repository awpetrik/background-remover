package id.eraserremake.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import id.eraserremake.app.ui.EditorScreen
import kotlinx.coroutines.launch
import id.eraserremake.app.ui.HelpScreen
import id.eraserremake.app.ui.HomeScreen
import id.eraserremake.app.ui.ModelsScreen
import id.eraserremake.app.ui.SettingsScreen
import id.eraserremake.app.ui.theme.EraserTheme
import id.eraserremake.app.ui.theme.ThemeMode

private enum class TopDest(val route: String, val label: String, val icon: ImageVector) {
    Home("home", "Beranda", Icons.Filled.Home),
    Models("models", "Model AI", Icons.Filled.Download),
    Settings("settings", "Pengaturan", Icons.Filled.Settings),
    Help("help", "Cara Pakai", Icons.Filled.HelpOutline),
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val settings = id.eraserremake.app.data.SettingsStore(this)
        setContent {
            var themeMode by remember { mutableStateOf(settings.themeMode) }
            var useNnapi by remember { mutableStateOf(settings.useNnapi) }
            EraserTheme(mode = themeMode) {
                MainScaffold(
                    themeMode = themeMode,
                    onThemeModeChange = { mode ->
                        themeMode = mode
                        settings.themeMode = mode
                    },
                    useNnapi = useNnapi,
                    onUseNnapiChange = { enabled ->
                        useNnapi = enabled
                        settings.useNnapi = enabled
                        // Terapkan ke runtime untuk sesi model berikutnya.
                        kotlinx.coroutines.CoroutineScope(
                            kotlinx.coroutines.Dispatchers.IO,
                        ).launch {
                            runCatching {
                                id.eraserremake.app.data.ModelRuntimeHolder
                                    .get(this@MainActivity)
                                    .setUseNnapi(enabled)
                            }
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun MainScaffold(
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    useNnapi: Boolean,
    onUseNnapiChange: (Boolean) -> Unit,
) {
    val navController = rememberNavController()
    val entry by navController.currentBackStackEntryAsState()
    val currentRoute = entry?.destination?.route

    Scaffold(
        bottomBar = {
            // Editor adalah alur kerja, bukan tab; bottom bar hanya untuk 4 destinasi utama.
            if (currentRoute?.startsWith("editor") != true) {
                NavigationBar {
                    TopDest.entries.forEach { dest ->
                        NavigationBarItem(
                            selected = currentRoute == dest.route,
                            onClick = {
                                navController.navigate(dest.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(dest.icon, contentDescription = dest.label) },
                            label = { Text(dest.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = TopDest.Home.route,
            modifier = Modifier.padding(padding),
        ) {
            composable(TopDest.Home.route) {
                HomeScreen(onOpenProject = { id -> navController.navigate("editor/$id") })
            }
            composable(
                route = "editor/{projectId}",
                arguments = listOf(
                    androidx.navigation.navArgument("projectId") {
                        type = androidx.navigation.NavType.LongType
                    },
                ),
            ) { entry ->
                EditorScreen(
                    projectId = entry.arguments?.getLong("projectId") ?: 0L,
                    onBack = { navController.popBackStack() },
                )
            }
            composable(TopDest.Models.route) { ModelsScreen() }
            composable(TopDest.Settings.route) {
                SettingsScreen(
                    themeMode = themeMode,
                    onThemeModeChange = onThemeModeChange,
                    useNnapi = useNnapi,
                    onUseNnapiChange = onUseNnapiChange,
                )
            }
            composable(TopDest.Help.route) { HelpScreen() }
        }
    }
}
