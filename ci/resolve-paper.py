#!/usr/bin/env python3
"""Resolve the newest Paper 1.21.11 build + its server-jar download URL
from the fill.papermc.io v3 builds response.

Usage: resolve-paper.py <builds.json>
Prints one JSON object: {"id": ..., "url": ..., "sha256": ..., "name": ...}
(url/name empty strings when nothing usable was found).
"""
import json
import sys


def newest_build(builds):
    items = list(builds)
    if isinstance(items, dict):
        items = [
            b for (k, b) in builds.items()
            if isinstance(b, dict) and b.setdefault("_id", k) is not None
        ]
    items = [b for b in items if isinstance(b, dict)]
    def key(build):
        raw = build.get("id", build.get("_id", -1))
        try:
            return int(raw)
        except (TypeError, ValueError):
            return -1
    items.sort(key=key)
    return items[-1] if items else None


def main():
    with open(sys.argv[1]) as handle:
        data = json.load(handle)
    builds = data.get("builds", data) if isinstance(data, dict) else data
    best = newest_build(builds)
    result = {"id": "", "url": "", "sha256": "", "name": ""}
    if best is not None:
        bid = best.get("id", best.get("_id", ""))
        downloads = best.get("downloads", {}) or {}
        for key, val in downloads.items():
            if not isinstance(val, dict) or "url" not in val:
                continue
            candidate_name = str(val.get("name", key)).lower()
            if "paper" in candidate_name or str(key).startswith("server"):
                result = {
                    "id": bid,
                    "url": val["url"],
                    "sha256": (val.get("checksums") or {}).get("sha256", ""),
                    "name": val.get("name") or f"paper-1.21.11-{bid}.jar",
                }
                break
    print(json.dumps(result))


if __name__ == "__main__":
    main()
