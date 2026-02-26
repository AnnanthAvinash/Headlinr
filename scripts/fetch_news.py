"""
Headlinr — News Fetcher for GitHub Actions
Fetches from multiple providers + RSS feeds, normalizes, deduplicates, uploads to Firebase.
Measures RSS feed quality and stores metrics in Firestore.

Two-tier schedule:
  TIER 1 (every 30 min): currentsapi latest headlines + all RSS feeds
  TIER 2 (every 4 hours): newsdata.io + contextualweb category fills + cleanup
"""

import hashlib
import html
import json
import os
import re
import time
from concurrent.futures import ThreadPoolExecutor, as_completed
from datetime import datetime, timedelta, timezone
from io import BytesIO
from pathlib import Path
from urllib.parse import urlparse, urlunparse

import feedparser
import requests
from google.cloud import firestore
from PIL import Image

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
FIRESTORE_RSS_METRICS = "rss_metrics"

REQUEST_TIMEOUT = 15
IMAGE_MIN_WIDTH = 600
IMAGE_CHECK_SAMPLE = 5
DESC_MIN_LEN = 200
DESC_MAX_LEN = 450
QUALITY_THRESHOLD = 75

# ---------------------------------------------------------------------------
# RSS Feed Configuration — 16 verified feeds
# ---------------------------------------------------------------------------

RSS_FEEDS = [
    # National — India
    {"url": "https://www.thehindu.com/news/national/feeder/default.rss", "category": "national", "source": "The Hindu"},
    {"url": "https://indianexpress.com/section/india/feed/", "category": "national", "source": "Indian Express"},
    {"url": "https://www.news18.com/rss/india.xml", "category": "national", "source": "News18"},
    # Entertainment / Movies — India
    {"url": "https://www.bollywoodhungama.com/rss/news.xml", "category": "entertainment", "source": "Bollywood Hungama"},
    {"url": "https://www.news18.com/rss/movies.xml", "category": "entertainment", "source": "News18"},
    {"url": "https://www.koimoi.com/feed/", "category": "entertainment", "source": "Koimoi"},
    # Technology — India + Global
    {"url": "https://indianexpress.com/section/technology/feed/", "category": "technology", "source": "Indian Express"},
    {"url": "https://yourstory.com/feed", "category": "technology", "source": "YourStory"},
    {"url": "https://www.theverge.com/rss/index.xml", "category": "technology", "source": "The Verge"},
    # Sports — India
    {"url": "https://indianexpress.com/section/sports/feed/", "category": "sports", "source": "Indian Express"},
    {"url": "https://www.thehindu.com/sport/feeder/default.rss", "category": "sports", "source": "The Hindu"},
    # Business — India
    {"url": "https://indianexpress.com/section/business/feed/", "category": "business", "source": "Indian Express"},
    {"url": "https://www.thehindu.com/business/feeder/default.rss", "category": "business", "source": "The Hindu"},
    # World — Indian lens
    {"url": "https://indianexpress.com/section/world/feed/", "category": "world", "source": "Indian Express"},
    {"url": "https://www.thehindu.com/news/international/feeder/default.rss", "category": "world", "source": "The Hindu"},
    # Science — India
    {"url": "https://www.thehindu.com/sci-tech/science/feeder/default.rss", "category": "science", "source": "The Hindu"},
]

AI_KEYWORDS = re.compile(
    r"\b(AI|Artificial Intelligence|Machine Learning|OpenAI|GPT|LLM|ChatGPT|"
    r"Gemini|Claude|DeepSeek|Neural Network|Deep Learning|Copilot)\b",
    re.IGNORECASE,
)

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
    "national": "National",
    "politics": "Politics",
    "business": "Business",
    "technology": "Technology",
    "ai": "AI",
    "sports": "Sports",
    "entertainment": "Entertainment",
    "health": "Health",
    "science": "Science",
    "world": "World",
    "education": "Education",
    "crime": "Crime",
    "food": "Food",
    "tourism": "Tourism",
    "gaming": "Gaming",
    "opinion": "Opinion",
    "general": "General",
}

CATEGORY_SORT_ORDER = {
    "trending": 0,
    "national": 1,
    "politics": 2,
    "business": 3,
    "technology": 4,
    "ai": 5,
    "sports": 6,
    "entertainment": 7,
    "health": 8,
    "science": 9,
    "world": 10,
    "gaming": 11,
    "education": 12,
    "crime": 13,
    "food": 14,
    "tourism": 15,
    "opinion": 16,
    "general": 99,
}

# ---------------------------------------------------------------------------
# Source name normalization
# ---------------------------------------------------------------------------

SOURCE_NORMALIZE = {
    # Indian sources
    "toi": "Times of India",
    "times of india": "Times of India",
    "the times of india": "Times of India",
    "timesofindia.indiatimes.com": "Times of India",
    "timesofindia.com": "Times of India",
    "et": "Economic Times",
    "economic times": "Economic Times",
    "the economic times": "Economic Times",
    "economictimes.indiatimes.com": "Economic Times",
    "economictimes.com": "Economic Times",
    "ndtv": "NDTV",
    "ndtv news": "NDTV",
    "ndtv.com": "NDTV",
    "the hindu": "The Hindu",
    "thehindu.com": "The Hindu",
    "hindustan times": "Hindustan Times",
    "hindustantimes.com": "Hindustan Times",
    "india today": "India Today",
    "indiatoday.in": "India Today",
    "livemint.com": "Mint",
    "mint": "Mint",
    "firstpost.com": "Firstpost",
    "moneycontrol.com": "Moneycontrol",
    "news18.com": "News18",
    "theprint.in": "The Print",
    "scroll.in": "Scroll",
    "thewire.in": "The Wire",
    "indianexpress.com": "Indian Express",
    "dnaindia.com": "DNA India",
    "zeenews.india.com": "Zee News",
    "aajtak.in": "Aaj Tak",
    # International sources
    "bbc": "BBC",
    "bbc news": "BBC",
    "bbc world": "BBC",
    "bbc.com": "BBC",
    "bbc.co.uk": "BBC",
    "cnn": "CNN",
    "cnn news": "CNN",
    "cnn.com": "CNN",
    "reuters": "Reuters",
    "reuters.com": "Reuters",
    "theguardian.com": "The Guardian",
    "the guardian": "The Guardian",
    "nytimes.com": "NY Times",
    "washingtonpost.com": "Washington Post",
    "aljazeera.com": "Al Jazeera",
    "apnews.com": "AP News",
    "foxnews.com": "Fox News",
    "cnbc.com": "CNBC",
    "techcrunch.com": "TechCrunch",
    "theverge.com": "The Verge",
    "wired.com": "Wired",
    "arstechnica.com": "Ars Technica",
    "engadget.com": "Engadget",
    "bloomberg.com": "Bloomberg",
    "forbes.com": "Forbes",
    "businessinsider.com": "Business Insider",
    "espn.com": "ESPN",
    "skysports.com": "Sky Sports",
    "cricbuzz.com": "Cricbuzz",
    "espncricinfo.com": "ESPNcricinfo",
    # RSS feed sources (www. variants)
    "www.news18.com": "News18",
    "bollywoodhungama.com": "Bollywood Hungama",
    "www.bollywoodhungama.com": "Bollywood Hungama",
    "koimoi.com": "Koimoi",
    "www.koimoi.com": "Koimoi",
    "yourstory.com": "YourStory",
    "www.theverge.com": "The Verge",
}


def normalize_category(raw: str) -> str:
    slug = raw.strip().lower().replace("-", " ").replace("_", " ")
    return CATEGORY_MAP.get(slug, slug if slug else "general")


def normalize_source(raw: str) -> str:
    key = raw.strip().lower()
    if key in SOURCE_NORMALIZE:
        return SOURCE_NORMALIZE[key]
    # Fallback: clean up domain-style names → readable display name
    # e.g., "news.sky.com" → "News Sky", "abcnews.go.com" → "Abcnews Go"
    clean = key.replace("www.", "").replace(".com", "").replace(".co.uk", "")
    clean = clean.replace(".in", "").replace(".org", "").replace(".net", "")
    clean = clean.replace(".", " ").replace("_", " ").replace("-", " ")
    return clean.strip().title() if clean.strip() else "Unknown"


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
    params = {"apikey": NEWSDATA_KEY, "language": "en", "country": "in", "category": category}

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
# URL normalization (Layer 2 dedup)
# ---------------------------------------------------------------------------

def normalize_url(raw_url: str) -> str:
    """Strip query params, fragment, www., trailing slash for dedup."""
    try:
        parsed = urlparse(raw_url)
        host = parsed.netloc.lower().replace("www.", "")
        path = parsed.path.rstrip("/")
        return urlunparse(("", host, path, "", "", ""))
    except Exception:
        return raw_url.strip().lower()


def make_url_hash(url: str) -> str:
    return hashlib.sha256(normalize_url(url).encode("utf-8")).hexdigest()[:32]


# ---------------------------------------------------------------------------
# Image quality checks
# ---------------------------------------------------------------------------

PLACEHOLDER_PATTERNS = re.compile(
    r"(placeholder|lazyload|fallback|spacer|blank|pixel|1x1|data:image)",
    re.IGNORECASE,
)


def check_image_quality(url: str) -> tuple[bool, bool, int]:
    """
    Check a single image URL.
    Returns (is_reachable, meets_quality, width).
    """
    if not url or not url.startswith("http"):
        return False, False, 0

    if PLACEHOLDER_PATTERNS.search(url):
        return False, False, 0

    if url.lower().endswith(".gif"):
        return False, False, 0

    try:
        resp = requests.get(
            url,
            headers={"User-Agent": "Mozilla/5.0 (compatible; Headlinr/1.0)"},
            timeout=8,
            stream=True,
        )
        if resp.status_code != 200:
            return False, False, 0

        img = Image.open(BytesIO(resp.content))
        w, h = img.size
        return True, w >= IMAGE_MIN_WIDTH, w
    except Exception:
        return False, False, 0


# ---------------------------------------------------------------------------
# Description quality checks
# ---------------------------------------------------------------------------

HTML_TAG_RE = re.compile(r"<[^>]+>")
WHITESPACE_RE = re.compile(r"\s+")


def clean_html(text: str) -> str:
    """Strip HTML tags, decode entities, collapse whitespace."""
    text = HTML_TAG_RE.sub(" ", text)
    text = html.unescape(text)
    return WHITESPACE_RE.sub(" ", text).strip()


def is_good_description(text: str) -> bool:
    cleaned = clean_html(text)
    length = len(cleaned)
    if length < DESC_MIN_LEN or length > DESC_MAX_LEN:
        return False
    html_ratio = len(HTML_TAG_RE.findall(text)) / max(len(text), 1)
    return html_ratio < 0.3


# ---------------------------------------------------------------------------
# RSS feed image extraction
# ---------------------------------------------------------------------------

def extract_image_from_entry(entry: dict) -> str | None:
    """Extract the best image URL from a feedparser entry."""
    for m in entry.get("media_content", []):
        url = m.get("url", "")
        if url and url.startswith("http") and not url.lower().endswith(".gif"):
            return url

    for t in entry.get("media_thumbnail", []):
        url = t.get("url", "")
        if url and url.startswith("http"):
            return url

    for enc in entry.get("enclosures", []):
        url = enc.get("href", "") or enc.get("url", "")
        if url and ("image" in enc.get("type", "") or
                     any(url.lower().endswith(ext) for ext in (".jpg", ".jpeg", ".png", ".webp"))):
            return url

    for field_name in ("content", "summary"):
        text = ""
        if field_name == "content":
            for c in entry.get("content", []):
                text += c.get("value", "")
        else:
            text = entry.get(field_name, "")
        img_urls = re.findall(r'<img[^>]+src=["\']([^"\']+)["\']', text)
        for img_url in img_urls:
            if (img_url.startswith("http") and
                    not PLACEHOLDER_PATTERNS.search(img_url) and
                    not img_url.lower().endswith(".gif")):
                return img_url

    return None


# ---------------------------------------------------------------------------
# RSS date parsing
# ---------------------------------------------------------------------------

def parse_rss_date(entry: dict) -> datetime:
    """Extract published date from a feedparser entry."""
    for field in ("published_parsed", "updated_parsed"):
        parsed = entry.get(field)
        if parsed:
            try:
                from calendar import timegm
                return datetime.fromtimestamp(timegm(parsed), tz=timezone.utc)
            except Exception:
                continue

    for field in ("published", "updated"):
        raw = entry.get(field, "")
        if raw:
            try:
                return datetime.fromisoformat(raw.replace("Z", "+00:00"))
            except Exception:
                pass

    return datetime.now(timezone.utc)


# ---------------------------------------------------------------------------
# RSS feed fetcher
# ---------------------------------------------------------------------------

def fetch_single_rss_feed(feed_config: dict, seen_urls: set[str]) -> tuple[list[dict], dict]:
    """
    Fetch and parse a single RSS feed.
    Returns (articles, metrics).
    """
    url = feed_config["url"]
    category = feed_config["category"]
    source_hint = feed_config["source"]

    metrics = {
        "category": category,
        "title": source_hint,
        "rssUrl": url,
        "totalHit": 0,
        "successHit": 0,
        "successRatio": 0.0,
        "qualityPercent": 0.0,
    }

    try:
        feed = feedparser.parse(url)
    except Exception as e:
        print(f"[ERROR] RSS {source_hint} ({category}): {e}")
        return [], metrics

    entries = feed.entries
    if not entries:
        print(f"[WARN] RSS {source_hint} ({category}): 0 entries")
        return [], metrics

    total = len(entries)
    metrics["totalHit"] = total

    # Counters for quality scoring
    valid_images = 0
    good_descriptions = 0
    working_images = 0
    unique_count = 0
    images_checked = 0

    # Randomly sample entries for image dimension checks
    sample_indices = set()
    step = max(1, total // IMAGE_CHECK_SAMPLE)
    for i in range(0, total, step):
        sample_indices.add(i)
        if len(sample_indices) >= IMAGE_CHECK_SAMPLE:
            break

    articles = []
    for idx, entry in enumerate(entries):
        title = (entry.get("title") or "").strip()
        if not title:
            continue

        article_url = entry.get("link", "") or entry.get("id", "")
        if not article_url:
            continue

        # Layer 2: URL dedup
        url_hash = make_url_hash(article_url)
        if url_hash in seen_urls:
            continue
        seen_urls.add(url_hash)
        unique_count += 1

        # Extract and validate image
        image_url = extract_image_from_entry(entry)
        if not image_url:
            continue

        # Sample-based image quality check
        if idx in sample_indices:
            is_reachable, meets_quality, width = check_image_quality(image_url)
            images_checked += 1
            if is_reachable:
                working_images += 1
            if meets_quality:
                valid_images += 1
        else:
            # Assume quality based on sampled results (skip HTTP calls for speed)
            pass

        # Extract and validate description
        raw_desc = entry.get("summary", "") or entry.get("description", "")
        for c in entry.get("content", []):
            val = c.get("value", "")
            if len(val) > len(raw_desc):
                raw_desc = val
        description = clean_html(raw_desc)[:500]

        if is_good_description(raw_desc):
            good_descriptions += 1

        # Fallback: use title as description if feed provides none
        if not description:
            description = title

        source_norm = normalize_source(source_hint)
        article_id = make_article_id(title, source_norm)
        published = parse_rss_date(entry)

        articles.append({
            "id": article_id,
            "title": title,
            "description": description,
            "imageUrl": image_url,
            "articleUrl": article_url,
            "publishedAt": published,
            "sourceName": source_norm,
            "category": normalize_category(category),
        })

    # Compute quality metrics
    sampled = max(images_checked, 1)
    valid_image_pct = (valid_images / sampled) * 100
    working_image_pct = (working_images / sampled) * 100
    good_desc_pct = (good_descriptions / max(total, 1)) * 100
    non_dup_pct = (unique_count / max(total, 1)) * 100

    quality = (
        (valid_image_pct * 0.4) +
        (good_desc_pct * 0.3) +
        (non_dup_pct * 0.1) +
        (working_image_pct * 0.1) +
        10  # category accuracy baseline
    )

    metrics["successHit"] = len(articles)
    metrics["successRatio"] = round((len(articles) / max(total, 1)) * 100, 1)
    metrics["qualityPercent"] = round(quality, 1)

    print(f"[OK] RSS {source_hint} ({category}): {len(articles)}/{total} articles | quality={metrics['qualityPercent']}%")
    return articles, metrics


def fetch_all_rss_feeds() -> tuple[list[dict], list[dict]]:
    """
    Fetch all RSS feeds in parallel.
    Returns (all_articles, all_metrics).
    """
    all_articles = []
    all_metrics = []
    seen_urls: set[str] = set()

    with ThreadPoolExecutor(max_workers=4) as pool:
        futures = {
            pool.submit(fetch_single_rss_feed, fc, seen_urls): fc
            for fc in RSS_FEEDS
        }
        for future in as_completed(futures):
            fc = futures[future]
            try:
                articles, metrics = future.result()
                all_articles.extend(articles)
                all_metrics.append(metrics)
            except Exception as e:
                print(f"[ERROR] RSS {fc['source']}: {e}")
                all_metrics.append({
                    "category": fc["category"],
                    "title": fc["source"],
                    "rssUrl": fc["url"],
                    "totalHit": 0,
                    "successHit": 0,
                    "successRatio": 0.0,
                    "qualityPercent": 0.0,
                })

    return all_articles, all_metrics


# ---------------------------------------------------------------------------
# AI keyword filter — clone matching tech articles into AI category
# ---------------------------------------------------------------------------

def extract_ai_articles(articles: list[dict]) -> list[dict]:
    """Find tech articles matching AI keywords, clone them into AI category."""
    ai_articles = []
    for a in articles:
        if a["category"] != "technology":
            continue
        text = f"{a['title']} {a['description']}"
        if AI_KEYWORDS.search(text):
            clone = dict(a)
            clone["category"] = "ai"
            clone["id"] = make_article_id(a["title"], a["sourceName"] + "_ai")
            ai_articles.append(clone)
    return ai_articles


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
# RSS metrics storage
# ---------------------------------------------------------------------------

def upload_rss_metrics(db: firestore.Client, metrics_list: list[dict]):
    """Store quality metrics for each RSS feed in Firestore."""
    if not metrics_list:
        return

    batch = db.batch()
    count = 0

    for m in metrics_list:
        doc_id = hashlib.sha256(m["rssUrl"].encode("utf-8")).hexdigest()[:16]
        doc_ref = db.collection(FIRESTORE_RSS_METRICS).document(doc_id)

        batch.set(doc_ref, {
            "category": m["category"],
            "title": m["title"],
            "rssUrl": m["rssUrl"],
            "totalHit": m["totalHit"],
            "successHit": m["successHit"],
            "successRatio": m["successRatio"],
            "qualityPercent": m["qualityPercent"],
            "evaluatedAt": firestore.SERVER_TIMESTAMP,
        }, merge=True)
        count += 1

    batch.commit()
    print(f"[OK] Stored metrics for {count} RSS feeds")


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
    rss_metrics = []

    # --- TIER 1: Freshness (every run) ---
    if tier1:
        print("\n--- TIER 1: currentsapi (latest headlines) ---")
        articles = fetch_currentsapi_latest()
        all_articles.extend(articles)

        print("\n--- TIER 1: RSS feeds (16 feeds, parallel) ---")
        rss_articles, rss_metrics = fetch_all_rss_feeds()
        all_articles.extend(rss_articles)
        print(f"RSS total: {len(rss_articles)} articles from {len(RSS_FEEDS)} feeds")

        # AI keyword filter: clone tech articles matching AI keywords
        ai_articles = extract_ai_articles(rss_articles)
        if ai_articles:
            all_articles.extend(ai_articles)
            print(f"AI filter: {len(ai_articles)} articles cloned to AI category")

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

    # --- Deduplicate within this batch (Layer 1: ID hash) ---
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

    # --- Upload RSS metrics ---
    if rss_metrics:
        print("\n--- RSS feed quality metrics ---")
        for m in sorted(rss_metrics, key=lambda x: x["qualityPercent"], reverse=True):
            status = "active" if m["qualityPercent"] >= QUALITY_THRESHOLD else "LOW"
            print(f"  {m['title']:20s} ({m['category']:15s}): "
                  f"{m['successHit']:>3}/{m['totalHit']:<3} articles | "
                  f"quality={m['qualityPercent']:5.1f}% | {status}")
        upload_rss_metrics(db, rss_metrics)

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
