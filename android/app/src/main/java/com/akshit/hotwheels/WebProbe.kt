package com.akshit.hotwheels

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject

/**
 * Can this phone read Zepto and Swiggy at all?
 *
 * Both sit behind AWS WAF with a JavaScript challenge, so the plain HTTP client
 * that works for Blinkit is refused outright. The only route is a real browser
 * engine that solves the challenge itself — a hidden WebView. But WAFs also
 * fingerprint WebViews, so whether this works is an empirical question about
 * *this device*, not something to reason about from the outside.
 *
 * This probe answers it: load each site, wait for the challenge and the app to
 * render, then report how much content came back and whether product names are
 * visible. If pages render with Hot Wheels in them, scraping is viable and
 * worth building. If they stay blank, these two platforms are out of reach.
 */
object WebProbe {

    private const val SETTLE_MS = 9_000L   // time given to WAF challenge + render
    private const val API_MS = 6_000L      // extra time for the in-page API call

    /** WebView's stock UA contains "; wv", which is a trivial automation tell. */
    private fun stripWv(ua: String) = ua.replace("; wv", "")

    private class Target(
        val site: String,
        val url: String,
        /** JS returning a promise-free kickoff; result lands on window.__probeApi */
        val apiJs: String?,
    )

    private val targets = listOf(
        Target(
            "Zepto",
            "https://www.zepto.com/search?query=hot+wheels",
            null, // its API is on another host; DOM is the signal that matters
        ),
        Target(
            "Swiggy Instamart",
            "https://www.swiggy.com/instamart/search?custom_back=true&query=hot%20wheels",
            """
            window.__probeApi = 'pending';
            fetch('/api/instamart/search?pageNumber=0&searchResultsOffset=0&limit=20' +
                  '&query=hot%20wheels&ageConsent=false&pageType=INSTAMART_SEARCH_PAGE',
                  {headers:{'accept':'application/json'}, credentials:'include'})
              .then(function(r){ return r.text().then(function(t){
                  window.__probeApi = 'HTTP ' + r.status + ', ' + t.length + ' bytes';
              });})
              .catch(function(e){ window.__probeApi = 'blocked: ' + e; });
            """.trimIndent(),
        ),
    )

    private val READ_JS = """
        (function(){
          var t = document.body ? document.body.innerText : '';
          var hits = (t.match(/hot\s*wheels/gi) || []).length;
          return JSON.stringify({
            len: t.length,
            hits: hits,
            waf: /aws-waf-token/.test(document.cookie),
            api: (typeof window.__probeApi === 'undefined') ? 'n/a' : String(window.__probeApi),
            sample: t.replace(/\s+/g,' ').slice(0, 160)
          });
        })()
    """.trimIndent()

    /**
     * Runs every target with both user agents and hands back a printable report.
     * Must be called from the main thread; [onDone] is called there too.
     */
    fun run(activity: Activity, onDone: (String) -> Unit) {
        val ui = Handler(Looper.getMainLooper())
        val report = StringBuilder()
        val jobs = ArrayDeque<Pair<Target, Boolean>>()   // target, stripWv
        for (t in targets) { jobs.add(t to false); jobs.add(t to true) }

        fun finish() {
            val text = report.toString()
            val worked = Regex("hits=([1-9]\\d*)").containsMatchIn(text)
            report.append("\n")
            report.append(
                if (worked)
                    "VERDICT: at least one page rendered Hot Wheels products. " +
                        "Scraping through a WebView is viable — tell Claude and it'll build it."
                else
                    "VERDICT: no page rendered products. Their WAF is blocking the WebView too, " +
                        "so Zepto and Swiggy can't be watched from this app."
            )
            onDone(report.toString())
        }

        fun next() {
            val job = jobs.removeFirstOrNull() ?: return finish()
            val (target, strip) = job
            probe(activity, target, strip) { line ->
                report.append(line).append("\n\n")
                ui.postDelayed({ next() }, 500)
            }
        }
        next()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun probe(
        activity: Activity,
        target: Target,
        stripWv: Boolean,
        onResult: (String) -> Unit,
    ) {
        val ui = Handler(Looper.getMainLooper())
        val web = WebView(activity)
        var httpStatus: Int? = null
        var done = false

        fun report(text: String) {
            if (done) return
            done = true
            runCatching { web.stopLoading(); web.destroy() }
            onResult(text)
        }

        val uaLabel = if (stripWv) "UA without \"wv\"" else "default WebView UA"
        val header = "${target.site} — $uaLabel"

        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            loadsImagesAutomatically = false      // we only need text
            userAgentString = if (stripWv) stripWv(userAgentString) else userAgentString
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)

        web.webViewClient = object : WebViewClient() {
            override fun onReceivedHttpError(
                view: WebView?, request: WebResourceRequest?, errorResponse: WebResourceResponse?,
            ) {
                // Only care about the main document, not sub-resources.
                if (request?.isForMainFrame == true) httpStatus = errorResponse?.statusCode
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                target.apiJs?.let { web.evaluateJavascript(it, null) }
                ui.postDelayed({
                    if (done) return@postDelayed
                    web.evaluateJavascript(READ_JS) { raw ->
                        val json = runCatching {
                            JSONObject(
                                raw.trim('"').replace("\\\"", "\"").replace("\\\\", "\\")
                            )
                        }.getOrNull()
                        if (json == null) {
                            report("$header\n   could not read the page (raw: ${raw.take(60)})")
                            return@evaluateJavascript
                        }
                        val len = json.optInt("len")
                        val hits = json.optInt("hits")
                        report(buildString {
                            append(header).append("\n   ")
                            append(if (httpStatus != null) "http=$httpStatus " else "")
                            append("bodyLen=").append(len)
                            append(" hits=").append(hits)
                            append(" waf=").append(json.optBoolean("waf"))
                            if (target.apiJs != null) append("\n   api: ").append(json.optString("api"))
                            append("\n   \"").append(json.optString("sample")).append("\"")
                        })
                    }
                }, if (target.apiJs != null) SETTLE_MS + API_MS else SETTLE_MS)
            }
        }

        // Hard ceiling in case the page never finishes loading at all.
        ui.postDelayed({ report("$header\n   timed out — page never finished loading") },
            SETTLE_MS + API_MS + 20_000L)

        web.loadUrl(target.url)
    }
}
