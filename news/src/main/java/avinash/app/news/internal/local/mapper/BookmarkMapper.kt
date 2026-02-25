package avinash.app.news.internal.local.mapper

import avinash.app.news.api.model.NewsArticle
import avinash.app.news.internal.local.entity.BookmarkEntity

fun BookmarkEntity.toDomain(): NewsArticle = NewsArticle(
    id = articleId,
    title = title,
    description = description,
    imageUrl = imageUrl,
    articleUrl = articleUrl,
    publishedAt = publishedAt,
    sourceName = sourceName,
    category = category
)

fun NewsArticle.toBookmarkEntity(): BookmarkEntity = BookmarkEntity(
    articleId = id,
    title = title,
    description = description,
    imageUrl = imageUrl,
    articleUrl = articleUrl,
    publishedAt = publishedAt,
    sourceName = sourceName,
    category = category,
    bookmarkedAt = System.currentTimeMillis()
)
