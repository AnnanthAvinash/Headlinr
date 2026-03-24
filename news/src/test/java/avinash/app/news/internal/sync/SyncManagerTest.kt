package avinash.app.news.internal.sync

import android.content.Context
import avinash.app.news.api.model.SyncResult
import avinash.app.news.api.model.SyncTrigger
import avinash.app.news.internal.local.dao.ArticleDao
import avinash.app.news.internal.local.dao.CategoryDao
import avinash.app.news.internal.local.entity.ArticleEntity
import avinash.app.news.internal.remote.FirebaseNewsSource
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.just
import io.mockk.mockk
import io.mockk.spyk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SyncManagerTest {

    private val context: Context = mockk(relaxed = true)
    private val firebaseNewsSource: FirebaseNewsSource = mockk()
    private val articleDao: ArticleDao = mockk(relaxed = true)
    private val categoryDao: CategoryDao = mockk(relaxed = true)
    private val fetchQuotaManager: FetchQuotaManager = mockk(relaxed = true)

    private lateinit var syncManager: SyncManager

    @Before
    fun setUp() {
        syncManager = spyk(
            SyncManager(context, firebaseNewsSource, articleDao, categoryDao, fetchQuotaManager),
            recordPrivateCalls = true
        )
        coEvery { syncManager["seedCategoriesIfNeeded"]() } returns Unit
        coEvery { syncManager["getLastSyncTimestamp"]() } returns 0L
        coEvery { syncManager["updateLastSyncTimestamp"](any<Long>()) } returns Unit
    }

    @Test
    fun `room empty forces fetch and returns SUCCESS`() = runTest {
        coEvery { articleDao.getCount() } returns 0
        coEvery { firebaseNewsSource.fetchLatestArticles(any()) } returns listOf(sampleEntity())
        coEvery { articleDao.upsertAll(any()) } just Runs
        coEvery { fetchQuotaManager.recordFetch() } just Runs

        val result = syncManager.syncNews(SyncTrigger.APP_OPEN)

        assertTrue(result.isSuccess)
        assertEquals(SyncResult.SUCCESS, result.getOrNull())
        coVerify { firebaseNewsSource.fetchLatestArticles(any()) }
        coVerify { articleDao.upsertAll(any()) }
        coVerify { fetchQuotaManager.recordFetch() }
    }

    @Test
    fun `room empty with no articles still returns SUCCESS`() = runTest {
        coEvery { articleDao.getCount() } returns 0
        coEvery { firebaseNewsSource.fetchLatestArticles(any()) } returns emptyList()
        coEvery { fetchQuotaManager.recordFetch() } just Runs

        val result = syncManager.syncNews(SyncTrigger.APP_OPEN)

        assertTrue(result.isSuccess)
        assertEquals(SyncResult.SUCCESS, result.getOrNull())
        coVerify(exactly = 0) { articleDao.upsertAll(any()) }
        coVerify { fetchQuotaManager.recordFetch() }
    }

    @Test
    fun `cooldown returns COOLDOWN when sync too recent`() = runTest {
        coEvery { articleDao.getCount() } returns 100
        coEvery { syncManager["getLastSyncTimestamp"]() } returns (System.currentTimeMillis() - 5_000L)

        val result = syncManager.syncNews(SyncTrigger.PULL_TO_REFRESH)

        assertTrue(result.isSuccess)
        assertEquals(SyncResult.COOLDOWN, result.getOrNull())
        coVerify(exactly = 0) { firebaseNewsSource.fetchLatestArticles(any()) }
        coVerify(exactly = 0) { firebaseNewsSource.fetchArticlesSince(any(), any()) }
    }

    @Test
    fun `bucket exhausted returns BUCKET_EXHAUSTED`() = runTest {
        coEvery { articleDao.getCount() } returns 100
        coEvery { fetchQuotaManager.canFetch() } returns false
        coEvery { fetchQuotaManager.getRemainingInBucket() } returns 0
        coEvery { fetchQuotaManager.getRemainingDaily() } returns 30

        val result = syncManager.syncNews(SyncTrigger.APP_OPEN)

        assertTrue(result.isSuccess)
        assertEquals(SyncResult.BUCKET_EXHAUSTED, result.getOrNull())
    }

    @Test
    fun `quota exhausted returns QUOTA_EXHAUSTED`() = runTest {
        coEvery { articleDao.getCount() } returns 100
        coEvery { fetchQuotaManager.canFetch() } returns false
        coEvery { fetchQuotaManager.getRemainingInBucket() } returns 0
        coEvery { fetchQuotaManager.getRemainingDaily() } returns 0

        val result = syncManager.syncNews(SyncTrigger.APP_OPEN)

        assertTrue(result.isSuccess)
        assertEquals(SyncResult.QUOTA_EXHAUSTED, result.getOrNull())
    }

    @Test
    fun `normal sync with lastSync fetches since timestamp`() = runTest {
        val lastSync = System.currentTimeMillis() - 60_000L
        coEvery { articleDao.getCount() } returns 100
        coEvery { syncManager["getLastSyncTimestamp"]() } returns lastSync
        coEvery { fetchQuotaManager.canFetch() } returns true
        coEvery { firebaseNewsSource.fetchArticlesSince(eq(lastSync), any()) } returns listOf(sampleEntity())
        coEvery { articleDao.upsertAll(any()) } just Runs
        coEvery { fetchQuotaManager.recordFetch() } just Runs

        val result = syncManager.syncNews(SyncTrigger.PULL_TO_REFRESH)

        assertTrue(result.isSuccess)
        assertEquals(SyncResult.SUCCESS, result.getOrNull())
        coVerify { firebaseNewsSource.fetchArticlesSince(eq(lastSync), any()) }
        coVerify { articleDao.upsertAll(any()) }
    }

    @Test
    fun `normal sync with no lastSync fetches latest`() = runTest {
        coEvery { articleDao.getCount() } returns 100
        coEvery { syncManager["getLastSyncTimestamp"]() } returns 0L
        coEvery { fetchQuotaManager.canFetch() } returns true
        coEvery { firebaseNewsSource.fetchLatestArticles(any()) } returns listOf(sampleEntity())
        coEvery { articleDao.upsertAll(any()) } just Runs
        coEvery { fetchQuotaManager.recordFetch() } just Runs

        val result = syncManager.syncNews(SyncTrigger.APP_OPEN)

        assertTrue(result.isSuccess)
        assertEquals(SyncResult.SUCCESS, result.getOrNull())
        coVerify { firebaseNewsSource.fetchLatestArticles(any()) }
    }

    @Test
    fun `firebase error propagates as failure`() = runTest {
        coEvery { articleDao.getCount() } returns 100
        coEvery { fetchQuotaManager.canFetch() } returns true
        coEvery { firebaseNewsSource.fetchLatestArticles(any()) } throws RuntimeException("Firebase offline")

        val result = syncManager.syncNews(SyncTrigger.APP_OPEN)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is RuntimeException)
    }

    @Test
    fun `recordFetch called after successful upsert`() = runTest {
        coEvery { articleDao.getCount() } returns 100
        coEvery { fetchQuotaManager.canFetch() } returns true
        coEvery { firebaseNewsSource.fetchLatestArticles(any()) } returns listOf(sampleEntity())
        coEvery { articleDao.upsertAll(any()) } just Runs
        coEvery { fetchQuotaManager.recordFetch() } just Runs

        syncManager.syncNews(SyncTrigger.APP_OPEN)

        coVerifyOrder {
            articleDao.upsertAll(any())
            fetchQuotaManager.recordFetch()
        }
    }

    private fun sampleEntity(id: String = "a1") = ArticleEntity(
        id = id, title = "Title", description = "Desc", imageUrl = null,
        articleUrl = "https://example.com/$id", publishedAt = 100L,
        sourceName = "Source", category = "nat", cachedAt = 200L
    )
}
