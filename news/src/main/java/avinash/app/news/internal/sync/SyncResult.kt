package avinash.app.news.internal.sync

enum class SyncResult {
    SUCCESS,
    COOLDOWN,
    BUCKET_EXHAUSTED,
    QUOTA_EXHAUSTED,
    SKIPPED
}
