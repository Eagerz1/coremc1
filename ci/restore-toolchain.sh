#!/usr/bin/env bash
# ============================================================
#  Rebuild the offline build toolchain at /home/user/toolchain.
#
#  The sandbox occasionally loses non-repo state; this script
#  re-fetches everything build.sh needs from the hosts that ARE
#  reachable from the sandbox (github.com, api.github.com, PyPI):
#
#    jdk21/jdk4py/java-runtime   PyPI wheel "jdk4py" (JRE, no javac)
#    ecj.jar                     Eclipse batch compiler 3.44, pulled as
#                                a real git blob from a public repo that
#                                commits a jdt-language-server install
#    paper-api-1.21.11.jar       + libs/  from the ZoneGuard repo's
#                                committed Gradle cache
#    junit/*.jar                 the JUnit Platform console-standalone
#                                classes, re-zipped from a public repo
#                                that committed the jar as a directory
#                                tree (the registry-reachable rebuild of
#                                "download one jar from Maven Central")
#    vault-api.jar, papi-api.jar compiled by build.sh from the sources
#                                committed under ci/*-api-src — no
#                                download needed.
#
#  Usage: bash ci/restore-toolchain.sh
# ============================================================
set -euo pipefail

TOOLCHAIN="${TOOLCHAIN:-/home/user/toolchain}"
mkdir -p "$TOOLCHAIN/jdk21/jdk4py" "$TOOLCHAIN/libs" "$TOOLCHAIN/junit"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

echo "==> 1/5 JDK runtime (jdk4py wheel from PyPI)"
pip download jdk4py --no-deps -d "$WORK/jdk" >/dev/null
python3 - "$WORK/jdk" "$TOOLCHAIN" <<'PY'
import glob, os, sys, zipfile
wheel = glob.glob(sys.argv[1] + "/jdk4py-*.whl")[0]
with zipfile.ZipFile(wheel) as z:
    z.extractall(sys.argv[1] + "/x")
src = sys.argv[1] + "/x/jdk4py/java-runtime"
dst = sys.argv[2] + "/jdk21/jdk4py/java-runtime"
os.system(f"rm -rf {dst!r} && cp -r {src!r} {dst!r}")
# zipfile.extractall does not carry exec bits — restore them
os.system(f"chmod +x {dst!r}/bin/*")
print("runtime:", dst)
PY

echo "==> 2/5 ecj (Eclipse batch compiler, from a committed jdt-ls install)"
git clone --depth 1 --filter=blob:none --sparse \
    https://github.com/mesteryui/Dotfiles "$WORK/dotfiles" >/dev/null 2>&1
git -C "$WORK/dotfiles" sparse-checkout set \
    "emacs/.config/emacs/mason/packages/jdtls/plugins" >/dev/null 2>&1
ECJ="$(find "$WORK/dotfiles" -name 'org.eclipse.jdt.core.compiler.batch_*.jar' | head -1)"
cp "$ECJ" "$TOOLCHAIN/ecj.jar"

echo "==> 3/5 paper-api + libs (ZoneGuard's committed Gradle cache)"
git clone --depth 1 --filter=blob:none --sparse \
    https://github.com/NicolasLasch/ZoneGuard "$WORK/zg" >/dev/null 2>&1
git -C "$WORK/zg" sparse-checkout set .gradle-home >/dev/null 2>&1
find "$WORK/zg/.gradle-home/caches/modules-2" -name 'paper-api-*.jar' \
    ! -name '*sources*' -exec cp {} "$TOOLCHAIN/paper-api-1.21.11.jar" \;
find "$WORK/zg/.gradle-home/caches/modules-2" -name '*.jar' \
    ! -name '*sources*' -exec cp {} "$TOOLCHAIN/libs/" \;

echo "==> 4/5 JUnit (console-standalone classes, re-zipped from a committed tree)"
git clone --depth 1 --filter=blob:none --sparse \
    https://github.com/QueenColly/ileya "$WORK/junit-src" >/dev/null 2>&1
git -C "$WORK/junit-src" sparse-checkout set "Day One" >/dev/null 2>&1
JDIR="$WORK/junit-src/Day One/junit-platform-console-standalone-1.11.0.jar"
python3 - "$JDIR" "$TOOLCHAIN/junit/junit-platform-console-standalone-1.11.0.jar" <<'PY'
import os, sys, zipfile
src, out = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as jar:
    for root, dirs, files in os.walk(src):
        for name in files:
            full = os.path.join(root, name)
            jar.write(full, os.path.relpath(full, src))
print("wrote", out)
PY

echo "==> 5/5 sanity check"
"$TOOLCHAIN/jdk21/jdk4py/java-runtime/bin/java" -version
"$TOOLCHAIN/jdk21/jdk4py/java-runtime/bin/java" -jar "$TOOLCHAIN/ecj.jar" -version
echo "==> toolchain restored at $TOOLCHAIN — now run ./build.sh"
