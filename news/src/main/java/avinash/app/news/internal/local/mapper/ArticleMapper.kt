package avinash.app.news.internal.local.mapper

import avinash.app.news.api.model.NewsArticle
import avinash.app.news.internal.local.entity.ArticleEntity

fun ArticleEntity.toDomain(): NewsArticle = NewsArticle(
    id = id,
    title = title,
    description = description,
    imageUrl = imageUrl,
    articleUrl = articleUrl,
    publishedAt = publishedAt,
    sourceName = sourceName,
    category = category,
)

fun NewsArticle.toEntity(cachedAt: Long = System.currentTimeMillis()): ArticleEntity = ArticleEntity(
    id = id,
    title = title,
    description = description,
    imageUrl = imageUrl,
    articleUrl = articleUrl,
    publishedAt = publishedAt,
    sourceName = sourceName,
    category = category,
    cachedAt = cachedAt
)
