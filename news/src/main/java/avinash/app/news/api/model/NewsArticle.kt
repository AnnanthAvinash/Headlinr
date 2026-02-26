package avinash.app.news.api.model

data class NewsArticle(
    val id: String,
    val title: String,
    val description: String,
    val imageUrl: String?,
    val articleUrl: String,
    val publishedAt: Long,
    val sourceName: String,
    val category: String,
    val quality: String = "high"
)
