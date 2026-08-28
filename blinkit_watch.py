#!/usr/bin/env python3
"""
Hot Wheels restock watcher for Blinkit.

Polls Blinkit's search API for your delivery location and sends a Telegram
message the moment a product flips from out-of-stock (or never-seen) to
in-stock. Keeps a small JSON state file so it never spams you about the same
item twice.

Requires nothing but Python 3.9+. No pip install needed.

Usage
-----
  python blinkit_watch.py --once          # one check (what CI runs)
  python blinkit_watch.py --loop          # run forever, checks every INTERVAL
  python blinkit_watch.py --selftest      # verify network + Telegram wiring
  python blinkit_watch.py --chat-id       # print your Telegram chat id

Configuration comes from environment variables (or a .env file next to this
script):

  BLINKIT_LAT          required, e.g. 28.4634
  BLINKIT_LON          required, e.g. 77.0768
  TELEGRAM_BOT_TOKEN   required
  TELEGRAM_CHAT_ID     required (run --chat-id once to find it)
  QUERIES              optional, comma-separated. default: "hot wheels"
  KEYWORDS             optional, comma-separated. only alert if the product
                       name contains one of these (case-insensitive).
  INTERVAL             optional, seconds between checks in --loop. default 300
  STATE_FILE           optional, default "state.json" next to this script
  MAX_PAGES            optional, default 8 (12 products per page)

A note on the HTTP client
-------------------------
Blinkit sits behind Cloudflare, which scores clients partly on how their
headers are shaped. The `requests` library gets a 403 here: it adds its own
Accept-Encoding and Connection: keep-alive and lowercases every header name.
Python's stdlib urllib title-cases headers and sends Connection: close, and
sails straight through. So urllib is the primary client. If Cloudflare ever
tightens further, install curl_cffi (`pip install curl_cffi`) and this script
will automatically fall back to it — it impersonates Chrome's TLS fingerprint.
"""

from __future__ import annotations

import argparse
import gzip
import json
import os
import random
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path
from typing import Any, Iterable

HERE = Path(__file__).resolve().parent

BASE = "https://blinkit.com"
SEARCH_PATH = "/v1/layout/search"
PRODUCT_URL = "https://blinkit.com/prn/{slug}/prid/{pid}"

UA = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/127.0.0.0 Safari/537.36")


# --------------------------------------------------------------------------
# http  (stdlib first, curl_cffi as an automatic fallback)
# --------------------------------------------------------------------------

class HttpError(RuntimeError):
    def __init__(self, status: int, body: str):
        super().__init__(f"HTTP {status}: {body[:200]}")
        self.status = status
        self.body = body


def _curl_cffi_post(url: str, payload: dict, headers: dict[str, str],
                    timeout: int) -> tuple[int, str]:
    from curl_cffi import requests as creq  # noqa: PLC0415 - optional dep
    r = creq.post(url, json=payload, headers=headers,
                  timeout=timeout, impersonate="chrome")
    return r.status_code, r.text


def post_json(url: str, payload: dict, headers: dict[str, str],
              timeout: int = 25) -> Any:
    """POST JSON and return the decoded response, or raise HttpError.

    Deliberately does NOT set Accept-Encoding — letting urllib omit it is part
    of what gets us past Cloudflare here.
    """
    data = json.dumps(payload).encode("utf-8")
    req = urllib.request.Request(url, data=data, headers=headers, method="POST")
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            raw = resp.read()
            if resp.headers.get("Content-Encoding") == "gzip":
                raw = gzip.decompress(raw)
            return json.loads(raw.decode("utf-8"))
    except urllib.error.HTTPError as e:
        body = e.read().decode("utf-8", "replace")
        # Cloudflare turned us away — try the impersonating client if present.
        if e.code in (403, 503):
            try:
                status, text = _curl_cffi_post(url, payload, headers, timeout)
            except ImportError:
                raise HttpError(e.code, body) from None
            if status == 200:
                return json.loads(text)
            raise HttpError(status, text) from None
        raise HttpError(e.code, body) from None


# --------------------------------------------------------------------------
# config
# --------------------------------------------------------------------------

def load_dotenv() -> None:
    """Minimal .env loader so local runs don't need shell exports."""
    path = HERE / ".env"
    if not path.exists():
        return
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, _, val = line.partition("=")
        os.environ.setdefault(key.strip(), val.strip().strip("'\""))


def env(name: str, default: str = "") -> str:
    """Read a config value, treating blank as unset.

    This matters more than it looks. GitHub Actions expands an undefined
    `vars.X` to an EMPTY STRING rather than omitting the variable, so
    os.environ.get(name, default) hands back "" and silently discards the
    default. That is how QUERIES ended up empty and the watcher searched for
    nothing at all while still reporting success.
    """
    value = os.environ.get(name, "").strip()
    return value or default


def env_int(name: str, default: int) -> int:
    try:
        return int(env(name, str(default)))
    except ValueError:
        print(f"  ({name} is not a number — using {default})", file=sys.stderr)
        return default


class Config:
    def __init__(self) -> None:
        self.lat = env("BLINKIT_LAT")
        self.lon = env("BLINKIT_LON")
        self.token = env("TELEGRAM_BOT_TOKEN")
        self.chat_id = env("TELEGRAM_CHAT_ID")
        self.queries = [q.strip() for q in env("QUERIES", "hot wheels").split(",") if q.strip()]
        self.keywords = [k.strip().lower() for k in env("KEYWORDS").split(",") if k.strip()]
        self.interval = env_int("INTERVAL", 300)
        self.max_pages = env_int("MAX_PAGES", 8)
        self.state_file = Path(env("STATE_FILE", str(HERE / "state.json")))
        # Share state through the git repo so a local watcher and GitHub Actions
        # don't both alert on the same restock.
        self.sync_git = env("SYNC_GIT", "0") == "1"
        # Belt and braces: an empty query list means searching for nothing,
        # which is indistinguishable from "everything is sold out".
        if not self.queries:
            self.queries = ["hot wheels"]

    def headers(self) -> dict[str, str]:
        return {
            "Accept": "*/*",
            "Accept-Language": "en-US,en;q=0.9",
            "Content-Type": "application/json",
            "Origin": BASE,
            "Referer": BASE + "/",
            "User-Agent": UA,
            "sec-ch-ua": '"Chromium";v="127", "Not)A;Brand";v="99"',
            "sec-ch-ua-mobile": "?0",
            "sec-ch-ua-platform": '"Windows"',
            "sec-fetch-dest": "empty",
            "sec-fetch-mode": "cors",
            "sec-fetch-site": "same-origin",
            "app_client": "consumer_web",
            "lat": self.lat,
            "lon": self.lon,
        }

    def require(self, *, telegram: bool = True) -> None:
        missing = [n for n, v in (("BLINKIT_LAT", self.lat),
                                  ("BLINKIT_LON", self.lon)) if not v]
        if telegram:
            missing += [n for n, v in (("TELEGRAM_BOT_TOKEN", self.token),
                                       ("TELEGRAM_CHAT_ID", self.chat_id)) if not v]
        if missing:
            sys.exit("Missing required config: " + ", ".join(missing))


# --------------------------------------------------------------------------
# blinkit
# --------------------------------------------------------------------------

BODY = {"applied_filters": None, "previous_search_query": "", "processed_rails": {}}


def fetch_page(cfg: Config, path_with_qs: str, attempts: int = 3) -> dict[str, Any]:
    url = BASE + path_with_qs if path_with_qs.startswith("/") else path_with_qs
    last: Exception | None = None
    for i in range(attempts):
        try:
            return post_json(url, BODY, cfg.headers())
        except HttpError as e:
            if e.status == 400 and "not serviceable" in e.body.lower():
                raise RuntimeError(
                    "Blinkit says this location is not serviceable. "
                    "Check BLINKIT_LAT / BLINKIT_LON."
                ) from None
            last = e
        except (urllib.error.URLError, OSError, json.JSONDecodeError) as e:
            last = e
        time.sleep(2 * (i + 1) + random.random())
    raise RuntimeError(f"Blinkit request failed after {attempts} attempts: {last}")


def _iter_cards(node: Any, depth: int = 0) -> Iterable[dict[str, Any]]:
    """Walk the widget tree and yield anything that looks like a product card."""
    if depth > 14 or not isinstance(node, (dict, list)):
        return
    if isinstance(node, dict):
        if "product_id" in node and "inventory" in node and "display_name" in node:
            yield node
            return
        for v in node.values():
            yield from _iter_cards(v, depth + 1)
    else:
        for v in node:
            yield from _iter_cards(v, depth + 1)


def _text(v: Any) -> str:
    if isinstance(v, dict):
        return str(v.get("text", "")).strip()
    return str(v or "").strip()


def slugify(name: str) -> str:
    """Blinkit product URLs read /prn/<slug>/prid/<id>.

    The id is what actually resolves the page — a wrong slug still works — but
    a real one makes the link readable and gives Telegram a sane preview.
    """
    out = []
    for ch in name.lower():
        if ch.isalnum():
            out.append(ch)
        elif out and out[-1] != "-":
            out.append("-")
    return "".join(out).strip("-")[:80] or "p"


def parse_products(payload: dict[str, Any]) -> list[dict[str, Any]]:
    out: list[dict[str, Any]] = []
    for c in _iter_cards(payload.get("response", payload)):
        pid = str(c.get("product_id") or "").strip()
        if not pid:
            continue
        inv = c.get("inventory")
        try:
            inv = int(inv)
        except (TypeError, ValueError):
            inv = 0
        state = str(c.get("product_state") or "").lower()
        in_stock = inv > 0 and not bool(c.get("is_sold_out")) and state in ("", "available")
        name = _text(c.get("display_name")) or _text(c.get("name"))
        image = c.get("image") or {}
        out.append({
            "id": pid,
            "name": name,
            "brand": _text(c.get("brand_name")),
            "price": _text(c.get("normal_price")),
            "mrp": _text(c.get("mrp_price")),
            "unit": _text(c.get("variant")),
            "qty": inv,
            "image": (image.get("url") or "").strip() if isinstance(image, dict) else "",
            "in_stock": in_stock,
            "url": PRODUCT_URL.format(slug=slugify(name), pid=pid),
        })
    return out


def probe_empty(cfg: Config, query: str) -> None:
    """Explain why a search came back with nothing.

    A 200 response carrying no product cards is the dangerous case: the run
    looks healthy but can never alert. Usually it means Blinkit served this
    network a serviceability page instead of results — which is what happens
    from outside India, GitHub's runners included.
    """
    qs = SEARCH_PATH + "?" + urllib.parse.urlencode(
        {"q": query, "search_type": "type_to_search"})
    try:
        payload = post_json(BASE + qs, BODY, cfg.headers())
    except HttpError as e:
        print(f"  probe: HTTP {e.status} — {e.body[:300]}", file=sys.stderr)
        return
    except (urllib.error.URLError, OSError) as e:
        print(f"  probe: network error — {e}", file=sys.stderr)
        return
    resp = payload.get("response") or {}
    snippets = resp.get("snippets") or []
    kinds: dict[str, int] = {}
    for s in snippets:
        if isinstance(s, dict):
            k = str(s.get("widget_type", "?"))
            kinds[k] = kinds.get(k, 0) + 1
    print(f"  probe: HTTP 200, top-level keys={list(payload)}, "
          f"{len(snippets)} snippets {kinds or '(none)'}", file=sys.stderr)
    blob = json.dumps(payload)[:200000].lower()
    for hint in ("not serviceable", "serviceab", "unavailable", "coming soon",
                 "not delivering", "no store", "out of service"):
        if hint in blob:
            i = blob.find(hint)
            print(f"  probe: response mentions …{blob[max(0, i-70):i+70]}…", file=sys.stderr)
            break


def search_all(cfg: Config, query: str, max_pages: int) -> list[dict[str, Any]]:
    qs = SEARCH_PATH + "?" + urllib.parse.urlencode(
        {"q": query, "search_type": "type_to_search"})
    seen: dict[str, dict[str, Any]] = {}
    for _ in range(max_pages):
        payload = fetch_page(cfg, qs)
        for p in parse_products(payload):
            seen.setdefault(p["id"], p)
        nxt = ((payload.get("response") or {}).get("pagination") or {}).get("next_url")
        if not nxt:
            break
        qs = nxt
        time.sleep(0.8 + random.random() * 0.7)
    return list(seen.values())


# --------------------------------------------------------------------------
# telegram
# --------------------------------------------------------------------------

def tg(cfg: Config, method: str, **params) -> dict[str, Any]:
    url = f"https://api.telegram.org/bot{cfg.token}/{method}"
    hdrs = {"Content-Type": "application/json", "User-Agent": UA}
    try:
        data = post_json(url, params, hdrs, timeout=20)
    except HttpError as e:
        raise RuntimeError(f"Telegram {method} failed: {e}") from None
    if not data.get("ok"):
        raise RuntimeError(f"Telegram {method} failed: {data}")
    return data


def notify(cfg: Config, text: str) -> None:
    tg(cfg, "sendMessage", chat_id=cfg.chat_id, text=text,
       parse_mode="HTML", disable_web_page_preview=True)


def notify_photo(cfg: Config, image: str, caption: str) -> None:
    tg(cfg, "sendPhoto", chat_id=cfg.chat_id, photo=image,
       caption=caption, parse_mode="HTML")


def esc(s: str) -> str:
    return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")


# How many restocks still get their own photo card before we group them.
PHOTO_LIMIT = 5


def price_line(p: dict[str, Any]) -> str:
    """'₹1,560  ~~₹2,599~~  (40% off)' — only the parts that exist."""
    if not p["price"]:
        return ""
    bits = [f"<b>{esc(p['price'])}</b>"]
    if p["mrp"] and p["mrp"] != p["price"]:
        bits.append(f"<s>{esc(p['mrp'])}</s>")
        try:
            now = float(p["price"].replace("₹", "").replace(",", ""))
            was = float(p["mrp"].replace("₹", "").replace(",", ""))
            if was > now > 0:
                bits.append(f"({round((was - now) / was * 100)}% off)")
        except ValueError:
            pass
    return "  ".join(bits)


def stock_line(p: dict[str, Any]) -> str:
    q = p.get("qty") or 0
    if q <= 0:
        return ""
    if q <= 3:
        return f"⚠️ only {q} left"
    return f"{q} in stock"


def build_alert(restocked: list[dict[str, Any]]) -> tuple[list[tuple[str, str]], str | None]:
    """Return (photo cards to send, grouped fallback message or None)."""
    if len(restocked) <= PHOTO_LIMIT:
        cards = []
        for p in restocked:
            lines = [f"🏎️ <b>{esc(p['name'])}</b>", ""]
            price = price_line(p)
            if price:
                lines.append(price)
            meta = " · ".join(x for x in (p.get("unit"), stock_line(p)) if x)
            if meta:
                lines.append(meta)
            lines += ["", f'<a href="{p["url"]}">Open on Blinkit →</a>']
            cards.append((p.get("image", ""), "\n".join(lines)))
        return cards, None

    lines = [f"🏎️ <b>{len(restocked)} Hot Wheels back in stock</b>", ""]
    for p in restocked[:30]:
        price = f" — {esc(p['price'])}" if p["price"] else ""
        left = stock_line(p)
        left = f" · {left}" if left else ""
        lines.append(f'• <a href="{p["url"]}">{esc(p["name"])}</a>{price}{left}')
    if len(restocked) > 30:
        lines.append(f"\n…and {len(restocked) - 30} more")
    return [], "\n".join(lines)


def send_alert(cfg: Config, restocked: list[dict[str, Any]]) -> None:
    cards, grouped = build_alert(restocked)
    for image, caption in cards:
        if image:
            try:
                notify_photo(cfg, image, caption)
                continue
            except RuntimeError:
                pass  # Telegram couldn't fetch the image — fall back to text
        notify(cfg, caption)
    if grouped:
        notify(cfg, grouped)


# --------------------------------------------------------------------------
# state + core check
# --------------------------------------------------------------------------

def git(*args: str, timeout: int = 90) -> tuple[int, str]:
    """Run a git command in the script's folder. Never raises."""
    import subprocess  # noqa: PLC0415 - only needed when syncing
    try:
        p = subprocess.run(["git", *args], cwd=str(HERE), capture_output=True,
                           text=True, timeout=timeout)
        return p.returncode, (p.stdout + p.stderr).strip()
    except FileNotFoundError:
        return 127, "git is not installed or not on PATH"
    except subprocess.TimeoutExpired:
        return 124, "git timed out"


def branch() -> str:
    code, out = git("rev-parse", "--abbrev-ref", "HEAD")
    return out.strip() if code == 0 and out.strip() else "main"


def remote_state(br: str, name: str) -> dict[str, Any] | None:
    """Read state.json as it exists on the remote, without touching the tree."""
    code, out = git("show", f"origin/{br}:{name}")
    if code:
        return None
    try:
        data = json.loads(out)
        data.setdefault("products", {})
        return data
    except json.JSONDecodeError:
        return None


def merge_states(mine: dict[str, Any], theirs: dict[str, Any]) -> dict[str, Any]:
    """Combine two watchers' views of the world.

    The only rule that matters: if EITHER side saw a product in stock, the
    merged state says in-stock. in_stock=True is the record that an alert was
    already sent, so ORing the flags is what guarantees you're never pinged
    twice for the same restock. Erring this way can at worst delay one alert;
    the opposite would duplicate every one.
    """
    out = dict(theirs)
    products = dict(theirs.get("products", {}))
    for pid, mine_p in mine.get("products", {}).items():
        their_p = products.get(pid)
        if not their_p:
            products[pid] = mine_p
            continue
        merged = dict(their_p)
        merged["in_stock"] = bool(their_p.get("in_stock")) or bool(mine_p.get("in_stock"))
        merged.setdefault("name", mine_p.get("name", ""))
        products[pid] = merged
    out["products"] = products
    out["last_check"] = max(str(mine.get("last_check", "")),
                            str(theirs.get("last_check", "")))
    return out


def sync_pull(cfg: Config) -> None:
    """Fold the remote's state into ours before checking.

    Deliberately does NOT use `git pull` — both watchers edit state.json, so a
    text merge conflicts every time. We fetch and merge the JSON ourselves.
    """
    if not cfg.sync_git:
        return
    code, out = git("fetch", "--quiet", "origin")
    if code:
        print(f"  (state sync: fetch failed, using local copy — {out[:90]})", file=sys.stderr)
        return
    theirs = remote_state(branch(), cfg.state_file.name)
    if theirs is None:
        return
    merged = merge_states(load_state(cfg.state_file), theirs)
    save_state(cfg.state_file, merged)


def sync_push(cfg: Config, attempts: int = 3) -> None:
    """Publish our state, re-merging if someone beat us to it."""
    if not cfg.sync_git:
        return
    name = cfg.state_file.name
    br = branch()
    for _ in range(attempts):
        code, out = git("status", "--porcelain", "--", name)
        if code or not out:
            return  # nothing changed, nothing to push
        git("add", "--", name)
        git("-c", "user.name=blinkit-watcher", "-c", "user.email=watcher@localhost",
            "commit", "-q", "-m", f"state: {time.strftime('%Y-%m-%d %H:%M')}")
        code, out = git("push", "--quiet", "origin", f"HEAD:{br}")
        if code == 0:
            return
        # Rejected: someone pushed first. Take their commit as the base, fold
        # our findings back in, and try again. No git merge, so no conflicts.
        mine = load_state(cfg.state_file)
        if git("fetch", "--quiet", "origin")[0]:
            break
        theirs = remote_state(br, name)
        if theirs is None:
            break
        git("reset", "--hard", "--quiet", f"origin/{br}")
        save_state(cfg.state_file, merge_states(mine, theirs))
    print("  (state sync: could not push, will retry next check)", file=sys.stderr)


def load_state(path: Path) -> dict[str, Any]:
    if not path.exists():
        return {"products": {}}
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
        data.setdefault("products", {})
        return data
    except (json.JSONDecodeError, OSError):
        return {"products": {}}


def save_state(path: Path, state: dict[str, Any]) -> None:
    path.write_text(json.dumps(state, indent=2, ensure_ascii=False), encoding="utf-8")


def matches_keywords(name: str, keywords: list[str]) -> bool:
    if not keywords:
        return True
    low = name.lower()
    return any(k in low for k in keywords)


def check(cfg: Config, *, dry_run: bool = False) -> list[dict[str, Any]]:
    """One pass. Returns the products that newly came into stock."""
    sync_pull(cfg)
    found: dict[str, dict[str, Any]] = {}
    for q in cfg.queries:
        for p in search_all(cfg, q, cfg.max_pages):
            found.setdefault(p["id"], p)

    # Zero products is never a legitimate result for "hot wheels" at a
    # serviceable location. Treat it as a failure rather than quietly
    # reporting "no change" forever.
    if not found:
        print("FAILED: search returned 0 products.", file=sys.stderr)
        probe_empty(cfg, cfg.queries[0])
        raise RuntimeError(
            "Blinkit returned no products. This network is most likely being "
            "served a non-India / non-serviceable response."
        )

    state = load_state(cfg.state_file)
    known: dict[str, Any] = state["products"]
    first_run = not known

    restocked: list[dict[str, Any]] = []
    for pid, p in found.items():
        was = known.get(pid, {}).get("in_stock")
        if p["in_stock"] and was is not True and matches_keywords(p["name"], cfg.keywords):
            restocked.append(p)
        known[pid] = {"name": p["name"], "in_stock": p["in_stock"], "price": p["price"]}

    state["last_check"] = time.strftime("%Y-%m-%d %H:%M:%S")
    state["last_count"] = len(found)

    # On the very first run everything looks "new" — record the baseline and
    # stay quiet rather than firing off an alert for the whole catalogue.
    if first_run:
        n_in = sum(1 for p in found.values() if p["in_stock"])
        print(f"Baseline recorded: {len(found)} products ({n_in} in stock). No alerts sent.")
        restocked = []
    elif restocked:
        if dry_run:
            cards, grouped = build_alert(restocked)
            for image, caption in cards:
                print(f"[dry-run] photo {image or '(none)'}\n{caption}\n")
            if grouped:
                print("[dry-run] message:\n" + grouped)
        else:
            send_alert(cfg, restocked)
        for p in restocked:
            print(f"  ALERT {p['name']} {p['price']} -> {p['url']}")
        print(f"Alerted on {len(restocked)} product(s).")
    else:
        n_in = sum(1 for p in found.values() if p["in_stock"])
        print(f"No change. {len(found)} products seen, {n_in} in stock.")

    if not dry_run:
        save_state(cfg.state_file, state)
        sync_push(cfg)
    return restocked


# --------------------------------------------------------------------------
# entry points
# --------------------------------------------------------------------------

def selftest(cfg: Config) -> int:
    cfg.require(telegram=False)
    print(f"Location: {cfg.lat}, {cfg.lon}")
    print(f"Queries : {', '.join(cfg.queries)}")
    try:
        products = search_all(cfg, cfg.queries[0], max_pages=1)
    except RuntimeError as e:
        print(f"FAIL  Blinkit: {e}")
        print("      Run  python diagnose.py  to see which HTTP client gets through.")
        return 1
    n_in = sum(1 for p in products if p["in_stock"])
    print(f"OK    Blinkit reachable — {len(products)} products on page 1, {n_in} in stock.")
    for p in products[:5]:
        print(f"        {'IN ' if p['in_stock'] else 'OUT'} {p['name']} {p['price']}")
    if cfg.token and cfg.chat_id:
        try:
            notify(cfg, "✅ Blinkit Hot Wheels watcher — self-test OK. Alerts will arrive here.")
            print("OK    Telegram test message sent.")
        except RuntimeError as e:
            print(f"FAIL  Telegram: {e}")
            return 1
    else:
        print("SKIP  Telegram not configured yet (set TELEGRAM_BOT_TOKEN / TELEGRAM_CHAT_ID).")
    return 0


def test_alert(cfg: Config) -> int:
    """Send a genuine alert for whatever is in stock now, without touching state.

    Lets you see exactly what a restock looks like instead of waiting for one.
    """
    cfg.require()
    products = [p for p in search_all(cfg, cfg.queries[0], max_pages=1) if p["in_stock"]]
    if not products:
        print("Nothing in stock right now — try again later.")
        return 1
    sample = products[:2]
    print(f"Sending a sample alert for {len(sample)} product(s)…")
    send_alert(cfg, sample)
    print("Sent. State file untouched — this changes nothing about real alerts.")
    return 0


def print_chat_id(cfg: Config) -> int:
    if not cfg.token:
        sys.exit("Set TELEGRAM_BOT_TOKEN in .env first.")
    data = tg(cfg, "getUpdates")
    chats = {}
    for u in data.get("result", []):
        msg = u.get("message") or u.get("channel_post") or {}
        chat = msg.get("chat") or {}
        if chat.get("id"):
            chats[chat["id"]] = (chat.get("username") or chat.get("title")
                                 or chat.get("first_name"))
    if not chats:
        print("No messages yet. Open Telegram, find your bot, and send it any message "
              "(/start works), then run this again.")
        return 1
    for cid, who in chats.items():
        print(f"TELEGRAM_CHAT_ID={cid}   ({who})")
    return 0


def main() -> int:
    load_dotenv()
    ap = argparse.ArgumentParser(description="Blinkit Hot Wheels restock watcher")
    g = ap.add_mutually_exclusive_group()
    g.add_argument("--once", action="store_true", help="run a single check (default)")
    g.add_argument("--loop", action="store_true", help="keep running, check every INTERVAL")
    g.add_argument("--selftest", action="store_true", help="verify Blinkit + Telegram wiring")
    g.add_argument("--chat-id", action="store_true", help="print your Telegram chat id")
    g.add_argument("--test-alert", action="store_true",
                   help="send a real alert for whatever is in stock right now")
    ap.add_argument("--dry-run", action="store_true", help="don't send or save, just print")
    args = ap.parse_args()

    cfg = Config()

    if args.selftest:
        return selftest(cfg)
    if args.chat_id:
        return print_chat_id(cfg)
    if args.test_alert:
        return test_alert(cfg)

    cfg.require(telegram=not args.dry_run)

    if args.loop:
        if cfg.interval < 30:
            print("INTERVAL below 30s is pointless and invites a block — using 30s.")
            cfg.interval = 30
        print(f"Watching every ~{cfg.interval}s. Ctrl-C to stop.")
        fails = 0
        while True:
            try:
                check(cfg, dry_run=args.dry_run)
                fails = 0
                # Jitter scales with the interval so a fast poll stays fast,
                # while never landing on a perfectly robotic schedule.
                delay = cfg.interval + random.uniform(0, min(30, cfg.interval * 0.25))
            except RuntimeError as e:
                fails += 1
                print(f"Check failed ({fails}): {e}", file=sys.stderr)
                # Back off hard when Blinkit is refusing us — hammering a
                # Cloudflare block is the fastest way to earn a longer one.
                delay = min(cfg.interval * (2 ** fails), 1800)
                print(f"Backing off {int(delay)}s before retrying.", file=sys.stderr)
            time.sleep(delay)

    try:
        check(cfg, dry_run=args.dry_run)
    except RuntimeError as e:
        print(f"Check failed: {e}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
