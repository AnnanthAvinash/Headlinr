package avinash.app.news.internal.remote.dto

import com.google.firebase.Timestamp

data class FirebaseArticleDto(
    val id: String = "",
    val title: String = "",
    val description: String = "",
    val imageUrl: String? = null,
    val articleUrl: String = "",
    val publishedAt: Timestamp? = null,
    val sourceName: String = "",
    val category: String = ""
)
