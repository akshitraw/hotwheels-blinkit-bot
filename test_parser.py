"""Offline checks for the parser + restock-transition logic.

The fixture below is a trimmed copy of a real blinkit.com/v1/layout/search
response (only the fields the parser reads), plus two hand-made cards for the
out-of-stock shapes.  Run:  python test_parser.py
"""
import json
import os
import tempfile
from pathlib import Path

import blinkit_watch as bw


def card(pid, name, inventory, sold_out, state, price, unit):
    return {
        "widget_type": "product_card_snippet_type_2",
        "data": {
            "product_id": pid,
            "display_name": {"text": name, "font": {"size": "200"}},
            "name": {"text": name},
            "inventory": inventory,
            "is_sold_out": sold_out,
            "product_state": state,
            "normal_price": {"text": price, "font": {"weight": "semibold"}},
            "variant": {"text": unit},
        },
    }


PAYLOAD = {
    "is_success": True,
    "response": {
        "snippets": [
            {"widget_type": "ImageTextViewRendererTypeHeader"},
            card("774452", "Hot Wheels Roller Toaster Die Cast Car", 1, False, "available", "₹179", "1 pc"),
            card("445097", "Hot Wheels Color Shifters Splash Track Set Science Lab", 1, False, "available", "₹1,560", "1 set"),
            {"widget_type": "grid_container_vr"},
            card("804932", "Hot Wheels Renault Espace F1 Die Cast Car", 2, False, "available", "₹179", "1 unit"),
            card("999001", "Hot Wheels Bugatti Chiron Die Cast Car", 0, True, "unavailable", "₹179", "1 unit"),
            card("999002", "Hot Wheels Nissan Skyline GT-R Die Cast Car", 0, False, "available", "₹179", "1 unit"),
        ],
        "pagination": {},
    },
}

FAILED = 0


def ok(label, cond):
    global FAILED
    print(("  PASS  " if cond else "  FAIL  ") + label)
    if not cond:
        FAILED += 1


def main():
    print("parse_products")
    products = bw.parse_products(PAYLOAD)
    by_id = {p["id"]: p for p in products}

    ok("finds 5 product cards, skips non-product widgets", len(products) == 5)
    ok("reads name from display_name.text",
       by_id["774452"]["name"] == "Hot Wheels Roller Toaster Die Cast Car")
    ok("reads price text", by_id["445097"]["price"] == "₹1,560")
    ok("reads unit text", by_id["445097"]["unit"] == "1 set")
    ok("builds product url", by_id["774452"]["url"].endswith("/prid/774452"))
    ok("inventory>0 + available  -> in stock", by_id["804932"]["in_stock"] is True)
    ok("is_sold_out=True         -> out of stock", by_id["999001"]["in_stock"] is False)
    ok("inventory=0              -> out of stock", by_id["999002"]["in_stock"] is False)

    print("keyword filter")
    ok("empty keyword list matches everything", bw.matches_keywords("anything", []))
    ok("matches case-insensitively", bw.matches_keywords("Hot Wheels BUGATTI Chiron", ["bugatti"]))
    ok("rejects non-matches", not bw.matches_keywords("Hot Wheels Roller Toaster", ["bugatti"]))

    print("transition logic")
    tmp = Path(tempfile.mkdtemp()) / "state.json"
    cfg = bw.Config()
    cfg.state_file = tmp

    # first run: baseline, no alerts even though 3 items are in stock
    state = {"products": {}}
    bw.save_state(tmp, state)

    def simulate(found, keywords=None):
        """Mirror check()'s transition rules without touching the network."""
        st = bw.load_state(tmp)
        known = st["products"]
        first_run = not known
        restocked = []
        for p in found:
            was = known.get(p["id"], {}).get("in_stock")
            if p["in_stock"] and was is not True and bw.matches_keywords(p["name"], keywords or []):
                restocked.append(p)
            known[p["id"]] = {"name": p["name"], "in_stock": p["in_stock"], "price": p["price"]}
        if first_run:
            restocked = []
        bw.save_state(tmp, st)
        return restocked

    r1 = simulate(products)
    ok("first run stays silent (baseline only)", r1 == [])
    ok("baseline persisted 5 products", len(bw.load_state(tmp)["products"]) == 5)

    r2 = simulate(products)
    ok("second identical run sends nothing", r2 == [])

    restocked_payload = [dict(p) for p in products]
    for p in restocked_payload:
        if p["id"] == "999001":
            p["in_stock"] = True
    r3 = simulate(restocked_payload)
    ok("out-of-stock -> in-stock fires exactly one alert", len(r3) == 1)
    ok("alert is the right product", r3 and r3[0]["id"] == "999001")

    r4 = simulate(restocked_payload)
    ok("still-in-stock does not re-alert", r4 == [])

    gone = [dict(p) for p in restocked_payload]
    for p in gone:
        if p["id"] == "999001":
            p["in_stock"] = False
    simulate(gone)
    back = [dict(p) for p in restocked_payload]
    r5 = simulate(back)
    ok("sold out then restocked alerts again", len(r5) == 1)

    new_item = products + [{"id": "555000", "name": "Hot Wheels Bugatti Divo",
                            "price": "₹179", "unit": "1 unit", "in_stock": True,
                            "url": "u"}]
    r6 = simulate(new_item)
    ok("brand-new in-stock product alerts", len(r6) == 1 and r6[0]["id"] == "555000")

    r7 = simulate([{"id": "555111", "name": "Hot Wheels Roller Toaster",
                    "price": "", "unit": "", "in_stock": True, "url": "u"}],
                  keywords=["bugatti"])
    ok("keyword filter suppresses non-matching restock", r7 == [])

    print()
    if FAILED:
        print(f"{FAILED} check(s) failed")
        return 1
    print("all checks passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
