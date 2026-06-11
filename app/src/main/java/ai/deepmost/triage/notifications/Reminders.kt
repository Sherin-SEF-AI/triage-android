package ai.deepmost.triage.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * Shift-start / shift-end reminder notifications via WorkManager daily periodic work. On-device,
 * no network. The channel is created once in TriageApplication; posting respects the
 * POST_NOTIFICATIONS runtime permission (already requested at capture).
 */
object Reminders {
    const val CHANNEL_ID = "triage_shift_reminders"
    private const val WORK_START = "triage_reminder_start"
    private const val WORK_END = "triage_reminder_end"

    fun ensureChannel(context: Context) {
        val mgr = context.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(CHANNEL_ID, "Shift reminders", NotificationManager.IMPORTANCE_DEFAULT)
            .apply { description = "Reminds drivers to run shift-start/end walkarounds" }
        mgr.createNotificationChannel(channel)
    }

    fun schedule(context: Context, enabled: Boolean, startHour: Int, endHour: Int) {
        val wm = WorkManager.getInstance(context)
        if (!enabled) {
            wm.cancelUniqueWork(WORK_START); wm.cancelUniqueWork(WORK_END); return
        }
        enqueueDaily(wm, WORK_START, startHour, "Shift start", "Run the shift-start walkaround for your vehicle.")
        enqueueDaily(wm, WORK_END, endHour, "Shift end", "Run the shift-end walkaround to settle the record.")
    }

    private fun enqueueDaily(wm: WorkManager, name: String, hour: Int, title: String, body: String) {
        val delay = initialDelayMillis(hour)
        val req = PeriodicWorkRequestBuilder<ReminderWorker>(24, TimeUnit.HOURS)
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .setInputData(workDataOf("title" to title, "body" to body, "id" to name.hashCode()))
            .build()
        wm.enqueueUniquePeriodicWork(name, ExistingPeriodicWorkPolicy.UPDATE, req)
    }

    /** Millis from now until the next occurrence of [hour]:00 local time. */
    private fun initialDelayMillis(hour: Int): Long {
        val now = Calendar.getInstance()
        val next = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour.coerceIn(0, 23)); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= now.timeInMillis) add(Calendar.DAY_OF_YEAR, 1)
        }
        return next.timeInMillis - now.timeInMillis
    }
}

class ReminderWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val title = inputData.getString("title") ?: "TRIAGE"
        val body = inputData.getString("body") ?: ""
        val id = inputData.getInt("id", 1)
        if (ContextCompat.checkSelfPermission(applicationContext, android.Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU
        ) return Result.success()
        val n = NotificationCompat.Builder(applicationContext, Reminders.CHANNEL_ID)
            .setSmallIcon(ai.deepmost.triage.R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(applicationContext).notify(id, n) }
        return Result.success()
    }
}
