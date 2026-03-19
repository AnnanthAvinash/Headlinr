package avinash.app.news.internal.local.dao

import androidx.paging.PagingSource
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import avinash.app.news.internal.local.entity.ArticleEntity

data class SourceProjection(
    @ColumnInfo(name = "sourceName") val sourceName: String,
    @ColumnInfo(name = "articleUrl") val articleUrl: String
)

@Dao
interface ArticleDao {

    @Query("SELECT * FROM articles ORDER BY publishedAt DESC")
    fun getArticlesPaged(): PagingSource<Int, ArticleEntity>

    @Query("SELECT * FROM articles WHERE category = :category ORDER BY publishedAt DESC")
    fun getArticlesByCategory(category: String): PagingSource<Int, ArticleEntity>

    @Query("SELECT * FROM articles WHERE category IN (:categories) ORDER BY publishedAt DESC")
    fun getArticlesByCategories(categories: List<String>): PagingSource<Int, ArticleEntity>

    @Query("SELECT * FROM articles WHERE sourceName = :source ORDER BY publishedAt DESC")
    fun getArticlesBySource(source: String): PagingSource<Int, ArticleEntity>

    @Query("SELECT * FROM articles WHERE category = :category AND sourceName = :source ORDER BY publishedAt DESC")
    fun getArticlesByCategoryAndSource(category: String, source: String): PagingSource<Int, ArticleEntity>

    @Query("SELECT * FROM articles WHERE category IN (:categories) AND sourceName = :source ORDER BY publishedAt DESC")
    fun getArticlesByCategoriesAndSource(categories: List<String>, source: String): PagingSource<Int, ArticleEntity>

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

    @Query(
        "DELETE FROM articles WHERE cachedAt < :threshold " +
        "AND category IN (" +
        "SELECT category FROM articles GROUP BY category HAVING COUNT(*) >= :minCount" +
        ")"
    )
    suspend fun deleteStaleFromLargeCategories(threshold: Long, minCount: Int = 100)

    @Query(
        "SELECT * FROM articles WHERE trending = 1 " +
        "ORDER BY publishedAt DESC LIMIT :limit"
    )
    suspend fun getTrendingArticles(limit: Int = 15): List<ArticleEntity>

    @Query(
        "SELECT sourceName, articleUrl FROM articles " +
        "WHERE sourceName IS NOT NULL AND sourceName != '' " +
        "GROUP BY sourceName ORDER BY sourceName ASC"
    )
    suspend fun getDistinctSources(): List<SourceProjection>

    @Query(
        "SELECT * FROM articles WHERE title LIKE '%' || :query || '%' " +
        "OR category LIKE '%' || :query || '%' " +
        "OR description LIKE '%' || :query || '%' " +
        "OR sourceName LIKE '%' || :query || '%' " +
        "ORDER BY publishedAt DESC"
    )
    fun searchArticles(query: String): PagingSource<Int, ArticleEntity>

    @Query("SELECT * FROM articles WHERE id = :id LIMIT 1")
    suspend fun getArticleById(id: String): ArticleEntity?
}
