package avinash.app.headlinr.ui.navigation

import androidx.compose.runtime.Composable
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import avinash.app.headlinr.ui.screens.ArticleDetailScreen
import avinash.app.headlinr.ui.screens.CategorySelectScreen
import avinash.app.headlinr.ui.screens.MainScreen
import avinash.app.headlinr.ui.screens.SplashScreen
import avinash.app.headlinr.ui.screens.WebViewScreen
import avinash.app.headlinr.viewmodel.NewsViewModel
import java.net.URLDecoder
import java.net.URLEncoder

@Composable
fun AppNavigation() {
    val navController = rememberNavController()
    val viewModel: NewsViewModel = hiltViewModel()

    NavHost(
        navController = navController,
        startDestination = "splash"
    ) {
        composable("splash") {
            SplashScreen(
                viewModel = viewModel,
                onNavigate = { hasCategories ->
                    val dest = if (hasCategories) "main" else "category_select"
                    navController.navigate(dest) {
                        popUpTo("splash") { inclusive = true }
                    }
                }
            )
        }

        composable("category_select") {
            CategorySelectScreen(
                viewModel = viewModel,
                onContinue = {
                    navController.navigate("main") {
                        popUpTo("category_select") { inclusive = true }
                    }
                }
            )
        }

        composable("main") {
            MainScreen(
                viewModel = viewModel,
                onArticleClick = { articleId ->
                    navController.navigate("detail/$articleId")
                }
            )
        }

        composable(
            route = "detail/{articleId}",
            arguments = listOf(
                navArgument("articleId") { type = NavType.StringType }
            )
        ) { backStackEntry ->
            val articleId = backStackEntry.arguments?.getString("articleId") ?: ""
            ArticleDetailScreen(
                articleId = articleId,
                viewModel = viewModel,
                onBack = { navController.popBackStack() },
                onReadMore = { url ->
                    val encoded = URLEncoder.encode(url, "UTF-8")
                    navController.navigate("webview/$encoded/$articleId")
                }
            )
        }

        composable(
            route = "webview/{url}/{articleId}",
            arguments = listOf(
                navArgument("url") { type = NavType.StringType },
                navArgument("articleId") { type = NavType.StringType }
            )
        ) { backStackEntry ->
            val url = URLDecoder.decode(backStackEntry.arguments?.getString("url") ?: "", "UTF-8")
            val articleId = backStackEntry.arguments?.getString("articleId") ?: ""
            WebViewScreen(
                articleUrl = url,
                articleId = articleId,
                viewModel = viewModel,
                onBack = { navController.popBackStack() }
            )
        }
    }
}
