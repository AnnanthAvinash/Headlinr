package avinash.app.news.internal.remote

import com.google.firebase.firestore.FirebaseFirestore
import avinash.app.news.internal.local.entity.CategoryEntity
import kotlinx.coroutines.tasks.await
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FirebaseCategorySource @Inject constructor(
    private val firestore: FirebaseFirestore
) {
    companion object {
        private const val COLLECTION = "categories"
    }

    suspend fun fetchCategories(): List<CategoryEntity> {
        return try {
            val snapshot = firestore.collection(COLLECTION)
                .orderBy("sortOrder")
                .get()
                .await()

            snapshot.documents.mapNotNull { doc ->
                try {
                    CategoryEntity(
                        id = doc.getString("id") ?: doc.id,
                        name = doc.getString("name") ?: return@mapNotNull null,
                        slug = doc.getString("slug") ?: doc.id,
                        sortOrder = doc.getLong("sortOrder")?.toInt() ?: 0
                    )
                } catch (e: Exception) {
                    Timber.w(e, "Skipping malformed category: ${doc.id}")
                    null
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to fetch categories from Firebase")
            throw e
        }
    }
}
