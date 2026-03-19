package avinash.app.news.internal.remote

import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import avinash.app.news.internal.local.entity.ArticleEntity
import kotlinx.coroutines.tasks.await
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.ceil

@Singleton
class FirebaseNewsSource @Inject constructor(
    private val firestore: FirebaseFirestore
) {
    companion object {
        private const val COLLECTION = "article_bundles"
        private const val BUNDLE_SIZE = 50
    }

    suspend fun fetchLatestArticles(limit: Long = 100): List<ArticleEntity> {
        val bundlesNeeded = ceil(limit / BUNDLE_SIZE.toDouble()).toLong()
        return try {
            val snapshot = firestore.collection(COLLECTION)
                .orderBy("ts", Query.Direction.DESCENDING)
                .limit(bundlesNeeded)
                .get()
                .await()

            val now = System.currentTimeMillis()
            val articles = mutableListOf<ArticleEntity>()

            for (doc in snapshot.documents) {
                @Suppress("UNCHECKED_CAST")
                val array = doc.get("a") as? List<Map<String, Any?>> ?: continue
                for (item in array) {
                    try {
                        val entity = ArticleEntity(
                            id = item["i"] as? String ?: continue,
                            title = item["t"] as? String ?: continue,
                            description = item["d"] as? String ?: "",
                            imageUrl = item["img"] as? String,
                            articleUrl = item["u"] as? String ?: continue,
                            publishedAt = (item["p"] as? Number)?.toLong() ?: 0L,
                            sourceName = item["s"] as? String ?: "",
                            category = item["c"] as? String ?: "nat",
                            trending = (item["tr"] as? Number)?.toInt() == 1,
                            cachedAt = now
                        )
                        articles.add(entity)
                    } catch (e: Exception) {
                        Timber.w(e, "Skipping malformed article in bundle ${doc.id}")
                    }
                }
            }

            articles
        } catch (e: Exception) {
            Timber.e(e, "Failed to fetch article bundles from Firebase")
            throw e
        }
    }

    suspend fun fetchArticlesSince(sinceTimestamp: Long, limit: Long = 50): List<ArticleEntity> {
        val bundlesNeeded = ceil(limit / BUNDLE_SIZE.toDouble()).toLong()
        return try {
            val snapshot = firestore.collection(COLLECTION)
                .whereGreaterThan("ts", sinceTimestamp)
                .orderBy("ts", Query.Direction.DESCENDING)
                .limit(bundlesNeeded)
                .get()
                .await()

            val now = System.currentTimeMillis()
            val articles = mutableListOf<ArticleEntity>()

            for (doc in snapshot.documents) {
                @Suppress("UNCHECKED_CAST")
                val array = doc.get("a") as? List<Map<String, Any?>> ?: continue
                for (item in array) {
                    try {
                        val pubMs = (item["p"] as? Number)?.toLong() ?: 0L
                        if (pubMs <= sinceTimestamp) continue
                        val entity = ArticleEntity(
                            id = item["i"] as? String ?: continue,
                            title = item["t"] as? String ?: continue,
                            description = item["d"] as? String ?: "",
                            imageUrl = item["img"] as? String,
                            articleUrl = item["u"] as? String ?: continue,
                            publishedAt = pubMs,
                            sourceName = item["s"] as? String ?: "",
                            category = item["c"] as? String ?: "nat",
                            trending = (item["tr"] as? Number)?.toInt() == 1,
                            cachedAt = now
                        )
                        articles.add(entity)
                    } catch (e: Exception) {
                        Timber.w(e, "Skipping malformed article in bundle ${doc.id}")
                    }
                }
            }

            articles
        } catch (e: Exception) {
            Timber.e(e, "Failed to fetch article bundles since $sinceTimestamp")
            throw e
        }
    }
}
