package avinash.app.news.internal.local.mapper

import avinash.app.news.api.model.Category
import avinash.app.news.internal.local.entity.CategoryEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class CategoryMapperTest {

    @Test
    fun `entity to domain maps all fields`() {
        val entity = CategoryEntity(id = "nat", name = "दुनिया", slug = "nat", sortOrder = 1)
        val domain = entity.toDomain()

        assertEquals("nat", domain.id)
        assertEquals("दुनिया", domain.name)
        assertEquals("nat", domain.slug)
    }

    @Test
    fun `domain to entity maps all fields with sortOrder`() {
        val domain = Category(id = "spt", name = "खेल", slug = "spt")
        val entity = domain.toEntity(sortOrder = 2)

        assertEquals("spt", entity.id)
        assertEquals("खेल", entity.name)
        assertEquals("spt", entity.slug)
        assertEquals(2, entity.sortOrder)
    }

    @Test
    fun `domain to entity uses default sortOrder of zero`() {
        val domain = Category(id = "ent", name = "मनोरंजन", slug = "ent")
        val entity = domain.toEntity()
        assertEquals(0, entity.sortOrder)
    }

    @Test
    fun `roundtrip preserves data`() {
        val original = CategoryEntity(id = "bus", name = "व्यापार", slug = "bus", sortOrder = 4)
        val roundtripped = original.toDomain().toEntity(sortOrder = original.sortOrder)

        assertEquals(original.id, roundtripped.id)
        assertEquals(original.name, roundtripped.name)
        assertEquals(original.slug, roundtripped.slug)
        assertEquals(original.sortOrder, roundtripped.sortOrder)
    }
}
