package sa.arsel.core.net

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import sa.arsel.core.model.QueuedRequest
import sa.arsel.core.store.ArselStore
import java.util.concurrent.TimeUnit

/**
 * Durable request queue. Persists each request to [ArselStore] and schedules a WorkManager job
 * (network-constrained, exponential backoff) to drain it via [PushSyncWorker]. Survives process
 * death — WorkManager re-runs the worker when connectivity returns even if the app was killed.
 * Dedupe (e.g. one pending register) is handled by the store on enqueue.
 */
internal class RequestQueue(
    private val context: Context,
    private val store: ArselStore,
) {
    fun enqueue(req: QueuedRequest) {
        store.addRequest(req)
        scheduleDrain()
    }

    /**
     * `APPEND_OR_REPLACE`, never `KEEP`. Under `KEEP` a request enqueued while a drain is already
     * running — the common case, since a push both renders and engagements — is silently dropped on the
     * floor: the running drain read the queue before that request existed, and the new schedule
     * request is discarded because work with the same name already exists. `APPEND_OR_REPLACE`
     * guarantees a drain runs *after* this enqueue.
     *
     * It also resets WorkManager's backoff attempt count, which used to mean an active app retried
     * every [BACKOFF_SECONDS] forever no matter how hard the server pushed back. That is why the
     * real wait lives in [RetryPolicy] and is persisted by [PushSyncWorker]: this schedule only
     * decides *when we wake up*, never whether we are allowed to send.
     */
    fun scheduleDrain() {
        val work =
            OneTimeWorkRequestBuilder<PushSyncWorker>()
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
                .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(UNIQUE_WORK, ExistingWorkPolicy.APPEND_OR_REPLACE, work)
    }

    companion object {
        const val UNIQUE_WORK = "arsel_push_sync"
        private const val BACKOFF_SECONDS = 10L
    }
}
