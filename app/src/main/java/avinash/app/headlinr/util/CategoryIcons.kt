package avinash.app.headlinr.util

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountBalance
import androidx.compose.material.icons.outlined.Article
import androidx.compose.material.icons.outlined.Biotech
import androidx.compose.material.icons.outlined.BusinessCenter
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.Fastfood
import androidx.compose.material.icons.outlined.FlightTakeoff
import androidx.compose.material.icons.outlined.Gavel
import androidx.compose.material.icons.outlined.HealthAndSafety
import androidx.compose.material.icons.outlined.LocalFireDepartment
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.School
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material.icons.outlined.SportsScore
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.material.icons.outlined.TrendingUp
import androidx.compose.ui.graphics.vector.ImageVector

private val categoryIconMap = mapOf(
    "trending" to Icons.Outlined.TrendingUp,
    "national" to Icons.Outlined.AccountBalance,
    "politics" to Icons.Outlined.Gavel,
    "business" to Icons.Outlined.BusinessCenter,
    "technology" to Icons.Outlined.Computer,
    "ai" to Icons.Outlined.SmartToy,
    "sports" to Icons.Outlined.SportsScore,
    "entertainment" to Icons.Outlined.Movie,
    "health" to Icons.Outlined.HealthAndSafety,
    "science" to Icons.Outlined.Biotech,
    "world" to Icons.Outlined.Public,
    "gaming" to Icons.Outlined.SportsEsports,
    "education" to Icons.Outlined.School,
    "crime" to Icons.Outlined.LocalFireDepartment,
    "food" to Icons.Outlined.Fastfood,
    "tourism" to Icons.Outlined.FlightTakeoff,
    "opinion" to Icons.Outlined.MenuBook,
    "general" to Icons.Outlined.Article,
)

fun categoryIcon(slug: String): ImageVector =
    categoryIconMap[slug] ?: Icons.Outlined.Article
