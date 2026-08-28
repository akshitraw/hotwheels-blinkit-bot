package com.akshit.hotwheels

import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * Fires the same Blinkit request through every entry of [Profiles.all] and
 * reports which are accepted. Kept in the app because Cloudflare's rules shift,
 * and when alerts go quiet this is the fastest way to find out why.
 */
object Diagnostics {

    private const val URL_SEARCH =
        "https://blinkit.com/v1/layout/search?q=hot%20wheels&search_type=type_to_search"
    private const val BODY =
        """{"applied_filters":null,"previous_search_query":"","processed_rails":{}}"""

    private fun attempt(profile: Profiles.Profile, lat: String, lon: String): Pair<Boolean, String> {
        val c = (URL(URL_SEARCH).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 15_000
            doOutput = true
        }
        return try {
            for ((k, v) in profile.build(lat, lon)) c.setRequestProperty(k, v)
            c.outputStream.use { it.write(BODY.toByteArray()) }
            val code = c.responseCode
            val body = (if (code in 200..299) c.inputStream else c.errorStream)
                ?.bufferedReader()?.use(BufferedReader::readText).orEmpty()
            when {
                code == 200 -> {
                    val n = runCatching { Blinkit.parse(JSONObject(body)).size }.getOrDefault(-1)
                    if (n > 0) true to "PASS — $n products" else false to "200 but $n products parsed"
                }
                code == 403 -> false to "FAIL — 403 blocked by Cloudflare"
                else -> false to "FAIL — HTTP $code ${body.take(60).replace("\n", " ")}"
            }
        } catch (e: Exception) {
            false to "ERROR — ${e.javaClass.simpleName}: ${e.message?.take(70)}"
        } finally {
            c.disconnect()
        }
    }

    fun run(lat: String, lon: String, activeIndex: Int): String {
        val sb = StringBuilder()
        sb.append("Active profile: ").append(activeIndex).append(" — ")
            .append(Profiles.nameOf(activeIndex)).append("\n\n")
        var firstPass = -1
        Profiles.all.forEachIndexed { i, p ->
            val (ok, result) = attempt(p, lat, lon)
            if (ok && firstPass < 0) firstPass = i
            sb.append(if (i == activeIndex) "▶ " else "  ")
                .append(i).append(". ").append(p.name).append("\n     ")
                .append(result).append("\n\n")
            Thread.sleep(700)
        }
        sb.append(
            when {
                firstPass < 0 ->
                    "Every shape was refused. This network can't reach Blinkit's API — " +
                        "try switching between wifi and mobile data."
                firstPass == activeIndex -> "The active profile works. Nothing to change."
                else -> "Profile $firstPass works. The watcher switches to a working " +
                    "profile automatically on its next check."
            }
        )
        return sb.toString()
    }
}
