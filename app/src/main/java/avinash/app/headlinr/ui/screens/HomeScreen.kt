package avinash.app.headlinr.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import avinash.app.headlinr.ui.components.ArticleCard
import avinash.app.headlinr.ui.components.CategoryChips
import avinash.app.headlinr.viewmodel.NewsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: NewsViewModel = hiltViewModel()
) {
    val categories by viewModel.categories.collectAsState()
    val selectedCategory by viewModel.selectedCategory.collectAsState()
    val isRefreshing by viewModel.isRefreshing.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    val articles = viewModel.articles.collectAsLazyPagingItems()
    val snackbarHostState = remember { SnackbarHostState() }
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val context = LocalContext.current

    LaunchedEffect(errorMessage) {
        errorMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Headlinr",
                        fontWeight = FontWeight.Bold
                    )
                },
                actions = {
                    IconButton(onClick = { viewModel.refresh() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                },
                scrollBehavior = scrollBehavior
            )
        },
        snackbarHost = {
            SnackbarHost(snackbarHostState) { data ->
                Snackbar(snackbarData = data)
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (categories.isNotEmpty()) {
                CategoryChips(
                    categories = categories,
                    selectedCategory = selectedCategory,
                    onCategorySelected = { viewModel.selectCategory(it) }
                )
                Spacer(modifier = Modifier.height(8.dp))
            }

            PullToRefreshBox(
                isRefreshing = isRefreshing,
                onRefresh = { viewModel.refresh() },
                modifier = Modifier.fillMaxSize()
            ) {
                when {
                    articles.loadState.refresh is LoadState.Loading && articles.itemCount == 0 -> {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator()
                        }
                    }

                    articles.loadState.refresh is LoadState.Error && articles.itemCount == 0 -> {
                        val error = (articles.loadState.refresh as LoadState.Error).error
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = error.localizedMessage ?: "Failed to load news",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.error
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                IconButton(onClick = { articles.retry() }) {
                                    Icon(Icons.Default.Refresh, contentDescription = "Retry")
                                }
                            }
                        }
                    }

                    articles.itemCount == 0 && !isRefreshing -> {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "No articles yet.\nPull to refresh.",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    else -> {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(
                                start = 16.dp,
                                end = 16.dp,
                                bottom = 16.dp
                            ),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            items(
                                count = articles.itemCount,
                                key = { index -> articles[index]?.id ?: index }
                            ) { index ->
                                articles[index]?.let { article ->
                                    ArticleCard(
                                        article = article,
                                        onClick = {
                                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(article.articleUrl))
                                            context.startActivity(intent)
                                        }
                                    )
                                }
                            }

                            if (articles.loadState.append is LoadState.Loading) {
                                item {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(16.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
