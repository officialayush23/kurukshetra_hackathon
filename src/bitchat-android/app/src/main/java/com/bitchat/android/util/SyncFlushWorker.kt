package com.bitchat.android.util

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.bitchat.android.net.CivilizationSyncClient
import com.bitchat.android.net.LastFix
import com.bitchat.android.services.AppStateStore
import com.bitchat.android.services.SyncBundleBuilder
import com.bitchat.android.services.SyncPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Background flush of accumulated mesh incident + shelter log to the user
 * configured civilization endpoint (normally the command centre's
 * `/api/v1/mesh/civ-sync`). Runs only while connected; backs off
 * per [SyncWorkScheduler] when network is unavailable. No raw device IDs or
 * keys are ever serialized — only message <id, sender display name, role,
 * lat/lon, content[:280]> and shelter descriptors.
 */
class SyncFlushWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    companion object {
        private const val TAG = "SyncFlushWorker"
        private const val MAX_MESSAGES_PER_FLUSH = 200
        private val mutex = Mutex()

        /**
         * One flush. Returns true when there was nothing to send or the endpoint took it,
         * false when it should be retried. Shared by the WorkManager job and the command
         * centre link's periodic loop, and serialised so the two never double-post.
         */
        suspend fun flushNow(ctx: Context): Boolean = mutex.withLock {
            withContext(Dispatchers.IO) {
                val prefs = SyncPreferences.getInstance(ctx)
                if (!prefs.enabled) return@withContext true

                val shelters = AppStateStore.shelters.value
                val messages = AppStateStore.publicMessages.value.takeLast(MAX_MESSAGES_PER_FLUSH)
                val sent = prefs.sentIncidentIds()
                val incidents = SyncBundleBuilder.extractIncidents(
                    messages, fallback = LastFix.get(ctx), exclude = sent
                )
                // Shelters are small and versioned: resend them only alongside new incidents
                // or on the first flush, so an idle phone is not posting every minute.
                if (incidents.isEmpty() && (sent.isNotEmpty() || shelters.isEmpty())) {
                    prefs.lastSyncError = null
                    return@withContext true
                }

                val bundle = SyncBundleBuilder.build(shelters, incidents, prefs.gatewayId, prefs.cityId)
                val ok = CivilizationSyncClient(ctx).postBundle(bundle.json)
                if (ok) {
                    prefs.lastFlushBytes = bundle.bytes
                    prefs.lastSyncError = null
                    prefs.markIncidentsSent(bundle.incidentIds.ifEmpty { listOf("_first") })
                    Log.i(TAG, "Flushed ${bundle.incidentCount} incidents, ${bundle.shelterCount} shelters")
                }
                ok
            }
        }
    }

    override suspend fun doWork(): Result {
        val prefs = SyncPreferences.getInstance(applicationContext)
        if (!prefs.enabled) {
            Log.i(TAG, "Sync disabled, skipping flush")
            return Result.success()
        }
        return if (flushNow(applicationContext)) Result.success() else Result.retry()
    }
}
