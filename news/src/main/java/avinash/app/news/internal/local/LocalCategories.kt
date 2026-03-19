package avinash.app.news.internal.local

import avinash.app.news.internal.local.entity.CategoryEntity

internal object LocalCategories {

    const val VERSION = 2

    val ALL: List<CategoryEntity> = listOf(
        CategoryEntity(id = "nat", name = "दुनिया",        slug = "nat", sortOrder = 1),
        CategoryEntity(id = "spt", name = "खेल",           slug = "spt", sortOrder = 2),
        CategoryEntity(id = "ent", name = "मनोरंजन",       slug = "ent", sortOrder = 3),
        CategoryEntity(id = "bus", name = "व्यापार",       slug = "bus", sortOrder = 4),
        CategoryEntity(id = "tec", name = "तकनीक",         slug = "tec", sortOrder = 5),
        CategoryEntity(id = "hlt", name = "स्वास्थ्य",    slug = "hlt", sortOrder = 6),
        CategoryEntity(id = "edu", name = "शिक्षा",        slug = "edu", sortOrder = 7),
    )
}
