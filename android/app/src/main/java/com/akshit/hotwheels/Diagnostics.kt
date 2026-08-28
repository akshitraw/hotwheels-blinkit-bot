package com.akshit.hotwheels

import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * Tries the same Blinkit request several different ways and reports which one
 * works. Blinkit sits behind Cloudflare, which scores clients on the exact
 * shape of the request — header set, header casing, declared identity. On a PC
 * Python's urllib gets through while the requests library is refused, so the
 * differences here are small on purpose.
 */
object Diagnostics {

    private const val URL_SEARCH =
        "https://blinkit.com/v1/layout/search?q=hot%20wheels&search_type=type_to_search"
    private const val BODY =
        """{"applied_filters":null,"previous_search_query":"","processed_rails":{}}"""

    private const val UA_DESKTOP =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/127.0.0.0 Safari/537.36"
    private const val UA_ANDROID =
        "Mozilla/5.0 (Linux; Android 16; Pixel 9) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/140.0.0.0 Mobile Safari/537.36"

    class Profile(val name: String, val headers: (String, String) -> LinkedHashMap<String, String>)

    private fun base(lat: String, lon: String) = linkedMapOf(
        "Content-Type" to "application/json",
        "lat" to lat,
        "lon" to lon,
    )

    val profiles = listOf(
        Profile("1. desktop UA + sec-ch (current)") { lat, lon ->
            base(lat, lon).apply {
                put("Accept", "*/*")
                put("Accept-Language", "en-US,en;q=0.9")
                put("Accept-Encoding", "identity")
                put("Origin", "https://blinkit.com")
                put("Referer", "https://blinkit.com/")
                put("User-Agent", UA_DESKTOP)
                put("sec-ch-ua", "\"Chromium\";v=\"127\", \"Not)A;Brand\";v=\"99\"")
                put("sec-ch-ua-mobile", "?0")
                put("sec-ch-ua-platform", "\"Windows\"")
                put("sec-fetch-dest", "empty")
                put("sec-fetch-mode", "cors")
                put("sec-fetch-site", "same-origin")
                put("app_client", "consumer_web")
            }
        },
        Profile("2. Android Chrome UA, matching sec-ch") { lat, lon ->
            base(lat, lon).apply {
                put("Accept", "*/*")
                put("Accept-Language", "en-IN,en;q=0.9")
                put("Origin", "https://blinkit.com")
                put("Referer", "https://blinkit.com/")
                put("User-Agent", UA_ANDROID)
                put("sec-ch-ua", "\"Chromium\";v=\"140\", \"Not=A?Brand\";v=\"24\"")
                put("sec-ch-ua-mobile", "?1")
                put("sec-ch-ua-platform", "\"Android\"")
                put("sec-fetch-dest", "empty")
                put("sec-fetch-mode", "cors")
                put("sec-fetch-site", "same-origin")
                put("app_client", "consumer_web")
            }
        },
        Profile("3. minimal: type, UA, lat, lon") { lat, lon ->
            base(lat, lon).apply { put("User-Agent", UA_ANDROID) }
        },
        Profile("4. no sec-ch, desktop UA, no origin") { lat, lon ->
            base(lat, lon).apply {
                put("Accept", "*/*")
                put("User-Agent", UA_DESKTOP)
                put("app_client", "consumer_web")
            }
        },
        Profile("5. bare: content-type + lat/lon only") { lat, lon -> base(lat, lon) },
        Profile("6. gzip allowed, Android UA") { lat, lon ->
            base(lat, lon).apply {
                put("Accept", "*/*")
                put("Accept-Encoding", "gzip, deflate, br")
                put("Origin", "https://blinkit.com")
                put("Referer", "https://blinkit.com/")
                put("User-Agent", UA_ANDROID)
                put("app_client", "consumer_web")
            }
        },
    )

    private fun countProducts(text: String): Int = runCatching {
        Blinkit.parse(JSONObject(text)).size
    }.getOrDefault(-1)

    private fun attempt(profile: Profile, lat: String, lon: String): String {
        val c = (URL(URL_SEARCH).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 15_000
            doOutput = true
        }
        return try {
            for ((k, v) in profile.headers(lat, lon)) c.setRequestProperty(k, v)
            c.outputStream.use { it.write(BODY.toByteArray()) }
            val code = c.responseCode
            val body = (if (code in 200..299) c.inputStream else c.errorStream)
                ?.bufferedReader()?.use(BufferedReader::readText).orEmpty()
            when {
                code == 200 -> {
                    val n = countProducts(body)
                    if (n > 0) "PASS — $n products" else "200 but $n products parsed"
                }
                code == 403 -> "FAIL — 403 blocked by Cloudflare"
                else -> "FAIL — HTTP $code ${body.take(60).replace("\n", " ")}"
            }
        } catch (e: Exception) {
            "ERROR — ${e.javaClass.simpleName}: ${e.message?.take(70)}"
        } finally {
            c.disconnect()
        }
    }

    /** Can we reach blinkit.com at all on this network? */
    private fun reachability(): String = runCatching {
        val c = (URL("https://blinkit.com/").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"; connectTimeout = 12_000; readTimeout = 12_000
            setRequestProperty("User-Agent", UA_ANDROID)
        }
        val code = c.responseCode
        c.disconnect()
        "homepage GET -> HTTP $code"
    }.getOrElse { "homepage GET -> ${it.javaClass.simpleName}: ${it.message?.take(60)}" }

    fun run(lat: String, lon: String): String {
        val sb = StringBuilder()
        sb.append("Network: ").append(reachability()).append("\n\n")
        var anyPass = false
        for (p in profiles) {
            val result = attempt(p, lat, lon)
            if (result.startsWith("PASS")) anyPass = true
            sb.append(p.name).append("\n   ").append(result).append("\n\n")
            Thread.sleep(700)
        }
        sb.append(
            if (anyPass) "At least one profile works — tell Claude which number and the app will switch to it."
            else "Every profile was refused. If the homepage GET also failed, this network can't reach Blinkit at all; try toggling wifi/mobile data."
        )
        return sb.toString()
    }
}
