package avinash.app.headlinr.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.InspectableModifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import avinash.app.headlinr.ui.components.ArticleCard
import avinash.app.headlinr.ui.components.InFeedAd
import avinash.app.headlinr.ui.components.TrendingCard
import avinash.app.headlinr.ui.theme.HeadlinrColors
import avinash.app.headlinr.util.sourceFaviconUrl
import avinash.app.headlinr.viewmodel.NewsViewModel
import avinash.app.news.api.model.Category
import avinash.app.news.api.model.SourceEntry
import coil.compose.SubcomposeAsyncImage

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: NewsViewModel,
    onArticleClick: (articleId: String) -> Unit,
    onDirectRead: (url: String, articleId: String) -> Unit = { _, _ -> }
) {
    val isRefreshing by viewModel.isRefreshing.collectAsState()
    val trendingArticles by viewModel.trendingArticles.collectAsState()
    val bookmarkedIds by viewModel.bookmarkedIds.collectAsState()
    val userCategories by viewModel.userCategories.collectAsState()
    val activeCategory by viewModel.activeCategory.collectAsState()
    val activeSource by viewModel.activeSource.collectAsState()
    val availableSources by viewModel.availableSources.collectAsState()
    val articles = viewModel.articles.collectAsLazyPagingItems()

    val adPositions = remember(articles.itemCount) {
        if (articles.itemCount < 5) emptySet()
        else buildSet {
            val rng = kotlin.random.Random(articles.itemCount)
            var next = rng.nextInt(4, 8)
            while (next < articles.itemCount) {
                add(next)
                next += rng.nextInt(5, 9)
            }
        }
    }

    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = { viewModel.refresh() },
        modifier = Modifier.fillMaxSize()
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 16.dp)
        ) {
           // item { TopBar() }

            if (availableSources.isNotEmpty()) {
                item {
                    SourceFilterRow(
                        sources = availableSources,
                        activeSource = activeSource,
                        onSourceClick = { entry ->
                            viewModel.selectSource(if (activeSource == entry.name) null else entry.name)
                        }
                    )
                }
            }

            if (trendingArticles.isNotEmpty()) {
                item {
                    SectionHeader(title = "Today Trending", onSeeMore = { })
                }

                item {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 20.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        itemsIndexed(trendingArticles, key = { _, it -> it.id }) { index, article ->
                            TrendingCard(
                                article = article,
                                onClick = {
                                    if (article.description.length < 200 && article.articleUrl.isNotBlank()) {
                                        onDirectRead(article.articleUrl, article.id)
                                    } else {
                                        onArticleClick(article.id)
                                    }
                                },
                                trendingIndex = index
                            )
                        }
                    }
                }

                item { Spacer(modifier = Modifier.height(20.dp)) }
            }

            if (userCategories.isNotEmpty()) {
                item {
                    SectionHeader(title = "Explore", onSeeMore = { })
                }

                item {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 20.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        items(userCategories, key = { it.slug }) { category ->
                            ExploreCategoryBubble(
                                category = category,
                                isSelected = activeCategory == category.slug,
                                onClick = {
                                    viewModel.selectCategory(
                                        if (activeCategory == category.slug) null else category.slug
                                    )
                                }
                            )
                        }
                    }
                }

                item { Spacer(modifier = Modifier.height(16.dp)) }
            }

            when {
                articles.loadState.refresh is LoadState.Loading && articles.itemCount == 0 -> {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(200.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }

                articles.itemCount == 0 && !isRefreshing -> {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(200.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "No articles yet.\nPull to refresh.",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                else -> {
                    items(
                        count = articles.itemCount,
                        key = { index -> articles[index]?.id ?: index }
                    ) { index ->
                        if (index in adPositions) {
                            InFeedAd()
                        }
                        articles[index]?.let { article ->
                            ArticleCard(
                                article = article,
                                isBookmarked = article.id in bookmarkedIds,
                                onClick = { onArticleClick(article.id) },
                                onBookmarkClick = { viewModel.toggleBookmark(article.id) },
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp)
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
                                CircularProgressIndicator(
                                    modifier = Modifier.size(24.dp),
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SourceFilterRow(
    sources: List<SourceEntry>,
    activeSource: String?,
    onSourceClick: (SourceEntry) -> Unit
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        items(sources, key = { it.name }) { entry ->
            val isSelected = activeSource == entry.name
            val favicon = sourceFaviconUrl(entry.sampleArticleUrl)
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .width(64.dp)
                    .clickable { onSourceClick(entry) }
            ) {
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .clip(CircleShape)
                        .background(
                            if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                            else MaterialTheme.colorScheme.surfaceContainerHigh
                        )
                        .then(
                            if (isSelected) Modifier.border(
                                width = 2.dp,
                                color = MaterialTheme.colorScheme.primary,
                                shape = CircleShape
                            ) else Modifier
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    if (favicon != null) {
                        SubcomposeAsyncImage(
                            model = favicon,
                            contentDescription = entry.name,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape),
                            loading = {
                                SourceInitials(entry.name, isSelected)
                            },
                            error = {
                                SourceInitials(entry.name, isSelected)
                            }
                        )
                    } else {
                        SourceInitials(entry.name, isSelected)
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = entry.name,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                        fontSize = 10.sp
                    ),
                    color = if (isSelected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
private fun SourceInitials(sourceName: String, isSelected: Boolean) {
    Text(
        text = sourceName.split(" ").take(2).joinToString("") { it.firstOrNull()?.uppercase()?:"TEST" },
        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
        color = if (isSelected) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun TopBar() {
    Row(
        modifier = Modifier
            .fillMaxWidth(),
        horizontalArrangement = Arrangement.End
    ) {

        IconButton(onClick = { }) {
            BadgedBox(
                badge = {
                    Badge(
                        containerColor = HeadlinrColors.notificationBadge,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(8.dp)
                    )
                }
            ) {
                Icon(
                    Icons.Outlined.Notifications,
                    contentDescription = "Notifications",
                    tint = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.size(26.dp)
                )
            }
        }

    }
}

@Composable
private fun SectionHeader(
    title: String,
    onSeeMore: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall.copy(
                fontWeight = FontWeight.Bold
            ),
            color = MaterialTheme.colorScheme.onBackground
        )
    }
}

@Composable
private fun ExploreCategoryBubble(
    category: Category,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(76.dp)
            .clickable(onClick = onClick)
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(
                    if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                    else MaterialTheme.colorScheme.surfaceContainerHigh
                ),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(66.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = if (isSelected) 0.25f else 0.08f)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = category.name.take(2).uppercase(),
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp
                    ),
                    color = if (isSelected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        Text(
            text = category.name,
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
            ),
            color = if (isSelected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1
        )
    }
}
