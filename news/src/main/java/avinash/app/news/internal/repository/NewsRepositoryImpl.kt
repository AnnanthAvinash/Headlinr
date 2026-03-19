package avinash.app.news.internal.repository

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.map
import avinash.app.news.api.NewsRepository
import avinash.app.news.api.model.Category
import avinash.app.news.api.model.NewsArticle
import avinash.app.news.api.model.SourceEntry
import avinash.app.news.internal.local.dao.ArticleDao
import avinash.app.news.internal.local.dao.BookmarkDao
import avinash.app.news.internal.local.dao.CategoryDao
import avinash.app.news.internal.local.mapper.toBookmarkEntity
import avinash.app.news.internal.local.mapper.toDomain
import avinash.app.news.internal.remote.RemoteConfigManager
import avinash.app.news.internal.sync.SyncManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NewsRepositoryImpl @Inject constructor(
    private val articleDao: ArticleDao,
    private val categoryDao: CategoryDao,
    private val bookmarkDao: BookmarkDao,
    private val syncManager: SyncManager,
    private val remoteConfigManager: RemoteConfigManager
) : NewsRepository {

    companion object {
        private const val PAGE_SIZE = 20

        private val CATEGORY_EXPANSION = mapOf(
            "nat" to listOf("nat", "pol", "cri", "int", "def"),
            "bus" to listOf("bus", "stk"),
            "tec" to listOf("tec", "sci"),
        )
    }

    private fun expandCategory(category: String): List<String>? =
        CATEGORY_EXPANSION[category]

    override fun getNewsPaged(category: String?, source: String?): Flow<PagingData<NewsArticle>> {
        return Pager(
            config = PagingConfig(
                pageSize = PAGE_SIZE,
                prefetchDistance = 10,
                enablePlaceholders = false
            ),
            pagingSourceFactory = {
                when {
                    category != null && source != null -> {
                        val expanded = expandCategory(category)
                        if (expanded != null) articleDao.getArticlesByCategoriesAndSource(expanded, source)
                        else articleDao.getArticlesByCategoryAndSource(category, source)
                    }
                    source != null ->
                        articleDao.getArticlesBySource(source)
                    category != null -> {
                        val expanded = expandCategory(category)
                        if (expanded != null) articleDao.getArticlesByCategories(expanded)
                        else articleDao.getArticlesByCategory(category)
                    }
                    else ->
                        articleDao.getArticlesPaged()
                }
            }
        ).flow.map { pagingData ->
            pagingData.map { it.toDomain() }
        }
    }

    override fun getCategories(): Flow<List<Category>> {
        return categoryDao.getCategories().map { entities ->
            entities.map { it.toDomain() }
        }
    }

    override suspend fun refreshNews(): Result<Unit> = syncManager.syncNews()

    override suspend fun refreshCategories(): Result<Unit> = Result.success(Unit)

    override suspend fun getArticleById(id: String): NewsArticle? {
        articleDao.getArticleById(id)?.let { return it.toDomain() }
        bookmarkDao.getBookmarkById(id)?.let { return it.toDomain() }
        return null
    }

    override suspend fun getTrendingArticles(limit: Int): List<NewsArticle> {
        return articleDao.getTrendingArticles(limit).map { it.toDomain() }
    }

    override suspend fun getDistinctSources(): List<SourceEntry> {
        return articleDao.getDistinctSources().map { SourceEntry(it.sourceName, it.articleUrl) }
    }

    override fun searchArticles(query: String): Flow<PagingData<NewsArticle>> {
        return Pager(
            config = PagingConfig(
                pageSize = PAGE_SIZE,
                prefetchDistance = 10,
                enablePlaceholders = false
            ),
            pagingSourceFactory = { articleDao.searchArticles(query) }
        ).flow.map { pagingData ->
            pagingData.map { it.toDomain() }
        }
    }

    override suspend fun bookmarkArticle(article: NewsArticle) {
        bookmarkDao.upsert(article.toBookmarkEntity())
    }

    override suspend fun removeBookmark(articleId: String) {
        bookmarkDao.delete(articleId)
    }

    override suspend fun isBookmarked(articleId: String): Boolean {
        return bookmarkDao.isBookmarked(articleId)
    }

    override fun observeIsBookmarked(articleId: String): Flow<Boolean> {
        return bookmarkDao.observeIsBookmarked(articleId)
    }

    override fun getBookmarks(): Flow<List<NewsArticle>> {
        return bookmarkDao.getBookmarks().map { entities ->
            entities.map { it.toDomain() }
        }
    }

    override fun getRemoteConfig(): RemoteConfigManager = remoteConfigManager
}
