package avinash.app.news.internal.local

import androidx.room.Database
import androidx.room.RoomDatabase
import avinash.app.news.internal.local.dao.ArticleDao
import avinash.app.news.internal.local.dao.BookmarkDao
import avinash.app.news.internal.local.dao.CategoryDao
import avinash.app.news.internal.local.entity.ArticleEntity
import avinash.app.news.internal.local.entity.BookmarkEntity
import avinash.app.news.internal.local.entity.CategoryEntity

@Database(
    entities = [ArticleEntity::class, CategoryEntity::class, BookmarkEntity::class],
    version = 3,
    exportSchema = false
)
abstract class NewsDatabase : RoomDatabase() {
    abstract fun articleDao(): ArticleDao
    abstract fun categoryDao(): CategoryDao
    abstract fun bookmarkDao(): BookmarkDao
}
