package app.ewe

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.ewe.podcast.EpisodeStore
import app.ewe.podcast.PrepareWorker
import app.ewe.ui.AuthScreen
import app.ewe.ui.C
import app.ewe.ui.EweTheme
import app.ewe.ui.HomeScreen
import app.ewe.ui.LocalToast
import app.ewe.ui.NewSessionScreen
import app.ewe.ui.RevisionScreen
import app.ewe.ui.SearchScreen
import app.ewe.ui.SessionScreen
import app.ewe.ui.SettingsScreen
import app.ewe.ui.ts
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val pendingOpen = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        pendingOpen.value = intent?.getStringExtra(EXTRA_OPEN)
        val repo = (application as EweApp).repo
        if (savedInstanceState == null) authLink(intent)
        val store = EpisodeStore.get(this)

        setContent {
            EweTheme {
                val signedIn by repo.signedIn.collectAsState()
                val snackbar = remember { SnackbarHostState() }
                val scope = rememberCoroutineScope()
                val toast: (String) -> Unit = remember {
                    { msg -> scope.launch { snackbar.currentSnackbarData?.dismiss(); snackbar.showSnackbar(msg) } }
                }
                // Pull fresh data whenever the app comes to the front.
                LifecycleResumeEffect(signedIn) {
                    if (signedIn && System.currentTimeMillis() - repo.snapshot.value.syncedAt > 30_000) repo.refreshInBackground()
                    onPauseOrDispose { }
                }
                val authMessage by repo.authMessage.collectAsState()
                LaunchedEffect(authMessage) { authMessage?.let { toast(it); repo.clearAuthMessage() } }
                val podcastOn = repo.snapshot.collectAsState().value.settings.podcastEnabled
                LaunchedEffect(signedIn, podcastOn) { PrepareWorker.schedule(applicationContext, repo, androidx.work.ExistingWorkPolicy.KEEP) }

                CompositionLocalProvider(LocalToast provides toast) {
                    Box(Modifier.fillMaxSize().background(C.White).windowInsetsPadding(WindowInsets.systemBars)) {
                        if (!signedIn) AuthScreen(repo) else {
                            val nav = rememberNavController()
                            LaunchedEffect(pendingOpen.value) {
                                if (pendingOpen.value == "revision") { nav.navigate("revision") { launchSingleTop = true }; pendingOpen.value = null }
                            }
                            Routes(nav, repo, store)
                        }
                        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 76.dp)) { data ->
                            Snackbar(Modifier.padding(horizontal = 16.dp), shape = RoundedCornerShape(12.dp), containerColor = C.Ink) {
                                Text(data.visuals.message, style = ts(14, 500, C.White, 1.4f))
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(EXTRA_OPEN)?.let { pendingOpen.value = it }
        authLink(intent)
    }

    private fun authLink(intent: Intent?) {
        val link = intent?.data ?: return
        if (link.scheme == "ewe" && link.host == "auth-callback") (application as EweApp).repo.handleAuthCallback(link)
    }

    companion object {
        const val EXTRA_OPEN = "open"
    }
}

@androidx.compose.runtime.Composable
private fun Routes(nav: NavHostController, repo: app.ewe.data.Repository, store: EpisodeStore) {
    fun openSession(id: String, notes: Boolean = false) = nav.navigate("session/$id?notes=$notes")
    NavHost(nav, startDestination = "home", modifier = Modifier.fillMaxSize()) {
        composable("home") {
            HomeScreen(repo, store, onOpen = { openSession(it) }, onNew = { nav.navigate("new") }, onSearch = { nav.navigate("search") },
                onSettings = { nav.navigate("settings") }, onRevision = { nav.navigate("revision") })
        }
        composable("new") {
            NewSessionScreen(repo, onBack = { nav.popBackStack() }, onCreated = { id ->
                nav.navigate("session/$id?notes=false") { popUpTo("home") }
            })
        }
        composable("session/{id}?notes={notes}", arguments = listOf(
            navArgument("id") { type = NavType.StringType },
            navArgument("notes") { type = NavType.BoolType; defaultValue = false },
        )) { entry ->
            SessionScreen(repo, entry.arguments?.getString("id").orEmpty(), entry.arguments?.getBoolean("notes") ?: false,
                onBack = { nav.popBackStack() }, onOpenSession = { openSession(it, true) }, onSettings = { nav.navigate("settings") })
        }
        composable("search") { SearchScreen(repo, onBack = { nav.popBackStack() }, onOpen = { id, notes -> openSession(id, notes) }) }
        composable("settings") { SettingsScreen(repo, onBack = { nav.popBackStack() }) }
        composable("revision") { RevisionScreen(repo, store, onBack = { nav.popBackStack() }, onSettings = { nav.navigate("settings") }) }
    }
}
