package avinash.app.news.di

import android.content.Context
import androidx.room.Room
import avinash.app.news.api.NewsRepository
import avinash.app.news.internal.local.NewsDatabase
import avinash.app.news.internal.local.dao.ArticleDao
import avinash.app.news.internal.local.dao.BookmarkDao
import avinash.app.news.internal.local.dao.CategoryDao
import avinash.app.news.internal.repository.NewsRepositoryImpl
import com.google.firebase.firestore.FirebaseFirestore
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class NewsBindingsModule {

    @Binds
    @Singleton
    abstract fun bindNewsRepository(impl: NewsRepositoryImpl): NewsRepository
}

@Module
@InstallIn(SingletonComponent::class)
object NewsProvidesModule {

    @Provides
    @Singleton
    fun provideFirestore(): FirebaseFirestore = FirebaseFirestore.getInstance()

    @Provides
    @Singleton
    fun provideNewsDatabase(@ApplicationContext context: Context): NewsDatabase {
        return Room.databaseBuilder(
            context,
            NewsDatabase::class.java,
            "news_database"
        )
            .fallbackToDestructiveMigration(true)
            .build()
    }

    @Provides
    fun provideArticleDao(db: NewsDatabase): ArticleDao = db.articleDao()

    @Provides
    fun provideCategoryDao(db: NewsDatabase): CategoryDao = db.categoryDao()

    @Provides
    fun provideBookmarkDao(db: NewsDatabase): BookmarkDao = db.bookmarkDao()
}
