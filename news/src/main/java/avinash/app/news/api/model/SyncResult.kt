package avinash.app.news.api.model

enum class SyncResult {
    SUCCESS,
    COOLDOWN,
    BUCKET_EXHAUSTED,
    QUOTA_EXHAUSTED,
    SKIPPED
}
