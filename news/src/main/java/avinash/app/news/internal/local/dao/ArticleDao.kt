package avinash.app.news.internal.local.dao

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import avinash.app.news.internal.local.entity.ArticleEntity

@Dao
interface ArticleDao {

    @Query("SELECT * FROM articles ORDER BY publishedAt DESC")
    fun getArticlesPaged(): PagingSource<Int, ArticleEntity>

    @Query("SELECT * FROM articles WHERE category = :category ORDER BY publishedAt DESC")
    fun getArticlesByCategory(category: String): PagingSource<Int, ArticleEntity>

    @Query("SELECT MAX(publishedAt) FROM articles")
    suspend fun getNewestTimestamp(): Long?

    @Query("SELECT COUNT(*) FROM articles")
    suspend fun getCount(): Int

    @Upsert
    suspend fun upsertAll(articles: List<ArticleEntity>)

    @Query("DELETE FROM articles")
    suspend fun deleteAll()

    @Query("DELETE FROM articles WHERE cachedAt < :threshold")
    suspend fun deleteOlderThan(threshold: Long)
}
