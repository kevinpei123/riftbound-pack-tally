#!/usr/bin/env python3
"""
Scrape the official Riftbound card gallery into ``app/src/main/assets/cards.json``.

Output schema (list of card objects):

    {
      "id": "ogn-179-298",
      "collectorNumber": "179",
      "name": "Acceptable Losses",
      "setCode": "OGN",
      "rarity": "uncommon",
      "isFoilByDefault": false,
      "hasSignatureVariant": false
    }

How it works
------------
The official gallery at riftbound.leagueoflegends.com is a Next.js page that
ships its full card list as JSON inside a ``<script id="__NEXT_DATA__">`` tag
(SSR with ``prefetchAll: true``). One HTTP request gets us ~950 cards with no
JavaScript rendering required. We use BeautifulSoup just to pull out that one
script tag, then parse the JSON directly.

Foil / signature
----------------
The official site does NOT expose per-card ``isFoilByDefault`` or
``hasSignatureVariant`` flags. Both are defaulted to ``false``. The OGS set
contains the 24 known signature-variant cards if you want to derive
``hasSignatureVariant`` later; the ``showcase`` rarity is the alternate-art
treatment that you may want to mark as foil. Adjust by hand in the JSON or
extend ``parse_card()`` below.

Usage
-----
    pip install requests beautifulsoup4
    python3 scripts/build_cards_json.py

A request rate-limit of 1.0s is honored between every HTTP call — currently
only one call is made, but the limiter is wired so future detail-page or
API-pagination passes are polite by default.
"""

from __future__ import annotations

import json
import logging
import sys
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Any

import requests
from bs4 import BeautifulSoup

GALLERY_URL = "https://riftbound.leagueoflegends.com/en-us/card-gallery/"
USER_AGENT = "riftbound-pack-tally/0.1 (personal-use card index builder)"
REQUEST_DELAY_SEC = 1.0

REPO_ROOT = Path(__file__).resolve().parent.parent
OUT_PATH = REPO_ROOT / "app" / "src" / "main" / "assets" / "cards.json"
FAILURE_LOG_PATH = Path(__file__).resolve().parent / "build_cards_json.failures.log"

log = logging.getLogger("build_cards_json")


class RateLimitedSession:
    """``requests.Session`` wrapper that enforces a minimum delay between calls."""

    def __init__(self, delay_sec: float) -> None:
        self._session = requests.Session()
        self._session.headers["User-Agent"] = USER_AGENT
        self._delay = delay_sec
        self._last_request_at: float = 0.0

    def get(self, url: str, **kwargs: Any) -> requests.Response:
        elapsed = time.monotonic() - self._last_request_at
        if elapsed < self._delay:
            time.sleep(self._delay - elapsed)
        resp = self._session.get(url, timeout=30, **kwargs)
        self._last_request_at = time.monotonic()
        resp.raise_for_status()
        return resp


@dataclass
class ParseFailure:
    raw_id: str
    raw_name: str
    reason: str


def fetch_gallery_html(session: RateLimitedSession) -> str:
    log.info("GET %s", GALLERY_URL)
    return session.get(GALLERY_URL).text


def extract_next_data(html: str) -> dict[str, Any]:
    soup = BeautifulSoup(html, "html.parser")
    tag = soup.find("script", id="__NEXT_DATA__")
    if tag is None or not tag.string:
        raise RuntimeError("__NEXT_DATA__ script tag not found — page layout changed?")
    return json.loads(tag.string)


def find_gallery_items(next_data: dict[str, Any]) -> list[dict[str, Any]]:
    try:
        blades = next_data["props"]["pageProps"]["page"]["blades"]
    except KeyError as e:
        raise RuntimeError(f"Next.js data shape changed; missing key {e!r}") from e

    for blade in blades:
        if blade.get("type") == "riftboundCardGallery":
            items = blade.get("cards", {}).get("items")
            if not isinstance(items, list):
                raise RuntimeError("Gallery blade has no cards.items list")
            metadata = blade.get("cards", {}).get("async", {}).get("metadata", {})
            total = metadata.get("totalItems")
            if total is not None and total != len(items):
                log.warning(
                    "SSR page contains %d items but API reports %d total — "
                    "%d cards may be missing. Fetch via paginated API if needed.",
                    len(items), total, total - len(items),
                )
            return items

    raise RuntimeError("riftboundCardGallery blade not found in __NEXT_DATA__")


def parse_card(raw: dict[str, Any]) -> dict[str, Any]:
    """Map one raw gallery item to the output schema. Raises on missing fields."""
    return {
        "id": str(raw["id"]),
        "collectorNumber": str(raw["collectorNumber"]),
        "name": str(raw["name"]),
        "setCode": str(raw["set"]["value"]["id"]),
        "rarity": str(raw["rarity"]["value"]["id"]),
        "isFoilByDefault": False,
        "hasSignatureVariant": False,
    }


def parse_all(items: list[dict[str, Any]]) -> tuple[list[dict[str, Any]], list[ParseFailure]]:
    cards: list[dict[str, Any]] = []
    failures: list[ParseFailure] = []
    for raw in items:
        raw_id = str(raw.get("id", "<unknown-id>"))
        raw_name = str(raw.get("name", "<unknown-name>"))
        try:
            cards.append(parse_card(raw))
        except (KeyError, TypeError, ValueError) as e:
            failures.append(ParseFailure(raw_id=raw_id, raw_name=raw_name, reason=repr(e)))
            log.warning("Failed to parse %s (%s): %s", raw_id, raw_name, e)
    return cards, failures


def sort_cards(cards: list[dict[str, Any]]) -> list[dict[str, Any]]:
    def key(c: dict[str, Any]) -> tuple[str, int, str]:
        try:
            num = int(c["collectorNumber"])
        except ValueError:
            num = 1 << 31
        return c["setCode"], num, c["id"]
    return sorted(cards, key=key)


def write_output(cards: list[dict[str, Any]]) -> None:
    OUT_PATH.parent.mkdir(parents=True, exist_ok=True)
    OUT_PATH.write_text(
        json.dumps(cards, indent=2, ensure_ascii=False) + "\n",
        encoding="utf-8",
    )
    log.info("Wrote %d cards to %s", len(cards), OUT_PATH.relative_to(REPO_ROOT))


def write_failures(failures: list[ParseFailure]) -> None:
    if not failures:
        if FAILURE_LOG_PATH.exists():
            FAILURE_LOG_PATH.unlink()
        return

    lines = ["# id\tname\treason"]
    lines.extend(f"{f.raw_id}\t{f.raw_name}\t{f.reason}" for f in failures)
    FAILURE_LOG_PATH.write_text("\n".join(lines) + "\n", encoding="utf-8")
    log.warning(
        "Wrote %d parse failures to %s — fill these in by hand.",
        len(failures), FAILURE_LOG_PATH.relative_to(REPO_ROOT),
    )


def main() -> int:
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")

    session = RateLimitedSession(REQUEST_DELAY_SEC)
    html = fetch_gallery_html(session)
    next_data = extract_next_data(html)
    items = find_gallery_items(next_data)
    log.info("Gallery returned %d card items", len(items))

    cards, failures = parse_all(items)
    cards = sort_cards(cards)
    log.info("Parsed %d cards (%d failures)", len(cards), len(failures))

    write_output(cards)
    write_failures(failures)
    return 0


if __name__ == "__main__":
    sys.exit(main())
