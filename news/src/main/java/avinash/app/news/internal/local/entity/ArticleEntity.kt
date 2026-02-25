package avinash.app.news.internal.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "articles",
    indices = [
        Index(value = ["category", "publishedAt"]),
        Index(value = ["publishedAt"]),
        Index(value = ["articleUrl"], unique = true)
    ]
)
data class ArticleEntity(
    @PrimaryKey val id: String,
    val title: String,
    val description: String,
    val imageUrl: String?,
    val articleUrl: String,
    val publishedAt: Long,
    val sourceName: String,
    val category: String,
    val cachedAt: Long
)
