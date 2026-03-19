package avinash.app.news.internal.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import avinash.app.news.internal.local.dao.ArticleDao
import avinash.app.news.internal.local.dao.BookmarkDao
import avinash.app.news.internal.local.dao.CategoryDao
import avinash.app.news.internal.local.entity.ArticleEntity
import avinash.app.news.internal.local.entity.BookmarkEntity
import avinash.app.news.internal.local.entity.CategoryEntity

@Database(
    entities = [ArticleEntity::class, CategoryEntity::class, BookmarkEntity::class],
    version = 5,
    exportSchema = false
)
abstract class NewsDatabase : RoomDatabase() {
    abstract fun articleDao(): ArticleDao
    abstract fun categoryDao(): CategoryDao
    abstract fun bookmarkDao(): BookmarkDao

    companion object {
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE articles ADD COLUMN trending INTEGER NOT NULL DEFAULT 0")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_articles_trending_publishedAt ON articles (trending, publishedAt)")
            }
        }
    }
}
