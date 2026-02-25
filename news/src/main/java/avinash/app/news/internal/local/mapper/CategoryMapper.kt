package avinash.app.news.internal.local.mapper

import avinash.app.news.api.model.Category
import avinash.app.news.internal.local.entity.CategoryEntity

fun CategoryEntity.toDomain(): Category = Category(
    id = id,
    name = name,
    slug = slug
)

fun Category.toEntity(sortOrder: Int = 0): CategoryEntity = CategoryEntity(
    id = id,
    name = name,
    slug = slug,
    sortOrder = sortOrder
)
