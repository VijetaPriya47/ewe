package app.ewe.podcast

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.ewe.EweApp
import app.ewe.MainActivity
import app.ewe.R
import app.ewe.ai.Providers
import app.ewe.data.Repository
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * Prepares today's episode on the phone: sync → pick what is due → fit it into the
 * target length → one AI call for the script → built-in text-to-speech → saved for offline.
 * Runs every night (1:00 AM by default) and on demand from Today's Revision.
 */
class PrepareWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val repo = (applicationContext as EweApp).repo
        val nightly = inputData.getBoolean(KEY_NIGHTLY, false)
        val result = prepare(repo, nightly)
        // Queue tomorrow's run once this one is settled (a retry keeps its own slot).
        if (nightly && result !is Result.Retry) schedule(applicationContext, repo, ExistingWorkPolicy.APPEND_OR_REPLACE)
        return result
    }

    private suspend fun prepare(repo: Repository, nightly: Boolean): Result {
        try {
            if (!repo.prefs.signedIn) return Result.success()
            stage("Syncing notes…")
            val synced = repo.refresh()
            val settings = repo.settings
            if (!settings.podcastEnabled) return Result.success()
            if (!synced && nightly) return Result.retry()

            val store = EpisodeStore.get(applicationContext)
            val today = repo.today()
            val existing = store.today(today)
            if (existing != null && nightly) return Result.success()

            // Sections already marked as listened today stay out of a re-made episode.
            val items = Planner.due(repo.snapshot.value, today, settings.revisionsPerLevel, repo.pendingListenedIds())
            if (items.isEmpty()) {
                store.save(Episode(today.toString(), System.currentTimeMillis(), settings.podcastTargetMinutes, emptyList()))
                return Result.success()
            }
            val plan = Planner.plan(items, settings.podcastTargetMinutes)

            stage("Writing the script…")
            val cfg = repo.prefs.cfg(settings.podcastProvider, settings.podcastModel)
                ?: return fail("Pick a podcast model in Settings and add its key.")
            val out = Providers().generateJson(cfg, Script.SYSTEM, Script.userMessage(plan, today), Script.schema(plan), "episode")
            val script = Script.parse(out, plan)

            val dir = store.dir(today)
            dir.listFiles { f -> f.extension == "wav" }?.forEach { it.delete() }
            val speech = Speech(applicationContext)
            val chapters = ArrayList<Chapter>()
            try {
                speech.start()
                val total = plan.segments.size + 2
                stage("Recording 1 of $total…")
                chapters += Chapter(topic = "Intro", script = script.intro, files = speech.synthesize(script.intro, dir, "intro"))
                plan.segments.forEachIndexed { i, seg ->
                    stage("Recording ${i + 2} of $total…")
                    val it = seg.item
                    chapters += Chapter(
                        scheduleId = it.scheduleId, sessionId = it.sessionId, topic = it.topic, section = it.section,
                        addedDate = it.addedDate, intervalDays = it.intervalDays, revisionNo = it.revisionNo,
                        level = it.level.label, script = script.segments[i],
                        files = speech.synthesize(script.segments[i], dir, "seg$i"),
                    )
                }
                stage("Recording $total of $total…")
                chapters += Chapter(topic = "Outro", script = script.outro, files = speech.synthesize(script.outro, dir, "outro"))
            } finally {
                speech.stop()
            }

            val episode = Episode(today.toString(), System.currentTimeMillis(), settings.podcastTargetMinutes, chapters, plan.deferred.size)
            store.save(episode)
            if (nightly) notifyReady(episode)
            return Result.success()
        } catch (e: Exception) {
            return if (nightly && runAttemptCount < 3) Result.retry() else fail(e.message ?: "Could not prepare the podcast.")
        }
    }

    private suspend fun stage(text: String) = setProgress(workDataOf(KEY_STAGE to text))
    private fun fail(message: String) = Result.failure(workDataOf(KEY_ERROR to message))

    private fun notifyReady(e: Episode) {
        val ctx = applicationContext
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            android.os.Build.VERSION.SDK_INT >= 33) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Daily revision", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(ctx, 0,
            Intent(ctx, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN, "revision").addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Today's revision is ready")
            .setContentText("${e.sections.size} sections · about ${(e.durationMs / 60000).coerceAtLeast(1)} min · plays offline")
            .setContentIntent(open).setAutoCancel(true).build()
        runCatching { NotificationManagerCompat.from(ctx).notify(1, n) }
    }

    companion object {
        const val KEY_NIGHTLY = "nightly"
        const val KEY_STAGE = "stage"
        const val KEY_ERROR = "error"
        const val NIGHTLY = "podcast-nightly"
        const val NOW = "podcast-now"
        private const val CHANNEL = "revision"
        private val online = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        /** (Re)schedules the nightly run, or cancels it when the podcast is off. */
        fun schedule(context: Context, repo: Repository, policy: ExistingWorkPolicy = ExistingWorkPolicy.REPLACE) {
            val wm = WorkManager.getInstance(context)
            val s = repo.settings
            if (!repo.prefs.signedIn || !s.podcastEnabled) { wm.cancelUniqueWork(NIGHTLY); return }
            val at = runCatching { LocalTime.parse(s.dailyJobTime) }.getOrDefault(LocalTime.of(1, 0))
            val now = LocalDateTime.now()
            var next = LocalDate.now().atTime(at)
            if (!next.isAfter(now.plusMinutes(1))) next = next.plusDays(1)
            wm.enqueueUniqueWork(NIGHTLY, policy,
                OneTimeWorkRequestBuilder<PrepareWorker>()
                    .setInitialDelay(Duration.between(now, next))
                    .setConstraints(online)
                    .setInputData(workDataOf(KEY_NIGHTLY to true))
                    .build())
        }

        fun prepareNow(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(NOW, ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<PrepareWorker>().setConstraints(online).build())
        }
    }
}
