#!/usr/bin/env bash
# ============================================================
#  Assemble journey-server/ — the throwaway Paper server the
#  journey suite boots. Pulls the server jar + JRE from sandbox/
#  and the freshly built plugin jars from the build outputs.
#
#  Run from the repo root after ./build.sh:
#      bash journey/setup-server.sh
# ============================================================
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SERVER="$ROOT/journey-server"
SANDBOX="$ROOT/sandbox"

rm -rf "$SERVER"
mkdir -p "$SERVER/plugins"

# Paper server jar + the jlinked Java runtime (both under sandbox/,
# refreshed by CI) — fall back to the first paper jar we can find.
PAPER="$(ls "$SANDBOX"/paper-*.jar 2>/dev/null | head -1)"
if [[ -z "$PAPER" ]]; then
    echo "no paper jar under $SANDBOX (CI fetches it on push)" >&2
    exit 1
fi
cp "$PAPER" "$SERVER/"
# Paper's paperclip needs the Mojang vanilla jar; it normally downloads it
# (blocked in the sandbox) so drop the CI-fetched copy into paperclip's cache.
MOJANG="$(ls "$SANDBOX"/mojang_*.jar 2>/dev/null | head -1)"
if [[ -n "$MOJANG" ]]; then
    mkdir -p "$SERVER/cache"
    cp "$MOJANG" "$SERVER/cache/"
fi
JAVA="$SANDBOX/runtime/linux-x64/bin/java"
[[ -x "$JAVA" ]] || JAVA="$(command -v java)"
echo "paper: $PAPER"
echo "java:  $JAVA"
printf '%s\n' "$JAVA" > "$SERVER/java-path.txt"

# Plugin jars: CoreMC from this build + the mock PlaceholderAPI.
if [[ -f "$ROOT/target/CoreMC-1.0.0-SNAPSHOT.jar" ]]; then
    cp "$ROOT/target/CoreMC-1.0.0-SNAPSHOT.jar" "$SERVER/plugins/CoreMC.jar"
elif [[ -f "$SANDBOX/plugin/CoreMC.jar" ]]; then
    cp "$SANDBOX/plugin/CoreMC.jar" "$SERVER/plugins/CoreMC.jar"
else
    echo "no CoreMC jar — run ./build.sh first" >&2
    exit 1
fi
cp "$SANDBOX/plugin/PlaceholderAPI.jar" "$SERVER/plugins/PlaceholderAPI.jar"

# eula + minimal server.properties (offline mode, RCON, tiny view).
cat > "$SERVER/eula.txt" <<'EOF'
eula=true
EOF
cat > "$SERVER/server.properties" <<'EOF'
online-mode=false
enable-rcon=true
rcon.port=25575
rcon.password=journey123
level-type=minecraft\:flat
generate-structures=false
view-distance=4
simulation-distance=4
spawn-protection=0
max-players=5
difficulty=normal
motd=CoreMC journey
EOF

# Pre-seed the coin economy for the journey owner (offline UUID v3 of
# "OfflinePlayer:JOwner") so the shop/spawner flows have working money.
mkdir -p "$SERVER/plugins/CoreMC"
JOOWNER="$(python3 - <<'PY'
import hashlib, uuid
b = bytearray(hashlib.md5(b"OfflinePlayer:JOwner").digest())
b[6] = (b[6] & 0x0f) | 0x30
b[8] = (b[8] & 0x3f) | 0x80
print(uuid.UUID(bytes=bytes(b)))
PY
)"
cat > "$SERVER/plugins/CoreMC/balances.yml" <<EOF
$JOOWNER: 500000
EOF

echo "journey-server ready at $SERVER"
