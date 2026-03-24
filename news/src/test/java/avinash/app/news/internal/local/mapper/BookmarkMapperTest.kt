package avinash.app.news.internal.local.mapper

import avinash.app.news.api.model.NewsArticle
import avinash.app.news.internal.local.entity.BookmarkEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BookmarkMapperTest {

    private val sampleBookmark = BookmarkEntity(
        articleId = "bm001",
        title = "Bookmark Title",
        description = "Bookmark Description",
        imageUrl = "https://example.com/bm.jpg",
        articleUrl = "https://example.com/bm-article",
        publishedAt = 1700000000000L,
        sourceName = "Bookmark Source",
        category = "spt",
        bookmarkedAt = 1700000002000L
    )

    private val sampleArticle = NewsArticle(
        id = "bm001",
        title = "Bookmark Title",
        description = "Bookmark Description",
        imageUrl = "https://example.com/bm.jpg",
        articleUrl = "https://example.com/bm-article",
        publishedAt = 1700000000000L,
        sourceName = "Bookmark Source",
        category = "spt"
    )

    @Test
    fun `bookmark entity to domain maps all fields`() {
        val domain = sampleBookmark.toDomain()

        assertEquals(sampleBookmark.articleId, domain.id)
        assertEquals(sampleBookmark.title, domain.title)
        assertEquals(sampleBookmark.description, domain.description)
        assertEquals(sampleBookmark.imageUrl, domain.imageUrl)
        assertEquals(sampleBookmark.articleUrl, domain.articleUrl)
        assertEquals(sampleBookmark.publishedAt, domain.publishedAt)
        assertEquals(sampleBookmark.sourceName, domain.sourceName)
        assertEquals(sampleBookmark.category, domain.category)
    }

    @Test
    fun `domain to bookmark entity maps all fields`() {
        val before = System.currentTimeMillis()
        val entity = sampleArticle.toBookmarkEntity()
        val after = System.currentTimeMillis()

        assertEquals(sampleArticle.id, entity.articleId)
        assertEquals(sampleArticle.title, entity.title)
        assertEquals(sampleArticle.description, entity.description)
        assertEquals(sampleArticle.imageUrl, entity.imageUrl)
        assertEquals(sampleArticle.articleUrl, entity.articleUrl)
        assertEquals(sampleArticle.publishedAt, entity.publishedAt)
        assertEquals(sampleArticle.sourceName, entity.sourceName)
        assertEquals(sampleArticle.category, entity.category)
        assertTrue(entity.bookmarkedAt in before..after)
    }

    @Test
    fun `bookmark with null imageUrl maps correctly`() {
        val entity = sampleBookmark.copy(imageUrl = null)
        val domain = entity.toDomain()
        assertNull(domain.imageUrl)
    }

    @Test
    fun `domain trending defaults to false for bookmarks`() {
        val domain = sampleBookmark.toDomain()
        assertFalse(domain.trending)
    }
}
