package avinash.app.news.internal.sync

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import avinash.app.news.internal.local.dao.ArticleDao
import avinash.app.news.internal.local.dao.CategoryDao
import avinash.app.news.internal.remote.FirebaseCategorySource
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
    private val firebaseCategorySource: FirebaseCategorySource,
    private val articleDao: ArticleDao,
    private val categoryDao: CategoryDao
) {
    companion object {
        private val KEY_LAST_SYNC = longPreferencesKey("last_sync_timestamp")
        private val KEY_LAST_CATEGORY_SYNC = longPreferencesKey("last_category_sync_timestamp")

        private const val MIN_SYNC_INTERVAL_MS = 10 * 60 * 1000L // 10 minutes
        private const val STALE_THRESHOLD_MS = 24 * 60 * 60 * 1000L // 24 hours
        private const val CATEGORY_SYNC_INTERVAL_MS = 24 * 60 * 60 * 1000L // 24 hours
        private const val BATCH_SIZE = 50L
        private const val MAX_SYNC_CAP = 300
        private const val OLD_DATA_TTL_MS = 7L * 24 * 60 * 60 * 1000 // 7 days
        private const val MIN_CATEGORY_RETENTION = 100
    }

    suspend fun syncNews(): Result<Unit> = runCatching {
        val now = System.currentTimeMillis()
        val lastSync = getLastSyncTimestamp()

        if (lastSync > 0 && (now - lastSync) < MIN_SYNC_INTERVAL_MS) {
            Timber.d("Skipping sync — last sync was ${(now - lastSync) / 1000}s ago")
            return Result.success(Unit)
        }

        val gap = now - lastSync
        val needsFullSync = lastSync == 0L || gap > STALE_THRESHOLD_MS

        if (needsFullSync) {
            Timber.d("Full sync required — gap: ${TimeUnit.MILLISECONDS.toHours(gap)}h")
            val limit = MAX_SYNC_CAP.toLong()
            val high = firebaseNewsSource.fetchLatestArticles(limit, quality = "high")
            val articles = if (high.size < MAX_SYNC_CAP) {
                val gapSize = MAX_SYNC_CAP - high.size
                high + firebaseNewsSource.fetchLatestArticles(gapSize.toLong(), quality = "low")
            } else high
            if (articles.isNotEmpty()) {
                articleDao.upsertAll(articles)
                Timber.d("Full sync: ${articles.size} articles (high=${high.size}, low=${articles.size - high.size})")
            }
        } else {
            Timber.d("Incremental sync — fetching since last sync")
            val high = firebaseNewsSource.fetchArticlesSince(lastSync, BATCH_SIZE, quality = "high")
            val articles = if (high.size < BATCH_SIZE.toInt()) {
                val gapSize = BATCH_SIZE - high.size
                high + firebaseNewsSource.fetchArticlesSince(lastSync, gapSize, quality = "low")
            } else high
            if (articles.isNotEmpty()) {
                articleDao.upsertAll(articles)
                Timber.d("Incremental sync: ${articles.size} articles (high=${high.size}, low=${articles.size - high.size})")
            }
        }

        updateLastSyncTimestamp(now)
    }

    suspend fun syncCategories(): Result<Unit> = runCatching {
        val now = System.currentTimeMillis()
        val lastCategorySync = getLastCategorySyncTimestamp()

        if (lastCategorySync > 0 && (now - lastCategorySync) < CATEGORY_SYNC_INTERVAL_MS) {
            Timber.d("Skipping category sync — still fresh")
            return Result.success(Unit)
        }

        val categories = firebaseCategorySource.fetchCategories()
        if (categories.isNotEmpty()) {
            categoryDao.upsertAll(categories)
            Timber.d("Synced ${categories.size} categories")
        }
        updateLastCategorySyncTimestamp(now)
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

    private suspend fun getLastCategorySyncTimestamp(): Long {
        val prefs = context.syncDataStore.data.first()
        return prefs[KEY_LAST_CATEGORY_SYNC] ?: 0L
    }

    private suspend fun updateLastCategorySyncTimestamp(timestamp: Long) {
        context.syncDataStore.edit { prefs ->
            prefs[KEY_LAST_CATEGORY_SYNC] = timestamp
        }
    }
}
