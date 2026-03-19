# Headlinr — Final Production Plan

---

## 1. Overview

A **plug-and-play Android library module** (`news`) designed for integration into ANY Android app with minimal setup. The module is **data-only** — it exposes models and a repository interface but **no UI**. Any host app builds its own screens, ViewModels, and ad placements using the data this module provides.

The module reads news articles and categories from **Firebase Firestore** and serves them through **Room DB** (single source of truth) with Paging 3 pagination. A **GitHub Actions** workflow fetches articles from multiple news API providers every 30 minutes, normalizes, deduplicates, and uploads to Firebase. The Android app **never** calls news APIs directly.

### Key Decisions

- **Data-only module** — no UI, no ViewModel, no Compose screens, no AdMob inside the module
- **Room is single source of truth** — all reads from Room, never Firestore directly
- **Categories are dynamic** — derived from providers, not hardcoded
- **No WorkManager** — sync only when user opens the app (zero wasted reads)
- **Firestore TTL not available on free plan** — cleanup done in GitHub Actions
- **Zero cost** — everything within free tier limits

---

## 2. Architecture

```
┌──────────────────────────────────────────────────────────────┐
│                    GitHub Actions (every 30 min)              │
│                                                              │
│  TIER 1 (every 30 min):         TIER 2 (every 4 hours):     │
│  ┌─────────────┐                ┌─────────────┐             │
│  │ currentsapi  │                │ newsdata.io │             │
│  │ (freshness)  │                │ (volume)    │             │
│  ├─────────────┤                ├─────────────┤             │
│  │ RSS feeds   │                │contextualweb│             │
│  │ (16 feeds,  │                │ (coverage)  │             │
│  │  parallel,  │                └──────┬──────┘             │
│  │  free)      │                       │                    │
│  └──────┬──────┘                       │                    │
│         └──────────┬───────────────────┘                    │
│                    ▼                                         │
│         ┌──────────────────────┐                             │
│         │ Normalize categories │                             │
│         │ Normalize sources    │                             │
│         │ Validate images ≥600 │                             │
│         │ Deduplicate (3-layer)│                             │
│         │ AI keyword filter    │                             │
│         │ Measure feed quality │                             │
│         └──────────┬───────────┘                             │
│                    ▼                                         │
│         ┌──────────────────────┐                             │
│         │   Firebase Firestore  │                             │
│         │  ┌────────────────┐  │                             │
│         │  │ news_articles   │  │                             │
│         │  ├────────────────┤  │                             │
│         │  │ categories      │  │                             │
│         │  ├────────────────┤  │                             │
│         │  │ rss_metrics     │  │  ← admin-only monitoring   │
│         │  └────────────────┘  │                             │
│         └──────────────────────┘                             │
│                    +                                         │
│         Delete articles > 7 days (in Tier 2 runs)            │
└──────────────────────────────────────────────────────────────┘
                     │
                     ▼
┌──────────────────────────────────────────────────────────────┐
│               Android — News Module                          │
│                                                              │
│  News Module (data-only):                                    │
│    → Exposes NewsRepository interface                        │
│    → Room serves cached data instantly                       │
│    → Sync: Firebase → Room in batches of 50                  │
│    → Room PagingSource auto-invalidates                      │
│    → All pagination = Room only. Zero Firestore reads.       │
│                                                              │
│  Host App (builds its own UI):                               │
│    → Injects NewsRepository                                  │
│    → Builds screens, ViewModels, ads, navigation             │
│    → Collects PagingData and category Flow                   │
└──────────────────────────────────────────────────────────────┘
```

---

## 3. Verified Free Tier Limits

### Firebase Firestore (Spark Plan)

| Resource | Daily Limit | Our Usage | % Used | Source |
|----------|-------------|-----------|--------|--------|
| Reads | 50,000/day | ~1,900 (100 users) | 3.8% | firebase.google.com/docs/firestore/quotas |
| Writes | 20,000/day | ~660 | 3.3% | firebase.google.com/docs/firestore/quotas |
| Deletes | 20,000/day | ~200 | 1.0% | firebase.google.com/docs/firestore/quotas |
| Storage | 1 GiB | ~1 MB | 0.1% | firebase.google.com/docs/firestore/quotas |
| Egress | 10 GiB/month | ~300 MB | 2.9% | firebase.google.com/docs/firestore/quotas |
| TTL Policy | **NOT on Spark** | N/A | — | firebase.google.com/docs/firestore/ttl |

Resets daily at midnight Pacific Time. Exceeding = shut off until next day (no billing).

### GitHub Actions

| Resource | Limit | Our Usage | % Used | Source |
|----------|-------|-----------|--------|--------|
| Minutes (private repo) | 2,000/month | 1,440/month | 72% | docs.github.com/billing |
| Minutes (public repo) | Unlimited | — | — | docs.github.com/billing |

### News API Providers

| Provider | Free Limit | Our Usage | % Used | Delay | Commercial Use |
|----------|-----------|-----------|--------|-------|----------------|
| currentsapi.services | 1,000 req/day | 48/day | 4.8% | Near real-time | **VERIFY before launch** |
| newsdata.io | 200 credits/day (=2,000 articles) | 18 credits/day | 9% | **12 hours** | **YES** (confirmed) |
| contextualweb.io | 10,000 req/month | 540/month | 5.4% | Unknown | **VERIFY before launch** |
| RSS feeds (16) | **Unlimited** | 768/day | **Free** | Real-time | N/A (public feeds) |

### Capacity (Revised — based on real sync math)

```
Firebase Spark free tier: 50,000 reads/day
GitHub Actions cleanup overhead: ~2,400 reads/day
Available for app users: ~47,600 reads/day

USER TYPE          SESSION   PULLS  AUTO-REFRESHES  READS/DAY
Light (15 min)     15 min    1      0               ~78
Normal (1 hr)      1 hr      2      1               ~168
Heavy (3-4 hr)     3 hr      5      5               ~438
Power (8 hr)       8 hr      10     16              ~828
First-time (full)  30 min    1      0               ~348

MAX DAILY ACTIVE USERS ON FREE TIER:
  All light:       610 DAU
  All normal/mid:  283 DAU
  All heavy:       108 DAU
  Realistic mix (60% light + 30% normal + 10% heavy): ~363 DAU
  Early stage (10-20 users): ~13% of free tier used

~30 minutes data freshness (from currentsapi + RSS)
```

---

## 4. Module Structure

```
NewsApp/
├── app/                                       ← Demo host app (builds its own UI)
│   ├── build.gradle.kts
│   └── src/main/java/avinash/app/headlinr/
│       ├── HeadlinrApp.kt                     ← @HiltAndroidApp
│       ├── MainActivity.kt                    ← NavHost entry
│       ├── ui/
│       │   ├── screens/
│       │   │   └── HomeScreen.kt              ← Category chips + paginated list
│       │   ├── components/
│       │   │   ├── ArticleCard.kt
│       │   │   └── CategoryChips.kt
│       │   ├── navigation/
│       │   │   └── AppNavigation.kt
│       │   └── ads/                           ← Future: AdMob integration
│       │       ├── NativeAdCard.kt
│       │       ├── BannerAdView.kt
│       │       └── AdManager.kt
│       └── viewmodel/
│           └── NewsViewModel.kt               ← Consumes NewsRepository
│
├── news/                                      ← Plug-and-play DATA-ONLY library module
│   ├── build.gradle.kts
│   └── src/main/java/avinash/app/news/
│       │
│       ├── api/                               ← PUBLIC (host app imports ONLY this)
│       │   ├── NewsRepository.kt              ← Single public interface
│       │   └── model/
│       │       ├── NewsArticle.kt             ← Domain model
│       │       └── Category.kt                ← Category model
│       │
│       ├── internal/                          ← PRIVATE (encapsulated, never imported by host)
│       │   ├── remote/
│       │   │   ├── FirebaseNewsSource.kt      ← Firestore article queries
│       │   │   ├── FirebaseCategorySource.kt  ← Firestore category queries
│       │   │   └── dto/
│       │   │       ├── FirebaseArticleDto.kt
│       │   │       └── FirebaseCategoryDto.kt
│       │   │
│       │   ├── local/
│       │   │   ├── NewsDatabase.kt            ← Room database
│       │   │   ├── dao/
│       │   │   │   ├── ArticleDao.kt          ← PagingSource + CRUD
│       │   │   │   └── CategoryDao.kt
│       │   │   ├── entity/
│       │   │   │   ├── ArticleEntity.kt
│       │   │   │   └── CategoryEntity.kt
│       │   │   └── mapper/
│       │   │       ├── ArticleMapper.kt       ← Entity ↔ Domain
│       │   │       └── CategoryMapper.kt
│       │   │
│       │   ├── repository/
│       │   │   └── NewsRepositoryImpl.kt      ← Sync orchestration
│       │   │
│       │   └── sync/
│       │       └── SyncManager.kt             ← Batched sync logic
│       │
│       └── di/
│           └── NewsModule.kt                  ← Hilt bindings
│
│   ⛔ NO ui/ folder — module is data-only
│   ⛔ NO ViewModel, Screens, Composables, AdMob
│   ✅ Host app builds its own UI using NewsRepository
│
└── .github/
    └── workflows/
        └── fetch-news.yml                     ← Two-tier scheduled workflow
```

---

## 5. API Endpoints — Complete Reference

### 5.1 currentsapi.services

**Base URL:** `https://api.currentsapi.services`

| Purpose | Endpoint | Method | Auth |
|---------|----------|--------|------|
| Latest/trending news | `/v1/latest-news` | GET | `?apiKey=XXX` |
| News by category/keyword | `/v1/search` | GET | `?apiKey=XXX` |
| List all categories | `/v1/available/categories` | GET | `?apiKey=XXX` |
| List all regions | `/v1/available/regions` | GET | `?apiKey=XXX` |
| List all languages | `/v1/available/languages` | GET | `?apiKey=XXX` |

**Categories available:** regional, technology, lifestyle, business, general, programming, science, entertainment, world, sports, finance, academia, politics, health, opinion, food, gaming

**Trending/latest news call:**

```
GET /v1/latest-news?apiKey=XXX&language=en
→ Returns latest articles across ALL categories (no category filter needed)
→ Use this for TIER 1 (freshness runs every 30 min)
```

**News by category call:**

```
GET /v1/search?apiKey=XXX&language=en&category=technology
GET /v1/search?apiKey=XXX&language=en&category=sports
GET /v1/search?apiKey=XXX&language=en&category=business
→ Returns articles filtered by specific category
→ Use this for TIER 2 (targeted category fill)
```

**Get categories call:**

```
GET /v1/available/categories?apiKey=XXX
→ Returns list of all available category slugs
→ Use this to dynamically build categories collection in Firebase
```

**Key parameters for `/v1/search`:**

| Parameter | Description | Example |
|-----------|-------------|---------|
| `keywords` | Exact match in title/description | `keywords=cricket` |
| `category` | Filter by category | `category=sports` |
| `language` | Language code | `language=en` |
| `country` | Country code | `country=IN` |
| `start_date` | Articles after date (RFC 3339) | `start_date=2026-02-24T00:00:00Z` |
| `page_number` | Pagination | `page_number=2` |
| `page_size` | Results per page (max 200) | `page_size=50` |

---

### 5.2 newsdata.io

**Base URL:** `https://newsdata.io/api/1`

| Purpose | Endpoint | Method | Auth |
|---------|----------|--------|------|
| Latest news (general/trending) | `/latest` | GET | `?apikey=XXX` |
| Latest news by category | `/latest` | GET | `?apikey=XXX&category=XXX` |
| News sources list | `/sources` | GET | `?apikey=XXX` |

**17 categories available:** business, crime, domestic, education, entertainment, environment, food, health, lifestyle, politics, science, sports, technology, top, tourism, world, other

**Trending/latest news call:**

```
GET /api/1/latest?apikey=XXX&language=en
→ Returns latest articles across all categories
→ "top" category = trending/headline news

GET /api/1/latest?apikey=XXX&language=en&category=top
→ Returns specifically trending/top headline articles
```

**News by category call:**

```
GET /api/1/latest?apikey=XXX&language=en&category=technology
GET /api/1/latest?apikey=XXX&language=en&category=sports
GET /api/1/latest?apikey=XXX&language=en&category=business,health
→ Supports multiple categories comma-separated (max 5)
```

**Key parameters for `/latest`:**

| Parameter | Description | Example |
|-----------|-------------|---------|
| `category` | Filter by category (max 5, comma-separated) | `category=sports,technology` |
| `language` | Language code | `language=en` |
| `country` | Country code | `country=in` |
| `q` | Search keyword (100 char limit on free) | `q=cricket` |
| `qInTitle` | Keyword in title only | `qInTitle=budget` |
| `timeframe` | Recency in minutes (1-2880) or hours (1-48) | `timeframe=6` |
| `page` | Pagination cursor (from `nextPage` in response) | `page=abc123nextpage` |

**Free tier limits per call:** 10 articles per credit, 1 credit per call. 200 credits/day. Articles delayed 12 hours.

---

### 5.3 contextualweb.io (via RapidAPI)

**Base URL:** `https://contextualwebsearch-websearch-v1.p.rapidapi.com`

| Purpose | Endpoint | Method | Auth |
|---------|----------|--------|------|
| News by keyword/category | `/api/search/NewsSearchAPI` | GET | RapidAPI headers |

**No separate categories endpoint.** Categories are simulated by using category names as the `q` (search query) parameter.

**Headers required:**

```
X-RapidAPI-Host: contextualwebsearch-websearch-v1.p.rapidapi.com
X-RapidAPI-Key: YOUR_RAPIDAPI_KEY
```

**Trending/latest news call:**

```
GET /api/search/NewsSearchAPI?q=trending+news&pageNumber=1&pageSize=50&autoCorrect=true&safeSearch=true
→ Returns latest trending news articles
```

**News by category call (category = search query):**

```
GET /api/search/NewsSearchAPI?q=technology&pageNumber=1&pageSize=50&autoCorrect=true&safeSearch=true
GET /api/search/NewsSearchAPI?q=sports&pageNumber=1&pageSize=50&autoCorrect=true&safeSearch=true
GET /api/search/NewsSearchAPI?q=business+finance&pageNumber=1&pageSize=50&autoCorrect=true&safeSearch=true
```

**Key parameters:**

| Parameter | Description | Example |
|-----------|-------------|---------|
| `q` | Search query (acts as category filter) | `q=technology` |
| `pageNumber` | Page number | `pageNumber=1` |
| `pageSize` | Results per page | `pageSize=50` |
| `autoCorrect` | Auto-correct spelling | `autoCorrect=true` |
| `safeSearch` | Safe search filter | `safeSearch=true` |

---

### 5.4 Category Normalization Map — All Providers to Firebase

```
PROVIDER SLUG              →  FIREBASE CATEGORY   SOURCE
─────────────              ─  ─────────────────   ──────
top (newsdata)             →  trending             newsdata
latest-news (currentsapi)  →  trending             currentsapi
trending+news (context)    →  trending             contextualweb

business (all 3)           →  business             all
finance (currentsapi)      →  business             currentsapi

sports (all 3)             →  sports               all
sport (newsdata)           →  sports               newsdata

technology (all 3)         →  technology           all
programming (currentsapi)  →  technology           currentsapi

entertainment (all 3)      →  entertainment        all

health (all 3)             →  health               all
lifestyle (both)           →  health               currentsapi/newsdata

science (all 3)            →  science              all
environment (newsdata)     →  science              newsdata

politics (both)            →  politics             currentsapi/newsdata

world (both)               →  world                currentsapi/newsdata
regional (currentsapi)     →  world                currentsapi

domestic (newsdata)        →  national             newsdata

crime (newsdata)           →  crime                newsdata

education (newsdata)       →  education            newsdata
academia (currentsapi)     →  education            currentsapi

food (both)                →  food                 currentsapi/newsdata
tourism (newsdata)         →  tourism              newsdata
gaming (currentsapi)       →  gaming               currentsapi
opinion (currentsapi)      →  opinion              currentsapi
general (currentsapi)      →  general              currentsapi
other (newsdata)           →  general              newsdata
(unknown)                  →  general              fallback
```

**Categories are DYNAMIC.** Firebase `categories` collection is auto-built from whatever normalized slugs appear in the articles. No hardcoded list.

---

### 5.5 GitHub Actions — Which API to Call When

```
TIER 1 (every 30 min — freshness):
  currentsapi → /v1/latest-news?language=en
  1 call → ~40 articles across all categories
  RSS feeds → 16 feeds in parallel (free, no API keys)
  ~400-500 articles across 8 categories + AI keyword filter
  Purpose: trending/latest news + India-focused category coverage

TIER 2 (every 4 hours — category fill):
  newsdata.io → /api/1/latest?category=sports&language=en         (1 credit)
  newsdata.io → /api/1/latest?category=technology&language=en     (1 credit)
  newsdata.io → /api/1/latest?category=business&language=en       (1 credit)
  contextualweb → /api/search/NewsSearchAPI?q=health              (1 call)
  contextualweb → /api/search/NewsSearchAPI?q=science             (1 call)
  contextualweb → /api/search/NewsSearchAPI?q=entertainment       (1 call)
  Purpose: fill specific categories with depth

TIER 2 also (once per day):
  currentsapi → /v1/available/categories
  → Update Firebase categories collection with any new categories
```

---

## 6. News API Response Models (GitHub Actions Parses These)

These are the raw JSON structures returned by each provider. The GitHub Actions script parses these, normalizes, and uploads to Firebase. The Android module never sees these.

### currentsapi.services — `/v1/latest-news`

```json
{
  "status": "ok",
  "news": [
    {
      "id": "abc123",
      "title": "India Wins Cricket World Cup Final",
      "description": "India beat Australia by 6 wickets in the final...",
      "url": "https://timesofindia.com/sports/cricket/...",
      "author": "Sports Desk",
      "image": "https://images.timesofindia.com/...",
      "language": "en",
      "category": ["sports"],
      "published": "2026-02-24 10:30:00 +0000"
    }
  ]
}
```

**Key fields mapping:**

| currentsapi field | → Firebase field |
|---|---|
| `news[].title` | `title` |
| `news[].description` | `description` |
| `news[].image` | `imageUrl` |
| `news[].url` | `articleUrl` |
| `news[].published` | `publishedAt` (parse to Firestore Timestamp) |
| `news[].author` or source from URL | `sourceName` (normalized) |
| `news[].category[0]` | `category` (normalized via synonym map) |

### newsdata.io — `/api/1/latest`

```json
{
  "status": "success",
  "totalResults": 50,
  "results": [
    {
      "article_id": "def456",
      "title": "Budget 2026 Highlights: Key Announcements",
      "description": "Finance Minister presented the union budget...",
      "link": "https://economictimes.com/budget-2026/...",
      "image_url": "https://images.economictimes.com/...",
      "source_id": "economic_times",
      "source_name": "Economic Times",
      "source_url": "https://economictimes.com",
      "pubDate": "2026-02-24 08:00:00",
      "category": ["business", "domestic"],
      "language": "english",
      "country": ["india"]
    }
  ],
  "nextPage": "abc123nextpage"
}
```

**Key fields mapping:**

| newsdata.io field | → Firebase field |
|---|---|
| `results[].title` | `title` |
| `results[].description` | `description` |
| `results[].image_url` | `imageUrl` |
| `results[].link` | `articleUrl` |
| `results[].pubDate` | `publishedAt` (parse to Firestore Timestamp) |
| `results[].source_name` | `sourceName` (normalized) |
| `results[].category[0]` | `category` (normalized via synonym map) |
| `results[].nextPage` | Used for pagination in script (not stored) |

**Note:** Free tier returns articles with 12-hour delay. `description` is truncated (no full content).

### contextualweb.io (via RapidAPI) — `/api/search/NewsSearchAPI`

```json
{
  "didUMean": "",
  "totalCount": 100,
  "relatedSearch": [],
  "value": [
    {
      "id": "ghi789",
      "title": "New AI Breakthrough in Medical Diagnosis",
      "description": "Researchers have developed a new AI system...",
      "url": "https://bbc.com/science/ai-medical/...",
      "image": {
        "url": "https://images.bbc.com/...",
        "height": 400,
        "width": 600
      },
      "provider": {
        "name": "BBC News",
        "favIcon": "https://bbc.com/favicon.ico"
      },
      "datePublished": "2026-02-24T06:15:00.0000000Z",
      "body": "Full article body text here..."
    }
  ]
}
```

**Key fields mapping:**

| contextualweb field | → Firebase field |
|---|---|
| `value[].title` | `title` |
| `value[].description` | `description` |
| `value[].image.url` | `imageUrl` |
| `value[].url` | `articleUrl` |
| `value[].datePublished` | `publishedAt` (parse ISO 8601 to Firestore Timestamp) |
| `value[].provider.name` | `sourceName` (normalized) |
| Category from search query param | `category` (normalized) |

**Note:** contextualweb doesn't return a `category` field. The category comes from the search query parameter used when calling the API (e.g., `q=technology`).

---

## 7. Data Models

### Domain Models — Public API (`news/api/model/`)

```kotlin
data class NewsArticle(
    val id: String,                // SHA-256(normalized_title + normalized_source)
    val title: String,
    val description: String,
    val imageUrl: String?,
    val articleUrl: String,
    val publishedAt: Long,         // epoch millis
    val sourceName: String,
    val category: String           // normalized category slug
)

data class Category(
    val id: String,                // same as slug
    val name: String,              // display name "Technology"
    val slug: String               // key "technology"
)
```

### Room Entities (`news/internal/local/entity/`)

```kotlin
@Entity(
    tableName = "articles",
    indices = [
        Index(value = ["category", "publishedAt"]),  // main feed query
        Index(value = ["publishedAt"]),               // timeline query
        Index(value = ["articleUrl"], unique = true)   // dedup safety
    ]
)
data class ArticleEntity(
    @PrimaryKey val id: String,
    val title: String,
    val description: String,
    val imageUrl: String?,
    val articleUrl: String,
    val publishedAt: Long,
    val sourceName: String,
    val category: String,
    val cachedAt: Long             // for staleness cleanup
)

@Entity(tableName = "categories")
data class CategoryEntity(
    @PrimaryKey val id: String,
    val name: String,
    val slug: String,
    val sortOrder: Int
)
```

### Firebase DTOs (`news/internal/remote/dto/`)

```kotlin
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

data class FirebaseCategoryDto(
    val id: String = "",
    val name: String = "",
    val slug: String = "",
    val sortOrder: Int = 0
)
```

---

## 8. Firebase Firestore Schema

### Collection: `news_articles`

```
news_articles/{articleId}            ← ID = SHA-256(title + source)
├── title: string
├── description: string
├── imageUrl: string | null
├── articleUrl: string
├── publishedAt: Timestamp           ← Firestore native Timestamp
├── sourceName: string               ← normalized ("times_of_india")
└── category: string                 ← normalized ("technology")
```

### Collection: `categories`

```
categories/{slug}                    ← ID = slug itself
├── name: string                     ← "Technology"
├── slug: string                     ← "technology"
└── sortOrder: number                ← display order
```

### Collection: `rss_metrics` (admin-only, app does NOT read this)

```
rss_metrics/{hash(feedUrl)[:16]}
├── category: string                 ← normalized slug
├── title: string                    ← source display name
├── rssUrl: string                   ← feed URL
├── totalHit: number                 ← total articles found
├── successHit: number               ← articles passing all filters
├── successRatio: number             ← (successHit / totalHit) × 100
├── qualityPercent: number           ← weighted quality score
└── evaluatedAt: Timestamp           ← server timestamp
```

### Required Composite Indexes

| Fields | Order | Purpose |
|--------|-------|---------|
| category ASC, publishedAt DESC | Composite | Fetch by category, latest first |
| publishedAt DESC | Single field | Global latest + sync queries |

---

## 9. Public API — NewsRepository

```kotlin
interface NewsRepository {
    fun getNewsPaged(category: String? = null): Flow<PagingData<NewsArticle>>
    fun getCategories(): Flow<List<Category>>
    suspend fun refreshNews(): Result<Unit>
    suspend fun refreshCategories(): Result<Unit>
}
```

- `getNewsPaged()` — Room PagingSource, 20 items/page, all local
- `getCategories()` — Room Flow, cached, auto-emits on change
- `refreshNews()` — batched Firebase→Room sync
- `refreshCategories()` — Firebase→Room, once per day max

---

## 10. Room DAO Design

```kotlin
@Dao
interface ArticleDao {
    @Query("SELECT * FROM articles ORDER BY publishedAt DESC")
    fun getArticlesPaged(): PagingSource<Int, ArticleEntity>

    @Query("SELECT * FROM articles WHERE category = :category ORDER BY publishedAt DESC")
    fun getArticlesByCategory(category: String): PagingSource<Int, ArticleEntity>

    @Query("SELECT MAX(publishedAt) FROM articles")
    suspend fun getNewestTimestamp(): Long?

    @Query("SELECT COUNT(*) FROM articles")
    suspend fun getCount(): Int

    @Upsert
    suspend fun upsertAll(articles: List<ArticleEntity>)

    @Query("DELETE FROM articles")
    suspend fun deleteAll()

    @Query("DELETE FROM articles WHERE cachedAt < :threshold")
    suspend fun deleteOlderThan(threshold: Long)

    // Smart delete: only prune categories with 100+ articles (thin categories keep all content)
    @Query(
        "DELETE FROM articles WHERE cachedAt < :threshold " +
        "AND category IN (" +
        "SELECT category FROM articles GROUP BY category HAVING COUNT(*) >= :minCount" +
        ")"
    )
    suspend fun deleteStaleFromLargeCategories(threshold: Long, minCount: Int = 100)

    @Query("SELECT * FROM articles WHERE category = 'trending' ORDER BY publishedAt DESC LIMIT :limit")
    suspend fun getTrendingArticles(limit: Int = 10): List<ArticleEntity>

    // Search matches: title, category slug, description, sourceName
    @Query(
        "SELECT * FROM articles WHERE title LIKE '%' || :query || '%' " +
        "OR category LIKE '%' || :query || '%' " +
        "OR description LIKE '%' || :query || '%' " +
        "OR sourceName LIKE '%' || :query || '%' " +
        "ORDER BY publishedAt DESC"
    )
    fun searchArticles(query: String): PagingSource<Int, ArticleEntity>

    @Query("SELECT * FROM articles WHERE id = :id LIMIT 1")
    suspend fun getArticleById(id: String): ArticleEntity?
}

@Dao
interface CategoryDao {
    @Query("SELECT * FROM categories ORDER BY sortOrder ASC")
    fun getCategories(): Flow<List<CategoryEntity>>

    @Upsert
    suspend fun upsertAll(categories: List<CategoryEntity>)
}
```

---

## 11. Sync Strategy — Complete Flow

### Flow 1: GitHub Actions → Firebase

```
TIER 1 (every 30 min — freshness):
  1. Fetch currentsapi "latest headlines" (1 API call → ~40 articles)
  2. Normalize category slugs via synonym map
  3. Normalize source names via source map
  4. Generate document ID: SHA-256(normalized_title + normalized_source)
  5. Compare with cached last_article_ids (GitHub Actions cache artifact)
  6. Write ONLY new articles to Firebase (set with document ID)
  7. Update last_article_ids cache

TIER 2 (every 4 hours — volume + cleanup):
  1. Fetch newsdata.io (3 categories × 1 credit = 3 credits)
  2. Fetch contextualweb (3 categories × 1 call = 3 calls)
  3. Same normalize + dedup + write pipeline
  4. Extract unique categories from all articles → upsert categories collection
  5. Query articles WHERE publishedAt < (now - 7 days) → batch delete
  6. Log summary
```

### Flow 2: Firebase → Room (Android App Sync)

```
APP OPENS
  │
  ▼
Check lastSyncTimestamp (DataStore)
  │
  ├── Never synced OR gap > 24 hours:
  │     → deleteAll() from Room
  │     → Full sync: fetch latest 300 from Firebase (MAX_SYNC_CAP)
  │     → 300 Firebase reads — fills all categories on first open
  │
  └── Gap < 24 hours:
        → Incremental sync: fetch articles WHERE publishedAt > lastSync
        → BATCH_SIZE = 30 articles per fetch
  │
  ▼
ROOM CLEANUP (after every sync):
  │
  Smart delete: only delete articles older than 7 days FROM categories with 100+ articles
  Thin categories (gaming, food, tourism, etc.) NEVER lose content
  │
  ▼
Update lastSyncTimestamp = now
  │
  ▼
AUTO-REFRESH (while app in foreground):
  Every 30 minutes → silent incremental sync (30 articles)
  Matches GitHub Actions schedule — always picks up the latest batch
  Uses viewModelScope → auto-cancelled when app goes to background
  │
  ▼
CATEGORIES SYNC (once per day):
  If lastCategorySyncTimestamp > 24 hours ago:
    → Fetch all categories from Firebase → upsert Room
    → Update lastCategorySyncTimestamp
```

### Flow 3: Room → Host App (via NewsRepository)

```
NEWS MODULE EXPOSES DATA. HOST APP BUILDS UI.

Host app's ViewModel:
  categories = newsRepository.getCategories()         ← Room Flow
  articles = newsRepository.getNewsPaged(category)     ← Room PagingSource
  │
  ▼
Host app's UI handles:
  Browse / scroll    → Room PagingSource (0 Firebase reads)
  Switch category    → Room PagingSource (0 Firebase reads)
  Pull to refresh    → Call newsRepository.refreshNews()
  Load more          → Room PagingSource serves next page
  Offline indicator  → Host app decides how to show it
  Ads                → Host app inserts ads between articles
  Navigation         → Host app owns all navigation
```

### Sync Safety Guards

```
GUARD 1: Pull-to-refresh cooldown = 10 minutes
  If lastSync < 10 min ago → skip → use Room cache
  Prevents user from spamming Firebase reads

GUARD 2: Max sync cap (full sync) = 300 articles
  Only on first open or after 24h gap
  300 reads = good content depth across all categories

GUARD 3: Incremental batch size = 30 articles
  Matches ~30 min of new content from GitHub Actions
  Keeps each sync lightweight

GUARD 4: Auto-refresh interval = 30 minutes
  Matches GitHub Actions schedule (no point refreshing faster)
  Silent — no spinner, no error toast
  Only runs while app is in foreground (viewModelScope)

GUARD 5: Categories sync = once per day
  ~18 reads instead of 18 per app open

GUARD 6: Smart Room retention
  Only delete 7-day-old articles from categories with 100+ articles
  Thin categories (gaming, food, tourism) keep all content forever
  Prevents empty category screens
```

---

## 12. GitHub Actions — Two-Tier Schedule

### Schedule

```yaml
on:
  schedule:
    # Tier 1: Freshness — every 30 minutes
    - cron: '*/30 * * * *'
  workflow_dispatch:
```

### Rotation Logic

```
HOUR:MIN  TIER   PROVIDER              CALLS
00:00     1+2    currentsapi + newsdata + contextualweb  7
00:30     1      currentsapi           1
01:00     1      currentsapi           1
01:30     1      currentsapi           1
02:00     1      currentsapi           1
02:30     1      currentsapi           1
03:00     1      currentsapi           1
03:30     1      currentsapi           1
04:00     1+2    currentsapi + newsdata + contextualweb  7
...repeats every 4 hours

Daily totals:
  currentsapi:    48 calls/day   (4.8% of 1,000)
  newsdata.io:    18 credits/day (9% of 200)
  contextualweb:  18 calls/day   (5.4% of 333/day)
  RSS feeds:      768 fetches/day (free, no limits)
  GitHub Actions: ~48 min/day    (1,440 min/month = 72% of 2,000)
  Firebase writes: ~1,100/day    (5.5% of 20,000)
```

### RSS Feeds — 16 Verified Feeds (Tier 1, every 30 min)

RSS feeds are free (no API keys, no rate limits) and run on every execution alongside currentsapi.
All feeds verified for: accessibility (HTTP 200), image quality (≥600px width), no thumbnails, no placeholders.

```
CATEGORY         SOURCE               URL                                                        IMG SIZE    ITEMS  SCOPE
─────────        ──────               ───                                                        ────────    ─────  ─────
national         The Hindu            thehindu.com/news/national/feeder/default.rss               1200x675    100    India
national         Indian Express       indianexpress.com/section/india/feed/                       1600x900    200    India
national         News18               news18.com/rss/india.xml                                    1200x800    200    India
entertainment    Bollywood Hungama    bollywoodhungama.com/rss/news.xml                           620x450     50     India
entertainment    News18               news18.com/rss/movies.xml                                   1200x800    200    India
entertainment    Koimoi               koimoi.com/feed/                                            1200x630    20     India
technology       Indian Express       indianexpress.com/section/technology/feed/                   1600x900    200    India
technology       YourStory            yourstory.com/feed                                          1012-2000   20     India
technology       The Verge            theverge.com/rss/index.xml                                  1303x868    10     Global
sports           Indian Express       indianexpress.com/section/sports/feed/                      1600x900    200    India
sports           The Hindu            thehindu.com/sport/feeder/default.rss                       1200x675    100    India
business         Indian Express       indianexpress.com/section/business/feed/                    1600x900    200    India
business         The Hindu            thehindu.com/business/feeder/default.rss                    1200x675    100    India
world            Indian Express       indianexpress.com/section/world/feed/                       1600x900    200    India lens
world            The Hindu            thehindu.com/news/international/feeder/default.rss          1200x675    100    India lens
science          The Hindu            thehindu.com/sci-tech/science/feeder/default.rss            1200x675    100    India
```

**AI — Virtual keyword-filtered category** (no dedicated feed):
After fetching tech feeds, articles matching keywords (AI, Artificial Intelligence, Machine Learning, OpenAI, GPT, LLM, ChatGPT, Gemini, Claude, DeepSeek, Neural Network, Deep Learning, Copilot) are cloned into the `ai` category.

### RSS Quality Metrics (Firestore: `rss_metrics`)

Each run measures feed quality and stores metrics for admin monitoring:

```
Collection: rss_metrics
Document ID: hash(feedUrl)[:16]

{
  category,          // normalized slug
  title,             // source display name
  rssUrl,            // feed URL
  totalHit,          // total articles in feed
  successHit,        // articles passing all filters
  successRatio,      // (successHit / totalHit) × 100
  qualityPercent,    // weighted score (image 40% + desc 30% + dedup 10% + reachable 10% + 10 base)
  evaluatedAt        // server timestamp
}

Minimum acceptable qualityPercent: 75
Feeds below 75 are flagged but NOT blocked.
Only feeds with status "disabled" (set manually) are skipped.
```

### Category Normalization (in GitHub Actions)

Categories are dynamic — derived from what providers return. A synonym map unifies duplicates:

```
RAW SLUG FROM PROVIDER         →  NORMALIZED SLUG (stored in Firebase)
domestic, nation, india, local →  national
world, international, global   →  world
sports, sport, cricket         →  sports
tech, technology, gadgets, ai  →  technology
entertainment, bollywood       →  entertainment
business, economy, finance     →  business
health, medical, wellness      →  health
science, space, environment    →  science
politics                       →  politics
top, general, other, (unknown) →  general

Unknown slug → passes through as-is → creates new category automatically
```

### Source Name Normalization

```
RAW SOURCE NAME                →  NORMALIZED
TOI, Times of India            →  Times of India
ET, Economic Times             →  Economic Times
NDTV, NDTV News                →  NDTV
BBC, BBC News, BBC World       →  BBC
News18, www.news18.com         →  News18
Bollywood Hungama              →  Bollywood Hungama
Koimoi                         →  Koimoi
YourStory                      →  YourStory
The Verge                      →  The Verge
```

### Deduplication (3 layers + Firestore safety net)

```
Layer 1 — Article ID hash:
  ID = SHA-256(lowercase(trim(title)) + lowercase(normalized_source))[:32]
  Same title + same source = same ID = skipped

Layer 2 — URL dedup (new, for RSS):
  Normalize URL: strip www., query params, trailing slash
  Hash normalized URL → skip if seen in this run
  Catches same article URL appearing across multiple feeds

Layer 3 — Cross-run cache:
  .article_cache.json stores IDs from previous runs (max 5000)
  Before Firebase upload → skip if ID already in cache

Firestore safety net:
  Firebase set() with document ID → overwrites, never creates duplicate
```

---

## 13. Offline + Freshness Behavior

### What the User Experiences

```
SCENARIO A: Opens app 30 min after last use
  → Room shows cached data instantly
  → Quick incremental sync: ~30 new articles from Firebase
  → UI refreshes in 2 seconds
  → Everything feels live

SCENARIO B: Opens app after 3 days
  → Room wiped (gap > 24h)
  → Shimmer loading for 2-3 seconds
  → Full sync: 300 articles from Firebase
  → Full fresh feed ready across all categories
  → Firebase reads: 300

SCENARIO C: Opens app with no internet
  → Room serves whatever is cached (could be hours/days old)
  → UI shows "Offline — showing cached articles"
  → All browsing, scrolling, category switching still work
  → Pull to refresh shows "No connection"

SCENARIO D: First install ever
  → Room empty → shimmer loading
  → Full sync: 300 articles from Firebase
  → Takes 3-5 seconds
  → All categories populated immediately
  → All subsequent opens are instant

SCENARIO E: User keeps app open for hours
  → Auto-refresh every 30 min silently fetches ~30 new articles
  → New content appears at the top of the feed
  → User scrolls and always finds fresh content
  → After 8 hours: ~780 articles in Room (very rich feed)

SCENARIO F: User scrolls through all cached articles in one category
  → "You're all caught up" in that category
  → Switch to another category via chips → fresh content there
  → Pull to refresh may bring more if GitHub Actions added new articles
```

### Content Strength (how much user sees vs Firebase)

```
TIME IN APP        ARTICLES IN ROOM    FIREBASE TOTAL    COVERAGE
First open         300                 ~2,500            12%
After 2 hours      360                 ~2,500            14%
After 8 hours      780                 ~2,500            31%
After 24 hours     1,740               ~2,500            70%
Day 2+             ~2,000+             ~2,500            80%+
Per category avg   17-100+             ~140              growing
Thin categories    never deleted       always content    no empty screens
```

### Freshness Summary

```
Firebase updated:      Every 30 minutes (by GitHub Actions)
App sync (open):       On every app open (if > 10 min since last sync)
App sync (auto):       Every 30 min while app is in foreground
Pull-to-refresh:       User-triggered, 10-min cooldown
Worst-case staleness:  ~60 min (if user just missed auto-refresh + GitHub Actions)
Best-case staleness:   ~30 min + 2-3 seconds
Offline:               Serves cached data, never crashes
```

---

## 14. AdMob Integration (Host App's Responsibility)

The news module does NOT include any ads. The host app integrates AdMob and decides placement.

### Recommended Ad Placements (in demo host app)

```
NEWS LIST SCREEN:
  Article 1
  Article 2
  Article 3
  Article 4
  Article 5
  ┌──────────────────┐
  │  Native Ad        │   ← after every 5 articles, marked as "Ad"
  └──────────────────┘
  Article 6
  ...

NEWS DETAIL SCREEN:
  Article content
  ┌──────────────────┐
  │  Banner Ad        │   ← at bottom
  └──────────────────┘

APP RESUME:
  App Open Ad           ← when returning from background (not every time)
```

### Future Subscription (Optional)

```
FREE (with ads):
  All articles, all categories, offline reading, ads shown

PREMIUM (₹49-99/month):
  No ads, bookmarks, read later list, breaking notifications
  → Google Play Billing Library
  → isPremium flag in DataStore → controls ad visibility
```

---

## 15. Error Handling

| Scenario | Handling |
|----------|---------|
| Firebase unreachable | Room serves cached data, sync returns `Result.failure()`, UI shows offline indicator |
| Empty Firebase | Room stays empty, UI shows empty state with "No articles yet" |
| Malformed Firebase document | Skip that document, log warning, continue with others |
| Room migration conflict | Destructive migration — news is ephemeral, no user data lost |
| GitHub Actions: one provider down | Skip it, continue with others, log error |
| GitHub Actions: all providers down | Workflow logs failure, existing Firebase data stays intact |
| Duplicate articles (same title+source) | SHA-256 hash = same document ID = overwrites, no duplicate |
| Sync during no internet | Catches exception, returns failure, Room serves cache |
| OOM during sync | Batched (50 at a time), max 100 cap, safe on any device |
| User rapid open/close | 5-min sync cooldown prevents excessive reads |
| Firebase quota exceeded | App gets rejected calls → same as "unreachable" → Room cache |

---

## 16. Dependencies

### `gradle/libs.versions.toml`

```toml
hilt = "2.51.1"
hiltNavigationCompose = "1.2.0"
room = "2.7.1"
paging = "3.3.6"
pagingCompose = "3.3.6"
firebaseBom = "33.7.0"
coil = "2.7.0"
navigationCompose = "2.8.9"
timber = "5.0.1"
datastore = "1.1.4"
playServicesAds = "23.6.0"
```

### news module (`news/build.gradle.kts`) — DATA ONLY

```
Hilt, Firebase Firestore, Room + Room Paging, Paging 3 (core, NOT compose),
Timber, DataStore, kotlinx-coroutines
```

No Coil, no Navigation, no Compose, no AdMob. Pure data module.

### app module (`app/build.gradle.kts`) — UI + everything else

```
implementation(project(":news"))
Hilt, Navigation Compose, Paging 3 Compose, Coil, AdMob (play-services-ads),
Material 3, Compose UI
```

---

## 17. Host App Integration (3 Steps)

### Step 1 — Add module

```kotlin
// settings.gradle.kts
include(":news")

// app/build.gradle.kts
dependencies {
    implementation(project(":news"))
}
```

### Step 2 — Add Firebase

- Place `google-services.json` in `app/`
- Apply google-services plugin in `app/build.gradle.kts`

### Step 3 — Build your own UI using the module's data

```kotlin
// In YOUR ViewModel (host app owns this)
@HiltViewModel
class NewsViewModel @Inject constructor(
    private val newsRepository: NewsRepository
) : ViewModel() {
    val news = newsRepository.getNewsPaged().cachedIn(viewModelScope)
    val categories = newsRepository.getCategories()

    fun refresh() = viewModelScope.launch {
        newsRepository.refreshNews()
    }
}

// In YOUR Composable (host app owns this)
@Composable
fun NewsListScreen(viewModel: NewsViewModel = hiltViewModel()) {
    val articles = viewModel.news.collectAsLazyPagingItems()
    val categories by viewModel.categories.collectAsState(initial = emptyList())
    // Build your own UI however you want
}
```

The module gives you `Flow<PagingData<NewsArticle>>` and `Flow<List<Category>>`. What you do with that data is entirely up to you — Compose, XML, MVVM, MVI, any architecture.

---

## 18. Google Play Store Requirements

### Mandatory Declaration (since Aug 27, 2025)

```
Entity type:         Commercial/private
Is aggregator:       YES
Content categories:  News and current affairs, Business, Sports,
                     Science and technology, Entertainment
Contact URL:         Website with email + phone (not just social media)
Legal entity:        Same as developer account
Editorial guidelines: Optional (not required)
Awards/memberships:  Optional (not required)
```

### Aggregator-Specific Rules

- MUST show source name per article (already in our model)
- MUST NOT be just a webview wrapper (we have native Compose UI)
- All linked content must comply with Play policies

### Required Before Submission

```
☐ Google Play Developer account (₹2,082 one-time)
☐ Privacy Policy URL (required for AdMob + Play Store)
☐ Contact page URL with email + phone
☐ Source attribution per article in app
☐ "About" page: "Content belongs to respective publishers"
☐ News & Magazine declaration completed in Play Console
☐ AdMob test ads replaced with production ad unit IDs
☐ API keys stored in GitHub Secrets (never in code)
```

---

## 19. Action Items Before Production Launch

```
MUST DO:
  1. ✉️ Email currentsapi support — confirm commercial use on free tier
  2. ✉️ Email contextualweb/RapidAPI — confirm commercial use on free tier
  3. 📝 Create Privacy Policy page (free generator)
  4. 📝 Create contact page with email + phone (Google Sites, free)
  5. 📝 Add source attribution per article in UI
  6. 📝 Add disclaimer: "We do not own or claim copyright on news content"
  7. 🔑 Create AdMob account + ad unit IDs
  8. 🔑 Store all API keys in GitHub Secrets
  9. 📊 Monitor Firebase usage dashboard daily for first 2 weeks
```

---

## 20. Budget Summary

```
┌────────────────────────────────────────────────────────────────┐
│                                                                │
│  FRESHNESS:      ~30 minutes                                   │
│  DAU CAPACITY:   ~283-363 users (free tier)                    │
│  MONTHLY COST:   ₹0                                            │
│  ONE-TIME COST:  ₹2,082 (Play Store developer account)         │
│                                                                │
│  SERVICE               LIMIT            USAGE          % USED  │
│  ───────               ─────            ─────          ──────  │
│  Firebase reads        50,000/day       ~1,908/user    varies  │
│  Firebase writes       20,000/day       ~1,100         5.5%    │
│  Firebase deletes      20,000/day       400            2.0%    │
│  Firebase storage      1 GiB            2-3 MB         0.3%    │
│  Firebase egress       10 GiB/month     500 MB         4.9%    │
│  GitHub Actions        2,000 min/month  1,440 min      72%     │
│  currentsapi           1,000/day        48             4.8%    │
│  newsdata.io           200 credits/day  18             9.0%    │
│  contextualweb         10,000/month     540            5.4%    │
│  RSS feeds (16)        Unlimited        768/day        FREE    │
│                                                                │
│  Firebase reads breakdown (per user/day, normal usage):        │
│    First open (incremental):    30 reads                       │
│    Auto-refresh (48× @ 30/ea):  1,440 reads                   │
│    Manual pull (~5×):           150 reads                      │
│    Category sync:               18 reads                       │
│    Total per user:              ~1,638 reads/day               │
│                                                                │
│  DAU capacity:                                                 │
│    10 users:   ~16,380 reads (33% of limit)                    │
│    50 users:   ~81,900 — EXCEEDS (need Blaze ≈ $0.02/day)     │
│    100 normal: ~16,800 reads (34% — fits easily)               │
│    283 normal: ~47,600 reads (95% — ceiling)                   │
│    363 mixed:  ~47,600 reads (95% — ceiling)                   │
│                                                                │
│  ALL WITHIN FREE TIER AT LAUNCH. ZERO COST.                    │
│                                                                │
└────────────────────────────────────────────────────────────────┘
```

---

## 21. Category UI Integration (DONE)

### Changes Implemented

```
1. ArticleDao.searchArticles — now searches: title, category, description, sourceName
   File: news/.../dao/ArticleDao.kt

2. CategoryIcons.kt — maps 18 category slugs to Material Icons
   File: app/.../util/CategoryIcons.kt (NEW)
   trending→TrendingUp, national→AccountBalance, politics→Gavel,
   business→BusinessCenter, technology→Computer, ai→SmartToy,
   sports→SportsScore, entertainment→Movie, health→HealthAndSafety,
   science→Biotech, world→Public, gaming→SportsEsports,
   education→School, crime→LocalFireDepartment, food→Fastfood,
   tourism→FlightTakeoff, opinion→MenuBook, general→Article

3. NewsViewModel — category filtering wired up
   File: app/.../viewmodel/NewsViewModel.kt
   - allCategories: all categories from Room
   - userCategories: filtered to onboarding selections
   - activeCategory: currently selected category chip (null = "All")
   - selectCategory(slug): called when user taps a chip
   - articles flow reacts to activeCategory changes
   - loadSelectedCategorySlugs(): reads onboarding prefs on init
   - saveSelectedCategories(): also updates live state

4. HomeScreen — horizontal category chip bar
   File: app/.../ui/screens/HomeScreen.kt
   - FilterChip LazyRow below Breaking News carousel
   - First chip: "All" (Dashboard icon)
   - Remaining: user's onboarding categories (per-category icons)
   - Selected chip: primary color
   - Section title changes to match active category name
```

---

## 22. Near Real-Time Sync (DONE)

### Implemented Changes

```
STATUS: IMPLEMENTED

1. ArticleDao.kt — added deleteStaleFromLargeCategories query
   Smart delete: only prunes categories with 100+ articles
   Thin categories never lose content

2. SyncManager.kt — updated sync parameters
   MIN_SYNC_INTERVAL_MS:  5 min → 10 min (pull-to-refresh cooldown)
   BATCH_SIZE:            50 (kept at 50 — matches ~50-80 new articles per 30 min)
   MAX_SYNC_CAP:          100 → 300 (first open gets rich content)
   MIN_CATEGORY_RETENTION: 100
   Replaced deleteOlderThan() with deleteStaleFromLargeCategories()

3. NewsViewModel.kt — added auto-refresh timer
   AUTO_REFRESH_INTERVAL_MS = 30 min
   startAutoRefresh() in init, runs in viewModelScope
   Silent — no spinner, no error toast
   Auto-cancelled when ViewModel is cleared

4. fetch_news.py — run metrics logging
   Each article tagged with _provider (currentsapi/rss/newsdata/contextualweb/ai)
   Metrics appended to logs/run_metrics.csv after every run:
     timestamp, tier, raw_total, within_run_dups, unique_this_run,
     cross_run_dups, fresh_unique, per-provider counts
   _provider key stripped before Firebase upload (explicit field mapping)

5. fetch-news.yml — workflow updates
   Reverted cache venv: removed actions/cache@v4 pip-cache + conditional install
   Now uses simple: pip install -r scripts/requirements.txt
   permissions: contents: write (was read)
   Added git commit+push step for logs/run_metrics.csv

6. logs/run_metrics.csv — created with header row
   Grows automatically with each GitHub Actions run
```

---

## 23. Pending Future Tasks

### Task F1: ~~Article Priority Scoring~~ — SUPERSEDED

```
STATUS: SUPERSEDED by Article Quality Tagging (implemented)

The quality field ("high"/"low") replaces the proposed multi-level priority score.
  - fetch_news.py computes quality based on: image presence + description >= 200 chars
  - Firebase stores quality field on every article
  - App fetches high quality first, fills gaps with low quality
  - No further action needed on F1.
```

### Task F2: Deletion Policy Redesign (Firebase + Room) — NOT YET IMPLEMENTED

```
STATUS: PENDING — no deletions anywhere until this is designed and approved

CURRENT STATE (as of Feb 2026):
  - Firebase: NO deletions. Articles accumulate indefinitely. Cleanup function removed.
  - Room: NO deletions. Only @Upsert. DAO delete methods exist but have zero callers.
  - Bookmarks: User can unbookmark (removes from bookmarks table only, not articles).

NEEDS DESIGN:
  1. Firebase retention — when to delete old articles? By age? By count per category?
     Must account for Spark plan limits (20,000 deletes/day) and storage (1 GiB).
  2. Room retention — when to prune cached articles? Quality-aware? Category-aware?
     Must never leave a category empty. Must never delete while Room is the only copy.
  3. Coordination — should Room deletion depend on Firebase state or be independent?
  4. Trigger — GitHub Actions (Firebase), SyncManager (Room), or both?

FILES THAT WILL BE AFFECTED:
  - scripts/fetch_news.py — Firebase cleanup (currently removed)
  - ArticleDao.kt — Room delete queries (exist but unused)
  - SyncManager.kt — Room delete calls (currently removed)

DO NOT ADD ANY DELETION CALLS UNTIL THIS TASK IS FULLY DESIGNED AND APPROVED.
```

---

## 24. Implementation Phases

| Phase | What | Module | Files |
|-------|------|--------|-------|
| **1** | Project setup — create `news` module, add dependencies, configure Hilt + Firebase | both | `settings.gradle.kts`, `libs.versions.toml`, `news/build.gradle.kts`, `app/build.gradle.kts`, `NewsApp.kt`, `AndroidManifest.xml` |
| **2** | Public API — domain models + repository interface | `news` | `NewsArticle.kt`, `Category.kt`, `NewsRepository.kt` |
| **3** | Room layer — entities, DAOs, database, mappers | `news` | `ArticleEntity.kt`, `CategoryEntity.kt`, `ArticleDao.kt`, `CategoryDao.kt`, `NewsDatabase.kt`, `ArticleMapper.kt`, `CategoryMapper.kt` |
| **4** | Firebase layer — Firestore sources + DTOs | `news` | `FirebaseNewsSource.kt`, `FirebaseCategorySource.kt`, `FirebaseArticleDto.kt`, `FirebaseCategoryDto.kt` |
| **5** | Sync manager — batched sync, safety guards, staleness logic | `news` | `SyncManager.kt` |
| **6** | Repository implementation — orchestrates sync + Room reads | `news` | `NewsRepositoryImpl.kt` |
| **7** | DI wiring — Hilt module binding all internals | `news` | `NewsModule.kt` |
| **8** | **NEWS MODULE COMPLETE** — data-only, plug-and-play ready | `news` | — |
| **9** | Demo app — ViewModel + screens + components | `app` | `NewsViewModel.kt`, `NewsListScreen.kt`, `NewsDetailScreen.kt`, `NewsCard.kt`, `CategoryTabRow.kt`, `ShimmerPlaceholder.kt`, `ErrorRetryCard.kt` |
| **10** | Demo app — AdMob integration | `app` | `NativeAdCard.kt`, `BannerAdView.kt`, `AdManager.kt` |
| **11** | Demo app — wiring, navigation, offline states | `app` | `MainActivity.kt` |
| **12** | GitHub Actions — two-tier workflow with normalization + dedup + RSS feeds + quality metrics | repo root | `.github/workflows/fetch-news.yml`, `scripts/fetch_news.py`, `scripts/requirements.txt` |

---

## 25. RSS URLs, Merged Categories & Final Update Plan

### 25.1 Merged Categories (13 → 7)

| New ID | Hindi Name | English | Merged From |
|--------|------------|---------|-------------|
| **nat** | दुनिया | Global | nat, pol, cri, int, def |
| **spt** | खेल | Sports | spt |
| **ent** | मनोरंजन | Entertainment | ent |
| **bus** | व्यापार | Business | bus, stk |
| **tec** | तकनीक | Technology | tec, sci |
| **hlt** | स्वास्थ्य | Health | hlt |
| **edu** | शिक्षा | Education | edu |

### 25.2 Final RSS URLs (19 feeds)

| Category | # | Source | URL |
|----------|---|--------|-----|
| **Global (nat)** | 1 | Aaj Tak | `https://www.aajtak.in/rssfeeds/?id=home` |
| | 2 | ABP Live | `https://www.abplive.com/news/india/feed` |
| | 3 | TV9 Hindi | `https://www.tv9hindi.com/india/feed` |
| | 4 | Business Standard Hindi | `https://hindi.business-standard.com/rss/politics.xml` |
| | 5 | Navjivan India | `https://www.navjivanindia.com/stories.rss?section=politics` |
| | 6 | ABP Live | `https://www.abplive.com/news/crime/feed` |
| **Sports (spt)** | 7 | TV9 Hindi | `https://www.tv9hindi.com/sports/feed` |
| | 8 | India TV | `https://www.indiatv.in/rssnews/topstory-sports.xml` |
| **Entertainment (ent)** | 9 | ABP Live | `https://www.abplive.com/entertainment/feed` |
| | 10 | TV9 Hindi | `https://www.tv9hindi.com/entertainment/feed` |
| | 11 | India TV | `https://www.indiatv.in/rssnews/topstory-entertainment.xml` |
| | 12 | Bollywood Hungama | `https://www.bollywoodhungama.com/rss/news.xml` |
| **Business (bus)** | 13 | ABP Live | `https://www.abplive.com/business/feed` |
| | 14 | TV9 Hindi | `https://www.tv9hindi.com/business/feed` |
| | 15 | Business Standard Hindi | `https://hindi.business-standard.com/rss/markets/share-market.xml` |
| **Technology (tec)** | 16 | ABP Live | `https://www.abplive.com/technology/feed` |
| | 17 | TV9 Hindi | `https://www.tv9hindi.com/technology/feed` |
| **Health (hlt)** | 18 | ABP Live | `https://www.abplive.com/health/feed` |
| **Education (edu)** | 19 | ABP Live | `https://www.abplive.com/education/feed` |

### 25.3 Files to Change

| File | Changes |
|------|---------|
| `scripts/fetch_news.py` | Replace RSS_FEEDS with 19 feeds; map pol/cri→nat, stk→bus; add User-Agent for Bollywood Hungama; TOP_TRENDING_CATEGORIES: `{"spt","ent","nat"}`; add normalize_title, HINDI_STOPWORDS, quality gate (min title/desc), trending algorithm (26) |
| `news/.../LocalCategories.kt` | Reduce to 7 categories: nat, spt, ent, bus, tec, hlt, edu |
| `news/.../ArticleDao.kt` | Add `getArticlesByCategories(categories: List<String>)`; update `getTrendingArticles` to use `('spt','ent','nat')` |
| `news/.../NewsRepositoryImpl.kt` | Category expansion: nat→(nat,pol,cri,int,def), bus→(bus,stk), tec→(tec,sci) |
| `app/.../CategoryIcons.kt` | Update categoryNameMap to 7 slugs only |
| `news/.../CategoryDao.kt` | Update seed to match LocalCategories |
| `.cursor/rules/github-actions-strategy.mdc` | Update RSS feed table, category codes |
| `.cursor/rules/project-context.mdc` | Update category short codes |

### 25.4 Category Expansion (Repository)

When user selects a merged category, query multiple DB categories:

| UI Slug | DB Categories Queried |
|---------|------------------------|
| nat | nat, pol, cri, int, def |
| bus | bus, stk |
| tec | tec, sci |
| spt, ent, hlt, edu | single category |

### 25.5 User Preference Migration

| Old Slug | New Slug |
|----------|----------|
| pol, cri, int, def | nat |
| stk | bus |
| sci | tec |

On app load: if stored `selected_categories` contains old slugs, map to new before use.

### 25.6 Implementation Order

1. Update `LocalCategories.kt` (7 categories)
2. Add `getArticlesByCategories()` in `ArticleDao.kt`
3. Update `NewsRepositoryImpl.kt` with category expansion
4. Update `CategoryIcons.kt` and `categoryNameMap`
5. Update `CategoryDao` seed (if applicable)
6. Update `fetch_news.py` (RSS_FEEDS, TOP_TRENDING_CATEGORIES, User-Agent)
7. Update `ArticleDao.getTrendingArticles` to use nat
8. Add user-pref migration for merged slugs
9. Update `.cursor/rules/github-actions-strategy.mdc` and `project-context.mdc`
10. Implement trending algorithm (26): normalize_title, quality gate, time decay, trending detection

### 25.7 Summary

| Metric | Before | After |
|--------|--------|-------|
| Categories | 13 | 7 |
| RSS feeds | 25 (4 dead) | 19 (all working) |
| Entertainment | 8 (incl. South) | 4 (Bollywood only) |
| Trending | spt, ent, pol | spt, ent, nat |

---

## 26. Trending Algorithm & Article Quality

### 26.1 Pipeline Order

```
Fetch (currentsapi + RSS + newsdata + contextualweb)
    ↓
Normalize (category, source, title → normalize_title for ALL)
    ↓
Dedup (use normalized title in hash)
    ↓
Quality gate (min title len, min desc len, valid image) — ALL articles
    ↓
Trending detection (24h window, cluster by normalized title, freq≥2, best article + time decay)
    ↓
Add "t": 1 to trending articles only
    ↓
Upload to Firebase
```

### 26.2 Title Normalization (ALL Articles)

Applied to every article during processing — used for dedup, trending clustering, and any future logic.

```python
def normalize_title(title: str) -> str:
    if not title or not title.strip():
        return ""
    t = title.lower().strip()
    t = re.sub(r'[^\w\s\u0900-\u097F]', '', t)  # keep Hindi chars, remove punctuation
    words = t.split()
    words = [w for w in words if w not in HINDI_STOPWORDS and len(w) > 1]
    return " ".join(words)
```

### 26.3 Hindi Stopwords (Primary)

```python
HINDI_STOPWORDS = frozenset({
    # Particles
    "का", "की", "के", "को", "में", "से", "पर", "तक", "द्वारा", "के लिए",
    "है", "हैं", "था", "थे", "था", "थी", "हो", "होता", "होती", "होते",
    "यह", "वह", "इस", "उस", "जो", "कि", "क्या", "कैसे", "कब", "कहाँ",
    "और", "या", "पर", "लेकिन", "तो", "भी", "ही", "सिर्फ", "बस",
    # Common
    "न्यूज़", "खबर", "समाचार", "रिपोर्ट", "दावा", "कहा", "बोला",
    "मिली", "मिला", "हुआ", "हुई", "कर", "किया", "किए", "गया", "गई",
    "दिया", "दी", "लिया", "ली", "पड़ा", "पड़ी",
    # English (secondary)
    "the", "a", "an", "and", "or", "in", "on", "at", "to", "for", "of",
    "is", "are", "was", "were", "news", "report", "says",
})
```

### 26.4 Article Quality Gate (ALL Articles)

Applies to every article regardless of trending. Min title/description length is part of article quality.

| Check | Value | Notes |
|-------|-------|-------|
| MIN_TITLE_LEN | 15 | chars after strip |
| MIN_DESC_LEN | 100 | regular articles |
| MIN_DESC_TRENDING | 30 | spt/ent/nat with short desc can still pass for trending |
| Valid image | required | _is_valid_image_url |

### 26.5 Time Decay (Trending Only)

Higher weight for recent articles when picking best article per cluster.

```python
def time_decay_score(article: dict) -> float:
    """Higher for more recent. 1.0 at now, decays over 24h."""
    pub = article.get("publishedAt")
    if not pub:
        return 0.5
    ts = pub.timestamp() if hasattr(pub, "timestamp") else pub / 1000
    age_hours = (datetime.now(timezone.utc).timestamp() - ts) / 3600
    return max(0.1, 2 ** (-age_hours / 6))  # half-life ~6h
```

### 26.6 Best Article Selection (Trending Cluster)

```python
def best_article(articles: list[dict]) -> dict:
    return max(articles, key=lambda a: (
        SOURCE_QUALITY.get(a.get("sourceName", ""), 0),
        time_decay_score(a),  # recent = higher
        len(a.get("description", ""))  # tie-breaker
    ))
```

### 26.7 Trending Detection Steps

1. Collect articles from last 24 hours only
2. Normalize title (lowercase, stopwords, punctuation) — uses shared normalize_title for ALL
3. Group similar articles by normalized title
4. For each cluster: frequency = number of distinct sources
5. Mark trending if frequency ≥ 2
6. From each trending cluster: keep 1 best article (source quality + time decay)
7. Add `"t": 1` only for these articles; omit for others to save memory

### 26.8 Scope Summary

| Item | Scope |
|------|-------|
| Title normalization | ALL articles |
| Hindi stopwords | Primary (expand as needed) |
| Time decay | Trending only (best-article selection) |
| Min title length | ALL articles (quality gate) |
| Min description length | ALL articles (quality gate) |

---

## 27. Task Breakdown

### Phase A: RSS & Fetch Pipeline

| ID | Task | File(s) | Depends |
|----|------|---------|---------|
| A1 | Replace RSS_FEEDS with 19 feeds; remove dead feeds | `scripts/fetch_news.py` | — |
| A2 | Add User-Agent for RSS requests (Bollywood Hungama) | `scripts/fetch_news.py` | — |
| A3 | Map pol/cri→nat, stk→bus in CATEGORY_SHORT | `scripts/fetch_news.py` | — |
| A4 | Add HINDI_STOPWORDS constant | `scripts/fetch_news.py` | — |
| A5 | Add normalize_title() and use for ALL articles | `scripts/fetch_news.py` | A4 |
| A6 | Update make_article_id / dedup to use normalized title | `scripts/fetch_news.py` | A5 |
| A7 | Add quality gate: MIN_TITLE_LEN=15, MIN_DESC_LEN=100, MIN_DESC_TRENDING=30 | `scripts/fetch_news.py` | — |
| A8 | Replace verification gate with quality gate (apply to ALL) | `scripts/fetch_news.py` | A7 |
| A9 | Add time_decay_score(), best_article(), SOURCE_QUALITY | `scripts/fetch_news.py` | — |
| A10 | Add compute_trending(): 24h filter, cluster, freq≥2, add "t":1 | `scripts/fetch_news.py` | A5, A9 |
| A11 | Call compute_trending() before upload_bundles | `scripts/fetch_news.py` | A10 |
| A12 | Add "t" to Firebase bundle short field mapping (optional) | `scripts/fetch_news.py` | A11 |
| A13 | Update TOP_TRENDING_CATEGORIES to {"spt","ent","nat"} | `scripts/fetch_news.py` | — |

### Phase B: Merged Categories (App)

| ID | Task | File(s) | Depends |
|----|------|---------|---------|
| B1 | Reduce LocalCategories to 7 (nat, spt, ent, bus, tec, hlt, edu) | `news/.../LocalCategories.kt` | — |
| B2 | Add getArticlesByCategories(categories: List<String>) to ArticleDao | `news/.../ArticleDao.kt` | — |
| B4 | Add category expansion map in NewsRepositoryImpl | `news/.../NewsRepositoryImpl.kt` | B2 |
| B5 | Use getArticlesByCategories when category is nat/bus/tec | `news/.../NewsRepositoryImpl.kt` | B4 |
| B6 | Update categoryNameMap to 7 slugs only | `app/.../CategoryIcons.kt` | B1 |
| B7 | Update CategoryDao seed (if seeds from LocalCategories) | `news/.../CategoryDao.kt` | B1 |
| B8 | Add user-pref migration: pol/cri/int/def→nat, stk→bus, sci→tec | `app/.../NewsViewModel.kt` | B1 |

### Phase C: Firebase + Room (Trending Flag)

| ID | Task | File(s) | Depends |
|----|------|---------|---------|
| C1 | Add "t" field to Firebase bundle parsing | `news/.../FirebaseNewsSource.kt` | A12 |
| C2 | Add trending: Boolean to ArticleEntity | `news/.../ArticleEntity.kt` | — |
| C3 | Map Firebase "t" to ArticleEntity.trending in sync | `news/.../FirebaseNewsSource.kt` or mapper | C1, C2 |
| C4 | Add trending column to articles table (migration if needed) | `news/.../NewsDatabase.kt` | C2 |
| C5 | Update getTrendingArticles to WHERE trending = 1 | `news/.../ArticleDao.kt` | C4 |

### Phase D: Context & Docs

| ID | Task | File(s) | Depends |
|----|------|---------|---------|
| D1 | Update RSS feed table in github-actions-strategy.mdc | `.cursor/rules/github-actions-strategy.mdc` | A1 |
| D2 | Update category codes in github-actions-strategy.mdc | `.cursor/rules/github-actions-strategy.mdc` | B1 |
| D3 | Update category short codes in project-context.mdc | `.cursor/rules/project-context.mdc` | B1 |
| D4 | Update firebase-read-strategy.mdc if trending affects read budget | `.cursor/rules/firebase-read-strategy.mdc` | — |

### Execution Order

```
A1, A2, A3, A4, A7, A13 (parallel)
    ↓
A5, A6, A8, A9 (A5 depends on A4)
    ↓
A10, A11, A12
    ↓
B1, B2 (parallel)
    ↓
B4, B5, B6, B7, B8
    ↓
C1, C2, C4 (parallel)
    ↓
C3, C5
    ↓
D1, D2, D3, D4 (parallel)
```

### Task Summary

| Phase | Tasks | Count |
|-------|-------|-------|
| A: RSS & Fetch | A1–A13 | 13 |
| B: Merged Categories | B1–B2, B4–B8 | 7 |
| C: Firebase + Room | C1–C5 | 5 |
| D: Context & Docs | D1–D4 | 4 |
| **Total** | | **29** |
