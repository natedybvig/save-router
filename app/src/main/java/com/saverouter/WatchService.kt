package com.saverouter

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.graphics.PixelFormat
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import java.io.File
import java.util.concurrent.Executors

/** Watches Downloads and posts a "Where should this go?" alert for each new file. */
class WatchService : Service() {
    companion object {
        private const val CH_WATCH = "watch"
        private const val CH_NEW = "new"
        private const val CH_QUIET = "new_quiet"
        private const val ONGOING_ID = 1
        private const val ACT_QUICK = "com.saverouter.QUICK"
        private const val ACT_STOP = "com.saverouter.STOP"
        const val ACT_SAMPLE = "com.saverouter.SAMPLE"

        fun start(ctx: Context) {
            if (!Environment.isExternalStorageManager()) return
            try {
                ctx.startForegroundService(Intent(ctx, WatchService::class.java))
            } catch (_: Exception) {}
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, WatchService::class.java))
        }

        fun channels(ctx: Context) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CH_WATCH, "Watching Downloads", NotificationManager.IMPORTANCE_MIN))
            nm.createNotificationChannel(NotificationChannel(CH_NEW, "New downloads", NotificationManager.IMPORTANCE_HIGH))
            nm.createNotificationChannel(NotificationChannel(CH_QUIET, "New downloads (when the on-screen card is used)", NotificationManager.IMPORTANCE_LOW))
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private val pool = Executors.newSingleThreadExecutor()
    private val known = HashSet<String>()
    private val pending = HashMap<String, Long>()
    private var primed = false
    private lateinit var store: Store
    private val nm by lazy { getSystemService(NotificationManager::class.java) }
    private val popup by lazy {
        PopupCard(this, store, pool).also { p -> p.report = { msg -> store.lastPopup = (store.lastPopup ?: "") + " Then: " + msg } }
    }
    private fun popupOn() = store.popup && Settings.canDrawOverlays(this)
    private fun whyNoCard(): String? = when {
        !store.popup -> "(Pop-up card is turned off in Save Router.) Click to choose where it goes."
        !Settings.canDrawOverlays(this) -> "No pop-up card: “Display over other apps” isn’t allowed yet. Open Save Router to allow it."
        else -> null
    }

    private val downloads: File get() = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)

    private val tick = object : Runnable {
        override fun run() {
            try { scan() } catch (_: Exception) {}
            handler.postDelayed(this, 3000)
        }
    }

    override fun onBind(i: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        store = Store(this)
        channels(this)
        goForeground()
    }

    override fun onStartCommand(i: Intent?, flags: Int, startId: Int): Int {
        goForeground()
        when (i?.action) {
            ACT_SAMPLE -> {
                val dir = java.io.File(cacheDir, "sample").apply { mkdirs() }
                val f = java.io.File(dir, "Sample card (nothing is saved).txt").apply { writeText("sample") }
                val ok = runCatching { popup.show(f, PopupCard.SAMPLE_ID) }.getOrElse { e -> popup.lastError = e.toString(); false }
                store.lastPopup = "sample card: " + (if (ok) "shown" else "refused (${popup.lastError})")
            }
            ACT_STOP -> {
                store.watch = false
                popup.closeAll()
                stopSelf()
                return START_NOT_STICKY
            }
            ACT_QUICK -> {
                val path = i.getStringExtra("path")
                val dest = i.getStringExtra("dest")
                if (path != null && dest != null) quickSave(path, dest, i.getIntExtra("nid", 0))
            }
        }
        if (!primed) {
            primed = true
            downloads.listFiles()?.forEach { known.add(it.name) }
            handler.post(tick)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        runCatching { popup.closeAll() }
        pool.shutdown()
        super.onDestroy()
    }

    private fun goForeground() {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, WatchService::class.java).setAction(ACT_STOP), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, CH_WATCH)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Save Router is watching Downloads")
            .setContentText("Each new download will ask where it should go.")
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_stat), "Stop watching", stop).build())
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(ONGOING_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(ONGOING_ID, n)
    }

    private fun ignore(n: String): Boolean =
        n.startsWith(".") || listOf(".crdownload", ".part", ".partial", ".tmp", ".download", ".opdownload")
            .any { n.endsWith(it, ignoreCase = true) }

    private fun scan() {
        val files = downloads.listFiles() ?: return
        val names = files.map { it.name }.toHashSet()
        known.retainAll(names)
        pending.keys.retainAll(names)
        for (f in files) {
            if (f.isDirectory || f.name in known || ignore(f.name)) continue
            val size = f.length()
            val last = pending[f.name]
            if (last != null && last == size) {
                pending.remove(f.name)
                known.add(f.name)
                notifyNew(f)
            } else {
                pending[f.name] = size
            }
        }
    }

    private fun idFor(f: File) = (f.absolutePath.hashCode() and 0x3fffffff) or 0x1000

    private fun chooserIntent(f: File, nid: Int): PendingIntent {
        val i = Intent(this, ChooserActivity::class.java)
            .putExtra(ChooserActivity.EXTRA_PATH, f.absolutePath)
            .putExtra(ChooserActivity.EXTRA_NOTIF, nid)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return PendingIntent.getActivity(this, nid, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun notifyNew(f: File) {
        val nid = idFor(f)
        val icon = Icon.createWithResource(this, R.drawable.ic_stat)
        val stamp = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date())

        // 1. Try the on-screen card. We try even if Android says the permission is off,
        //    because some devices report it wrongly.
        var cardShown = false
        if (store.popup) {
            cardShown = runCatching { popup.show(f, nid) }.getOrElse { e -> popup.lastError = e.toString(); false }
            store.lastPopup = if (cardShown) "$stamp: card shown for ${f.name}"
            else "$stamp: card refused (${popup.lastError ?: "unknown"}). Permission reported as " +
                (if (Settings.canDrawOverlays(this)) "allowed" else "not allowed") + "."
        }

        // 2. The card is up, so no taskbar alert is needed.
        if (cardShown) return

        // 3. No card: a notification that stays until handled (full-screen when allowed).
        val b = Notification.Builder(this, CH_NEW)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Where should “${f.name}” go?")
            .setContentText(if (cardShown || !store.popup) "It’s in Downloads now. Pick a place to save it."
                else "The pop-up card was blocked. Click here to choose where it goes.")
            .setContentIntent(chooserIntent(f, nid))
            .setCategory(Notification.CATEGORY_REMINDER)
            .setOnlyAlertOnce(true)
        if (fullScreenAllowed()) {
            b.setFullScreenIntent(chooserIntent(f, nid), true)
            if (!cardShown) store.lastPopup = (store.lastPopup ?: "") + " Used the full-screen alert instead."
        }
        store.dests().filter { it.kind != "drive" && it.quick }.take(2).forEachIndexed { k, d ->
            val qi = Intent(this, WatchService::class.java).setAction(ACT_QUICK)
                .putExtra("path", f.absolutePath).putExtra("dest", d.id).putExtra("nid", nid)
            val pi = PendingIntent.getService(this, nid + (k + 1) * 7919, qi, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            b.addAction(Notification.Action.Builder(icon, d.label, pi).build())
        }
        b.addAction(Notification.Action.Builder(icon, "Choose…", chooserIntent(f, nid)).build())
        nm.notify(nid, b.build())
    }

    private fun fullScreenAllowed(): Boolean =
        Build.VERSION.SDK_INT < 34 || nm.canUseFullScreenIntent()

    private fun quickSave(path: String, destId: String, nid: Int) {
        val f = File(path)
        val d = store.get(destId) ?: return
        val icon = Icon.createWithResource(this, R.drawable.ic_stat)
        nm.notify(nid, Notification.Builder(this, CH_NEW)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Saving “${f.name}” to ${d.label}…")
            .setProgress(0, 0, true)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .build())
        pool.execute {
            try {
                val saved = Saver.save(this, Source.fromFile(f), d, f.name)
                if (store.deleteAfter) Saver.removeOriginal(this, f)
                nm.notify(nid, Notification.Builder(this, CH_NEW)
                    .setSmallIcon(R.drawable.ic_stat)
                    .setContentTitle("Saved to ${d.label}")
                    .setContentText(saved)
                    .setOnlyAlertOnce(true)
                    .setTimeoutAfter(8000)
                    .setAutoCancel(true)
                    .build())
            } catch (e: Exception) {
                nm.notify(nid, Notification.Builder(this, CH_NEW)
                    .setSmallIcon(R.drawable.ic_stat)
                    .setContentTitle("Couldn’t save “${f.name}” to ${d.label}")
                    .setContentText(Smb.friendly(e, d))
                    .setStyle(Notification.BigTextStyle().bigText(Smb.friendly(e, d)))
                    .setContentIntent(chooserIntent(f, nid))
                    .addAction(Notification.Action.Builder(icon, "Choose another place…", chooserIntent(f, nid)).build())
                    .setAutoCancel(true)
                    .build())
            }
        }
    }
}

/** Restarts the watcher after the Googlebook restarts or the app updates. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (Store(ctx).watch) WatchService.start(ctx)
    }
}
