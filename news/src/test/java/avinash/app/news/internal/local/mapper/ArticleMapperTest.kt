package avinash.app.news.internal.local.mapper

import avinash.app.news.api.model.NewsArticle
import avinash.app.news.internal.local.entity.ArticleEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ArticleMapperTest {

    private val sampleEntity = ArticleEntity(
        id = "abc123",
        title = "Test Title",
        description = "Test Description",
        imageUrl = "https://example.com/image.jpg",
        articleUrl = "https://example.com/article",
        publishedAt = 1700000000000L,
        sourceName = "Test Source",
        category = "nat",
        trending = true,
        cachedAt = 1700000001000L
    )

    private val sampleArticle = NewsArticle(
        id = "abc123",
        title = "Test Title",
        description = "Test Description",
        imageUrl = "https://example.com/image.jpg",
        articleUrl = "https://example.com/article",
        publishedAt = 1700000000000L,
        sourceName = "Test Source",
        category = "nat",
        trending = true
    )

    @Test
    fun `entity to domain maps all fields correctly`() {
        val domain = sampleEntity.toDomain()

        assertEquals(sampleEntity.id, domain.id)
        assertEquals(sampleEntity.title, domain.title)
        assertEquals(sampleEntity.description, domain.description)
        assertEquals(sampleEntity.imageUrl, domain.imageUrl)
        assertEquals(sampleEntity.articleUrl, domain.articleUrl)
        assertEquals(sampleEntity.publishedAt, domain.publishedAt)
        assertEquals(sampleEntity.sourceName, domain.sourceName)
        assertEquals(sampleEntity.category, domain.category)
        assertEquals(sampleEntity.trending, domain.trending)
    }

    @Test
    fun `domain to entity maps all fields with provided cachedAt`() {
        val cachedAt = 1700000005000L
        val entity = sampleArticle.toEntity(cachedAt = cachedAt)

        assertEquals(sampleArticle.id, entity.id)
        assertEquals(sampleArticle.title, entity.title)
        assertEquals(sampleArticle.description, entity.description)
        assertEquals(sampleArticle.imageUrl, entity.imageUrl)
        assertEquals(sampleArticle.articleUrl, entity.articleUrl)
        assertEquals(sampleArticle.publishedAt, entity.publishedAt)
        assertEquals(sampleArticle.sourceName, entity.sourceName)
        assertEquals(sampleArticle.category, entity.category)
        assertEquals(sampleArticle.trending, entity.trending)
        assertEquals(cachedAt, entity.cachedAt)
    }

    @Test
    fun `entity with null imageUrl maps correctly`() {
        val entity = sampleEntity.copy(imageUrl = null)
        val domain = entity.toDomain()
        assertNull(domain.imageUrl)
    }

    @Test
    fun `domain to entity uses default cachedAt close to current time`() {
        val before = System.currentTimeMillis()
        val entity = sampleArticle.toEntity()
        val after = System.currentTimeMillis()

        assertTrue(entity.cachedAt in before..after)
    }

    @Test
    fun `roundtrip entity to domain to entity preserves data`() {
        val cachedAt = 1700000005000L
        val domain = sampleEntity.toDomain()
        val roundtripped = domain.toEntity(cachedAt = cachedAt)

        assertEquals(sampleEntity.id, roundtripped.id)
        assertEquals(sampleEntity.title, roundtripped.title)
        assertEquals(sampleEntity.description, roundtripped.description)
        assertEquals(sampleEntity.imageUrl, roundtripped.imageUrl)
        assertEquals(sampleEntity.articleUrl, roundtripped.articleUrl)
        assertEquals(sampleEntity.publishedAt, roundtripped.publishedAt)
        assertEquals(sampleEntity.sourceName, roundtripped.sourceName)
        assertEquals(sampleEntity.category, roundtripped.category)
        assertEquals(sampleEntity.trending, roundtripped.trending)
    }

    @Test
    fun `trending false maps correctly`() {
        val entity = sampleEntity.copy(trending = false)
        val domain = entity.toDomain()
        assertFalse(domain.trending)
    }
}
