package avinash.app.news.internal.repository

import avinash.app.news.api.model.NewsArticle
import avinash.app.news.api.model.SyncResult
import avinash.app.news.api.model.SyncTrigger
import avinash.app.news.internal.local.dao.ArticleDao
import avinash.app.news.internal.local.dao.BookmarkDao
import avinash.app.news.internal.local.dao.CategoryDao
import avinash.app.news.internal.local.dao.SourceProjection
import avinash.app.news.internal.local.entity.ArticleEntity
import avinash.app.news.internal.local.entity.BookmarkEntity
import avinash.app.news.internal.remote.RemoteConfigManager
import avinash.app.news.internal.sync.SyncManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.Runs
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class NewsRepositoryImplTest {

    private val articleDao: ArticleDao = mockk(relaxed = true)
    private val categoryDao: CategoryDao = mockk(relaxed = true)
    private val bookmarkDao: BookmarkDao = mockk(relaxed = true)
    private val syncManager: SyncManager = mockk()
    private val remoteConfigManager: RemoteConfigManager = mockk()

    private lateinit var repository: NewsRepositoryImpl

    @Before
    fun setUp() {
        repository = NewsRepositoryImpl(
            articleDao, categoryDao, bookmarkDao, syncManager, remoteConfigManager
        )
    }

    @Test
    fun `refreshNews APP_OPEN delegates to syncManager`() = runTest {
        coEvery { syncManager.syncNews(SyncTrigger.APP_OPEN) } returns Result.success(SyncResult.SUCCESS)

        val result = repository.refreshNews(SyncTrigger.APP_OPEN)

        assertTrue(result.isSuccess)
        assertEquals(SyncResult.SUCCESS, result.getOrNull())
        coVerify { syncManager.syncNews(SyncTrigger.APP_OPEN) }
    }

    @Test
    fun `refreshNews PULL_TO_REFRESH delegates to syncManager`() = runTest {
        coEvery {
            syncManager.syncNews(SyncTrigger.PULL_TO_REFRESH)
        } returns Result.success(SyncResult.COOLDOWN)

        val result = repository.refreshNews(SyncTrigger.PULL_TO_REFRESH)

        assertTrue(result.isSuccess)
        assertEquals(SyncResult.COOLDOWN, result.getOrNull())
        coVerify { syncManager.syncNews(SyncTrigger.PULL_TO_REFRESH) }
    }

    @Test
    fun `getArticleById finds article in articles table`() = runTest {
        val entity = makeArticleEntity("art1")
        coEvery { articleDao.getArticleById("art1") } returns entity

        val result = repository.getArticleById("art1")

        assertNotNull(result)
        assertEquals("art1", result?.id)
    }

    @Test
    fun `getArticleById falls back to bookmarks when not in articles`() = runTest {
        coEvery { articleDao.getArticleById("bm1") } returns null
        coEvery { bookmarkDao.getBookmarkById("bm1") } returns BookmarkEntity(
            articleId = "bm1", title = "T", description = "D", imageUrl = null,
            articleUrl = "https://x.com", publishedAt = 100L, sourceName = "S",
            category = "spt", bookmarkedAt = 300L
        )

        val result = repository.getArticleById("bm1")

        assertNotNull(result)
        assertEquals("bm1", result?.id)
    }

    @Test
    fun `getArticleById returns null when not found anywhere`() = runTest {
        coEvery { articleDao.getArticleById("missing") } returns null
        coEvery { bookmarkDao.getBookmarkById("missing") } returns null

        val result = repository.getArticleById("missing")

        assertNull(result)
    }

    @Test
    fun `bookmarkArticle delegates to bookmarkDao`() = runTest {
        val article = makeNewsArticle("a1")
        coEvery { bookmarkDao.upsert(any()) } just Runs

        repository.bookmarkArticle(article)

        coVerify { bookmarkDao.upsert(match { it.articleId == "a1" }) }
    }

    @Test
    fun `removeBookmark delegates to bookmarkDao`() = runTest {
        coEvery { bookmarkDao.delete("a1") } just Runs

        repository.removeBookmark("a1")

        coVerify { bookmarkDao.delete("a1") }
    }

    @Test
    fun `isBookmarked returns true when bookmarked`() = runTest {
        coEvery { bookmarkDao.isBookmarked("a1") } returns true

        val result = repository.isBookmarked("a1")

        assertTrue(result)
    }

    @Test
    fun `isBookmarked returns false when not bookmarked`() = runTest {
        coEvery { bookmarkDao.isBookmarked("a2") } returns false

        val result = repository.isBookmarked("a2")

        assertEquals(false, result)
    }

    @Test
    fun `refreshCategories returns success`() = runTest {
        val result = repository.refreshCategories()
        assertTrue(result.isSuccess)
    }

    @Test
    fun `getTrendingArticles delegates to articleDao and maps`() = runTest {
        val entities = listOf(
            makeArticleEntity("tr1", trending = true),
            makeArticleEntity("tr2", trending = true)
        )
        coEvery { articleDao.getTrendingArticles(10) } returns entities

        val result = repository.getTrendingArticles(10)

        assertEquals(2, result.size)
        assertEquals("tr1", result[0].id)
        assertEquals("tr2", result[1].id)
    }

    @Test
    fun `getDistinctSources maps projections to SourceEntry`() = runTest {
        coEvery { articleDao.getDistinctSources() } returns listOf(
            SourceProjection(sourceName = "BBC Hindi", articleUrl = "https://bbc.com/hindi"),
            SourceProjection(sourceName = "Aaj Tak", articleUrl = "https://aajtak.in")
        )

        val result = repository.getDistinctSources()

        assertEquals(2, result.size)
        assertEquals("BBC Hindi", result[0].name)
        assertEquals("https://bbc.com/hindi", result[0].sampleArticleUrl)
        assertEquals("Aaj Tak", result[1].name)
    }

    @Test
    fun `getRemoteConfig returns injected manager`() {
        val config = repository.getRemoteConfig()
        assertSame(remoteConfigManager, config)
    }

    @Test
    fun `refreshNews propagates sync failure`() = runTest {
        val error = RuntimeException("Network error")
        coEvery { syncManager.syncNews(any()) } returns Result.failure(error)

        val result = repository.refreshNews(SyncTrigger.APP_OPEN)

        assertTrue(result.isFailure)
        assertEquals("Network error", result.exceptionOrNull()?.message)
    }

    private fun makeArticleEntity(id: String, trending: Boolean = false) = ArticleEntity(
        id = id, title = "Title $id", description = "Desc $id", imageUrl = null,
        articleUrl = "https://example.com/$id", publishedAt = 100L,
        sourceName = "Source", category = "nat", trending = trending, cachedAt = 200L
    )

    private fun makeNewsArticle(id: String) = NewsArticle(
        id = id, title = "Title $id", description = "Desc $id", imageUrl = null,
        articleUrl = "https://example.com/$id", publishedAt = 100L,
        sourceName = "Source", category = "nat"
    )
}
