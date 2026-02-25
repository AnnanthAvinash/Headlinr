package avinash.app.headlinr.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import avinash.app.news.api.NewsRepository
import avinash.app.news.api.model.Category
import avinash.app.news.api.model.NewsArticle
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class NewsViewModel @Inject constructor(
    private val newsRepository: NewsRepository
) : ViewModel() {

    private val _selectedCategory = MutableStateFlow<String?>(null)
    val selectedCategory: StateFlow<String?> = _selectedCategory.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    val categories: StateFlow<List<Category>> = newsRepository.getCategories()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    @OptIn(ExperimentalCoroutinesApi::class)
    val articles: Flow<PagingData<NewsArticle>> = _selectedCategory
        .flatMapLatest { category ->
            newsRepository.getNewsPaged(category)
        }
        .cachedIn(viewModelScope)

    init {
        initialSync()
    }

    private fun initialSync() {
        viewModelScope.launch {
            _isRefreshing.value = true
            newsRepository.refreshCategories()
            newsRepository.refreshNews().onFailure { e ->
                _errorMessage.value = e.message ?: "Sync failed"
            }
            _isRefreshing.value = false
        }
    }

    fun selectCategory(category: String?) {
        _selectedCategory.value = category
    }

    fun refresh() {
        viewModelScope.launch {
            _isRefreshing.value = true
            _errorMessage.value = null
            newsRepository.refreshNews().onFailure { e ->
                _errorMessage.value = e.message ?: "Refresh failed"
            }
            _isRefreshing.value = false
        }
    }

    fun clearError() {
        _errorMessage.value = null
    }
}
