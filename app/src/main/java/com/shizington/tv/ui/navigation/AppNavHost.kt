package com.shizington.tv.ui.navigation

import android.net.Uri
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.shizington.tv.AppContainer
import com.shizington.tv.data.model.PlaybackRequest
import com.shizington.tv.ui.components.appContainer
import com.shizington.tv.ui.detail.MovieDetailScreen
import com.shizington.tv.ui.detail.SeriesDetailScreen
import com.shizington.tv.ui.login.LoginScreen
import com.shizington.tv.ui.login.SplashScreen
import com.shizington.tv.ui.main.MainScreen
import com.shizington.tv.ui.player.PlayerScreen

/** Navigation actions shared by every screen. */
class AppNavigator(private val nav: NavHostController, private val container: AppContainer) {
    fun openMovie(id: Long) = nav.navigate("movie/$id") { launchSingleTop = true }
    fun openSeries(id: Long) = nav.navigate("series/$id") { launchSingleTop = true }

    fun play(request: PlaybackRequest) {
        container.pendingPlayback = request
        nav.navigate("player") { launchSingleTop = true }
    }

    fun back() = nav.popBackStack()

    fun toMain() = nav.navigate("main") { popUpTo(0) { inclusive = true } }

    fun toLogin(message: String? = null) {
        val route = if (message != null) "login?msg=${Uri.encode(message)}" else "login"
        nav.navigate(route) { popUpTo(0) { inclusive = true } }
    }
}

@Composable
fun AppNavHost() {
    val nav = rememberNavController()
    val container = appContainer()
    val navigator = remember(nav) { AppNavigator(nav, container) }

    NavHost(
        navController = nav,
        startDestination = "splash",
        enterTransition = { fadeIn(tween(180)) },
        exitTransition = { fadeOut(tween(120)) },
        popEnterTransition = { fadeIn(tween(180)) },
        popExitTransition = { fadeOut(tween(120)) },
    ) {
        composable("splash") { SplashScreen(navigator) }
        composable(
            "login?msg={msg}",
            arguments = listOf(navArgument("msg") { type = NavType.StringType; nullable = true; defaultValue = null }),
        ) { entry -> LoginScreen(navigator, entry.arguments?.getString("msg")) }
        composable("main") { MainScreen(navigator) }
        composable("movie/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) { entry ->
            MovieDetailScreen(entry.arguments!!.getLong("id"), navigator)
        }
        composable("series/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) { entry ->
            SeriesDetailScreen(entry.arguments!!.getLong("id"), navigator)
        }
        composable("player") { PlayerScreen(navigator) }
    }
}
