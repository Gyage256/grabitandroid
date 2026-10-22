package com.grabit

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class DlService : Service() {
    private lateinit var pool: ExecutorService
    private val h = Handler(Looper.getMainLooper())
    private var wake: PowerManager.WakeLock? = null
    private var lastId = 0
    private val running = HashSet<Int>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        pool = Executors.newFixedThreadPool(2)
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel("gi", "GrabIt", NotificationManager.IMPORTANCE_LOW))
        wake = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "grabit:dl")
            .also { it.acquire(6 * 60 * 60 * 1000L) }
    }

    private fun notif(text: String, pct: Int, indeterminate: Boolean): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, "gi")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("GrabIt")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, pct, indeterminate)
            .build()
    }

    private val tick = object : Runnable {
        override fun run() {
            val act = Store.active()
            if (act.isEmpty()) {
                ServiceCompat.stopForeground(this@DlService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf(lastId)
                return
            }
            var done = 0L
            var total = 0L
            var unknown = false
            var speed = 0L
            for (i in act) {
                done += i.done
                if (i.total > 0) total += i.total else unknown = true
                speed += i.speed
            }
            val pct = if (!unknown && total > 0) (done * 100 / total).toInt() else 0
            val text = "${act.size} downloading - ${fmtBytes(speed)}/s"
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(1, notif(text, pct, unknown || total <= 0))
            h.postDelayed(this, 1000)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastId = startId
        ServiceCompat.startForeground(
            this, 1, notif("Starting...", 0, true), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
        val id = intent?.getIntExtra("id", -1) ?: -1
        val item = Store.get(id)
        if (item != null && item.status == Status.QUEUED) {
            val start = synchronized(running) { running.add(id) }
            if (start) {
                pool.execute {
                    try {
                        Downloader.run(applicationContext, item)
                    } catch (t: Throwable) {
                        item.error = t.message ?: "Unexpected error"
                        item.status = Status.FAILED
                    } finally {
                        synchronized(running) { running.remove(id) }
                    }
                }
            }
        }
        h.removeCallbacks(tick)
        h.post(tick)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        h.removeCallbacks(tick)
        pool.shutdownNow()
        try { wake?.release() } catch (_: Exception) {}
        super.onDestroy()
    }
}
