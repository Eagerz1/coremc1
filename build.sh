#!/usr/bin/env bash
# ============================================================
#  CoreMC offline sandbox build (mirrors `mvn package`)
#
#  The canonical build is Maven (see pom.xml). This script exists
#  because the sandbox has no Maven Central access: it uses the
#  Eclipse JDT batch compiler and locally mirrored dependencies to
#  produce the exact same CoreMC-x.y.z.jar. On a normal machine,
#  just run:  mvn package
# ============================================================
set -euo pipefail

ROOT="/home/user/coremc1"
TOOLCHAIN="${TOOLCHAIN:-/home/user/toolchain}"
JRE="${JRE:-$TOOLCHAIN/jdk21/jdk4py/java-runtime}"
JDT="$TOOLCHAIN/ecj.jar"
PAPER_API="$TOOLCHAIN/paper-api-1.21.11.jar"
VERSION="$(grep -m1 -o '<version>[^<]*' "$ROOT/pom.xml" | sed 's/<version>//')"

join_cp() { ls "$1"/*.jar | tr '\n' ':' ; }
LIB_CP="$(join_cp "$TOOLCHAIN/libs")"
JUNIT_CP="$(join_cp "$TOOLCHAIN/junit")"
MAIN_CP="$PAPER_API:${LIB_CP%:}"

# VaultAPI (official economy API sources, committed under ci/vault-api-src)
VAULT_API="$TOOLCHAIN/vault-api.jar"
if [[ ! -f "$VAULT_API" ]]; then
    echo "==> Compiling VaultAPI economy classes"
    rm -rf target/vault-api-classes
    mkdir -p target/vault-api-classes "$TOOLCHAIN"
    find ci/vault-api-src -name '*.java' > target/vault-sources.txt
    "$JRE/bin/java" -cp "$JDT" org.eclipse.jdt.internal.compiler.batch.Main \
        -21 -encoding UTF-8 -nowarn -proc:none \
        -cp "$MAIN_CP" \
        -d target/vault-api-classes \
        @target/vault-sources.txt
    python3 - "$VAULT_API" <<'VJAR'
import os, sys, zipfile
out = sys.argv[1]
with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as jar:
    for root, dirs, files in os.walk("target/vault-api-classes"):
        for name in files:
            full = os.path.join(root, name)
            jar.write(full, os.path.relpath(full, "target/vault-api-classes"))
print("wrote", out)
VJAR
fi
MAIN_CP="$VAULT_API:$MAIN_CP"

cd "$ROOT"
echo "==> CoreMC $VERSION — compiling main sources"
rm -rf target
mkdir -p target/classes target/test-classes

find src/main/java -name '*.java' > target/main-sources.txt
"$JRE/bin/java" -cp "$JDT" org.eclipse.jdt.internal.compiler.batch.Main \
    -21 -encoding UTF-8 -nowarn -proc:none \
    -cp "$MAIN_CP" \
    -d target/classes \
    @target/main-sources.txt 2>&1 | tee target/compile-main.log
if grep -q "ERROR" target/compile-main.log; then
    echo "!!! main compilation FAILED"
    exit 1
fi

echo "==> Copying resources (version substitution)"
# copy EVERY resource recursively (config, messages, schematics, future
# files) — the explicit list previously shipped a jar missing new files
# (v0.7.0 shop.yml bug), and maxdepth 1 later missed schematics/*.yml
find src/main/resources -type f | while read -r res; do
    rel="${res#src/main/resources/}"
    mkdir -p "target/classes/$(dirname "$rel")"
    sed "s/@project.version@/$VERSION/g" "$res" > "target/classes/$rel"
done

echo "==> Packaging target/CoreMC-$VERSION.jar"
python3 - "$VERSION" <<'PYEOF'
import os, sys, zipfile
version = sys.argv[1]
out = f"target/CoreMC-{version}.jar"
with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as jar:
    for root, dirs, files in os.walk("target/classes"):
        for name in files:
            full = os.path.join(root, name)
            rel = os.path.relpath(full, "target/classes")
            jar.write(full, rel)
print("wrote", out)
PYEOF

if [[ "${1:-}" != "--no-test" ]]; then
    echo "==> Compiling tests"
    find src/test/java -name '*.java' > target/test-sources.txt
    TEST_CP="target/classes:${JUNIT_CP}${LIB_CP}$VAULT_API:$PAPER_API"
    "$JRE/bin/java" -cp "$JDT" org.eclipse.jdt.internal.compiler.batch.Main \
        -21 -encoding UTF-8 -nowarn -proc:none \
        -cp "$TEST_CP" \
        -d target/test-classes \
        @target/test-sources.txt 2>&1 | tee target/compile-test.log
    if grep -q "ERROR" target/compile-test.log; then
        echo "!!! test compilation FAILED"
        exit 1
    fi

    echo "==> Running tests"
    "$JRE/bin/java" -cp "target/classes:target/test-classes:${JUNIT_CP}${VAULT_API}:${PAPER_API}:${LIB_CP%:}" \
        com.coremc.testrun.TestRunner
fi

echo "==> DONE: target/CoreMC-$VERSION.jar"
