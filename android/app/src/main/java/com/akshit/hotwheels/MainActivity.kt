package com.akshit.hotwheels

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Address
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private companion object {
        const val REQ_NOTIFICATIONS = 1
        const val REQ_LOCATION = 2
    }

    private lateinit var store: Store
    private lateinit var status: TextView
    private lateinit var locationLabel: TextView
    private lateinit var lat: EditText
    private lateinit var lon: EditText
    private lateinit var query: EditText
    private lateinit var keywords: EditText
    private lateinit var interval: EditText
    private val ui = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        store = Store(this)
        Notifier.ensureChannels(this)
        // Restore whichever request shape Blinkit last accepted, and persist
        // any change the automatic fallback discovers.
        Blinkit.profileIndex = store.httpProfile
        Blinkit.onProfileChanged = { store.httpProfile = it }

        lat = findViewById(R.id.lat)
        lon = findViewById(R.id.lon)
        query = findViewById(R.id.query)
        keywords = findViewById(R.id.keywords)
        interval = findViewById(R.id.interval)
        status = findViewById(R.id.status)
        locationLabel = findViewById(R.id.locationLabel)

        lat.setText(store.lat)
        lon.setText(store.lon)
        query.setText(store.query)
        keywords.setText(store.keywords)
        interval.setText(store.intervalMinutes.toString())

        val manualBox = findViewById<LinearLayout>(R.id.manualBox)
        findViewById<TextView>(R.id.manualToggle).setOnClickListener {
            manualBox.visibility =
                if (manualBox.visibility == View.GONE) View.VISIBLE else View.GONE
        }

        findViewById<Button>(R.id.useGps).setOnClickListener { useCurrentLocation() }
        findViewById<Button>(R.id.searchAddress).setOnClickListener { askForAddress() }

        findViewById<Button>(R.id.start).setOnClickListener {
            save()
            if (!hasNotificationPermission()) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFICATIONS)
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

        findViewById<Button>(R.id.test).setOnClickListener { save(); testAlert() }
        findViewById<Button>(R.id.diagnose).setOnClickListener { save(); runDiagnostics() }

        findViewById<Button>(R.id.reset).setOnClickListener {
            store.forgetAll()
            toast("Baseline cleared — next check re-learns what's in stock")
            refresh()
        }

        // If we have coordinates but never named them, name them now.
        if (store.locationLabel.isBlank()) describeSaved()
        refresh()
    }

    override fun onResume() { super.onResume(); refresh() }

    private fun save() {
        store.lat = lat.text.toString()
        store.lon = lon.text.toString()
        store.query = query.text.toString()
        store.keywords = keywords.text.toString()
        store.intervalMinutes = interval.text.toString().toIntOrNull() ?: 2
    }

    private fun refresh() {
        locationLabel.text = store.locationLabel.ifBlank { "${store.lat}, ${store.lon}" }
        status.text = buildString {
            append(if (store.enabled) "● Watching every ${store.intervalMinutes} min\n\n" else "○ Not watching\n\n")
            append(store.lastStatus)
        }
    }

    // ---- location ------------------------------------------------------

    /** Store a chosen point, name it, and confirm Blinkit actually serves it. */
    private fun applyLocation(latitude: Double, longitude: Double, label: String?) {
        store.lat = String.format(java.util.Locale.US, "%.6f", latitude)
        store.lon = String.format(java.util.Locale.US, "%.6f", longitude)
        lat.setText(store.lat)
        lon.setText(store.lon)
        store.locationLabel = label ?: LocationPicker.format(latitude, longitude)
        // The baseline belongs to the old store, so it would be meaningless here.
        store.forgetAll()
        refresh()
        verifyServiceable()
    }

    /**
     * A wrong location fails silently — Blinkit just returns a different
     * store's catalogue, or none. Checking immediately turns that into a
     * visible answer while the user is still looking at the screen.
     */
    private fun verifyServiceable() {
        toast("Checking Blinkit at this location…")
        Thread {
            val result = runCatching {
                Blinkit.search(store.lat, store.lon, store.query, maxPages = 1)
            }
            ui.post {
                result.onSuccess { products ->
                    val inStock = products.count { it.inStock }
                    if (products.isEmpty()) {
                        toast("Blinkit returned nothing here — try a nearby address")
                    } else {
                        toast("Serviceable: ${products.size} found, $inStock in stock")
                        store.lastStatus =
                            "Location set. ${products.size} cars visible, $inStock in stock."
                        refresh()
                    }
                }.onFailure { toast(it.message ?: "Couldn't reach Blinkit") }
            }
        }.start()
    }

    private fun describeSaved() {
        val la = store.lat.toDoubleOrNull() ?: return
        val lo = store.lon.toDoubleOrNull() ?: return
        Thread {
            val text = LocationPicker.describe(this, la, lo)
            ui.post { store.locationLabel = text; refresh() }
        }.start()
    }

    private fun useCurrentLocation() {
        if (!LocationPicker.hasPermission(this)) {
            requestPermissions(LocationPicker.permissions, REQ_LOCATION)
            return
        }
        toast("Getting your location…")
        LocationPicker.current(this) { location ->
            if (location == null) {
                toast("Couldn't get a fix — is location switched on?")
                return@current
            }
            Thread {
                val label = LocationPicker.describe(this, location.latitude, location.longitude)
                ui.post { applyLocation(location.latitude, location.longitude, label) }
            }.start()
        }
    }

    private fun askForAddress() {
        val input = EditText(this).apply {
            hint = "e.g. Sushant Lok Phase 1, Gurugram"
            setSingleLine()
        }
        val pad = (resources.displayMetrics.density * 20).toInt()
        AlertDialog.Builder(this)
            .setTitle("Search for your address")
            .setMessage("Use the address you'd have Blinkit deliver to — stock differs between nearby stores.")
            .setView(LinearLayout(this).apply {
                setPadding(pad, pad / 2, pad, 0); addView(input)
            })
            .setPositiveButton("Search") { _, _ -> doSearch(input.text.toString()) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun doSearch(text: String) {
        if (text.isBlank()) return
        toast("Searching…")
        Thread {
            val results: List<Address> = LocationPicker.search(this, text)
            ui.post {
                if (results.isEmpty()) {
                    toast("No matches. Try adding the city, or use manual coordinates.")
                    return@post
                }
                val labels = results.map { LocationPicker.label(it) }.toTypedArray()
                AlertDialog.Builder(this)
                    .setTitle("Pick your location")
                    .setItems(labels) { _, which ->
                        val a = results[which]
                        applyLocation(a.latitude, a.longitude, labels[which])
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }.start()
    }

    // ---- permissions ---------------------------------------------------

    private fun hasNotificationPermission(): Boolean =
        checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        val granted = grantResults.isNotEmpty() &&
            grantResults.any { it == PackageManager.PERMISSION_GRANTED }
        when (requestCode) {
            REQ_LOCATION ->
                if (granted) useCurrentLocation()
                else toast("Location permission denied — use Search address instead")

            REQ_NOTIFICATIONS ->
                if (granted) {
                    askToIgnoreBatteryOptimisation()
                    store.enabled = true
                    WatcherService.start(this)
                    refresh()
                } else {
                    toast("Without notification permission the app can't alert you")
                }
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

    // ---- diagnostics + test --------------------------------------------

    private fun runDiagnostics() {
        val progress = AlertDialog.Builder(this)
            .setTitle("Testing connection")
            .setMessage("Trying every request shape against Blinkit…")
            .setCancelable(false)
            .show()
        Thread {
            val report = runCatching { Diagnostics.run(store.lat, store.lon, Blinkit.profileIndex) }
                .getOrElse { "Diagnostics crashed: ${it.message}" }
            ui.post {
                progress.dismiss()
                val view = TextView(this).apply {
                    text = report
                    setTextIsSelectable(true)
                    setPadding(48, 32, 48, 32)
                    textSize = 13f
                    typeface = android.graphics.Typeface.MONOSPACE
                }
                AlertDialog.Builder(this)
                    .setTitle("Connection report")
                    .setView(ScrollView(this).apply { addView(view) })
                    .setPositiveButton("Copy") { _, _ ->
                        getSystemService(ClipboardManager::class.java)
                            .setPrimaryClip(ClipData.newPlainText("blinkit diagnostics", report))
                        toast("Copied — paste it to Claude")
                    }
                    .setNegativeButton("Close", null)
                    .show()
            }
        }.start()
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
