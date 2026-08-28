package com.akshit.hotwheels

/**
 * The different request shapes we know how to send to Blinkit.
 *
 * Blinkit sits behind Cloudflare, which scores a client on how coherent it
 * looks. Counter-intuitively, the shape that works from Android is the plainest
 * one: no User-Agent, no Origin, no sec-ch-* headers. Claiming to be Chrome
 * while running on Android's OkHttp stack is a *worse* signal than making no
 * claim at all, because the declared identity and the TLS/HTTP2 fingerprint
 * disagree. Verified on-device: every browser-imitating profile below is
 * refused with 403; BARE passes.
 *
 * They stay in this file rather than inline so the watcher can fall back
 * through them automatically if Cloudflare ever retunes its rules.
 */
object Profiles {

    class Profile(
        val name: String,
        val build: (lat: String, lon: String) -> LinkedHashMap<String, String>,
    )

    private const val UA_DESKTOP =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/127.0.0.0 Safari/537.36"
    private const val UA_ANDROID =
        "Mozilla/5.0 (Linux; Android 16; Pixel 9) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/140.0.0.0 Mobile Safari/537.36"

    private fun base(lat: String, lon: String) = linkedMapOf(
        "Content-Type" to "application/json",
        "lat" to lat,
        "lon" to lon,
    )

    /** Index 0 is the default and the one confirmed working on-device. */
    val all = listOf(

        Profile("bare: content-type + lat/lon only") { lat, lon -> base(lat, lon) },

        Profile("minimal + Android UA") { lat, lon ->
            base(lat, lon).apply { put("User-Agent", UA_ANDROID) }
        },

        Profile("no sec-ch, desktop UA, no origin") { lat, lon ->
            base(lat, lon).apply {
                put("Accept", "*/*")
                put("User-Agent", UA_DESKTOP)
                put("app_client", "consumer_web")
            }
        },

        Profile("Android Chrome UA, matching sec-ch") { lat, lon ->
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

        Profile("desktop UA + sec-ch") { lat, lon ->
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

        Profile("gzip allowed, Android UA") { lat, lon ->
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

    fun nameOf(index: Int): String = all.getOrNull(index)?.name ?: "unknown"
}
