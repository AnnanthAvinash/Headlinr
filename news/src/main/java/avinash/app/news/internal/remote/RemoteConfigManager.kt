package avinash.app.news.internal.remote

import com.google.firebase.remoteconfig.FirebaseRemoteConfig
import com.google.firebase.remoteconfig.FirebaseRemoteConfigSettings
import kotlinx.coroutines.tasks.await
import org.json.JSONArray
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RemoteConfigManager @Inject constructor() {

    private val remoteConfig: FirebaseRemoteConfig = FirebaseRemoteConfig.getInstance()

    companion object {
        private const val KEY_SYNC_INTERVAL = "sync_interval_minutes"
        private const val KEY_TRENDING_LIMIT = "trending_article_limit"
        private const val KEY_TRENDING_SOURCE = "trending_topics_source"
        private const val KEY_TRENDING_LIST = "trending_topics_list"
        private const val KEY_FORCE_UPDATE = "force_update_enabled"
        private const val KEY_FORCE_UPDATE_VERSION = "force_update_version"
        private const val KEY_MAINTENANCE = "maintenance_enabled"
        private const val KEY_ADS = "ads_enabled"
        private const val KEY_BOOKMARKS = "bookmarks_enabled"
        private const val KEY_SEARCH = "search_enabled"
        private const val KEY_CATEGORY_SORT = "category_sort_override"

        private val DEFAULTS = mapOf(
            KEY_SYNC_INTERVAL to 5L,
            KEY_TRENDING_LIMIT to 10L,
            KEY_TRENDING_SOURCE to "local",
            KEY_TRENDING_LIST to "[]",
            KEY_FORCE_UPDATE to false,
            KEY_FORCE_UPDATE_VERSION to 0L,
            KEY_MAINTENANCE to false,
            KEY_ADS to false,
            KEY_BOOKMARKS to true,
            KEY_SEARCH to true,
            KEY_CATEGORY_SORT to "[]",
        )
    }

    init {
        val settings = FirebaseRemoteConfigSettings.Builder()
            .setMinimumFetchIntervalInSeconds(3600)
            .build()
        remoteConfig.setConfigSettingsAsync(settings)
        remoteConfig.setDefaultsAsync(DEFAULTS)
    }

    suspend fun fetchAndActivate(): Boolean {
        return try {
            remoteConfig.fetchAndActivate().await()
        } catch (e: Exception) {
            Timber.w(e, "Remote config fetch failed, using defaults")
            false
        }
    }

    val syncIntervalMinutes: Long
        get() = remoteConfig.getLong(KEY_SYNC_INTERVAL)

    val trendingArticleLimit: Int
        get() = remoteConfig.getLong(KEY_TRENDING_LIMIT).toInt()

    val trendingTopicsSource: String
        get() = remoteConfig.getString(KEY_TRENDING_SOURCE)

    val trendingTopicsList: List<String>
        get() = try {
            val json = remoteConfig.getString(KEY_TRENDING_LIST)
            val arr = JSONArray(json)
            (0 until arr.length()).map { arr.getString(it) }
        } catch (e: Exception) {
            emptyList()
        }

    val forceUpdateEnabled: Boolean
        get() = remoteConfig.getBoolean(KEY_FORCE_UPDATE)

    val forceUpdateVersion: Long
        get() = remoteConfig.getLong(KEY_FORCE_UPDATE_VERSION)

    val maintenanceEnabled: Boolean
        get() = remoteConfig.getBoolean(KEY_MAINTENANCE)

    val adsEnabled: Boolean
        get() = remoteConfig.getBoolean(KEY_ADS)

    val bookmarksEnabled: Boolean
        get() = remoteConfig.getBoolean(KEY_BOOKMARKS)

    val searchEnabled: Boolean
        get() = remoteConfig.getBoolean(KEY_SEARCH)

    val categorySortOverride: List<String>
        get() = try {
            val json = remoteConfig.getString(KEY_CATEGORY_SORT)
            val arr = JSONArray(json)
            (0 until arr.length()).map { arr.getString(it) }
        } catch (e: Exception) {
            emptyList()
        }
}
