package avinash.app.news.api

import androidx.paging.PagingData
import avinash.app.news.api.model.Category
import avinash.app.news.api.model.NewsArticle
import avinash.app.news.api.model.SourceEntry
import avinash.app.news.api.model.SyncResult
import avinash.app.news.api.model.SyncTrigger
import avinash.app.news.internal.remote.RemoteConfigManager
import kotlinx.coroutines.flow.Flow

interface NewsRepository {
    fun getNewsPaged(category: String? = null, source: String? = null): Flow<PagingData<NewsArticle>>
    fun getCategories(): Flow<List<Category>>
    suspend fun refreshNews(trigger: SyncTrigger): Result<SyncResult>
    suspend fun refreshCategories(): Result<Unit>

    suspend fun getArticleById(id: String): NewsArticle?
    suspend fun getTrendingArticles(limit: Int = 15): List<NewsArticle>
    suspend fun getDistinctSources(): List<SourceEntry>
    fun searchArticles(query: String): Flow<PagingData<NewsArticle>>

    suspend fun bookmarkArticle(article: NewsArticle)
    suspend fun removeBookmark(articleId: String)
    suspend fun isBookmarked(articleId: String): Boolean
    fun observeIsBookmarked(articleId: String): Flow<Boolean>
    fun getBookmarks(): Flow<List<NewsArticle>>

    fun getRemoteConfig(): RemoteConfigManager
}
