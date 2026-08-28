#!/usr/bin/env python3
"""
Figure out why Blinkit is returning 403.

Tries the same request through several different HTTP clients. They differ in
what headers they send AND in their TLS handshake fingerprint, which is what
bot-protection systems usually key on. Whichever one succeeds tells us the fix.

    python diagnose.py
"""
from __future__ import annotations

import json
import os
import re
import subprocess
import sys
import tempfile

URL = "https://blinkit.com/v1/layout/search?q=hot%20wheels&search_type=type_to_search"
BODY = {"applied_filters": None, "previous_search_query": "", "processed_rails": {}}

LAT = os.environ.get("BLINKIT_LAT", "28.4634")
LON = os.environ.get("BLINKIT_LON", "77.0768")

UA = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/127.0.0.0 Safari/537.36")

MINIMAL = {
    "content-type": "application/json",
    "user-agent": UA,
    "lat": LAT,
    "lon": LON,
}

FULL = {
    "accept": "*/*",
    "accept-language": "en-US,en;q=0.9",
    "accept-encoding": "gzip, deflate, br",
    "content-type": "application/json",
    "origin": "https://blinkit.com",
    "referer": "https://blinkit.com/",
    "user-agent": UA,
    "sec-ch-ua": '"Chromium";v="127", "Not)A;Brand";v="99"',
    "sec-ch-ua-mobile": "?0",
    "sec-ch-ua-platform": '"Windows"',
    "sec-fetch-dest": "empty",
    "sec-fetch-mode": "cors",
    "sec-fetch-site": "same-origin",
    "app_client": "consumer_web",
    "lat": LAT,
    "lon": LON,
}


def describe(status: int, text: str) -> str:
    """Say who sent this response, from the shape of the body."""
    low = text.lower()
    title = ""
    m = re.search(r"<title[^>]*>(.*?)</title>", text, re.S | re.I)
    if m:
        title = " ".join(m.group(1).split())[:90]
    who = []
    if "purl.org/rss" in low or "drupal" in low:
        who.append("Drupal site (NOT Blinkit — something is intercepting)")
    if "cloudflare" in low or "cf-ray" in low:
        who.append("Cloudflare")
    if "akamai" in low or "reference #" in low:
        who.append("Akamai")
    if "access denied" in low or "forbidden" in low:
        who.append("generic deny page")
    if any(w in low for w in ("blocked", "not permitted", "policy", "firewall", "proxy")):
        who.append("filter/proxy block page")
    bits = f"HTTP {status}"
    if title:
        bits += f' | title="{title}"'
    if who:
        bits += " | looks like: " + ", ".join(who)
    return bits


def show_ok(payload: dict) -> str:
    try:
        snips = payload["response"]["snippets"]
        names = [s["data"]["display_name"]["text"]
                 for s in snips if s.get("data", {}).get("display_name")]
        return f"OK — {len(names)} products, e.g. {names[0]!r}"
    except (KeyError, IndexError, TypeError):
        return "OK — 200 but unexpected shape"


results: dict[str, str] = {}


def record(name: str, outcome: str, verdict: str) -> None:
    results[name] = verdict
    print(f"\n[{name}]\n  {outcome}")


# ---------------------------------------------------------------- 1 & 2
def try_requests(label: str, headers: dict) -> None:
    try:
        import requests
    except ImportError:
        record(label, "requests not installed", "skip")
        return
    try:
        r = requests.post(URL, json=BODY, headers=headers, timeout=25)
        if r.status_code == 200:
            record(label, show_ok(r.json()), "pass")
        else:
            record(label, describe(r.status_code, r.text), "fail")
            srv = {k: v for k, v in r.headers.items()
                   if k.lower() in ("server", "cf-ray", "via", "x-cache", "x-served-by")}
            if srv:
                print(f"  response headers: {srv}")
    except Exception as e:  # noqa: BLE001 - diagnostics
        record(label, f"{type(e).__name__}: {e}", "error")


# ---------------------------------------------------------------- 3
def try_urllib() -> None:
    import urllib.request
    import urllib.error
    req = urllib.request.Request(
        URL, data=json.dumps(BODY).encode(),
        headers={k: v for k, v in FULL.items() if k != "accept-encoding"},
        method="POST")
    try:
        with urllib.request.urlopen(req, timeout=25) as resp:
            record("urllib", show_ok(json.loads(resp.read())), "pass")
    except urllib.error.HTTPError as e:
        record("urllib", describe(e.code, e.read().decode("utf-8", "replace")), "fail")
    except Exception as e:  # noqa: BLE001
        record("urllib", f"{type(e).__name__}: {e}", "error")


# ---------------------------------------------------------------- 4
def try_curl() -> None:
    """Windows 10+ ships curl.exe. Different TLS stack (Schannel) entirely."""
    tmp = os.path.join(tempfile.gettempdir(), "blinkit_body.json")
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(BODY, f)
    cmd = ["curl", "-s", "-o", "-", "-w", "\n__STATUS__%{http_code}",
           "-X", "POST", URL, "--max-time", "25", "-d", f"@{tmp}"]
    for k, v in FULL.items():
        if k == "accept-encoding":
            continue
        cmd += ["-H", f"{k}: {v}"]
    try:
        p = subprocess.run(cmd, capture_output=True, text=True, timeout=40)
    except FileNotFoundError:
        record("curl.exe", "curl not found on PATH", "skip")
        return
    except subprocess.TimeoutExpired:
        record("curl.exe", "timed out", "error")
        return
    out = p.stdout
    status = 0
    if "__STATUS__" in out:
        out, _, s = out.rpartition("__STATUS__")
        status = int(s.strip() or 0)
    if status == 200:
        try:
            record("curl.exe", show_ok(json.loads(out)), "pass")
        except json.JSONDecodeError:
            record("curl.exe", "200 but body was not JSON", "fail")
    else:
        record("curl.exe", describe(status, out), "fail")


# ---------------------------------------------------------------- 5
def try_curl_cffi() -> None:
    """Impersonates a real Chrome TLS/JA3 fingerprint. The usual fix."""
    try:
        from curl_cffi import requests as creq
    except ImportError:
        record("curl_cffi", "not installed — run:  pip install curl_cffi", "skip")
        return
    try:
        r = creq.post(URL, json=BODY, headers=FULL, timeout=25, impersonate="chrome")
        if r.status_code == 200:
            record("curl_cffi (chrome TLS)", show_ok(r.json()), "pass")
        else:
            record("curl_cffi (chrome TLS)", describe(r.status_code, r.text), "fail")
    except Exception as e:  # noqa: BLE001
        record("curl_cffi (chrome TLS)", f"{type(e).__name__}: {e}", "error")


def main() -> int:
    print(f"Testing {URL}")
    print(f"Location: {LAT}, {LON}")
    print("=" * 68)

    try_requests("requests + minimal headers", MINIMAL)
    try_requests("requests + full browser headers", FULL)
    try_urllib()
    try_curl()
    try_curl_cffi()

    print("\n" + "=" * 68)
    print("SUMMARY")
    for name, verdict in results.items():
        print(f"  {verdict.upper():6} {name}")

    passed = [n for n, v in results.items() if v == "pass"]
    print()
    if passed:
        print(f"Working client(s): {', '.join(passed)}")
        print("Tell Claude which one passed and the watcher will be switched to it.")
    elif all(v in ("fail", "error", "skip") for v in results.values()):
        print("Everything was refused. Two likely causes:")
        print("  1. A network-level filter (office wifi / VPN / ISP) is intercepting —")
        print("     the Drupal-looking page points this way. Try a phone hotspot.")
        print("  2. Blinkit is blocking non-browser TLS fingerprints. Install the")
        print("     impersonating client and re-run:   pip install curl_cffi")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
