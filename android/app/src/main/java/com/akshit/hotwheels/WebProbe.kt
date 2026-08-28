package com.akshit.hotwheels

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * Looks inside Zepto and Swiggy so a scraper can be written for them.
 *
 * Both sit behind AWS WAF challenges that a plain HTTP client can't pass, and
 * Swiggy's JSON API returns 403 even from inside its own page. A hidden WebView
 * does render both (confirmed on-device), so the remaining unknowns are
 * mechanical: how each site stores the chosen delivery location, and what a
 * product card looks like in the DOM.
 *
 * This dumps exactly that. It reads nothing personal — location keys and
 * product markup only.
 */
object WebProbe {

    private const val SETTLE_MS = 11_000L

    private class Target(val site: String, val url: String)

    private val targets = listOf(
        Target("Zepto", "https://www.zepto.com/search?query=hot+wheels"),
        Target("Swiggy", "https://www.swiggy.com/instamart/search?custom_back=true&query=hot%20wheels"),
    )

    /**
     * Reports (a) storage keys that look location-related, so we can set the
     * delivery address, and (b) the smallest DOM elements containing a product
     * name, so we can write selectors.
     */
    private val INSPECT_JS = """
        (function(){
          function t(s,n){ s=String(s==null?'':s); return s.length>n ? s.slice(0,n)+'~' : s; }
          var out = {ls:{}, ck:{}, cards:[], flags:[]};

          for (var i=0;i<localStorage.length;i++){
            var k = localStorage.key(i);
            if (/pos|loc|addr|store|lat|lng|lon|geo|place|pin/i.test(k))
              out.ls[k] = t(localStorage.getItem(k), 220);
          }
          document.cookie.split(';').forEach(function(c){
            var i = c.indexOf('='); if (i < 0) return;
            var k = c.slice(0,i).trim();
            if (/loc|addr|store|lat|lng|lon|geo|place|pin|tid|sid/i.test(k)) {
              var v = c.slice(i+1); try { v = decodeURIComponent(v); } catch(e){}
              out.ck[k] = t(v, 220);
            }
          });

          var nodes = document.querySelectorAll('a,div,li,article');
          for (var j=0; j<nodes.length && out.cards.length<3; j++){
            var el = nodes[j], txt = el.innerText || '';
            if (!/hot\s*wheels/i.test(txt)) continue;
            if (txt.length > 420) continue;
            if (el.querySelectorAll('*').length > 45) continue;
            out.cards.push({
              tag: el.tagName,
              cls: t(el.className, 150),
              href: t(el.getAttribute('href') || '', 90),
              text: t(txt.replace(/\s+/g,' '), 240),
              html: t(el.outerHTML.replace(/\s+/g,' '), 620)
            });
          }

          var body = document.body ? document.body.innerText : '';
          out.flags = (body.match(/sold out|out of stock|notify me|unavailable|coming soon/gi) || []).slice(0,6);
          out.len = body.length;
          return JSON.stringify(out);
        })()
    """.trimIndent()

    fun run(activity: Activity, onDone: (String) -> Unit) {
        val ui = Handler(Looper.getMainLooper())
        val report = StringBuilder("Inspection for building Zepto/Swiggy support\n\n")
        val queue = ArrayDeque(targets)

        fun next() {
            val target = queue.removeFirstOrNull() ?: return onDone(report.toString())
            inspect(activity, target) { text ->
                report.append(text).append("\n\n")
                ui.postDelayed({ next() }, 600)
            }
        }
        next()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun inspect(activity: Activity, target: Target, onResult: (String) -> Unit) {
        val ui = Handler(Looper.getMainLooper())
        val web = WebView(activity)
        var done = false

        fun report(text: String) {
            if (done) return
            done = true
            runCatching { web.stopLoading(); web.destroy() }
            onResult("===== ${target.site} =====\n$text")
        }

        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            loadsImagesAutomatically = false
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)

        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                ui.postDelayed({
                    if (done) return@postDelayed
                    web.evaluateJavascript(INSPECT_JS) { raw ->
                        report(decode(raw))
                    }
                }, SETTLE_MS)
            }
        }

        ui.postDelayed({ report("timed out — page never finished loading") }, SETTLE_MS + 25_000L)
        web.loadUrl(target.url)
    }

    /**
     * evaluateJavascript hands back a JSON *string literal*, so the payload
     * arrives double-encoded. Unwrap it into something readable rather than
     * making the reader decipher backslashes.
     */
    private fun decode(raw: String): String {
        val unquoted = runCatching {
            org.json.JSONArray("[$raw]").getString(0)
        }.getOrElse { return "could not decode: ${raw.take(120)}" }

        val o = runCatching { org.json.JSONObject(unquoted) }
            .getOrElse { return unquoted.take(2500) }

        return buildString {
            append("bodyLen=").append(o.optInt("len")).append("\n")

            append("\n-- location storage --\n")
            val ls = o.optJSONObject("ls")
            if (ls == null || ls.length() == 0) append("  (none found)\n")
            else for (k in ls.keys()) append("  LS ").append(k).append(" = ").append(ls.optString(k)).append("\n")
            val ck = o.optJSONObject("ck")
            if (ck != null) for (k in ck.keys()) append("  CK ").append(k).append(" = ").append(ck.optString(k)).append("\n")

            append("\n-- stock wording seen --\n  ")
            val flags = o.optJSONArray("flags")
            append(if (flags == null || flags.length() == 0) "(none)"
                   else (0 until flags.length()).joinToString(", ") { flags.optString(it) })
            append("\n")

            append("\n-- product cards --\n")
            val cards = o.optJSONArray("cards")
            if (cards == null || cards.length() == 0) append("  (no card matched)\n")
            else for (i in 0 until cards.length()) {
                val c = cards.optJSONObject(i) ?: continue
                append("  [").append(i).append("] <").append(c.optString("tag")).append("> class=")
                append(c.optString("cls")).append("\n")
                if (c.optString("href").isNotEmpty())
                    append("      href=").append(c.optString("href")).append("\n")
                append("      text: ").append(c.optString("text")).append("\n")
                append("      html: ").append(c.optString("html")).append("\n")
            }
        }
    }
}
