## Jira
HLNAPP-22

## Summary
Implements the GitHub Actions & Fetch Pipeline story: 19 RSS feeds, quality gate, trending algorithm, bundle size 50, User-Agent for RSS.

## Changes

### Quality Gate
- **Description:** min 250 chars, max 500 chars (truncated)
- **Image:** rules unchanged (valid URL, ≥600px, no placeholders/logos)

### RSS Feeds (19)
- Added Aaj Tak (nat)
- Removed Live Hindustan (4 feeds), Business Standard business.xml
- Entertainment: 4 Bollywood feeds (ABP bollywood, TV9 ent, India TV ent, Bollywood Hungama)
- Added TV9 Hindi technology
- pol/cri feeds map to nat; stk to bus

### User-Agent
- `RSS_USER_AGENT` for feedparser (Bollywood Hungama compatibility)

### Category Mapping
- CATEGORY_SHORT: pol/cri/int/def → nat; stk → bus; sci → tec
- URL_CATEGORY_SIGNALS: mapped to merged codes (nat/bus/tec) — fixes bug where URL override produced old codes

### Title Normalization
- `HINDI_STOPWORDS`, `normalize_title()`
- `make_article_id` uses normalized title for dedup

### Trending
- `time_decay_score`, `best_article`, `SOURCE_QUALITY`
- `compute_trending`: 24h filter, cluster by normalized title, freq≥2 sources
- Add `tr`:1 to Firebase bundle for trending articles

### Bundle
- Size 30 → 50 articles per document

## Files
- `scripts/fetch_news.py`
- `.cursor/rules/github-actions-strategy.mdc`
- `jira-tickets.md`
