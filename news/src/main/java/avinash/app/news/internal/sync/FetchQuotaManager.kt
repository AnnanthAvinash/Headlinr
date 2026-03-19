package avinash.app.news.internal.sync

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import timber.log.Timber
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZonedDateTime
import javax.inject.Inject
import javax.inject.Singleton

private val Context.quotaDataStore: DataStore<Preferences> by preferencesDataStore(name = "news_quota")

@Singleton
class FetchQuotaManager @Inject constructor(
    @ApplicationContext private val context: Context
) {

    enum class TimeBucket(val maxFetches: Int) {
        MORNING(20),
        AFTERNOON(8),
        EVENING(12),
        NIGHT(8),
        LATE_NIGHT(2);

        companion object {
            private val IST = ZoneId.of("Asia/Kolkata")

            fun current(): TimeBucket {
                val hour = ZonedDateTime.now(IST).hour
                return when (hour) {
                    in 5..10 -> MORNING
                    in 11..15 -> AFTERNOON
                    in 16..18 -> EVENING
                    in 19..22 -> NIGHT
                    else -> LATE_NIGHT
                }
            }
        }
    }

    companion object {
        private val IST = ZoneId.of("Asia/Kolkata")

        const val DAILY_CAP = 50
        const val MONTHLY_CAP = 1300

        private val KEY_QUOTA_DATE = stringPreferencesKey("quota_date")
        private val KEY_DAILY_TOTAL = intPreferencesKey("daily_total")
        private val KEY_MONTHLY_BUCKET = intPreferencesKey("monthly_bucket")
        private val KEY_MONTHLY_RESET_MONTH = stringPreferencesKey("monthly_reset_month")

        private val KEY_BUCKET_MORNING = intPreferencesKey("bucket_morning")
        private val KEY_BUCKET_AFTERNOON = intPreferencesKey("bucket_afternoon")
        private val KEY_BUCKET_EVENING = intPreferencesKey("bucket_evening")
        private val KEY_BUCKET_NIGHT = intPreferencesKey("bucket_night")
        private val KEY_BUCKET_LATE_NIGHT = intPreferencesKey("bucket_late_night")

        private fun bucketKey(bucket: TimeBucket): Preferences.Key<Int> = when (bucket) {
            TimeBucket.MORNING -> KEY_BUCKET_MORNING
            TimeBucket.AFTERNOON -> KEY_BUCKET_AFTERNOON
            TimeBucket.EVENING -> KEY_BUCKET_EVENING
            TimeBucket.NIGHT -> KEY_BUCKET_NIGHT
            TimeBucket.LATE_NIGHT -> KEY_BUCKET_LATE_NIGHT
        }
    }

    private val dataStore get() = context.quotaDataStore

    suspend fun canFetch(): Boolean {
        resetIfNewDay()
        resetMonthlyIfNewMonth()

        val prefs = dataStore.data.first()
        val bucket = TimeBucket.current()
        val bucketCount = prefs[bucketKey(bucket)] ?: 0
        val dailyTotal = prefs[KEY_DAILY_TOTAL] ?: 0
        val monthlyBucket = prefs[KEY_MONTHLY_BUCKET] ?: 0

        if (bucketCount >= bucket.maxFetches) {
            Timber.d("Quota: bucket ${bucket.name} exhausted ($bucketCount/${bucket.maxFetches})")
            return false
        }

        if (dailyTotal >= DAILY_CAP) {
            if (monthlyBucket > 0) {
                Timber.d("Quota: daily exhausted, using monthly bucket ($monthlyBucket remaining)")
                return true
            }
            Timber.d("Quota: daily exhausted ($dailyTotal/$DAILY_CAP) and monthly empty")
            return false
        }

        return true
    }

    suspend fun recordFetch() {
        resetIfNewDay()
        resetMonthlyIfNewMonth()

        dataStore.edit { prefs ->
            val bucket = TimeBucket.current()
            val key = bucketKey(bucket)
            val bucketCount = prefs[key] ?: 0
            prefs[key] = bucketCount + 1

            val dailyTotal = prefs[KEY_DAILY_TOTAL] ?: 0
            if (dailyTotal < DAILY_CAP) {
                prefs[KEY_DAILY_TOTAL] = dailyTotal + 1
            } else {
                val monthly = prefs[KEY_MONTHLY_BUCKET] ?: 0
                if (monthly > 0) {
                    prefs[KEY_MONTHLY_BUCKET] = monthly - 1
                    Timber.d("Quota: used monthly bucket ($monthly → ${monthly - 1})")
                }
            }

            Timber.d("Quota: recorded fetch — bucket ${bucket.name} (${bucketCount + 1}/${bucket.maxFetches}), daily ${minOf(dailyTotal + 1, DAILY_CAP)}/$DAILY_CAP")
        }
    }

    suspend fun getRemainingDaily(): Int {
        resetIfNewDay()
        val prefs = dataStore.data.first()
        val dailyTotal = prefs[KEY_DAILY_TOTAL] ?: 0
        return (DAILY_CAP - dailyTotal).coerceAtLeast(0)
    }

    suspend fun getRemainingInBucket(): Int {
        resetIfNewDay()
        val prefs = dataStore.data.first()
        val bucket = TimeBucket.current()
        val bucketCount = prefs[bucketKey(bucket)] ?: 0
        return (bucket.maxFetches - bucketCount).coerceAtLeast(0)
    }

    suspend fun getMonthlyBucket(): Int {
        resetMonthlyIfNewMonth()
        val prefs = dataStore.data.first()
        return prefs[KEY_MONTHLY_BUCKET] ?: 0
    }

    private suspend fun resetIfNewDay() {
        val todayIST = LocalDate.now(IST).toString()

        val prefs = dataStore.data.first()
        val storedDate = prefs[KEY_QUOTA_DATE]

        if (storedDate == todayIST) return

        dataStore.edit { mutablePrefs ->
            val currentDate = mutablePrefs[KEY_QUOTA_DATE]
            if (currentDate == todayIST) return@edit

            val dailyTotal = mutablePrefs[KEY_DAILY_TOTAL] ?: 0
            val unused = (DAILY_CAP - dailyTotal).coerceAtLeast(0)
            val currentMonthly = mutablePrefs[KEY_MONTHLY_BUCKET] ?: 0
            val newMonthly = (currentMonthly + unused).coerceAtMost(MONTHLY_CAP)

            Timber.d("Quota: day reset — rolling $unused unused fetches to monthly ($currentMonthly → $newMonthly)")

            mutablePrefs[KEY_QUOTA_DATE] = todayIST
            mutablePrefs[KEY_DAILY_TOTAL] = 0
            mutablePrefs[KEY_BUCKET_MORNING] = 0
            mutablePrefs[KEY_BUCKET_AFTERNOON] = 0
            mutablePrefs[KEY_BUCKET_EVENING] = 0
            mutablePrefs[KEY_BUCKET_NIGHT] = 0
            mutablePrefs[KEY_BUCKET_LATE_NIGHT] = 0
            mutablePrefs[KEY_MONTHLY_BUCKET] = newMonthly
        }
    }

    private suspend fun resetMonthlyIfNewMonth() {
        val currentMonth = YearMonth.now(IST).toString()

        val prefs = dataStore.data.first()
        val storedMonth = prefs[KEY_MONTHLY_RESET_MONTH]

        if (storedMonth == currentMonth) return

        dataStore.edit { mutablePrefs ->
            val existing = mutablePrefs[KEY_MONTHLY_RESET_MONTH]
            if (existing == currentMonth) return@edit

            Timber.d("Quota: monthly reset — new month $currentMonth (was $existing)")
            mutablePrefs[KEY_MONTHLY_RESET_MONTH] = currentMonth
            mutablePrefs[KEY_MONTHLY_BUCKET] = 0
        }
    }
}
