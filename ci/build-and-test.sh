#!/usr/bin/env bash
# CoreMC CI build: compile main -> fetch JUnit -> compile tests -> run tests
# -> package jar into sandbox/plugin/. Mirrors `mvn package` semantics for an
# environment where the sandbox cannot reach Maven Central.
set -euo pipefail

git clone --depth 1 --filter=blob:none --sparse https://github.com/NicolasLasch/ZoneGuard zg
(cd zg && git sparse-checkout set .gradle-home)
mkdir -p classes test-classes sandbox/plugin
VER=$(grep -m1 -oE '<version>[0-9.]+' pom.xml | grep -oE '[0-9.]+')
sed -i "s/@project.version@/$VER/g" src/main/resources/plugin.yml
CP=$(find zg/.gradle-home -name '*.jar' | grep -v sources | tr '\n' ':')

echo "==> compile main sources"
javac --release 21 -nowarn -cp "$CP" -d classes $(find src/main/java -name '*.java')

echo "==> fetch junit-platform-console-standalone 1.10.2"
curl -fsSL -o /tmp/junit-runner.jar \
  https://repo1.maven.org/maven2/org/junit/platform/junit-platform-console-standalone/1.10.2/junit-platform-console-standalone-1.10.2.jar
echo "junit runner sha256: $(sha256sum /tmp/junit-runner.jar | cut -d' ' -f1)"

echo "==> compile tests"
javac --release 21 -nowarn -cp "classes:$CP/tmp/junit-runner.jar" \
  -d test-classes $(find src/test/java -name '*.java')

echo "==> run tests"
java -jar /tmp/junit-runner.jar execute \
  --class-path "classes:test-classes" \
  --scan-class-path \
  --fail-if-no-tests \
  --details=summary

echo "==> package jar"
jar --create --file "sandbox/plugin/CoreMC-$VER.jar" -C classes . -C src/main/resources .
cp "sandbox/plugin/CoreMC-$VER.jar" sandbox/plugin/CoreMC.jar
echo "BUILT sandbox/plugin/CoreMC-$VER.jar"
