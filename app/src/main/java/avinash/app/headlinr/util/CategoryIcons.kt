package avinash.app.headlinr.util

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Article
import androidx.compose.material.icons.outlined.BusinessCenter
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.HealthAndSafety
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.School
import androidx.compose.material.icons.outlined.SportsScore
import androidx.compose.ui.graphics.vector.ImageVector

private val categoryIconMap = mapOf(
    "nat" to Icons.Outlined.Public,
    "spt" to Icons.Outlined.SportsScore,
    "ent" to Icons.Outlined.Movie,
    "bus" to Icons.Outlined.BusinessCenter,
    "tec" to Icons.Outlined.Computer,
    "hlt" to Icons.Outlined.HealthAndSafety,
    "edu" to Icons.Outlined.School,
)

fun categoryIcon(slug: String): ImageVector =
    categoryIconMap[slug] ?: Icons.Outlined.Article

private val categoryNameMap = mapOf(
    "nat" to "दुनिया",
    "spt" to "खेल",
    "ent" to "मनोरंजन",
    "bus" to "व्यापार",
    "tec" to "तकनीक",
    "hlt" to "स्वास्थ्य",
    "edu" to "शिक्षा",
)

fun categoryDisplayName(slug: String): String =
    categoryNameMap[slug.lowercase()] ?: slug.replaceFirstChar { it.uppercase() }

/**
 * Derives a 64px favicon URL directly from any article URL.
 * Works for any source — no hardcoded name mapping needed.
 */
fun sourceFaviconUrl(articleUrl: String): String? {
    if (articleUrl.isBlank()) return null
    return try {
        val host = android.net.Uri.parse(articleUrl).host?.takeIf { it.isNotBlank() } ?: return null
        "https://t3.gstatic.com/faviconV2?client=SOCIAL&type=FAVICON&fallback_opts=TYPE,SIZE,URL&url=https://$host&size=64"
    } catch (_: Exception) {
        null
    }
}
