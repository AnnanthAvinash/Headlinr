package avinash.app.news.internal.repository

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.map
import avinash.app.news.api.NewsRepository
import avinash.app.news.api.model.Category
import avinash.app.news.api.model.NewsArticle
import avinash.app.news.internal.local.dao.ArticleDao
import avinash.app.news.internal.local.dao.CategoryDao
import avinash.app.news.internal.local.mapper.toDomain
import avinash.app.news.internal.sync.SyncManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NewsRepositoryImpl @Inject constructor(
    private val articleDao: ArticleDao,
    private val categoryDao: CategoryDao,
    private val syncManager: SyncManager
) : NewsRepository {

    companion object {
        private const val PAGE_SIZE = 20
    }

    override fun getNewsPaged(category: String?): Flow<PagingData<NewsArticle>> {
        val pagingSource = if (category == null) {
            articleDao.getArticlesPaged()
        } else {
            articleDao.getArticlesByCategory(category)
        }

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

    override suspend fun refreshNews(): Result<Unit> {
        return syncManager.syncNews()
    }

    override suspend fun refreshCategories(): Result<Unit> {
        return syncManager.syncCategories()
    }
}
