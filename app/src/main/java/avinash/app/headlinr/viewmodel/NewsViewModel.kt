package avinash.app.headlinr.viewmodel

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import avinash.app.news.api.NewsRepository
import avinash.app.news.api.model.Category
import avinash.app.news.api.model.NewsArticle
import avinash.app.news.api.model.SourceEntry
import avinash.app.news.api.model.SyncResult
import avinash.app.news.api.model.SyncTrigger
import avinash.app.news.internal.remote.RemoteConfigManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import javax.inject.Inject

private val Context.appPrefs: DataStore<Preferences> by preferencesDataStore(name = "headlinr_prefs")

@HiltViewModel
class NewsViewModel @Inject constructor(
    private val newsRepository: NewsRepository,
    @ApplicationContext private val context: Context
) : ViewModel() {

    companion object {
        private val KEY_HAS_CATEGORIES = booleanPreferencesKey("has_selected_categories")
        private val KEY_SELECTED_CATEGORIES = stringSetPreferencesKey("selected_categories")
        private val KEY_RECENT_SEARCHES = stringPreferencesKey("recent_searches")
        private const val MAX_RECENT = 10
    }

    val remoteConfig: RemoteConfigManager get() = newsRepository.getRemoteConfig()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _snackbarMessage = MutableStateFlow<String?>(null)
    val snackbarMessage: StateFlow<String?> = _snackbarMessage.asStateFlow()

    private val _trendingArticles = MutableStateFlow<List<NewsArticle>>(emptyList())
    val trendingArticles: StateFlow<List<NewsArticle>> = _trendingArticles.asStateFlow()

    private val _searchQuery = MutableStateFlow("")

    private val allCategories: StateFlow<List<Category>> = newsRepository.getCategories()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    private val _selectedCategorySlugs = MutableStateFlow<Set<String>>(emptySet())

    val userCategories: StateFlow<List<Category>> = combine(
        allCategories,
        _selectedCategorySlugs
    ) { all, selected ->
        if (selected.isEmpty()) all else all.filter { it.slug in selected }
    }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val categories: StateFlow<List<Category>> get() = allCategories

    private val _activeCategory = MutableStateFlow<String?>(null)
    val activeCategory: StateFlow<String?> = _activeCategory.asStateFlow()

    private val _activeSource = MutableStateFlow<String?>(null)
    val activeSource: StateFlow<String?> = _activeSource.asStateFlow()

    private val _availableSources = MutableStateFlow<List<SourceEntry>>(emptyList())
    val availableSources: StateFlow<List<SourceEntry>> = _availableSources.asStateFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    val articles: Flow<PagingData<NewsArticle>> = combine(_activeCategory, _activeSource) { cat, src ->
        cat to src
    }.flatMapLatest { (cat, src) ->
        newsRepository.getNewsPaged(category = cat, source = src)
    }.cachedIn(viewModelScope)

    @OptIn(ExperimentalCoroutinesApi::class)
    val searchResults: Flow<PagingData<NewsArticle>> = _searchQuery
        .flatMapLatest { query ->
            if (query.isBlank()) MutableStateFlow(PagingData.empty())
            else newsRepository.searchArticles(query)
        }
        .cachedIn(viewModelScope)

    val bookmarks: StateFlow<List<NewsArticle>> = newsRepository.getBookmarks()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val bookmarkedIds: StateFlow<Set<String>> = newsRepository.getBookmarks()
        .map { list -> list.map { it.id }.toSet() }
        .stateIn(viewModelScope, SharingStarted.Lazily, emptySet())

    private val _recentSearches = MutableStateFlow<List<String>>(emptyList())
    val recentSearches: StateFlow<List<String>> = _recentSearches.asStateFlow()

    private val _trendingTopics = MutableStateFlow<List<String>>(emptyList())
    val trendingTopics: StateFlow<List<String>> = _trendingTopics.asStateFlow()

    init {
        loadSelectedCategorySlugs()
        initialSync()
        loadRecentSearches()
    }

    fun selectCategory(slug: String?) {
        _activeCategory.value = slug
    }

    fun selectSource(sourceName: String?) {
        _activeSource.value = sourceName
    }

    private fun initialSync() {
        viewModelScope.launch {
            _isRefreshing.value = true
            remoteConfig.fetchAndActivate()
            newsRepository.refreshCategories()
            newsRepository.refreshNews(SyncTrigger.APP_OPEN)
                .onSuccess { handleSyncResult(it) }
                .onFailure { e -> _errorMessage.value = e.message ?: "Sync failed" }
            loadTrendingArticles()
            loadTrendingTopics()
            loadAvailableSources()
            _isRefreshing.value = false
        }
    }

    private fun loadTrendingArticles() {
        viewModelScope.launch {
            val limit = remoteConfig.trendingArticleLimit
            _trendingArticles.value = newsRepository.getTrendingArticles(limit)
        }
    }

    private fun loadAvailableSources() {
        viewModelScope.launch {
            _availableSources.value = newsRepository.getDistinctSources()
        }
    }

    private fun loadTrendingTopics() {
        viewModelScope.launch {
            val source = remoteConfig.trendingTopicsSource
            val topics = if (source == "remote") {
                remoteConfig.trendingTopicsList
            } else {
                categories.value.map { it.name }
            }
            _trendingTopics.value = topics
        }
    }

    fun refresh() {
        viewModelScope.launch {
            _isRefreshing.value = true
            _errorMessage.value = null
            newsRepository.refreshNews(SyncTrigger.PULL_TO_REFRESH)
                .onSuccess { handleSyncResult(it) }
                .onFailure { e -> _errorMessage.value = e.message ?: "Refresh failed" }
            loadTrendingArticles()
            loadAvailableSources()
            _isRefreshing.value = false
        }
    }

    fun search(query: String) {
        _searchQuery.value = query
        addRecentSearch(query)
    }

    fun clearError() {
        _errorMessage.value = null
    }

    fun clearSnackbar() {
        _snackbarMessage.value = null
    }

    private fun handleSyncResult(result: SyncResult) {
        when (result) {
            SyncResult.COOLDOWN -> _snackbarMessage.value = "You already have the latest news"
            SyncResult.BUCKET_EXHAUSTED -> _snackbarMessage.value = "Refresh available after next time window"
            SyncResult.QUOTA_EXHAUSTED -> _snackbarMessage.value = "Daily refresh limit reached"
            SyncResult.SUCCESS, SyncResult.SKIPPED -> { }
        }
    }

    // --- Bookmarks ---

    fun toggleBookmark(articleId: String) {
        viewModelScope.launch {
            if (newsRepository.isBookmarked(articleId)) {
                newsRepository.removeBookmark(articleId)
            } else {
                val article = findArticleById(articleId) ?: return@launch
                newsRepository.bookmarkArticle(article)
            }
        }
    }

    fun observeBookmark(articleId: String): Flow<Boolean> {
        return newsRepository.observeIsBookmarked(articleId)
    }

    suspend fun getArticleById(articleId: String): NewsArticle? {
        return newsRepository.getArticleById(articleId)
    }

    private fun findArticleById(articleId: String): NewsArticle? {
        return trendingArticles.value.find { it.id == articleId }
            ?: bookmarks.value.find { it.id == articleId }
    }

    // --- Category selection (onboarding) ---

    private val slugMigration = mapOf(
        "pol" to "nat", "cri" to "nat",
        "stk" to "bus",
        "sci" to "tec",
    )

    private fun loadSelectedCategorySlugs() {
        viewModelScope.launch {
            val prefs = context.appPrefs.data.first()
            val slugs = prefs[KEY_SELECTED_CATEGORIES] ?: emptySet()
            val migrated = slugs.map { slugMigration[it] ?: it }.toSet()
            if (migrated != slugs) {
                context.appPrefs.edit { it[KEY_SELECTED_CATEGORIES] = migrated }
            }
            _selectedCategorySlugs.value = migrated
        }
    }

    fun hasSelectedCategories(): Boolean {
        return runBlocking {
            context.appPrefs.data.first()[KEY_HAS_CATEGORIES] ?: false
        }
    }

    fun saveSelectedCategories(slugs: Set<String>) {
        viewModelScope.launch {
            context.appPrefs.edit { prefs ->
                prefs[KEY_HAS_CATEGORIES] = true
                prefs[KEY_SELECTED_CATEGORIES] = slugs
            }
            _selectedCategorySlugs.value = slugs
        }
    }

    // --- Recent searches ---

    private fun loadRecentSearches() {
        viewModelScope.launch {
            val prefs = context.appPrefs.data.first()
            val json = prefs[KEY_RECENT_SEARCHES] ?: "[]"
            try {
                val arr = JSONArray(json)
                _recentSearches.value = (0 until arr.length()).map { arr.getString(it) }
            } catch (_: Exception) {
                _recentSearches.value = emptyList()
            }
        }
    }

    private fun addRecentSearch(query: String) {
        viewModelScope.launch {
            val current = _recentSearches.value.toMutableList()
            current.remove(query)
            current.add(0, query)
            val trimmed = current.take(MAX_RECENT)
            _recentSearches.value = trimmed
            context.appPrefs.edit { prefs ->
                prefs[KEY_RECENT_SEARCHES] = JSONArray(trimmed).toString()
            }
        }
    }

    fun clearRecentSearches() {
        viewModelScope.launch {
            _recentSearches.value = emptyList()
            context.appPrefs.edit { prefs ->
                prefs[KEY_RECENT_SEARCHES] = "[]"
            }
        }
    }
}
