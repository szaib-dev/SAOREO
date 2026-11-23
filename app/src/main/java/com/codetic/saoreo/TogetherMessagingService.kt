package com.codetic.saoreo

import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.work.*
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import java.util.concurrent.TimeUnit

/** Push carries a wake-up identifier only. Room and call information comes from authenticated HTTPS. */
class TogetherMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        getSharedPreferences("together-push", MODE_PRIVATE).edit().putString("token", token).apply()
        scheduleRegistration(applicationContext)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        if (message.data["type"] != "call_wake") return
        val id = message.data["ring"]?.takeIf { it.matches(Regex("[a-f0-9]{16}")) } ?: return
        val pairing = (application as SaoreoApp).pairing
        if (!pairing.resumeAvailabilityAtBoot) return
        if (message.priority == RemoteMessage.PRIORITY_HIGH) {
            runCatching {
                ContextCompat.startForegroundService(this, Intent(this, TogetherAvailabilityService::class.java)
                    .putExtra("pushRing", id))
            }.onFailure { scheduleWake(id) }
        } else scheduleWake(id)
    }

    private fun scheduleWake(id: String) {
        WorkManager.getInstance(this).enqueueUniqueWork("call-wake-$id", ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<CallPushWakeWorker>().setInputData(workDataOf("ring" to id))
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST).build())
    }

    companion object {
        internal fun register(app: android.app.Application) {
            if (com.google.firebase.FirebaseApp.getApps(app).isEmpty()) return
            FirebaseMessaging.getInstance().token.addOnSuccessListener { token ->
                app.getSharedPreferences("together-push", MODE_PRIVATE).edit().putString("token", token).apply()
                scheduleRegistration(app)
            }
        }
        private fun scheduleRegistration(context: android.content.Context) {
            WorkManager.getInstance(context).enqueueUniqueWork("call-push-registration", ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<CallPushRegistrationWorker>()
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build())
        }
    }
}

class CallPushRegistrationWorker(context: android.content.Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        (applicationContext as SaoreoApp).pairing.registerPush()
        Result.success()
    } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
    catch (_: Exception) { if (runAttemptCount < 5) Result.retry() else Result.failure() }
}

class CallPushWakeWorker(context: android.content.Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as SaoreoApp
        val id = inputData.getString("ring") ?: return Result.failure()
        return try {
            app.pairing.refreshIncoming(id)
            Result.success()
        } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
        catch (_: Exception) { Result.failure() }
    }
}
