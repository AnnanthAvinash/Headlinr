package avinash.app.news.internal.sync

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import avinash.app.news.api.model.SyncResult
import avinash.app.news.api.model.SyncTrigger
import avinash.app.news.internal.local.LocalCategories
import avinash.app.news.internal.local.dao.ArticleDao
import avinash.app.news.internal.local.dao.CategoryDao
import avinash.app.news.internal.remote.FirebaseNewsSource
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

private val Context.syncDataStore: DataStore<Preferences> by preferencesDataStore(name = "news_sync")

@Singleton
class SyncManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val firebaseNewsSource: FirebaseNewsSource,
    private val articleDao: ArticleDao,
    private val categoryDao: CategoryDao,
    private val fetchQuotaManager: FetchQuotaManager
) {
    companion object {
        private val KEY_LAST_SYNC = longPreferencesKey("last_sync_timestamp")
        private val KEY_CATEGORY_VERSION = intPreferencesKey("category_version")

        private const val MIN_SYNC_INTERVAL_MS = 30_000L // 30 seconds
    }

    suspend fun syncNews(trigger: SyncTrigger): Result<SyncResult> = runCatching {
        val now = System.currentTimeMillis()
        val lastSync = getLastSyncTimestamp()
        val roomEmpty = articleDao.getCount() == 0

        seedCategoriesIfNeeded()

        if (roomEmpty) {
            Timber.d("Room empty — force fetch (first install), bypassing quota")
            val articles = firebaseNewsSource.fetchLatestArticles()
            if (articles.isNotEmpty()) {
                articleDao.upsertAll(articles)
                Timber.d("First-install sync: ${articles.size} articles")
            }
            fetchQuotaManager.recordFetch()
            updateLastSyncTimestamp(now)
            return Result.success(SyncResult.SUCCESS)
        }

        if (lastSync > 0 && (now - lastSync) < MIN_SYNC_INTERVAL_MS) {
            Timber.d("Cooldown — last sync was ${(now - lastSync) / 1000}s ago")
            return Result.success(SyncResult.COOLDOWN)
        }

        if (!fetchQuotaManager.canFetch()) {
            val remaining = fetchQuotaManager.getRemainingInBucket()
            val dailyRemaining = fetchQuotaManager.getRemainingDaily()
            return if (remaining == 0 && dailyRemaining > 0) {
                Timber.d("Bucket exhausted for current time window")
                Result.success(SyncResult.BUCKET_EXHAUSTED)
            } else {
                Timber.d("Daily quota exhausted")
                Result.success(SyncResult.QUOTA_EXHAUSTED)
            }
        }

        Timber.d("Fetching articles — trigger: ${trigger.name}")
        val articles = if (lastSync > 0) {
            firebaseNewsSource.fetchArticlesSince(lastSync)
        } else {
            firebaseNewsSource.fetchLatestArticles()
        }

        if (articles.isNotEmpty()) {
            articleDao.upsertAll(articles)
            Timber.d("Synced ${articles.size} articles")
        }

        fetchQuotaManager.recordFetch()
        updateLastSyncTimestamp(now)
        SyncResult.SUCCESS
    }

    private suspend fun seedCategoriesIfNeeded() {
        val prefs = context.syncDataStore.data.first()
        val storedVersion = prefs[KEY_CATEGORY_VERSION] ?: 0

        if (categoryDao.getCount() == 0 || storedVersion < LocalCategories.VERSION) {
            categoryDao.deleteAll()
            categoryDao.upsertAll(LocalCategories.ALL)
            context.syncDataStore.edit { it[KEY_CATEGORY_VERSION] = LocalCategories.VERSION }
            Timber.d("Seeded ${LocalCategories.ALL.size} categories (v${LocalCategories.VERSION})")
        }
    }

    private suspend fun getLastSyncTimestamp(): Long {
        val prefs = context.syncDataStore.data.first()
        return prefs[KEY_LAST_SYNC] ?: 0L
    }

    private suspend fun updateLastSyncTimestamp(timestamp: Long) {
        context.syncDataStore.edit { prefs ->
            prefs[KEY_LAST_SYNC] = timestamp
        }
    }
}
