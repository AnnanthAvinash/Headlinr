package avinash.app.news.internal.remote

import com.google.firebase.Timestamp
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import avinash.app.news.internal.local.entity.ArticleEntity
import kotlinx.coroutines.tasks.await
import timber.log.Timber
import java.util.Date
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FirebaseNewsSource @Inject constructor(
    private val firestore: FirebaseFirestore
) {
    companion object {
        private const val COLLECTION = "news_articles"
    }

    suspend fun fetchArticlesSince(
        sinceTimestamp: Long,
        limit: Long = 50,
        quality: String = "high"
    ): List<ArticleEntity> {
        return try {
            val snapshot = firestore.collection(COLLECTION)
                .whereEqualTo("quality", quality)
                .whereGreaterThan("publishedAt", Timestamp(Date(sinceTimestamp)))
                .orderBy("publishedAt", Query.Direction.DESCENDING)
                .limit(limit)
                .get()
                .await()

            val now = System.currentTimeMillis()
            snapshot.documents.mapNotNull { doc ->
                try {
                    ArticleEntity(
                        id = doc.getString("id") ?: doc.id,
                        title = doc.getString("title") ?: return@mapNotNull null,
                        description = doc.getString("description") ?: "",
                        imageUrl = doc.getString("imageUrl"),
                        articleUrl = doc.getString("articleUrl") ?: return@mapNotNull null,
                        publishedAt = doc.getTimestamp("publishedAt")?.toDate()?.time ?: 0L,
                        sourceName = doc.getString("sourceName") ?: "",
                        category = doc.getString("category") ?: "general",
                        quality = doc.getString("quality") ?: "low",
                        cachedAt = now
                    )
                } catch (e: Exception) {
                    Timber.w(e, "Skipping malformed document: ${doc.id}")
                    null
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to fetch articles from Firebase")
            throw e
        }
    }

    suspend fun fetchLatestArticles(
        limit: Long = 100,
        quality: String = "high"
    ): List<ArticleEntity> {
        return try {
            val snapshot = firestore.collection(COLLECTION)
                .whereEqualTo("quality", quality)
                .orderBy("publishedAt", Query.Direction.DESCENDING)
                .limit(limit)
                .get()
                .await()

            val now = System.currentTimeMillis()
            snapshot.documents.mapNotNull { doc ->
                try {
                    ArticleEntity(
                        id = doc.getString("id") ?: doc.id,
                        title = doc.getString("title") ?: return@mapNotNull null,
                        description = doc.getString("description") ?: "",
                        imageUrl = doc.getString("imageUrl"),
                        articleUrl = doc.getString("articleUrl") ?: return@mapNotNull null,
                        publishedAt = doc.getTimestamp("publishedAt")?.toDate()?.time ?: 0L,
                        sourceName = doc.getString("sourceName") ?: "",
                        category = doc.getString("category") ?: "general",
                        quality = doc.getString("quality") ?: "low",
                        cachedAt = now
                    )
                } catch (e: Exception) {
                    Timber.w(e, "Skipping malformed document: ${doc.id}")
                    null
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to fetch latest articles from Firebase")
            throw e
        }
    }
}
