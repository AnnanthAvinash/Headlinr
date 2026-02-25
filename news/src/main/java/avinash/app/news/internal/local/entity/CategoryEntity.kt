package avinash.app.news.internal.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "categories")
data class CategoryEntity(
    @PrimaryKey val id: String,
    val name: String,
    val slug: String,
    val sortOrder: Int
)
