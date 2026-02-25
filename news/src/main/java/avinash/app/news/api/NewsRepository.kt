package avinash.app.news.api

import androidx.paging.PagingData
import avinash.app.news.api.model.Category
import avinash.app.news.api.model.NewsArticle
import kotlinx.coroutines.flow.Flow

interface NewsRepository {
    fun getNewsPaged(category: String? = null): Flow<PagingData<NewsArticle>>
    fun getCategories(): Flow<List<Category>>
    suspend fun refreshNews(): Result<Unit>
    suspend fun refreshCategories(): Result<Unit>
}
