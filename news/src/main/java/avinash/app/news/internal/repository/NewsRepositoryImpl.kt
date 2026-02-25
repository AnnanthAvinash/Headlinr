package avinash.app.news.internal.repository

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.map
import avinash.app.news.api.NewsRepository
import avinash.app.news.api.model.Category
import avinash.app.news.api.model.NewsArticle
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
    }

    override fun getNewsPaged(category: String?): Flow<PagingData<NewsArticle>> {
        return Pager(
            config = PagingConfig(
                pageSize = PAGE_SIZE,
                prefetchDistance = 10,
                enablePlaceholders = false
            ),
            pagingSourceFactory = {
                if (category == null) {
                    articleDao.getArticlesPaged()
                } else {
                    articleDao.getArticlesByCategory(category)
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

    override suspend fun refreshCategories(): Result<Unit> = syncManager.syncCategories()

    override suspend fun getTrendingArticles(limit: Int): List<NewsArticle> {
        return articleDao.getTrendingArticles(limit).map { it.toDomain() }
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
