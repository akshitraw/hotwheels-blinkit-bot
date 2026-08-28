package com.akshit.hotwheels

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var store: Store
    private lateinit var status: TextView
    private val ui = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        store = Store(this)
        Notifier.ensureChannels(this)

        val lat = findViewById<EditText>(R.id.lat)
        val lon = findViewById<EditText>(R.id.lon)
        val query = findViewById<EditText>(R.id.query)
        val keywords = findViewById<EditText>(R.id.keywords)
        val interval = findViewById<EditText>(R.id.interval)
        status = findViewById(R.id.status)

        lat.setText(store.lat)
        lon.setText(store.lon)
        query.setText(store.query)
        keywords.setText(store.keywords)
        interval.setText(store.intervalMinutes.toString())

        fun save() {
            store.lat = lat.text.toString()
            store.lon = lon.text.toString()
            store.query = query.text.toString()
            store.keywords = keywords.text.toString()
            store.intervalMinutes = interval.text.toString().toIntOrNull() ?: 2
        }

        findViewById<Button>(R.id.start).setOnClickListener {
            save()
            if (!hasNotificationPermission()) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
                return@setOnClickListener
            }
            askToIgnoreBatteryOptimisation()
            store.enabled = true
            WatcherService.start(this)
            toast("Watching started")
            refresh()
        }

        findViewById<Button>(R.id.stop).setOnClickListener {
            store.enabled = false
            WatcherService.stop(this)
            toast("Watching stopped")
            refresh()
        }

        findViewById<Button>(R.id.test).setOnClickListener {
            save()
            testAlert()
        }

        findViewById<Button>(R.id.reset).setOnClickListener {
            store.forgetAll()
            toast("Baseline cleared — next check re-learns what's in stock")
            refresh()
        }

        refresh()
    }

    override fun onResume() { super.onResume(); refresh() }

    private fun refresh() {
        status.text = buildString {
            append(if (store.enabled) "● Watching every ${store.intervalMinutes} min\n\n" else "○ Not watching\n\n")
            append(store.lastStatus)
        }
    }

    private fun hasNotificationPermission(): Boolean =
        checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            askToIgnoreBatteryOptimisation()
            store.enabled = true
            WatcherService.start(this)
            refresh()
        } else {
            toast("Without notification permission the app can't alert you")
        }
    }

    /**
     * Android will otherwise doze the service to sleep and your checks stop
     * happening while the screen is off — which is exactly when a drop lands.
     */
    private fun askToIgnoreBatteryOptimisation() {
        val pm = getSystemService(PowerManager::class.java)
        if (pm.isIgnoringBatteryOptimizations(packageName)) return
        runCatching {
            startActivity(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName")
                )
            )
        }
    }

    /** Fetch live results and notify on the first couple, so you can see it work. */
    private fun testAlert() {
        toast("Checking Blinkit…")
        Thread {
            val result = runCatching {
                Blinkit.search(store.lat, store.lon, store.query, maxPages = 1)
                    .filter { it.inStock }.take(2)
            }
            ui.post {
                result.onSuccess { products ->
                    if (products.isEmpty()) toast("Nothing in stock right now")
                    else {
                        products.forEach { Notifier.alert(this, it) }
                        toast("Sent ${products.size} sample alert(s)")
                    }
                }.onFailure { toast("Failed: ${it.message}") }
            }
        }.start()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
