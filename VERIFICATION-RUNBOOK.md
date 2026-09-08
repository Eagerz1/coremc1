# CoreMC restoration — verification runbook

State: all restoration commits (A→F) are implemented and committed locally on
`arena/01a077ab-coremc1`. Two environment capabilities are currently down and
block the final certification. This runbook is the exact recovery sequence.

## Blockers (as of last check)

1. **GitHub auth** (`gh api user` -> 401; push fails): reconnect GitHub in Arena.
2. **Sandbox egress to Minecraft infra** (papermc.io, spigotmc, geysermc all 000):
   a Paper server jar cannot be fetched right now. pypi.org (jdk4py works) and
   registry.npmjs.org remain reachable.

## Step 1 — push + CI (needs GitHub)

```bash
cd /home/user/coremc1
git push origin arena/01a077ab-coremc1   # abee30b..c2a396a (A→F in 4 commits)
# wait for workflow, then:
git pull origin arena/01a077ab-coremc1   # fetch tell-back:
#   sandbox/BUILD-OK  + sandbox/plugin/CoreMC-0.10.0-SNAPSHOT.jar
# If BUILD-FAIL appears: fix the compile error, push, repeat.
```

Notes: `ci/build-and-test.sh` runs javac against ZoneGuard's cached paper-api,
runs the full JUnit suite via the curated TestRunner, then packages the jar.
94/94 Java files were syntax-verified offline with tree-sitter-java.

## Step 2 — restore the live server (needs any Paper 1.21.11 binary)

Directory `/home/user/server12111` (previous host) was lost on re-provision.

```bash
JRE=/usr/local/lib/python3.11/dist-packages/jdk4py/java-runtime  # pip: jdk4py
mkdir -p /home/user/server12111/plugins/CoreMC /home/user/server12111/plugins
cd /home/user/server12111
curl -fLO 'https://api.papermc.io/v2/projects/paper/versions/1.21.11/builds/92/downloads/paper-1.21.11-92.jar'
mv paper-1.21.11-92.jar paper.jar
cat > server.properties <<'P'
server-port=25565
online-mode=false
view-distance=6
simulation-distance=5
spawn-protection=0
network-compression-threshold=-1
enable-command-block=false
max-tick-time=-1
P
echo 'eula=true' > eula.txt
mkfifo console.in
# server launch (run from the server dir!):
tail -f console.in | $JRE/bin/java -Xms2G -Xmx2G -jar paper.jar nogui | tee server.log
```

Jar slot: `cp /home/user/coremc1/sandbox/plugin/CoreMC-0.10.0-SNAPSHOT.jar plugins/`
(first boot with the 0.9.1 commit-A jar is fine for phase A re-verification —
`CoreMC-0.9.1.jar` survives in `coremc1/sandbox/plugin/`.)
Stop cleanly: `echo stop > console.in` (wait ~14s).

## Step 3 — journey matrix (bot project: /home/user/bot, mineflayer ready)

```bash
cd /home/user/bot
# Commit A (islands/themes/GUI):
PHASE=1 node restore-journey.js && PHASE=2 node restore-journey.js
# (restart server) PHASE=4 node restore-journey.js
# Commit B+C (upgrades hub, mining cube, crop regrowth):
PHASE=1 node upgrades-journey.js && PHASE=2 node upgrades-journey.js
PHASE=4 node upgrades-journey.js   # after restart
# Commit D (omnitool upgrades):
PHASE=1 node omni-journey.js && PHASE=2 node omni-journey.js && PHASE=4 node omni-journey.js
# Commit E (spawner lanes):
PHASE=1 node spawners-journey.js && PHASE=2 node spawners-journey.js && PHASE=4 node spawners-journey.js
```

Console commands used by journeys (FIFO): `credits set <name> <amt>`,
`skytokens set <name> <amt>`, `execute in <world> run summon/setblock ...`.
Player profile files: `plugins/CoreMC/profiles/<offline-uuid>.yml`.

## Acceptance (spec)

- `/is` GUI sections incl. Settings + Permissions; dedicated void world; 3 themes.
- `/is upgrades`: 27-slot, 6 categories, Crop Regrowth 0–20, Mining Cube to 5×5.
- OmniTool double-chest shows progression + 3 purchasable upgrades (enchants/smelter).
- `/spawners`: per-mob kill lanes -> per-mob small-chest submenu, 4 tiers spaced,
  LOCKED/UNLOCKED display; placed tiers have better spawn params.
- Persistence across restart: profiles v5 (currencies/kills/omni-upgrades), islands
  (world/theme/settings/permissions/upgrades), placeables.
- Zero CoreMC exceptions in server.log across all journeys.
