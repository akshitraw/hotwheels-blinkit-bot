package com.akshit.hotwheels

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL

/** One product as Blinkit describes it. */
data class Product(
    val id: String,
    val name: String,
    val price: String,
    val mrp: String,
    val unit: String,
    val qty: Int,
    val image: String,
    val inStock: Boolean,
) {
    val url: String get() = "https://blinkit.com/prn/${slugify(name)}/prid/$id"

    companion object {
        fun slugify(name: String): String {
            val sb = StringBuilder()
            for (ch in name.lowercase()) {
                if (ch.isLetterOrDigit()) sb.append(ch)
                else if (sb.isNotEmpty() && sb.last() != '-') sb.append('-')
            }
            return sb.toString().trim('-').take(80).ifEmpty { "p" }
        }
    }
}

class BlinkitError(message: String) : Exception(message)

object Blinkit {

    private const val BASE = "https://blinkit.com"
    private const val SEARCH = "/v1/layout/search"
    private const val BODY =
        """{"applied_filters":null,"previous_search_query":"","processed_rails":{}}"""

    /** Which entry of [Profiles.all] to send. Set from saved settings at startup. */
    @Volatile var profileIndex: Int = 0

    /** Called when a fallback finds a different profile that works, so it can be saved. */
    @Volatile var onProfileChanged: ((Int) -> Unit)? = null

    private class Response(val code: Int, val body: String)

    private fun once(url: String, lat: String, lon: String, profile: Int): Response {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 30_000
            readTimeout = 30_000
            doOutput = true
        }
        return try {
            val headers = Profiles.all[profile.coerceIn(Profiles.all.indices)].build(lat, lon)
            for ((k, v) in headers) c.setRequestProperty(k, v)
            c.outputStream.use { it.write(BODY.toByteArray()) }
            val code = c.responseCode
            // Left to HttpURLConnection: it advertises gzip and decodes it for us.
            val body = (if (code in 200..299) c.inputStream else c.errorStream)
                ?.bufferedReader()?.use(BufferedReader::readText).orEmpty()
            Response(code, body)
        } finally {
            c.disconnect()
        }
    }

    /**
     * A timeout on a phone usually is not Blinkit's fault.
     *
     * With the screen off the wifi radio drops into power save, and the socket
     * pooled from the last check is dead by the time we reuse it. The first
     * request hangs, wakes the radio, and a retry moments later succeeds. So a
     * timeout gets retried on a fresh connection rather than counted as a
     * failure — that alone turned "works 3-4 times then stops" into steady
     * running.
     */
    private fun attempt(url: String, lat: String, lon: String, profile: Int): Response {
        var last: Exception? = null
        for (i in 0 until 3) {
            try {
                return once(url, lat, lon, profile)
            } catch (e: java.net.SocketTimeoutException) {
                last = e
            } catch (e: java.io.IOException) {
                last = e
            }
            Thread.sleep(2_500L * (i + 1))
        }
        throw BlinkitError(
            "no reply after 3 tries (${last?.javaClass?.simpleName ?: "timeout"}) — " +
                "network asleep or unreachable"
        )
    }

    private fun post(path: String, lat: String, lon: String): JSONObject {
        val url = if (path.startsWith("/")) BASE + path else path

        var response = attempt(url, lat, lon, profileIndex)

        // Cloudflare rules shift over time. Rather than fail outright, walk the
        // other known request shapes and adopt whichever one is accepted.
        if (response.code == 403) {
            for (i in Profiles.all.indices) {
                if (i == profileIndex) continue
                val retry = runCatching { attempt(url, lat, lon, i) }.getOrNull() ?: continue
                if (retry.code == 200) {
                    profileIndex = i
                    onProfileChanged?.invoke(i)
                    response = retry
                    break
                }
                Thread.sleep(400)
            }
        }

        if (response.code == 400 && response.body.contains("not serviceable", true)) {
            throw BlinkitError("This location is not serviceable — check the coordinates.")
        }
        if (response.code != 200) {
            throw BlinkitError(
                if (response.code == 403) "403 — Blinkit refused every request shape from this network"
                else "HTTP ${response.code} from Blinkit"
            )
        }
        return runCatching { JSONObject(response.body) }
            .getOrElse { throw BlinkitError("Blinkit sent something that isn't JSON") }
    }

    /** Depth-first walk for anything shaped like a product card. */
    private fun collect(node: Any?, out: MutableList<JSONObject>, depth: Int = 0) {
        if (depth > 14) return
        when (node) {
            is JSONObject -> {
                if (node.has("product_id") && node.has("inventory") && node.has("display_name")) {
                    out.add(node); return
                }
                for (key in node.keys()) collect(node.opt(key), out, depth + 1)
            }
            is JSONArray -> for (i in 0 until node.length()) collect(node.opt(i), out, depth + 1)
        }
    }

    private fun text(o: Any?): String = when (o) {
        is JSONObject -> o.optString("text", "").trim()
        null -> ""
        else -> o.toString().trim()
    }

    fun parse(payload: JSONObject): List<Product> {
        val cards = mutableListOf<JSONObject>()
        collect(payload.opt("response") ?: payload, cards)
        return cards.mapNotNull { c ->
            val id = c.optString("product_id").trim()
            if (id.isEmpty()) return@mapNotNull null
            val qty = c.optInt("inventory", 0)
            val state = c.optString("product_state", "").lowercase()
            val inStock = qty > 0 && !c.optBoolean("is_sold_out", false) &&
                (state.isEmpty() || state == "available")
            Product(
                id = id,
                name = text(c.opt("display_name")).ifEmpty { text(c.opt("name")) },
                price = text(c.opt("normal_price")),
                mrp = text(c.opt("mrp_price")),
                unit = text(c.opt("variant")),
                qty = qty,
                image = c.optJSONObject("image")?.optString("url", "").orEmpty(),
                inStock = inStock,
            )
        }
    }

    /**
     * Search several comma-separated terms and merge the results.
     * "hot wheels, matchbox" is two searches, not one product name.
     */
    fun searchAll(lat: String, lon: String, terms: List<String>, maxPages: Int = 4): List<Product> {
        val merged = LinkedHashMap<String, Product>()
        for (term in terms.ifEmpty { listOf("hot wheels") }) {
            for (p in search(lat, lon, term, maxPages)) merged.putIfAbsent(p.id, p)
        }
        return merged.values.toList()
    }

    /** Walk every page of results for one query. */
    fun search(lat: String, lon: String, query: String, maxPages: Int = 4): List<Product> {
        var path = "$SEARCH?q=${java.net.URLEncoder.encode(query, "UTF-8")}&search_type=type_to_search"
        val seen = LinkedHashMap<String, Product>()
        repeat(maxPages) {
            val payload = post(path, lat, lon)
            for (p in parse(payload)) seen.putIfAbsent(p.id, p)
            val next = payload.optJSONObject("response")
                ?.optJSONObject("pagination")?.optString("next_url").orEmpty()
            if (next.isEmpty()) return seen.values.toList()
            path = next
            Thread.sleep(900)
        }
        return seen.values.toList()
    }
}
