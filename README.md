# Blinkit Hot Wheels restock watcher

Polls Blinkit's search API for your delivery location and Telegram-pings you the
moment a Hot Wheels product flips from sold-out (or never-seen) to in-stock.

It talks to the same endpoint the Blinkit website uses — `POST
/v1/layout/search` with `lat` / `lon` headers. No login, no cookies, no API key.

---

## 1. Make the Telegram bot (~2 minutes)

1. In Telegram, message **@BotFather** → `/newbot` → pick a name and a username.
2. He replies with a token like `8123456789:AAH...`. That's `TELEGRAM_BOT_TOKEN`.
3. Search for your new bot, open it, and send it any message (`/start` works).
   A bot can't message you until you message it first.

## 2. Find your coordinates

Open Google Maps, right-click your delivery address, click the `lat, lon` at the
top of the menu — it copies something like `28.6139, 77.2090`.

Accuracy matters: Blinkit stock is per dark-store, so use your actual delivery
pin, not your city centre.

## 3. Configure

```bash
cp .env.example .env
```

Fill in `.env`:

```
BLINKIT_LAT=28.6139
BLINKIT_LON=77.2090
TELEGRAM_BOT_TOKEN=8123456789:AAH...
TELEGRAM_CHAT_ID=
```

Then get your chat id and paste it in:

```bash
pip install -r requirements.txt
python blinkit_watch.py --chat-id
```

## 4. Check it works

```bash
python blinkit_watch.py --selftest
```

This prints the first page of Hot Wheels results with their stock state and
sends a test message to Telegram. If both work, you're done.

---

## Running it

### On your PC

```bash
python blinkit_watch.py --loop
```

Checks every 5 minutes (`INTERVAL` in `.env`) until you Ctrl-C. Leave it in a
terminal, or wrap it in a Task Scheduler / `systemd` entry to start at boot.

The first run is silent on purpose — it records what's currently in stock as a
baseline so you don't get 48 alerts at once. From the second run on, you only
hear about genuine changes.

### On GitHub Actions (free, no PC needed)

1. Push this folder to a **private** GitHub repo.
2. Settings → Secrets and variables → Actions → **New repository secret**, add:
   `BLINKIT_LAT`, `BLINKIT_LON`, `TELEGRAM_BOT_TOKEN`, `TELEGRAM_CHAT_ID`.
3. Optionally add repo **variables** `QUERIES` and `KEYWORDS`.
4. Actions tab → "Blinkit Hot Wheels watcher" → **Run workflow** to test.

It runs every 10 minutes and commits `state.json` back to the repo so it
remembers across runs.

> **One caveat worth knowing up front.** GitHub's runners are outside India.
> Blinkit serves its site to them today, but quick-commerce sites tighten
> geo/bot rules often, so a cloud run can start failing without warning. Watch
> the first few Actions runs — if the "Check stock" step starts erroring on
> connection or 403, the fix is to run it on your own machine (or any India-based
> box) instead. That's why the local mode exists too.

---

## Tuning

Set these in `.env` (local) or as repo variables (Actions):

| Variable | Default | What it does |
|---|---|---|
| `QUERIES` | `hot wheels` | Comma-separated searches. Try `hot wheels,hotwheels,matchbox` |
| `KEYWORDS` | *(empty)* | Only alert if the product name contains one of these. `bugatti,skyline,supra,premium` |
| `INTERVAL` | `300` | Seconds between checks in `--loop` |
| `MAX_PAGES` | `8` | Pages to walk, 12 products each |

Blinkit's own site loads 12 products a page, so 8 pages ≈ the full catalogue for
a query. Don't crank the interval below ~2 minutes — you'll hammer their API for
no benefit, and stock changes propagate slower than that anyway.

## Files

```
blinkit_watch.py            the watcher
test_parser.py              offline checks for the parsing + alert logic
requirements.txt
.env.example
.github/workflows/watch.yml GitHub Actions schedule
state.json                  created on first run; delete it to reset the baseline
```

Run `python test_parser.py` any time — it verifies the parser and the
"only alert on a real restock" rules without touching the network.

## If something breaks

**"location not serviceable"** — your lat/lon is outside Blinkit's delivery area,
or has a typo. Check it on blinkit.com first.

**No alerts ever** — expected if nothing has actually restocked. `--selftest`
confirms the pipe works end to end. Deleting `state.json` re-baselines.

**Blinkit changes their response shape** — `parse_products()` walks the widget
tree looking for any object with `product_id` + `inventory` + `display_name`
rather than hardcoding a path, so it survives most reshuffles. If it ever returns
zero products while the site clearly has them, that's the function to look at.
