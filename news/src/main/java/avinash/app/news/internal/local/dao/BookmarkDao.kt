package avinash.app.news.internal.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import avinash.app.news.internal.local.entity.BookmarkEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface BookmarkDao {

    @Query("SELECT * FROM bookmarks ORDER BY bookmarkedAt DESC")
    fun getBookmarks(): Flow<List<BookmarkEntity>>

    @Upsert
    suspend fun upsert(bookmark: BookmarkEntity)

    @Query("DELETE FROM bookmarks WHERE articleId = :articleId")
    suspend fun delete(articleId: String)

    @Query("SELECT EXISTS(SELECT 1 FROM bookmarks WHERE articleId = :articleId)")
    suspend fun isBookmarked(articleId: String): Boolean

    @Query("SELECT EXISTS(SELECT 1 FROM bookmarks WHERE articleId = :articleId)")
    fun observeIsBookmarked(articleId: String): Flow<Boolean>
}
