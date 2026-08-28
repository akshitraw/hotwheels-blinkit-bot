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

    // The desktop Chrome UA is what Blinkit's web API expects. Header names are
    // capitalised deliberately: Cloudflare scores lowercase, library-style
    // headers worse, and this exact shape is the one verified to get through.
    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/127.0.0.0 Safari/537.36"

    private const val BODY = """{"applied_filters":null,"previous_search_query":"","processed_rails":{}}"""

    private fun post(path: String, lat: String, lon: String): JSONObject {
        val url = URL(if (path.startsWith("/")) BASE + path else path)
        val c = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 20_000
            readTimeout = 20_000
            doOutput = true
            instanceFollowRedirects = true
            setRequestProperty("Accept", "*/*")
            setRequestProperty("Accept-Language", "en-US,en;q=0.9")
            // Ask for no compression: it matches the request shape that works,
            // and saves us decoding gzip by hand.
            setRequestProperty("Accept-Encoding", "identity")
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Origin", BASE)
            setRequestProperty("Referer", "$BASE/")
            setRequestProperty("User-Agent", UA)
            setRequestProperty("sec-ch-ua", "\"Chromium\";v=\"127\", \"Not)A;Brand\";v=\"99\"")
            setRequestProperty("sec-ch-ua-mobile", "?0")
            setRequestProperty("sec-ch-ua-platform", "\"Windows\"")
            setRequestProperty("sec-fetch-dest", "empty")
            setRequestProperty("sec-fetch-mode", "cors")
            setRequestProperty("sec-fetch-site", "same-origin")
            setRequestProperty("app_client", "consumer_web")
            setRequestProperty("lat", lat)
            setRequestProperty("lon", lon)
        }
        try {
            c.outputStream.use { it.write(BODY.toByteArray()) }
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)
                ?.bufferedReader()?.use(BufferedReader::readText).orEmpty()
            if (code == 400 && text.contains("not serviceable", true)) {
                throw BlinkitError("This location is not serviceable. Check the coordinates.")
            }
            if (code != 200) {
                throw BlinkitError("HTTP $code from Blinkit${if (code == 403) " (blocked — is this on mobile data or wifi?)" else ""}")
            }
            return JSONObject(text)
        } finally {
            c.disconnect()
        }
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
