package avinash.app.headlinr.ui.screens

import android.content.Intent
import android.widget.TextView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.text.HtmlCompat
import avinash.app.headlinr.ui.theme.HeadlinrColors
import avinash.app.headlinr.util.categoryDisplayName
import avinash.app.headlinr.util.sourceFaviconUrl
import avinash.app.headlinr.viewmodel.NewsViewModel
import avinash.app.news.api.model.NewsArticle
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ArticleDetailScreen(
    articleId: String,
    viewModel: NewsViewModel,
    onBack: () -> Unit,
    onReadMore: (articleUrl: String) -> Unit
) {
    var article by remember { mutableStateOf<NewsArticle?>(null) }
    var isLoading by remember { mutableStateOf(true) }

    val isBookmarked by remember(articleId) {
        viewModel.observeBookmark(articleId)
    }.collectAsState(initial = false)

    val context = LocalContext.current

    LaunchedEffect(articleId) {
        article = viewModel.getArticleById(articleId)
        isLoading = false
    }

    when {
        isLoading -> {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }

        article == null -> {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = "Article not found",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        else -> {
            val art = article!!

            val titleAnnotated = remember(art.title) {
                buildAnnotatedString {
                    if (art.title.isNotEmpty()) {
                        withStyle(SpanStyle(fontSize = 38.sp, fontWeight = FontWeight.ExtraBold)) {
                            append(art.title.first())
                        }
                        withStyle(SpanStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold)) {
                            append(art.title.drop(1))
                        }
                    } else {
                        append(art.title)
                    }
                }
            }

            val descriptionColor = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.85f).toArgb()
            val descriptionTextSize = MaterialTheme.typography.bodyMedium.fontSize.value

            BoxWithConstraints(Modifier.fillMaxSize()) {
                val screenHeight = maxHeight

                Box(Modifier.fillMaxSize()) {

                    // ── Layer 1: Fixed hero image ─────────────────────────────
                    SubcomposeAsyncImage(
                        model = ImageRequest.Builder(LocalContext.current)
                            .data(art.imageUrl)
                            .crossfade(true)
                            .build(),
                        contentDescription = art.title,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(screenHeight * 0.52f),
                        contentScale = ContentScale.Crop,
                        error = {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(screenHeight * 0.52f)
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                            )
                        }
                    )

                    // Top scrim for TopBar readability
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(130.dp)
                            .background(
                                Brush.verticalGradient(
                                    colors = listOf(
                                        HeadlinrColors.gradientOverlay.copy(alpha = 0.55f),
                                        HeadlinrColors.gradientOverlay.copy(alpha = 0f)
                                    )
                                )
                            )
                    )

                    // ── Layer 2: Scrollable content card ──────────────────────
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                    ) {
                        Spacer(modifier = Modifier.height(screenHeight * 0.44f))

                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .defaultMinSize(minHeight = screenHeight * 0.6f)
                                .background(
                                    color = MaterialTheme.colorScheme.background,
                                    shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
                                )
                                .padding(horizontal = 20.dp)
                        ) {
                            // Pull handle
                            Spacer(modifier = Modifier.height(12.dp))
                            Box(
                                modifier = Modifier
                                    .width(36.dp)
                                    .height(4.dp)
                                    .align(Alignment.CenterHorizontally)
                                    .background(
                                        color = MaterialTheme.colorScheme.outlineVariant,
                                        shape = CircleShape
                                    )
                            )
                            Spacer(modifier = Modifier.height(20.dp))

                            // Drop cap title
                            Text(
                                text = titleAnnotated,
                                lineHeight = 30.sp,
                                color = MaterialTheme.colorScheme.onBackground
                            )

                            Spacer(modifier = Modifier.height(12.dp))

                            // Category · time
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = categoryDisplayName(art.category),
                                    style = MaterialTheme.typography.labelLarge.copy(
                                        fontWeight = FontWeight.SemiBold
                                    ),
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = " · ",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = formatRelativeTime(art.publishedAt),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "· ${art.sourceName}",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            Spacer(modifier = Modifier.height(16.dp))

                            HorizontalDivider(
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                            )

                            Spacer(modifier = Modifier.height(16.dp))

                            // Description — HTML rendered via TextView
                            AndroidView(
                                modifier = Modifier.fillMaxWidth(),
                                factory = { ctx ->
                                    TextView(ctx).apply {
                                        setLineSpacing(0f, 1.6f)
                                    }
                                },
                                update = { tv ->
                                    tv.setTextColor(descriptionColor)
                                    tv.textSize = descriptionTextSize
                                    tv.text = HtmlCompat.fromHtml(
                                        art.description,
                                        HtmlCompat.FROM_HTML_MODE_COMPACT
                                    )
                                }
                            )

                            Spacer(modifier = Modifier.height(16.dp))
                            Spacer(modifier = Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
                            Spacer(modifier = Modifier.height(72.dp))
                        }
                    }

                    // ── Layer 3: Floating TopBar ──────────────────────────────
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .statusBarsPadding()
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = onBack,
                            modifier = Modifier
                                .size(40.dp)
                                .background(HeadlinrColors.categoryBubbleOverlay, CircleShape)
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back",
                                tint = HeadlinrColors.white,
                                modifier = Modifier.size(22.dp)
                            )
                        }

                        Spacer(modifier = Modifier.weight(1f))

                        // Source favicon + name — centre of TopBar
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            val favicon = sourceFaviconUrl(art.articleUrl)
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .background(HeadlinrColors.white, CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                if (favicon != null) {
                                    SubcomposeAsyncImage(
                                        model = favicon,
                                        contentDescription = art.sourceName,
                                        contentScale = ContentScale.Fit,
                                        modifier = Modifier
                                            .size(22.dp)
                                            .clip(CircleShape),
                                        error = {
                                            Text(
                                                text = art.sourceName
                                                    .split(" ").take(2)
                                                    .joinToString("") { it.first().uppercase() },
                                                style = MaterialTheme.typography.labelSmall.copy(
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 9.sp
                                                ),
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                        }
                                    )
                                } else {
                                    Text(
                                        text = art.sourceName
                                            .split(" ").take(2)
                                            .joinToString("") { it.first().uppercase() },
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 9.sp
                                        ),
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }
                            Text(
                                text = art.sourceName,
                                style = MaterialTheme.typography.titleSmall.copy(
                                    fontWeight = FontWeight.Bold
                                ),
                                color = HeadlinrColors.white
                            )
                        }

                        Spacer(modifier = Modifier.weight(1f))

                        IconButton(
                            onClick = {
                                context.startActivity(
                                    Intent.createChooser(
                                        Intent().apply {
                                            action = Intent.ACTION_SEND
                                            putExtra(Intent.EXTRA_TEXT, art.articleUrl)
                                            type = "text/plain"
                                        }, null
                                    )
                                )
                            },
                            modifier = Modifier
                                .size(40.dp)
                                .background(HeadlinrColors.categoryBubbleOverlay, CircleShape)
                        ) {
                            Icon(
                                Icons.Default.Share,
                                contentDescription = "Share",
                                tint = HeadlinrColors.white,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }

                    // ── Layer 4: Fixed BottomBar ──────────────────────────────
                    Row(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.background)
                            .navigationBarsPadding()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Read Full Article →
                        Row(
                            modifier = Modifier
                                .clickable { onReadMore(art.articleUrl) }
                                .padding(vertical = 8.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = "Read Full Article",
                                style = MaterialTheme.typography.labelLarge.copy(
                                    fontWeight = FontWeight.SemiBold
                                ),
                                color = MaterialTheme.colorScheme.primary
                            )
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowForward,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }

                        Spacer(modifier = Modifier.weight(1f))

                        // Bookmark
                        IconButton(
                            onClick = { viewModel.toggleBookmark(articleId) },
                            modifier = Modifier.size(40.dp)
                        ) {
                            Icon(
                                imageVector = if (isBookmarked) Icons.Filled.Bookmark
                                else Icons.Outlined.BookmarkBorder,
                                contentDescription = "Bookmark",
                                modifier = Modifier.size(24.dp),
                                tint = if (isBookmarked) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun formatRelativeTime(millis: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - millis
    return when {
        diff < 60 * 60 * 1000 -> "${diff / (60 * 1000)} min ago"
        diff < 24 * 60 * 60 * 1000 -> "${diff / (60 * 60 * 1000)} hour ago"
        else -> SimpleDateFormat("MMM dd", Locale.getDefault()).format(Date(millis))
    }
}
