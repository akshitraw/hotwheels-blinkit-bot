# Hot Wheels Watcher — Android app

Watches Blinkit at your delivery location and posts a notification the moment a
Hot Wheels car comes back in stock. Runs on your phone, which is the point:
Blinkit's Cloudflare blocks datacentre IPs, so cloud servers and GitHub Actions
get a 403. A phone on home wifi or mobile data is exactly the kind of connection
it accepts.

No Telegram, no PC, no server. Tapping a notification opens that car on Blinkit.

---

## Getting the APK

You don't need Android Studio. GitHub builds it for you.

1. Copy this `android/` folder and `.github/workflows/android.yml` into your
   `hotwheels-blinkit-bot` repo, then push.
2. Repo → **Actions** → **Build Android APK** → **Run workflow**.
3. When it goes green, open the run and download the **hotwheels-watcher-apk**
   artifact from the Summary page.
4. Unzip it — inside is `hotwheels-watcher.apk`. Move it to your phone.

## Installing it

It's a debug-signed APK, so Android will ask permission to install from an
unknown source. Open the file, tap through the prompt ("Allow from this source"),
then Install. This is normal for an app that isn't from the Play Store.

## First run

1. Open the app and set your location — **Use my location** (grant the location
   permission when asked) or **Search address** and pick from the results. The
   app names the place, then immediately checks Blinkit and tells you how many
   cars that store has, so you know straight away it's the right one. There's an
   "Enter coordinates manually" toggle if you'd rather type lat/lon.
2. Tap **Send a test alert**. You should get a notification for a car that's in
   stock right now, with its photo and price. That proves the whole chain works.
3. Tap **Start watching**. Android will ask for notification permission, then
   ask to exempt the app from battery optimisation — **say yes to both**. Without
   the battery exemption, Android puts the watcher to sleep when your screen is
   off, which is precisely when you'd want it awake.

The first check after starting is silent on purpose: it records what's already
in stock so you only hear about genuine changes. From the second check on, you
get one notification per car that comes back.

A permanent low-priority notification shows what it's doing ("14 of 17 in stock ·
checked 21:04"). Android requires it for anything that runs continuously, and it
doubles as proof the watcher is alive. You can't dismiss it while watching, but
you can long-press it and turn off just the "Watcher status" channel if it
bothers you — the restock alerts come through a separate channel and are
unaffected.

## Settings

| Field | What it does |
|---|---|
| Delivery location | Set by GPS or address search. Blinkit stock is per dark store, so use your actual delivery address rather than a city centre — a few kilometres can mean a different warehouse with different stock. Changing it clears the baseline, since the old one described a different store. |
| Brands to accept | Default `hot wheels`. Blinkit's search is fuzzy: a search for "hot wheels" returns 48 results at a typical store, of which only 34 are Hot Wheels — the rest are Toyshine, Marvel Go, Wembley and similar. Anything whose brand doesn't match is ignored entirely. |
| Search terms | Comma separated — `hot wheels, matchbox` runs two searches and merges the results. |
| Narrow to specific models | **Leave blank** to be told about every Hot Wheels. Filling it in (e.g. `bugatti, skyline`) means *only* cars with those words alert — everything else is silently dropped, which is the most common reason the app goes quiet. The app shows a warning while this field is set. |
| Check every (minutes) | Default 2. Below 2 is pointless — dark-store inventory doesn't update that fast — and burns battery. |

**Activity log** shows one line per check — how many cars were in stock, what it
alerted on, and anything it hid. If you think alerts are missing, look here
first: it distinguishes "nothing changed" from "the watcher stopped running".

Notifications are labelled **NEW ·** for a car never seen before and
**Back in stock ·** for one that sold out and returned.

**Reset baseline** makes it forget what it has seen, so the next check re-learns
from scratch. Useful if you change location or search term.

## Battery

A 2-minute poll with the screen off costs a few percent a day; it's two small
HTTPS requests per check. If that's too much, 5 minutes is still fast enough to
catch a restock in practice. Leave the phone plugged in if you want it running
permanently.

## If notifications stop

Most likely the battery exemption got revoked — some phone makers (Xiaomi, OnePlus,
Realme, Samsung) are aggressive about this and re-enable optimisation on their own.
Check Settings → Apps → Hot Wheels Watcher → Battery → **Unrestricted**, and on
those brands also add the app to the "protected"/"auto-start" list.

If the ongoing notification says `HTTP 403`, Blinkit is refusing that network —
switch between wifi and mobile data and see whether one works.

If it says **no reply after 3 tries**, the phone's radio is likely sleeping
between checks. The app holds a wifi lock and retries on a fresh connection to
prevent this, but some phones override it. Widening the interval to 5 minutes,
or keeping the phone on charge, both help.

## The Cloudflare quirk

Blinkit is behind Cloudflare, and the request shape that gets through from
Android is the **plainest** one: `Content-Type`, `lat`, `lon`, and nothing else.
No User-Agent, no Origin, no `sec-ch-*`.

This is the opposite of the usual advice. Every profile that impersonated Chrome
was refused with 403 — verified on-device across six variants. The reason is that
a declared identity of "Chrome on Windows" contradicts the TLS and HTTP/2
fingerprint of Android's OkHttp stack, and that contradiction scores worse than
making no claim at all.

`Profiles.kt` keeps all six shapes. If Blinkit ever stops accepting the current
one, the watcher walks the others automatically, adopts whichever works, and
remembers it. **Diagnose connection** in the app shows the current state of all
six, which is the first thing to check if alerts go quiet.

## How it works

`Blinkit.kt` posts to `blinkit.com/v1/layout/search` with `lat`/`lon` headers —
the same endpoint the website uses, no login required — then walks the response
tree for anything carrying `product_id`, `inventory` and `display_name`. The
request carries only the three headers above, for the reason described in the
previous section.

`WatcherService.kt` is a foreground service rather than a WorkManager job because
WorkManager's minimum period is 15 minutes, slower than a hot car sells out. It
holds a partial wake lock, backs off exponentially when Blinkit errors, and
restarts after a reboot via `BootReceiver`.

State is a small map of product id to in-stock flag in SharedPreferences. A
notification fires only on a `false → true` transition, which is what stops it
re-alerting every two minutes for something already sitting in stock.
