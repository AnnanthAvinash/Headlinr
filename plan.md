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
│  └──────┬──────┘                ├─────────────┤             │
│         │                        │contextualweb│             │
│         │                        │ (coverage)  │             │
│         │                        └──────┬──────┘             │
│         └──────────┬────────────────────┘                    │
│                    ▼                                         │
│         ┌──────────────────────┐                             │
│         │ Normalize categories │                             │
│         │ Normalize sources    │                             │
│         │ Deduplicate (hash)   │                             │
│         │ Filter already-known │                             │
│         └──────────┬───────────┘                             │
│                    ▼                                         │
│         ┌──────────────────────┐                             │
│         │   Firebase Firestore  │                             │
│         │  ┌────────────────┐  │                             │
│         │  │ news_articles   │  │                             │
│         │  ├────────────────┤  │                             │
│         │  │ categories      │  │                             │
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
| Minutes (private repo) | 2,000/month | 750/month | 37.5% | docs.github.com/billing |
| Minutes (public repo) | Unlimited | — | — | docs.github.com/billing |

### News API Providers

| Provider | Free Limit | Our Usage | % Used | Delay | Commercial Use |
|----------|-----------|-----------|--------|-------|----------------|
| currentsapi.services | 1,000 req/day | 48/day | 4.8% | Near real-time | **VERIFY before launch** |
| newsdata.io | 200 credits/day (=2,000 articles) | 18 credits/day | 9% | **12 hours** | **YES** (confirmed) |
| contextualweb.io | 10,000 req/month | 540/month | 5.4% | Unknown | **VERIFY before launch** |

### Capacity

```
~2,600 daily active users on free tier (₹0/month)
~30 minutes data freshness (from currentsapi)
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
  Purpose: trending/latest news

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

    @Upsert
    suspend fun upsertAll(articles: List<ArticleEntity>)

    @Query("DELETE FROM articles")
    suspend fun deleteAll()

    @Query("DELETE FROM articles WHERE cachedAt < :threshold")
    suspend fun deleteOlderThan(threshold: Long)
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
  ├── Never synced OR gap > 7 days:
  │     → deleteAll() from Room
  │     → Full sync: fetch latest 100 from Firebase
  │
  ├── Gap > 24 hours:
  │     → deleteAll() from Room
  │     → Full sync: fetch latest 100 from Firebase
  │
  └── Gap < 24 hours:
        → Incremental sync: fetch articles WHERE publishedAt > lastSync
  │
  ▼
SYNC EXECUTION (batched, crash-proof):
  │
  BATCH 1: Fetch 50 newest → upsert Room → UI shows immediately (2-3 sec)
  BATCH 2: Fetch next 50 → upsert Room → background, silent
  STOP when: batch returns < 50 OR total >= 100 (MAX_SYNC_CAP)
  │
  ▼
Update lastSyncTimestamp = now
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
GUARD 1: Minimum sync interval = 5 minutes
  If lastSync < 5 min ago → skip → use Room cache

GUARD 2: Max sync cap = 100 articles per sync
  Prevents one user consuming thousands of reads

GUARD 3: Categories sync = once per day
  15 reads instead of 15 per app open

GUARD 4: Batch size = 50
  Max 50 articles in memory at any time
  Prevents OOM on low-end devices

GUARD 5: "Load older" = user-triggered only
  No auto-fetch beyond cap
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
  GitHub Actions: ~25 min/day    (750 min/month = 37.5% of 2,000)
  Firebase writes: ~660/day      (3.3% of 20,000)
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
top, general, other, (unknown) →  general

Unknown slug → passes through as-is → creates new category automatically
```

### Source Name Normalization

```
RAW SOURCE NAME                →  NORMALIZED
TOI, Times of India            →  times_of_india
ET, Economic Times             →  economic_times
NDTV, NDTV News                →  ndtv
BBC, BBC News, BBC World       →  bbc
```

### Deduplication

```
Document ID = SHA-256(lowercase(trim(title)) + lowercase(normalized_source))

Firebase set() with document ID:
  → Same article = same ID = overwrites (no duplicate)
  → New article = new ID = created

Pre-write filter:
  → Compare generated IDs with last_article_ids cache (GitHub Actions artifact)
  → Write ONLY new articles → saves Firebase writes
```

---

## 13. Offline + Freshness Behavior

### What the User Experiences

```
SCENARIO A: Opens app 30 min after last use
  → Room shows cached data instantly
  → Quick sync: ~5 new articles from Firebase (5 reads)
  → UI refreshes in 2 seconds
  → Everything feels live

SCENARIO B: Opens app after 3 days
  → Room wiped (gap > 24h)
  → Shimmer loading for 2-3 seconds
  → Sync fetches 100 articles (2 batches)
  → Full fresh feed ready
  → Firebase reads: 100

SCENARIO C: Opens app with no internet
  → Room serves whatever is cached (could be hours/days old)
  → UI shows "Offline — showing cached articles"
  → All browsing, scrolling, categories still work
  → Pull to refresh shows "No connection"

SCENARIO D: First install ever
  → Room empty → shimmer loading
  → Full sync: 100 articles from Firebase
  → Takes 3-5 seconds
  → All subsequent opens are instant

SCENARIO E: User scrolls through all cached articles
  → After all articles: "You're all caught up"
  → Optional: "Load older articles" button (user-triggered, 50 reads)
```

### Freshness Summary

```
Firebase updated:     Every 30 minutes (by GitHub Actions)
App sync:             On every app open (if > 5 min since last sync)
Worst-case staleness: ~30 minutes (article age in Firebase) + time since last app open
Best-case staleness:  ~30 minutes + 2-3 seconds
Offline:              Serves cached data, never crashes
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
│  DAU CAPACITY:   ~2,600 users                                  │
│  MONTHLY COST:   ₹0                                            │
│  ONE-TIME COST:  ₹2,082 (Play Store developer account)         │
│                                                                │
│  SERVICE               LIMIT            USAGE       % USED     │
│  ───────               ─────            ─────       ──────     │
│  Firebase reads        50,000/day       1,900       3.8%       │
│  Firebase writes       20,000/day       660         3.3%       │
│  Firebase deletes      20,000/day       200         1.0%       │
│  Firebase storage      1 GiB            1 MB        0.1%       │
│  Firebase egress       10 GiB/month     300 MB      2.9%       │
│  GitHub Actions        2,000 min/month  750 min     37.5%      │
│  currentsapi           1,000/day        48          4.8%       │
│  newsdata.io           200 credits/day  18          9.0%       │
│  contextualweb         10,000/month     540         5.4%       │
│                                                                │
│  ALL LIMITS UNDER 40%. MASSIVE SAFETY BUFFER.                  │
│                                                                │
└────────────────────────────────────────────────────────────────┘
```

---

## 21. Implementation Phases

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
| **12** | GitHub Actions — two-tier workflow with normalization + dedup | repo root | `.github/workflows/fetch-news.yml`, `scripts/fetch_news.py` |
