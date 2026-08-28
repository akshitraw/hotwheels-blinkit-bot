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

    var lastStatus: String
        get() = prefs.getString("status", "Not started yet")!!
        set(v) = prefs.edit().putString("status", v).apply()

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

    fun forgetAll() = prefs.edit().remove("seen").apply()
}
