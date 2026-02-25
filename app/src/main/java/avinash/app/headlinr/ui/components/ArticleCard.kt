package avinash.app.headlinr.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import avinash.app.news.api.model.NewsArticle
import coil.compose.AsyncImage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ArticleCard(
    article: NewsArticle,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        shape = MaterialTheme.shapes.medium
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.Top
        ) {
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = article.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = article.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = article.sourceName,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = " \u2022 ",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = formatTimestamp(article.publishedAt),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (article.imageUrl != null) {
                Spacer(modifier = Modifier.width(12.dp))
                AsyncImage(
                    model = article.imageUrl,
                    contentDescription = null,
                    modifier = Modifier
                        .size(88.dp)
                        .clip(MaterialTheme.shapes.small),
                    contentScale = ContentScale.Crop
                )
            }
        }
    }
}

private fun formatTimestamp(millis: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - millis
    return when {
        diff < 60 * 60 * 1000 -> "${diff / (60 * 1000)}m ago"
        diff < 24 * 60 * 60 * 1000 -> "${diff / (60 * 60 * 1000)}h ago"
        else -> SimpleDateFormat("MMM dd", Locale.getDefault()).format(Date(millis))
    }
}
