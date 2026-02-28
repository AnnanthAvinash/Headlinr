package avinash.app.news.internal.local

import avinash.app.news.internal.local.entity.CategoryEntity

internal object LocalCategories {

    val ALL: List<CategoryEntity> = listOf(
        CategoryEntity(id = "pol", name = "राजनीति",      slug = "pol", sortOrder = 1),
        CategoryEntity(id = "nat", name = "राष्ट्रीय",    slug = "nat", sortOrder = 2),
        CategoryEntity(id = "spt", name = "खेल",           slug = "spt", sortOrder = 3),
        CategoryEntity(id = "ent", name = "मनोरंजन",       slug = "ent", sortOrder = 4),
        CategoryEntity(id = "bus", name = "व्यापार",       slug = "bus", sortOrder = 5),
        CategoryEntity(id = "cri", name = "अपराध",         slug = "cri", sortOrder = 6),
        CategoryEntity(id = "def", name = "रक्षा",         slug = "def", sortOrder = 7),
        CategoryEntity(id = "stk", name = "शेयर बाजार",   slug = "stk", sortOrder = 8),
        CategoryEntity(id = "hlt", name = "स्वास्थ्य",    slug = "hlt", sortOrder = 9),
        CategoryEntity(id = "tec", name = "तकनीक",         slug = "tec", sortOrder = 10),
        CategoryEntity(id = "edu", name = "शिक्षा",        slug = "edu", sortOrder = 11),
        CategoryEntity(id = "int", name = "अंतर्राष्ट्रीय", slug = "int", sortOrder = 12),
        CategoryEntity(id = "sci", name = "विज्ञान",       slug = "sci", sortOrder = 13),
    )
}
