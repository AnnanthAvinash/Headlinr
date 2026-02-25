"""
Headlinr — News Fetcher for GitHub Actions
Fetches from multiple providers, normalizes, deduplicates, uploads to Firebase.

Two-tier schedule:
  TIER 1 (every 30 min): currentsapi latest headlines
  TIER 2 (every 4 hours): newsdata.io + contextualweb category fills + cleanup
"""

import hashlib
import json
import os
import sys
import time
from datetime import datetime, timedelta, timezone
from pathlib import Path

import requests
from google.cloud import firestore

# ---------------------------------------------------------------------------
# Config
# ---------------------------------------------------------------------------

CURRENTSAPI_KEY = os.environ.get("CURRENTSAPI_KEY", "")
NEWSDATA_KEY = os.environ.get("NEWSDATA_KEY", "")
RAPIDAPI_KEY = os.environ.get("RAPIDAPI_KEY", "")

CACHE_FILE = Path(__file__).parent / ".article_cache.json"
MAX_CACHE_SIZE = 5000

FIRESTORE_ARTICLES = "news_articles"
FIRESTORE_CATEGORIES = "categories"

REQUEST_TIMEOUT = 15

# ---------------------------------------------------------------------------
# Category normalization
# ---------------------------------------------------------------------------

CATEGORY_MAP = {
    "top": "trending",
    "latest-news": "trending",
    "trending+news": "trending",
    "trending news": "trending",
    "business": "business",
    "economy": "business",
    "finance": "business",
    "sports": "sports",
    "sport": "sports",
    "cricket": "sports",
    "technology": "technology",
    "tech": "technology",
    "programming": "technology",
    "gadgets": "technology",
    "ai": "technology",
    "entertainment": "entertainment",
    "bollywood": "entertainment",
    "health": "health",
    "medical": "health",
    "wellness": "health",
    "lifestyle": "health",
    "science": "science",
    "space": "science",
    "environment": "science",
    "politics": "politics",
    "world": "world",
    "international": "world",
    "global": "world",
    "regional": "world",
    "domestic": "national",
    "nation": "national",
    "india": "national",
    "local": "national",
    "crime": "crime",
    "education": "education",
    "academia": "education",
    "food": "food",
    "tourism": "tourism",
    "gaming": "gaming",
    "opinion": "opinion",
    "general": "general",
    "other": "general",
}

CATEGORY_DISPLAY_NAMES = {
    "trending": "Trending",
    "business": "Business",
    "sports": "Sports",
    "technology": "Technology",
    "entertainment": "Entertainment",
    "health": "Health",
    "science": "Science",
    "politics": "Politics",
    "world": "World",
    "national": "National",
    "crime": "Crime",
    "education": "Education",
    "food": "Food",
    "tourism": "Tourism",
    "gaming": "Gaming",
    "opinion": "Opinion",
    "general": "General",
}

CATEGORY_SORT_ORDER = {
    "trending": 0,
    "national": 1,
    "world": 2,
    "business": 3,
    "technology": 4,
    "sports": 5,
    "entertainment": 6,
    "health": 7,
    "science": 8,
    "politics": 9,
    "education": 10,
    "crime": 11,
    "food": 12,
    "tourism": 13,
    "gaming": 14,
    "opinion": 15,
    "general": 99,
}

# ---------------------------------------------------------------------------
# Source name normalization
# ---------------------------------------------------------------------------

SOURCE_NORMALIZE = {
    "toi": "times_of_india",
    "times of india": "times_of_india",
    "the times of india": "times_of_india",
    "et": "economic_times",
    "economic times": "economic_times",
    "the economic times": "economic_times",
    "ndtv": "ndtv",
    "ndtv news": "ndtv",
    "ndtv.com": "ndtv",
    "bbc": "bbc",
    "bbc news": "bbc",
    "bbc world": "bbc",
    "bbc.com": "bbc",
    "cnn": "cnn",
    "cnn news": "cnn",
    "cnn.com": "cnn",
    "reuters": "reuters",
    "reuters.com": "reuters",
    "the hindu": "the_hindu",
    "hindustan times": "hindustan_times",
    "india today": "india_today",
}


def normalize_category(raw: str) -> str:
    slug = raw.strip().lower().replace("-", " ").replace("_", " ")
    return CATEGORY_MAP.get(slug, slug if slug else "general")


def normalize_source(raw: str) -> str:
    key = raw.strip().lower()
    return SOURCE_NORMALIZE.get(key, key.replace(" ", "_").replace(".", "_"))


def make_article_id(title: str, source: str) -> str:
    normalized = (title.strip().lower() + source.strip().lower()).encode("utf-8")
    return hashlib.sha256(normalized).hexdigest()[:32]


# ---------------------------------------------------------------------------
# Date parsing
# ---------------------------------------------------------------------------

def parse_date_currentsapi(date_str: str) -> datetime:
    """Parse '2026-02-24 10:30:00 +0000' format."""
    try:
        return datetime.strptime(date_str, "%Y-%m-%d %H:%M:%S %z")
    except (ValueError, TypeError):
        return datetime.now(timezone.utc)


def parse_date_newsdata(date_str: str) -> datetime:
    """Parse '2026-02-24 08:00:00' format (assumes UTC)."""
    try:
        return datetime.strptime(date_str, "%Y-%m-%d %H:%M:%S").replace(tzinfo=timezone.utc)
    except (ValueError, TypeError):
        return datetime.now(timezone.utc)


def parse_date_contextual(date_str: str) -> datetime:
    """Parse ISO 8601 '2026-02-24T06:15:00.0000000Z' format."""
    try:
        clean = date_str.rstrip("Z").split(".")[0]
        return datetime.strptime(clean, "%Y-%m-%dT%H:%M:%S").replace(tzinfo=timezone.utc)
    except (ValueError, TypeError):
        return datetime.now(timezone.utc)


# ---------------------------------------------------------------------------
# API fetchers
# ---------------------------------------------------------------------------

def fetch_currentsapi_latest() -> list[dict]:
    """Tier 1: Fetch latest headlines from currentsapi."""
    if not CURRENTSAPI_KEY:
        print("[SKIP] currentsapi — no API key")
        return []

    url = "https://api.currentsapi.services/v1/latest-news"
    params = {"apiKey": CURRENTSAPI_KEY, "language": "en"}

    try:
        resp = requests.get(url, params=params, timeout=REQUEST_TIMEOUT)
        resp.raise_for_status()
        data = resp.json()
    except Exception as e:
        print(f"[ERROR] currentsapi latest: {e}")
        return []

    articles = []
    for item in data.get("news", []):
        title = (item.get("title") or "").strip()
        if not title:
            continue

        raw_cats = item.get("category", [])
        raw_cat = raw_cats[0] if raw_cats else "general"
        source_raw = item.get("author") or ""
        if not source_raw:
            article_url = item.get("url") or ""
            try:
                from urllib.parse import urlparse
                source_raw = urlparse(article_url).netloc.replace("www.", "")
            except Exception:
                source_raw = "unknown"

        source_norm = normalize_source(source_raw)
        article_id = make_article_id(title, source_norm)
        published = parse_date_currentsapi(item.get("published", ""))

        articles.append({
            "id": article_id,
            "title": title,
            "description": (item.get("description") or "")[:500],
            "imageUrl": item.get("image") or None,
            "articleUrl": item.get("url") or "",
            "publishedAt": published,
            "sourceName": source_norm,
            "category": normalize_category(raw_cat),
        })

    print(f"[OK] currentsapi latest: {len(articles)} articles")
    return articles


def fetch_newsdata_category(category: str) -> list[dict]:
    """Tier 2: Fetch from newsdata.io for a specific category."""
    if not NEWSDATA_KEY:
        print(f"[SKIP] newsdata {category} — no API key")
        return []

    url = "https://newsdata.io/api/1/latest"
    params = {"apikey": NEWSDATA_KEY, "language": "en", "category": category}

    try:
        resp = requests.get(url, params=params, timeout=REQUEST_TIMEOUT)
        resp.raise_for_status()
        data = resp.json()
    except Exception as e:
        print(f"[ERROR] newsdata {category}: {e}")
        return []

    if data.get("status") != "success":
        print(f"[ERROR] newsdata {category}: status={data.get('status')}")
        return []

    articles = []
    for item in data.get("results", []):
        title = (item.get("title") or "").strip()
        if not title:
            continue

        source_raw = item.get("source_name") or item.get("source_id") or "unknown"
        source_norm = normalize_source(source_raw)
        article_id = make_article_id(title, source_norm)

        raw_cats = item.get("category", [])
        raw_cat = raw_cats[0] if raw_cats else category
        published = parse_date_newsdata(item.get("pubDate", ""))

        articles.append({
            "id": article_id,
            "title": title,
            "description": (item.get("description") or "")[:500],
            "imageUrl": item.get("image_url") or None,
            "articleUrl": item.get("link") or "",
            "publishedAt": published,
            "sourceName": source_norm,
            "category": normalize_category(raw_cat),
        })

    print(f"[OK] newsdata {category}: {len(articles)} articles")
    return articles


def fetch_contextualweb_query(query: str, target_category: str) -> list[dict]:
    """Tier 2: Fetch from contextualweb via RapidAPI."""
    if not RAPIDAPI_KEY:
        print(f"[SKIP] contextualweb '{query}' — no API key")
        return []

    url = "https://contextualwebsearch-websearch-v1.p.rapidapi.com/api/search/NewsSearchAPI"
    headers = {
        "X-RapidAPI-Host": "contextualwebsearch-websearch-v1.p.rapidapi.com",
        "X-RapidAPI-Key": RAPIDAPI_KEY,
    }
    params = {
        "q": query,
        "pageNumber": 1,
        "pageSize": 50,
        "autoCorrect": "true",
        "safeSearch": "true",
    }

    try:
        resp = requests.get(url, headers=headers, params=params, timeout=REQUEST_TIMEOUT)
        resp.raise_for_status()
        data = resp.json()
    except Exception as e:
        print(f"[ERROR] contextualweb '{query}': {e}")
        return []

    articles = []
    for item in data.get("value", []):
        title = (item.get("title") or "").strip()
        if not title:
            continue

        provider = item.get("provider", {})
        source_raw = provider.get("name") or "unknown"
        source_norm = normalize_source(source_raw)
        article_id = make_article_id(title, source_norm)
        published = parse_date_contextual(item.get("datePublished", ""))

        image_data = item.get("image", {})
        image_url = image_data.get("url") if isinstance(image_data, dict) else None

        articles.append({
            "id": article_id,
            "title": title,
            "description": (item.get("description") or "")[:500],
            "imageUrl": image_url,
            "articleUrl": item.get("url") or "",
            "publishedAt": published,
            "sourceName": source_norm,
            "category": normalize_category(target_category),
        })

    print(f"[OK] contextualweb '{query}': {len(articles)} articles")
    return articles


# ---------------------------------------------------------------------------
# Cache management
# ---------------------------------------------------------------------------

def load_cache() -> set[str]:
    if CACHE_FILE.exists():
        try:
            data = json.loads(CACHE_FILE.read_text())
            return set(data.get("ids", []))
        except Exception:
            pass
    return set()


def save_cache(ids: set[str]):
    trimmed = sorted(ids)[-MAX_CACHE_SIZE:]
    CACHE_FILE.write_text(json.dumps({"ids": trimmed}))


# ---------------------------------------------------------------------------
# Firebase upload
# ---------------------------------------------------------------------------

def upload_articles(db: firestore.Client, articles: list[dict], cached_ids: set[str]) -> int:
    """Upload new articles to Firestore. Returns count of newly written."""
    new_articles = [a for a in articles if a["id"] not in cached_ids]
    if not new_articles:
        print("[INFO] No new articles to upload")
        return 0

    batch = db.batch()
    count = 0
    for article in new_articles:
        doc_ref = db.collection(FIRESTORE_ARTICLES).document(article["id"])
        batch.set(doc_ref, {
            "id": article["id"],
            "title": article["title"],
            "description": article["description"],
            "imageUrl": article["imageUrl"],
            "articleUrl": article["articleUrl"],
            "publishedAt": article["publishedAt"],
            "sourceName": article["sourceName"],
            "category": article["category"],
        })
        count += 1

        if count % 450 == 0:
            batch.commit()
            batch = db.batch()
            print(f"  Committed batch of 450...")

    if count % 450 != 0:
        batch.commit()

    print(f"[OK] Uploaded {count} new articles to Firebase")
    return count


def update_categories(db: firestore.Client, articles: list[dict]):
    """Extract unique categories from articles and upsert to categories collection."""
    seen = {}
    for a in articles:
        cat = a["category"]
        if cat not in seen:
            seen[cat] = True

    batch = db.batch()
    for slug in seen:
        display = CATEGORY_DISPLAY_NAMES.get(slug, slug.replace("_", " ").title())
        sort = CATEGORY_SORT_ORDER.get(slug, 50)
        doc_ref = db.collection(FIRESTORE_CATEGORIES).document(slug)
        batch.set(doc_ref, {
            "id": slug,
            "name": display,
            "slug": slug,
            "sortOrder": sort,
        }, merge=True)

    batch.commit()
    print(f"[OK] Updated {len(seen)} categories")


def cleanup_old_articles(db: firestore.Client):
    """Delete articles older than 7 days."""
    cutoff = datetime.now(timezone.utc) - timedelta(days=7)

    try:
        old_docs = (
            db.collection(FIRESTORE_ARTICLES)
            .where("publishedAt", "<", cutoff)
            .limit(400)
            .stream()
        )

        batch = db.batch()
        count = 0
        for doc in old_docs:
            batch.delete(doc.reference)
            count += 1
            if count % 450 == 0:
                batch.commit()
                batch = db.batch()

        if count % 450 != 0:
            batch.commit()

        if count > 0:
            print(f"[OK] Cleaned up {count} old articles (>7 days)")
        else:
            print("[INFO] No old articles to clean up")
    except Exception as e:
        print(f"[WARN] Cleanup failed: {e}")


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

def determine_tier() -> tuple[bool, bool]:
    """Determine which tiers to run based on current UTC hour/minute."""
    now = datetime.now(timezone.utc)
    minute = now.minute
    hour = now.hour

    tier1 = True
    tier2 = (hour % 4 == 0) and (minute < 30)

    return tier1, tier2


def main():
    print("=" * 60)
    print(f"Headlinr News Fetcher — {datetime.now(timezone.utc).isoformat()}")
    print("=" * 60)

    tier1, tier2 = determine_tier()
    print(f"Tier 1 (freshness): {'YES' if tier1 else 'NO'}")
    print(f"Tier 2 (volume):    {'YES' if tier2 else 'NO'}")
    print()

    db = firestore.Client()
    cached_ids = load_cache()
    print(f"Loaded {len(cached_ids)} cached article IDs")

    all_articles = []

    # --- TIER 1: Freshness (every run) ---
    if tier1:
        print("\n--- TIER 1: currentsapi (latest headlines) ---")
        articles = fetch_currentsapi_latest()
        all_articles.extend(articles)

    # --- TIER 2: Volume + Cleanup (every 4 hours) ---
    if tier2:
        print("\n--- TIER 2: newsdata.io (category fills) ---")
        for cat in ["sports", "technology", "business"]:
            articles = fetch_newsdata_category(cat)
            all_articles.extend(articles)
            time.sleep(1)

        print("\n--- TIER 2: contextualweb (category fills) ---")
        cw_queries = [
            ("health", "health"),
            ("science", "science"),
            ("entertainment", "entertainment"),
        ]
        for query, target_cat in cw_queries:
            articles = fetch_contextualweb_query(query, target_cat)
            all_articles.extend(articles)
            time.sleep(1)

    if not all_articles:
        print("\n[DONE] No articles fetched. Exiting.")
        return

    # --- Deduplicate within this batch ---
    seen_ids = set()
    unique_articles = []
    for a in all_articles:
        if a["id"] not in seen_ids:
            seen_ids.add(a["id"])
            unique_articles.append(a)

    print(f"\nTotal unique articles this run: {len(unique_articles)}")

    # --- Upload ---
    written = upload_articles(db, unique_articles, cached_ids)

    # --- Update categories ---
    if unique_articles:
        update_categories(db, unique_articles)

    # --- Cleanup (tier 2 only) ---
    if tier2:
        print("\n--- Cleanup: removing articles older than 7 days ---")
        cleanup_old_articles(db)

    # --- Save cache ---
    new_ids = {a["id"] for a in unique_articles}
    cached_ids.update(new_ids)
    save_cache(cached_ids)
    print(f"Saved {len(cached_ids)} article IDs to cache")

    print(f"\n{'=' * 60}")
    print(f"DONE — Written: {written} | Total cached: {len(cached_ids)}")
    print(f"{'=' * 60}")


if __name__ == "__main__":
    main()
