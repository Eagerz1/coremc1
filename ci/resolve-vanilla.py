#!/usr/bin/env python3
"""Resolve the vanilla Mojang server jar for a Minecraft version.

Usage: resolve-vanilla.py <minecraft-version> <out-dir>

Follows launchermeta -> version json -> downloads.server.url; downloads
into <out-dir>/mojang_<version>.jar and prints its path on stdout.
Exits non-zero (with diagnostics on stderr) if resolution fails.
"""
import json
import sys
import urllib.request

V_MANIFEST = "https://launchermeta.mojang.com/mc/game/version_manifest_v2.json"


def fetch_json(url):
    with urllib.request.urlopen(url, timeout=60) as response:
        return json.load(response)


def main():
    version = sys.argv[1]
    out_dir = sys.argv[2]
    manifest = fetch_json(V_MANIFEST)
    entry = next(
        (v for v in manifest["versions"] if v["id"] == version), None
    )
    if entry is None:
        raise SystemExit(f"version {version} not found in Mojang manifest")
    version_json = fetch_json(entry["url"])
    server = version_json["downloads"]["server"]
    url = server["url"]
    sha1 = server["sha1"]
    print(f"vanilla {version}: {url} (sha1 {sha1})", file=sys.stderr)
    target = f"{out_dir}/mojang_{version}.jar"
    urllib.request.urlretrieve(url, target)
    import hashlib

    actual = hashlib.sha1(open(target, "rb").read()).hexdigest()
    if actual != sha1:
        raise SystemExit(f"sha1 mismatch for {target}: {actual} != {sha1}")
    print(target)


if __name__ == "__main__":
    main()
