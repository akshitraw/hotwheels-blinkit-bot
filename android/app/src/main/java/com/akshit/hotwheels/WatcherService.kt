package com.akshit.hotwheels

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Foreground service that polls Blinkit on a loop.
 *
 * A foreground service (rather than WorkManager) because WorkManager's minimum
 * period is 15 minutes — too slow for a restock that sells out in one. The
 * trade-off is a permanent notification, which Android requires and which also
 * makes it obvious the watcher is alive.
 */
class WatcherService : Service() {

    companion object {
        const val ACTION_STOP = "com.akshit.hotwheels.STOP"
        private const val ONGOING_ID = 1

        fun start(context: Context) {
            val i = Intent(context, WatcherService::class.java)
            context.startForegroundService(i)
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, WatcherService::class.java).setAction(ACTION_STOP)
            )
        }
    }

    @Volatile private var running = false
    private var worker: Thread? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: android.net.wifi.WifiManager.WifiLock? = null
    private val clock = SimpleDateFormat("HH:mm", Locale.getDefault())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Store(this).enabled = false
            shutdown()
            return START_NOT_STICKY
        }

        Notifier.ensureChannels(this)
        startForegroundCompat("Starting…")

        if (!running) {
            running = true
            acquireLocks()
            worker = Thread(::loop, "blinkit-watcher").apply { isDaemon = true; start() }
        }
        // START_STICKY: if Android kills us for memory, restart when it can.
        return START_STICKY
    }

    private fun startForegroundCompat(text: String) {
        val notification = Notifier.serviceNotification(this, text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                ONGOING_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(ONGOING_ID, notification)
        }
    }

    private fun updateNotice(text: String) {
        Store(this).lastStatus = text
        runCatching {
            getSystemService(android.app.NotificationManager::class.java)
                .notify(ONGOING_ID, Notifier.serviceNotification(this, text))
        }
    }

    /**
     * A partial wake lock keeps the CPU running but lets the wifi radio drop
     * into power save, which is what makes requests time out after the screen
     * has been off a while. The wifi lock keeps the radio reachable too.
     */
    private fun acquireLocks() {
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "hotwheels:watcher").apply {
            setReferenceCounted(false)
            acquire()
        }
        runCatching {
            val wm = applicationContext.getSystemService(android.net.wifi.WifiManager::class.java)
            wifiLock = wm.createWifiLock(
                android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF, "hotwheels:wifi"
            ).apply { setReferenceCounted(false); acquire() }
        }
    }

    private fun releaseLocks() {
        runCatching { wakeLock?.release() }; wakeLock = null
        runCatching { wifiLock?.release() }; wifiLock = null
    }

    private fun loop() {
        val store = Store(this)
        Blinkit.profileIndex = store.httpProfile
        Blinkit.onProfileChanged = { store.httpProfile = it }
        var failures = 0

        while (running) {
            val started = System.currentTimeMillis()
            try {
                val products = Blinkit.searchAll(store.lat, store.lon, store.queryList())
                if (products.isEmpty()) throw BlinkitError("Search returned nothing at all")

                val keywords = store.keywordList()
                val firstRun = store.isFirstRun()
                val restocked = products.filter { p ->
                    p.inStock && !store.wasInStock(p.id) &&
                        (keywords.isEmpty() || keywords.any { p.name.lowercase().contains(it) })
                }
                store.remember(products)

                val inStock = products.count { it.inStock }
                if (firstRun) {
                    updateNotice("Baseline set: ${products.size} cars, $inStock in stock. Watching from now.")
                } else {
                    for (p in restocked) Notifier.alert(this, p)
                    updateNotice(
                        if (restocked.isEmpty())
                            "$inStock of ${products.size} in stock · checked ${clock.format(Date())}"
                        else
                            "${restocked.size} restocked! · ${clock.format(Date())}"
                    )
                }
                failures = 0
            } catch (e: InterruptedException) {
                return
            } catch (e: Exception) {
                failures++
                updateNotice("Check failed (${e.message}) · retry ${failures}")
            }

            // Back off when Blinkit is unhappy; otherwise use the chosen interval.
            val waitMs = if (failures > 0)
                minOf(store.intervalMinutes * 60_000L * (1L shl minOf(failures, 5)), 30 * 60_000L)
            else
                store.intervalMinutes * 60_000L
            val elapsed = System.currentTimeMillis() - started
            val sleep = (waitMs - elapsed).coerceAtLeast(5_000L)
            try {
                var slept = 0L
                while (running && slept < sleep) {
                    Thread.sleep(minOf(2_000L, sleep - slept)); slept += 2_000L
                }
            } catch (e: InterruptedException) {
                return
            }
        }
    }

    private fun shutdown() {
        running = false
        worker?.interrupt()
        worker = null
        releaseLocks()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        running = false
        releaseLocks()
        super.onDestroy()
    }
}
