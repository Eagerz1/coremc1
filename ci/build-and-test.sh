#!/usr/bin/env bash
# Canonical CoreMC build: compile + JUnit tests + package with Maven.
# Requires JDK 21 and network access to repo.papermc.io / Maven Central
# (GitHub runners and normal dev machines satisfy this).
set -euo pipefail

mvn -B verify

echo
echo "Built jar:"
ls -la target/CoreMC-*.jar
