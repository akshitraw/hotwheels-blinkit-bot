package com.akshit.hotwheels

import android.content.Context
import org.json.JSONObject

/** Settings and the seen-products memory, both in SharedPreferences. */
class Store(context: Context) {

    private val prefs = context.getSharedPreferences("hotwheels", Context.MODE_PRIVATE)

    var lat: String
        get() = prefs.getString("lat", "28.4634")!!
        set(v) = prefs.edit().putString("lat", v.trim()).apply()

    var lon: String
        get() = prefs.getString("lon", "77.0768")!!
        set(v) = prefs.edit().putString("lon", v.trim()).apply()

    var query: String
        get() = prefs.getString("query", "hot wheels")!!
        set(v) = prefs.edit().putString("query", v.trim().ifEmpty { "hot wheels" }).apply()

    /**
     * Brands that count. A Blinkit search for "hot wheels" also returns
     * unrelated toy cars, so results are filtered against the product's own
     * brand field (falling back to its name) before anything is alerted.
     */
    var brands: String
        get() = prefs.getString("brands", "hot wheels")!!
        set(v) = prefs.edit().putString("brands", v.trim()).apply()

    /** Comma-separated; empty means alert on everything. */
    var keywords: String
        get() = prefs.getString("keywords", "")!!
        set(v) = prefs.edit().putString("keywords", v.trim()).apply()

    var intervalMinutes: Int
        get() = prefs.getInt("interval", 2).coerceIn(1, 120)
        set(v) = prefs.edit().putInt("interval", v.coerceIn(1, 120)).apply()

    var enabled: Boolean
        get() = prefs.getBoolean("enabled", false)
        set(v) = prefs.edit().putBoolean("enabled", v).apply()

    /** Index into Profiles.all that Blinkit currently accepts. */
    var httpProfile: Int
        get() = prefs.getInt("httpProfile", 0)
        set(v) = prefs.edit().putInt("httpProfile", v).apply()

    /** Human-readable name for the saved coordinates, shown in the UI. */
    var locationLabel: String
        get() = prefs.getString("locationLabel", "")!!
        set(v) = prefs.edit().putString("locationLabel", v).apply()

    var lastStatus: String
        get() = prefs.getString("status", "Not started yet")!!
        set(v) = prefs.edit().putString("status", v).apply()

    /** The search box accepts a comma-separated list: "hot wheels, matchbox". */
    fun queryList(): List<String> =
        query.split(",").map { it.trim() }.filter { it.isNotEmpty() }.ifEmpty { listOf("hot wheels") }

    fun brandList(): List<String> =
        brands.split(",").map { it.trim().lowercase() }.filter { it.isNotEmpty() }

    fun keywordList(): List<String> =
        keywords.split(",").map { it.trim().lowercase() }.filter { it.isNotEmpty() }

    // ---- seen products -------------------------------------------------

    private fun seen(): JSONObject =
        runCatching { JSONObject(prefs.getString("seen", "{}")!!) }.getOrElse { JSONObject() }

    fun wasInStock(id: String): Boolean = seen().optBoolean(id, false)

    fun isFirstRun(): Boolean = seen().length() == 0

    fun remember(products: List<Product>) {
        val s = seen()
        for (p in products) s.put(p.id, p.inStock)
        prefs.edit().putString("seen", s.toString()).apply()
    }

    /** Has this product ever been recorded? Distinguishes new from restocked. */
    fun everSeen(id: String): Boolean = seen().has(id)

    fun forgetAll() = prefs.edit().remove("seen").apply()

    // ---- activity log ---------------------------------------------------
    // A visible record of what each check did. Without it, "no notifications"
    // is indistinguishable from "the watcher is dead", which is the single
    // most confusing failure this app can have.

    fun log(line: String) {
        val stamp = java.text.SimpleDateFormat("dd MMM HH:mm", java.util.Locale.getDefault())
            .format(java.util.Date())
        val entries = logLines().toMutableList()
        entries.add(0, "$stamp  $line")
        while (entries.size > 60) entries.removeAt(entries.size - 1)
        prefs.edit().putString("log", org.json.JSONArray(entries).toString()).apply()
    }

    fun logLines(): List<String> = runCatching {
        val arr = org.json.JSONArray(prefs.getString("log", "[]")!!)
        (0 until arr.length()).map { arr.getString(it) }
    }.getOrDefault(emptyList())

    fun clearLog() = prefs.edit().remove("log").apply()
}
