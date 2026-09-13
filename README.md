# CoreMC

CoreMC is a Paper plugin (Java 21, Paper 1.21.x). The previous
implementation was removed in its entirety — the plugin is being rebuilt
from scratch. What remains in this repository is the build pipeline and a
minimal plugin skeleton to keep that pipeline green.

## Building

The canonical build is Maven (single source of truth):

```bash
mvn package          # produces target/CoreMC-<version>.jar
```

- `paper-api` is a `provided` dependency
- tests run via JUnit 5 + Surefire (none yet — the rewrite starts at zero)

CI (`.github/workflows/build.yml`) compiles, tests and packages on every
push to `arena/**` branches, then commits build status and artifacts back
to `sandbox/`. `build.sh` mirrors the Maven build offline for the
development sandbox; it is not a second build system.

## Layout

| Path | Purpose |
|---|---|
| `src/main/java` | Plugin sources (currently one skeleton class) |
| `src/main/resources` | Bundled resources (`plugin.yml`) |
| `src/test/java` | Tests + the `TestRunner` harness used by offline/CI builds |
| `pom.xml` | Maven build definition |
| `ci/` | CI build script and Paper/vanilla server-jar resolvers |
| `sandbox/` | CI-committed build status, packaged jars, and Paper test-server bits |
