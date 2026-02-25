package avinash.app.news.internal.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "bookmarks")
data class BookmarkEntity(
    @PrimaryKey val articleId: String,
    val title: String,
    val description: String,
    val imageUrl: String?,
    val articleUrl: String,
    val publishedAt: Long,
    val sourceName: String,
    val category: String,
    val bookmarkedAt: Long
)
