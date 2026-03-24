package avinash.app.news.internal.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalCategoriesTest {

    @Test
    fun `contains exactly 7 categories`() {
        assertEquals(7, LocalCategories.ALL.size)
    }

    @Test
    fun `all short codes present`() {
        val codes = LocalCategories.ALL.map { it.id }.toSet()
        assertEquals(setOf("nat", "spt", "ent", "bus", "tec", "hlt", "edu"), codes)
    }

    @Test
    fun `slugs match ids`() {
        LocalCategories.ALL.forEach { cat ->
            assertEquals("Slug mismatch for ${cat.id}", cat.id, cat.slug)
        }
    }

    @Test
    fun `all have non-empty Hindi labels`() {
        LocalCategories.ALL.forEach { cat ->
            assertTrue("Empty name for ${cat.id}", cat.name.isNotBlank())
        }
    }

    @Test
    fun `sort orders are sequential starting from 1`() {
        val sortOrders = LocalCategories.ALL.map { it.sortOrder }
        assertEquals(listOf(1, 2, 3, 4, 5, 6, 7), sortOrders)
    }

    @Test
    fun `version is positive`() {
        assertTrue(LocalCategories.VERSION > 0)
    }

    @Test
    fun `correct Hindi labels for each category`() {
        val labelMap = LocalCategories.ALL.associate { it.id to it.name }

        assertEquals("दुनिया", labelMap["nat"])
        assertEquals("खेल", labelMap["spt"])
        assertEquals("मनोरंजन", labelMap["ent"])
        assertEquals("व्यापार", labelMap["bus"])
        assertEquals("तकनीक", labelMap["tec"])
        assertEquals("स्वास्थ्य", labelMap["hlt"])
        assertEquals("शिक्षा", labelMap["edu"])
    }
}
