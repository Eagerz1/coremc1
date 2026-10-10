#!/usr/bin/env python3
"""Offline checks for external deployment files; no server/network required."""
from __future__ import annotations
import pathlib
import re
import struct

ROOT = pathlib.Path(__file__).resolve().parents[1]
failures: list[str] = []

def check(condition, message):
    if not condition:
        failures.append(message)

tab = (ROOT / "standalone/TAB/config.yml").read_text()
properties = (ROOT / "standalone/server.properties.example").read_text()
lproles = (ROOT / "luckperms/roles.commands.txt").read_text()
chat = (ROOT / "coremc/chat-ranks-overlay.yml").read_text()
velocity = (ROOT / "velocity/velocity.toml.example").read_text()

for feature in ["scoreboard", "bossbar", "scoreboard-teams", "playerlist-objective"]:
    m = re.search(rf"(?m)^{re.escape(feature)}:\s*\n\s+enabled:\s*(true|false)", tab)
    check(m is not None and m.group(1) == "false", f"TAB must not own {feature} initially")
check("online-mode=true" in properties and "online-mode=false" not in properties,
      "Standalone mode MUST authenticate at Paper")
check("player-info-forwarding-mode = \"modern\"" in velocity,
      "Velocity future forwarding must be modern")
check("online-mode = true" in velocity, "Velocity must authenticate players")
for rank in ("core", "coreplus", "coreplusplus", "helper", "mod", "admin", "owner"):
    check(f"lp creategroup {rank}" in lproles, f"Missing group: {rank}")
for rank in ("core", "coreplus", "coreplusplus"):
    check(f"coremc.rank.{rank}" in chat, f"Missing CoreMC rank: {rank}")
check("lp group core permission set coremc.*" not in lproles, "Wildcard privilege in donor group")
check(not re.search(r"(?m)^lp group (core|coreplus|coreplusplus) permission set (?:\*|luckperms\.\*|coremc\.\*)", lproles),
      "Donor wildcard privilege detected")

icon = ROOT / "build/server-icon.png"
if icon.exists():
    data = icon.read_bytes()
    check(data[:8] == b"\x89PNG\r\n\x1a\n", "Generated icon is not a PNG")
    if len(data) >= 24:
        check(struct.unpack("!II", data[16:24]) == (64, 64), "Icon is not 64x64")
hub = ROOT / "build/lobby-shell-commands.txt"
if hub.exists():
    lines = [x for x in hub.read_text().splitlines() if x and not x.startswith("#")]
    check(len(lines) >= 15, "Lobby command file is unexpectedly short")
    check(all(x.startswith("execute in minecraft:coremc_spawn run ") for x in lines),
          "Lobby command not constrained to the explicit lobby dimension")

if failures:
    for item in failures:
        print("FAIL:", item)
    raise SystemExit(1)
print("PASS: CoreMC external server templates validated")
