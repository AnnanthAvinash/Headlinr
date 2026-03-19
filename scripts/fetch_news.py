"""
Headlinr — News Fetcher for GitHub Actions
Fetches from multiple providers + RSS feeds, normalizes, deduplicates,
bundles up to 50 articles per Firestore document, and uploads to Firebase.

Two-tier schedule:
  TIER 1 (every 30 min): currentsapi latest headlines + all RSS feeds
  TIER 2 (every 4 hours): newsdata.io + contextualweb category fills
"""

import csv
import hashlib
import html
import json
import os
import re
import time
from collections import Counter
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

FIRESTORE_ARTICLES = "article_bundles"

REQUEST_TIMEOUT = 15
IMAGE_MIN_WIDTH = 600
IMAGE_CHECK_SAMPLE = 5
DESC_MAX_LEN = 500

# ---------------------------------------------------------------------------
# RSS Feed Configuration — 19 verified Hindi feeds (plan 25.2)
# Category values are short codes; pol/cri map to nat, stk to bus
# ---------------------------------------------------------------------------

RSS_FEEDS = [
    # Global (nat) — merged from nat, pol, cri, int, def
    {"url": "https://www.aajtak.in/rssfeeds/?id=home", "category": "nat", "source": "Aaj Tak"},
    {"url": "https://www.abplive.com/news/india/feed", "category": "nat", "source": "ABP Live"},
    {"url": "https://www.tv9hindi.com/india/feed", "category": "nat", "source": "TV9 Hindi"},
    {"url": "https://hindi.business-standard.com/rss/politics.xml", "category": "nat", "source": "Business Standard Hindi"},
    {"url": "https://www.navjivanindia.com/stories.rss?section=politics", "category": "nat", "source": "Navjivan India"},
    {"url": "https://www.abplive.com/news/crime/feed", "category": "nat", "source": "ABP Live"},
    # Sports (spt)
    {"url": "https://www.tv9hindi.com/sports/feed", "category": "spt", "source": "TV9 Hindi"},
    {"url": "https://www.indiatv.in/rssnews/topstory-sports.xml", "category": "spt", "source": "India TV"},
    # Entertainment — 4 Bollywood-only (ent)
    {"url": "https://www.abplive.com/entertainment/bollywood/feed", "category": "ent", "source": "ABP Live"},
    {"url": "https://www.tv9hindi.com/entertainment/feed", "category": "ent", "source": "TV9 Hindi"},
    {"url": "https://www.indiatv.in/rssnews/topstory-entertainment.xml", "category": "ent", "source": "India TV"},
    {"url": "https://www.bollywoodhungama.com/rss/news.xml", "category": "ent", "source": "Bollywood Hungama"},
    # Business (bus) — stk merged to bus
    {"url": "https://www.abplive.com/business/feed", "category": "bus", "source": "ABP Live"},
    {"url": "https://www.tv9hindi.com/business/feed", "category": "bus", "source": "TV9 Hindi"},
    {"url": "https://hindi.business-standard.com/rss/markets/share-market.xml", "category": "bus", "source": "Business Standard Hindi"},
    # Technology (tec)
    {"url": "https://www.abplive.com/technology/feed", "category": "tec", "source": "ABP Live"},
    {"url": "https://www.tv9hindi.com/technology/feed", "category": "tec", "source": "TV9 Hindi"},
    # Health (hlt)
    {"url": "https://www.abplive.com/health/feed", "category": "hlt", "source": "ABP Live"},
    # Education (edu)
    {"url": "https://www.abplive.com/education/feed", "category": "edu", "source": "ABP Live"},
]

# User-Agent for RSS requests (Bollywood Hungama may block default)
RSS_USER_AGENT = "Mozilla/5.0 (compatible; HeadlinrBot/1.0; +https://github.com/AnnanthAvinash/Headlinr)"

# ---------------------------------------------------------------------------
# URL-based category signals
# Override feed-assigned category when the article URL clearly belongs
# to a different section (e.g. abplive.com/education/feed leaking ent articles)
# Checked in order — first match wins.
# ---------------------------------------------------------------------------
URL_CATEGORY_SIGNALS = [
    ("/entertainment/", "ent"),
    ("/bollywood/",     "ent"),
    ("/south-cinema/",  "ent"),
    ("/television/",    "ent"),
    ("/ott/",           "ent"),
    ("/sports/",        "spt"),
    ("/cricket/",       "spt"),
    ("/business/",      "bus"),
    ("/technology/",    "tec"),
    ("/tech/",          "tec"),
    ("/health/",        "hlt"),
    ("/education/",     "edu"),
    ("/crime/",         "cri"),
    ("/politics/",      "pol"),
    ("/science/",       "sci"),
    ("/world/",         "int"),
    ("/international/", "int"),
    ("/defence/",       "def"),
    ("/defense/",       "def"),
    ("/share-market/",  "stk"),
    ("/markets/",       "stk"),
    ("/india/",         "nat"),
    ("/national/",      "nat"),
]


def url_derived_category(article_url: str, feed_category: str) -> str:
    """Return feed_category unless the article URL signals a different category."""
    url_lower = article_url.lower()
    for path_fragment, cat in URL_CATEGORY_SIGNALS:
        if path_fragment in url_lower:
            if cat != feed_category:
                print(f"  [cat-fix] {feed_category!r} → {cat!r}  ({article_url})")
            return cat
    return feed_category


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

# pol/cri/int/def map to nat; stk to bus; sci to tec (plan 25.3)
CATEGORY_SHORT = {
    "national": "nat", "trending": "nat", "general": "nat",
    "politics": "nat", "crime": "nat", "world": "nat", "international": "nat", "defense": "nat",
    "business": "bus", "finance": "bus", "economy": "bus", "stock": "bus",
    "sports": "spt", "cricket": "spt",
    "entertainment": "ent", "bollywood": "ent",
    "technology": "tec", "tech": "tec", "ai": "tec", "gaming": "tec", "science": "tec", "space": "tec", "environment": "tec",
    "health": "hlt", "lifestyle": "hlt", "wellness": "hlt",
    "education": "edu",
}

# Hindi stopwords for title normalization (plan 26.3)
HINDI_STOPWORDS = frozenset({
    "का", "की", "के", "को", "में", "से", "पर", "तक", "द्वारा", "के लिए",
    "है", "हैं", "था", "थे", "था", "थी", "हो", "होता", "होती", "होते",
    "यह", "वह", "इस", "उस", "जो", "कि", "क्या", "कैसे", "कब", "कहाँ",
    "और", "या", "पर", "लेकिन", "तो", "भी", "ही", "सिर्फ", "बस",
    "न्यूज़", "खबर", "समाचार", "रिपोर्ट", "दावा", "कहा", "बोला",
    "मिली", "मिला", "हुआ", "हुई", "कर", "किया", "किए", "गया", "गई",
    "दिया", "दी", "लिया", "ली", "पड़ा", "पड़ी",
    "the", "a", "an", "and", "or", "in", "on", "at", "to", "for", "of",
    "is", "are", "was", "were", "news", "report", "says",
})

# Quality gate (plan 26.4)
MIN_TITLE_LEN = 100
MIN_DESC_LEN = 250
TOP_TRENDING_CATEGORIES = {"spt", "ent", "nat"}

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


def short_category(slug: str) -> str:
    """Convert a normalized category slug to its short code for Firebase storage."""
    return CATEGORY_SHORT.get(slug, "nat")


def normalize_title(title: str) -> str:
    """Normalize title for dedup and trending clustering (plan 26.2)."""
    if not title or not title.strip():
        return ""
    t = title.lower().strip()
    t = re.sub(r"[^\w\s\u0900-\u097F]", "", t)
    words = t.split()
    words = [w for w in words if w not in HINDI_STOPWORDS and len(w) > 1]
    return " ".join(words)


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
    """Use normalized title for consistent dedup across providers."""
    norm_title = normalize_title(title) or title.strip().lower()
    normalized = (norm_title + source.strip().lower()).encode("utf-8")
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
            "description": clean_html(item.get("description") or "")[:DESC_MAX_LEN],
            "imageUrl": item.get("image") or None,
            "articleUrl": item.get("url") or "",
            "publishedAt": published,
            "sourceName": source_norm,
            "category": short_category(normalize_category(raw_cat)),
            "_provider": "currentsapi",
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
            "description": clean_html(item.get("description") or "")[:DESC_MAX_LEN],
            "imageUrl": item.get("image_url") or None,
            "articleUrl": item.get("link") or "",
            "publishedAt": published,
            "sourceName": source_norm,
            "category": short_category(normalize_category(raw_cat)),
            "_provider": "newsdata",
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
            "description": clean_html(item.get("description") or "")[:DESC_MAX_LEN],
            "imageUrl": image_url,
            "articleUrl": item.get("url") or "",
            "publishedAt": published,
            "sourceName": source_norm,
            "category": short_category(normalize_category(target_category)),
            "_provider": "contextualweb",
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
    """Strip HTML tags, decode entities, collapse whitespace.
    Two-pass: strip tags → unescape entities → strip any tags that were entity-encoded."""
    text = HTML_TAG_RE.sub(" ", text)       # strip literal <tags>
    text = html.unescape(text)              # decode &lt; &gt; &amp; &lsquo; etc.
    text = HTML_TAG_RE.sub(" ", text)       # strip tags that were entity-encoded (&lt;p&gt;)
    return WHITESPACE_RE.sub(" ", text).strip()


def is_good_description(text: str) -> bool:
    cleaned = clean_html(text)
    length = len(cleaned)
    if length < 200 or length > DESC_MAX_LEN:
        return False
    html_ratio = len(HTML_TAG_RE.findall(text)) / max(len(text), 1)
    return html_ratio < 0.3


# ---------------------------------------------------------------------------
# RSS feed image extraction
# ---------------------------------------------------------------------------

JUNK_IMAGE_PATTERNS = re.compile(
    r"("
    # Thumbnails & small crops
    r"thumbnail|thumb[_\-]|/s[12]\d{2}/|/w[12]\d{2}/|-\d{2,3}x\d{2,3}\."
    r"|_small|_tiny|_mini"
    # Logos & brand images
    r"|logo|brand[_\-]|masthead|site[_\-]icon|header[_\-]logo|footer[_\-]logo"
    r"|default[_\-]image|default[_\-]og|og[_\-]default|no[_\-]image|noimage"
    r"|fallback[_\-]img|generic[_\-]image|stock[_\-]image"
    # Avatars & author photos
    r"|avatar|author[_\-]photo|author[_\-]img|profile[_\-]pic|headshot|byline"
    # Social & icons
    r"|favicon|icon[_\-]|social[_\-]share|share[_\-]icon|badge[_\-]"
    # Ads & tracking pixels
    r"|ad[_\-]banner|sponsor|watermark|tracking|pixel|beacon"
    r")",
    re.IGNORECASE,
)

IMAGE_EXTENSIONS = (".jpg", ".jpeg", ".png", ".webp")


def _is_valid_image_url(url: str) -> bool:
    """Reject GIFs, placeholders, thumbnails, logos, brand images, avatars, and tracking pixels."""
    if not url or not url.startswith("http"):
        return False
    if url.lower().endswith(".gif"):
        return False
    if PLACEHOLDER_PATTERNS.search(url):
        return False
    if JUNK_IMAGE_PATTERNS.search(url):
        return False
    return True


def extract_image_from_entry(entry: dict) -> str | None:
    """Extract article image. Uses media_content first; searches full entry only if empty."""

    # Fast path: media_content is the primary image source for most feeds
    for m in entry.get("media_content", []):
        url = m.get("url", "")
        if _is_valid_image_url(url):
            return url

    # Primary source empty — search remaining fields for a valid image
    for enc in entry.get("enclosures", []):
        url = enc.get("href", "") or enc.get("url", "")
        enc_type = enc.get("type", "")
        if _is_valid_image_url(url) and (
            enc_type.startswith("image/") or
            any(url.lower().endswith(ext) for ext in IMAGE_EXTENSIONS)
        ):
            return url

    for link in entry.get("links", []):
        url = link.get("href", "")
        if _is_valid_image_url(url) and link.get("type", "").startswith("image/"):
            return url

    for field_name in ("content", "summary"):
        text = ""
        if field_name == "content":
            for c in entry.get("content", []):
                text += c.get("value", "")
        else:
            text = entry.get(field_name, "")
        for img_url in re.findall(r'<img[^>]+src=["\']([^"\']+)["\']', text):
            if _is_valid_image_url(img_url):
                return img_url

    for t in entry.get("media_thumbnail", []):
        url = t.get("url", "")
        if _is_valid_image_url(url):
            return url

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
        feed = feedparser.parse(url, request_headers={"User-Agent": RSS_USER_AGENT})
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
        description = clean_html(raw_desc)[:DESC_MAX_LEN]

        if is_good_description(raw_desc):
            good_descriptions += 1

        # Fallback: use title as description if feed provides none
        if not description:
            description = title

        source_norm = normalize_source(source_hint)
        article_id = make_article_id(title, source_norm)
        published = parse_rss_date(entry)
        resolved_category = url_derived_category(article_url, category)

        articles.append({
            "id": article_id,
            "title": title,
            "description": description,
            "imageUrl": image_url,
            "articleUrl": article_url,
            "publishedAt": published,
            "sourceName": source_norm,
            "category": resolved_category,
            "_provider": "rss",
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
# Trending algorithm (plan 26)
# ---------------------------------------------------------------------------

SOURCE_QUALITY = {
    "Times of India": 1, "NDTV": 1, "Reuters": 1, "BBC": 1, "The Hindu": 1,
    "ABP Live": 1, "TV9 Hindi": 1, "India TV": 1, "Aaj Tak": 1,
}


def time_decay_score(article: dict) -> float:
    """Higher for more recent. 1.0 at now, decays over 24h (plan 26.5)."""
    pub = article.get("publishedAt")
    if not pub:
        return 0.5
    ts = pub.timestamp() if hasattr(pub, "timestamp") else pub / 1000
    age_hours = (datetime.now(timezone.utc).timestamp() - ts) / 3600
    return max(0.1, 2 ** (-age_hours / 6))


def best_article(articles: list[dict]) -> dict:
    """Pick best article from cluster: source quality, time decay, desc length (plan 26.6)."""
    return max(articles, key=lambda a: (
        SOURCE_QUALITY.get(a.get("sourceName", ""), 0),
        time_decay_score(a),
        len(a.get("description", "")),
    ))


def compute_trending(articles: list[dict]) -> list[dict]:
    """
    Mark trending articles with "tr": 1 (plan 26.7).
    24h filter, cluster by normalized title, freq >= 2 distinct sources.
    """
    cutoff = datetime.now(timezone.utc) - timedelta(hours=24)
    recent = [a for a in articles if (a.get("publishedAt") or datetime.min.replace(tzinfo=timezone.utc)) >= cutoff]
    recent = [a for a in recent if a.get("category") in TOP_TRENDING_CATEGORIES]

    clusters: dict[str, list[dict]] = {}
    for a in recent:
        key = normalize_title(a.get("title", ""))
        if not key:
            continue
        clusters.setdefault(key, []).append(a)

    trending_ids = set()
    for key, group in clusters.items():
        sources = {a.get("sourceName", "") for a in group}
        if len(sources) >= 2:
            best = best_article(group)
            trending_ids.add(best["id"])

    for a in articles:
        if a["id"] in trending_ids:
            a["tr"] = 1
    return articles


# ---------------------------------------------------------------------------
# Firebase upload
# ---------------------------------------------------------------------------

def upload_bundles(db: firestore.Client, articles: list[dict], cached_ids: set[str]) -> int:
    """
    Bundle fresh articles (up to 50 per document) and write to article_bundles.
    Each bundle document uses short field names to minimise Firestore read bytes:
      ts  — epoch-millis of the newest article in the bundle
      c   — most common short category code in the bundle
      a   — array of article maps with keys: i, t, d, img, u, p, s, c, tr (tr=trending)
    Returns the total number of fresh articles written.
    """
    new_articles = [a for a in articles if a["id"] not in cached_ids]
    if not new_articles:
        print("[INFO] No new articles to upload")
        return 0

    def _ts(a):
        p = a.get("publishedAt")
        if p is None:
            return 0
        return p.timestamp() if hasattr(p, "timestamp") else (p / 1000 if isinstance(p, (int, float)) else 0)
    new_articles.sort(key=_ts, reverse=True)
    bundle_size = 50
    written = 0
    batch = db.batch()
    batch_ops = 0

    for chunk_start in range(0, len(new_articles), bundle_size):
        chunk = new_articles[chunk_start:chunk_start + bundle_size]

        # Determine most common category in this chunk
        cat_counts: dict[str, int] = {}
        for a in chunk:
            cat_counts[a["category"]] = cat_counts.get(a["category"], 0) + 1
        dominant_cat = max(cat_counts, key=lambda k: cat_counts[k])

        # Newest article's timestamp (articles sorted descending by publishedAt)
        newest_ts = int(chunk[0]["publishedAt"].timestamp() * 1000) if hasattr(chunk[0]["publishedAt"], "timestamp") else int(chunk[0]["publishedAt"])

        article_array = []
        for a in chunk:
            pub = a["publishedAt"]
            pub_ms = int(pub.timestamp() * 1000) if hasattr(pub, "timestamp") else int(pub)
            item = {
                "i": a["id"],
                "t": a["title"],
                "d": a["description"],
                "img": a.get("imageUrl"),
                "u": a["articleUrl"],
                "p": pub_ms,
                "s": a["sourceName"],
                "c": a["category"],
            }
            if a.get("tr") == 1:
                item["tr"] = 1
            article_array.append(item)
            written += 1

        bundle_id = f"{newest_ts}_{dominant_cat}_{chunk[0]['id'][:8]}"
        doc_ref = db.collection(FIRESTORE_ARTICLES).document(bundle_id)
        batch.set(doc_ref, {
            "ts": newest_ts,
            "c": dominant_cat,
            "a": article_array,
        })
        batch_ops += 1

        if batch_ops >= 450:
            batch.commit()
            batch = db.batch()
            batch_ops = 0
            print("  Committed batch of 450 bundle ops...")

    if batch_ops > 0:
        batch.commit()

    bundles = (len(new_articles) + bundle_size - 1) // bundle_size
    print(f"[OK] Uploaded {written} new articles in {bundles} bundles to Firebase")
    return written


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

METRICS_CSV = Path(__file__).resolve().parent.parent / "logs" / "run_metrics.csv"


def append_run_metrics(
    tier_label: str,
    raw_total: int,
    within_run_dups: int,
    unique_this_run: int,
    cross_run_dups: int,
    fresh_unique: int,
    all_articles: list[dict],
):
    """Append one row of run metrics to the CSV log file."""
    provider_counts = Counter(a.get("_provider", "unknown") for a in all_articles)
    row = {
        "timestamp": datetime.now(timezone.utc).strftime("%Y-%m-%d %H:%M:%S"),
        "tier": tier_label,
        "raw_total": raw_total,
        "within_run_dups": within_run_dups,
        "unique_this_run": unique_this_run,
        "cross_run_dups": cross_run_dups,
        "fresh_unique": fresh_unique,
        "currentsapi": provider_counts.get("currentsapi", 0),
        "rss": provider_counts.get("rss", 0),
        "newsdata": provider_counts.get("newsdata", 0),
        "contextualweb": provider_counts.get("contextualweb", 0),
        "ai": provider_counts.get("ai", 0),
    }
    fieldnames = list(row.keys())
    write_header = not METRICS_CSV.exists() or METRICS_CSV.stat().st_size == 0
    METRICS_CSV.parent.mkdir(parents=True, exist_ok=True)
    with open(METRICS_CSV, "a", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=fieldnames)
        if write_header:
            writer.writeheader()
        writer.writerow(row)
    print(f"[OK] Appended run metrics to {METRICS_CSV}")


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

        print(f"\n--- TIER 1: RSS feeds ({len(RSS_FEEDS)} feeds, parallel) ---")
        rss_articles, rss_metrics = fetch_all_rss_feeds()
        all_articles.extend(rss_articles)
        print(f"RSS total: {len(rss_articles)} articles from {len(RSS_FEEDS)} feeds")

    # --- TIER 2: Volume (every 4 hours) ---
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

    raw_total = len(all_articles)
    within_run_dups = raw_total - len(unique_articles)
    print(f"\nTotal unique articles this run: {len(unique_articles)}")

    # --- Quality gate (plan 26.4) ---
    # MIN_TITLE_LEN=100, MIN_DESC_LEN=250; description truncated to max 500 chars
    verified = []
    gate_no_img = 0
    gate_short_title = 0
    gate_short_desc = 0
    for a in unique_articles:
        img = a.get("imageUrl") or ""
        if not _is_valid_image_url(img):
            gate_no_img += 1
            continue
        title_len = len((a.get("title") or "").strip())
        if title_len < MIN_TITLE_LEN:
            gate_short_title += 1
            continue
        desc_len = len((a.get("description") or "").strip())
        if desc_len < MIN_DESC_LEN:
            gate_short_desc += 1
            continue
        verified.append(a)
    print(f"Quality gate: {len(verified)} passed | "
          f"{gate_no_img} dropped (bad image) | "
          f"{gate_short_title} dropped (title < {MIN_TITLE_LEN}) | "
          f"{gate_short_desc} dropped (desc < {MIN_DESC_LEN})")

    # --- Compute trending (plan 26) ---
    verified = compute_trending(verified)

    # --- Upload bundles ---
    written = upload_bundles(db, verified, cached_ids)

    # --- Run metrics ---
    cross_run_dups = len(unique_articles) - written
    tier_label = "tier1+2" if tier2 else "tier1"
    append_run_metrics(
        tier_label=tier_label,
        raw_total=raw_total,
        within_run_dups=within_run_dups,
        unique_this_run=len(unique_articles),
        cross_run_dups=cross_run_dups,
        fresh_unique=written,
        all_articles=all_articles,
    )

    # --- RSS feed summary (local print only) ---
    if rss_metrics:
        print("\n--- RSS feed summary ---")
        for m in sorted(rss_metrics, key=lambda x: x["qualityPercent"], reverse=True):
            print(f"  {m['title']:20s} ({m['category']:5s}): "
                  f"{m['successHit']:>3}/{m['totalHit']:<3} articles | "
                  f"quality={m['qualityPercent']:5.1f}%")

    # --- Save cache (only verified articles — dropped ones can retry next run) ---
    new_ids = {a["id"] for a in verified}
    cached_ids.update(new_ids)
    save_cache(cached_ids)
    print(f"Saved {len(cached_ids)} article IDs to cache")

    print(f"\n{'=' * 60}")
    print(f"DONE — Written: {written} | Total cached: {len(cached_ids)}")
    print(f"{'=' * 60}")


if __name__ == "__main__":
    main()
