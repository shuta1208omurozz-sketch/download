package jp.example.floatsaver

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import com.yausername.youtubedl_android.YoutubeDL
import java.util.ArrayDeque

/** Serial, bounded download queue. A second start must not stop the first download. */
class DownloadService : Service() {
    companion object {
        const val KEY_URL = "url"
        const val KEY_FORMAT = "format"
        const val KEY_GIF = "gif"
        const val KEY_CONTAINER = "container"
        const val KEY_TITLE = "title"
        const val KEY_REFERER = "referer"
        const val KEY_USER_AGENT = "userAgent"
        private const val CHANNEL = "float_saver_downloads"
        private const val NOTIFICATION_ID = 204
        @Volatile var active: Boolean = false
            private set

        fun start(context: Context, url: String, selector: String, isGif: Boolean,
                  container: String, title: String, referer: String? = null,
                  userAgent: String? = null) {
            val intent = Intent(context, DownloadService::class.java).apply {
                putExtra(KEY_URL, url)
                putExtra(KEY_FORMAT, selector)
                putExtra(KEY_GIF, isGif)
                putExtra(KEY_CONTAINER, container)
                putExtra(KEY_TITLE, title)
                putExtra(KEY_REFERER, referer)
                putExtra(KEY_USER_AGENT, userAgent)
            }
            context.startForegroundService(intent)
        }
    }

    private data class Job(
        val url: String, val selector: String, val isGif: Boolean,
        val container: String, val title: String, val referer: String?, val userAgent: String?
    )

    private lateinit var notifications: NotificationManager
    private val main = Handler(Looper.getMainLooper())
    private val queue = ArrayDeque<Job>() // main-thread only
    private var current: Job? = null
    private var lastUpdate = 0L
    private var resultSerial = 0
    private var lastStartId = 0
    @Volatile private var timedOut = false
    @Volatile private var activeProcessId: String? = null

    override fun onCreate() {
        super.onCreate()
        active = true
        notifications = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notifications.createNotificationChannel(NotificationChannel(CHANNEL,
            "ダウンロード状況", NotificationManager.IMPORTANCE_LOW))
        // If Android killed the previous process, stale 保存中 rows must not remain forever.
        runCatching { HistoryStore(this).use { it.markInterrupted() } }
        runCatching { MediaEngine.cleanupOldStaging(this) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        val url = intent?.getStringExtra(KEY_URL)
        val selector = intent?.getStringExtra(KEY_FORMAT)
        if (url.isNullOrBlank() || selector.isNullOrBlank()) {
            if (current == null && queue.isEmpty()) stopSelfResult(startId)
            return START_NOT_STICKY
        }
        val job = Job(url, selector, intent.getBooleanExtra(KEY_GIF, false),
            intent.getStringExtra(KEY_CONTAINER) ?: MediaEngine.CONTAINER_ORIGINAL,
            intent.getStringExtra(KEY_TITLE) ?: "動画",
            intent.getStringExtra(KEY_REFERER), intent.getStringExtra(KEY_USER_AGENT))
        if (current == job || queue.contains(job)) {
            notifications.notify(NOTIFICATION_ID + 5,
                buildNotification("同じ保存処理は既に受付済みです", 0, false))
            return START_NOT_STICKY
        }
        if (queue.size >= 4) {
            notifications.notify(NOTIFICATION_ID + 5,
                buildNotification("保存待ちは4件までです。完了後に再度お試しください", 0, false))
            return START_NOT_STICKY
        }
        queue.addLast(job)
        if (current == null) {
            startForeground(NOTIFICATION_ID, buildNotification("保存を準備しています", 0, true))
            next()
        } else {
            notifications.notify(NOTIFICATION_ID + 5,
                buildNotification("保存待ち: ${queue.size}件", 0, false))
        }
        return START_NOT_STICKY
    }

    /** Called on the main thread only. */
    private fun next() {
        if (timedOut) {
            current = null
            stopSelf()
            return
        }
        val job = queue.pollFirst()
        if (job == null) {
            current = null
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelfResult(lastStartId)
            return
        }
        current = job
        lastUpdate = 0L
        val processId = "fs_${SystemClock.elapsedRealtimeNanos()}"
        activeProcessId = processId
        notifications.notify(NOTIFICATION_ID, buildNotification("保存中: ${job.title.take(34)}", 0, true))
        Thread {
            // A broken/full history DB must not prevent actual video downloads.
            val history = runCatching { HistoryStore(applicationContext) }.getOrNull()
            var historyId: Long? = null
            try {
                historyId = history?.let { db ->
                    runCatching { db.begin(job.title, job.url, job.container) }.getOrNull()
                }
                val result = MediaEngine.download(applicationContext, job.url, job.selector,
                    job.isGif, job.container, job.referer, job.userAgent,
                    processId, { timedOut }) { percent ->
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastUpdate > 800 || percent >= 99) {
                        lastUpdate = now
                        notifications.notify(NOTIFICATION_ID,
                            buildNotification("${job.title.take(26)}: $percent%", percent, false))
                    }
                }
                historyId?.let { id -> runCatching { history?.succeed(id, result) } }
                notifications.notify(NOTIFICATION_ID + 10 + (resultSerial++ % 10),
                    buildNotification("保存完了: ${result.fileName.take(45)}", 100, false))
            } catch (error: Exception) {
                val message = ErrorHints.describe(error)
                historyId?.let { id -> runCatching { history?.fail(id, message) } }
                notifications.notify(NOTIFICATION_ID + 20 + (resultSerial++ % 10),
                    buildNotification("保存失敗: $message", 0, false))
            } finally {
                runCatching { history?.close() }
                main.post {
                    activeProcessId = null
                    current = null
                    next()
                }
            }
        }.start()
    }

    private fun buildNotification(message: String, progress: Int, indeterminate: Boolean): Notification {
        val pending = PendingIntent.getActivity(this, 1,
            Intent(this, HistoryActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("FloatSaver")
            .setContentText(message)
            .setContentIntent(pending)
            .setOnlyAlertOnce(true)
            .setProgress(100, progress, indeterminate)
            .build()
    }

    // Android 15 imposes a rolling six-hour dataSync foreground-service budget.
    // Stop promptly on timeout rather than causing an Android foreground-service ANR.
    override fun onTimeout(startId: Int, fgsType: Int) {
        timedOut = true
        queue.clear()
        activeProcessId?.let { id ->
            runCatching { YoutubeDL.getInstance().destroyProcessById(id) }
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        active = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
