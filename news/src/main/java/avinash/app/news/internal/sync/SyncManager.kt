package avinash.app.news.internal.sync

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import avinash.app.news.internal.local.LocalCategories
import avinash.app.news.internal.local.dao.ArticleDao
import avinash.app.news.internal.local.dao.CategoryDao
import avinash.app.news.internal.remote.FirebaseNewsSource
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import timber.log.Timber
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

private val Context.syncDataStore: DataStore<Preferences> by preferencesDataStore(name = "news_sync")

@Singleton
class SyncManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val firebaseNewsSource: FirebaseNewsSource,
    private val articleDao: ArticleDao,
    private val categoryDao: CategoryDao
) {
    companion object {
        private val KEY_LAST_SYNC = longPreferencesKey("last_sync_timestamp")
        private val KEY_CATEGORY_VERSION = intPreferencesKey("category_version")

        private const val MIN_SYNC_INTERVAL_MS = 10 * 60 * 1000L // 10 minutes
        private const val STALE_THRESHOLD_MS = 24 * 60 * 60 * 1000L // 24 hours
        private const val BATCH_SIZE = 50L
        private const val MAX_SYNC_CAP = 300L
    }

    suspend fun syncNews(): Result<Unit> = runCatching {
        val now = System.currentTimeMillis()
        val lastSync = getLastSyncTimestamp()

        val roomEmpty = articleDao.getCount() == 0

        if (!roomEmpty && lastSync > 0 && (now - lastSync) < MIN_SYNC_INTERVAL_MS) {
            Timber.d("Skipping sync — last sync was ${(now - lastSync) / 1000}s ago")
            return Result.success(Unit)
        }

        seedCategoriesIfNeeded()

        val gap = now - lastSync
        val needsFullSync = lastSync == 0L || gap > STALE_THRESHOLD_MS || roomEmpty

        if (needsFullSync) {
            Timber.d("Full sync required — gap: ${TimeUnit.MILLISECONDS.toHours(gap)}h")
            val articles = firebaseNewsSource.fetchLatestArticles(MAX_SYNC_CAP)
            if (articles.isNotEmpty()) {
                articleDao.upsertAll(articles)
                Timber.d("Full sync: ${articles.size} articles")
            }
        } else {
            Timber.d("Incremental sync — fetching since last sync")
            val articles = firebaseNewsSource.fetchArticlesSince(lastSync, BATCH_SIZE)
            if (articles.isNotEmpty()) {
                articleDao.upsertAll(articles)
                Timber.d("Incremental sync: ${articles.size} articles")
            }
        }

        updateLastSyncTimestamp(now)
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
