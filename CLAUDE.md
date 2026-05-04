# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

MapTool is a Java desktop Virtual Tabletop (VTT) application for tabletop RPGs. It runs as both a client and an embedded server, supports multi-player networked play (direct, NAT-traversal via UPnP, and WebRTC), and exposes a programmable macro language used inside campaigns.

Java 21 (Temurin), JavaFX 22, Swing UI, JIDE/FlatLaf/TinyLaF themes, libGDX for some rendering, GraalVM JS engine for scripting, Protobuf for the network wire protocol.

## Build / Run / Test

The build is **Gradle multi-module** with `./gradlew` (Gradle 8.2.1). Default tasks are `clean build` (see `defaultTasks` in `build.gradle`).

```bash
./gradlew run                      # Run MapTool from source (main: net.rptools.maptool.client.LaunchInstructions)
./gradlew build                    # Compile, instrument forms, run all tests, package
./gradlew test                     # Run JUnit 5 tests (Mockito wired as a javaagent)
./gradlew :common:test             # Run tests for one subproject
./gradlew test --tests net.rptools.maptool.client.functions.SomeTest    # Single test class
./gradlew test --tests "*SomeTest.someMethod"                            # Single test method
./gradlew spotlessCheck            # Verify formatting — CI runs this
./gradlew spotlessApply            # Auto-fix formatting (Google Java Format 1.24.0 + license header)
./gradlew shadowJar                # Build the fat jar in releases/
./gradlew jpackageImage / jpackage # Build native app image / installer (uses bundled JDK 21 download)
```

Pass extra JVM args to `run` via `-Dexec.args="..."`. The build defines a long list of `--add-opens` JVM flags in `javaArgs` (build.gradle ~line 95) that are required for Swing/JavaFX reflection — keep these in sync if you add new modules.

CI (`.github/workflows/verify-build.yml`) runs `spotlessCheck` and then `./gradlew build --no-daemon` on Windows/Linux/macOS with JDK 21.

## Module Layout

The Gradle subprojects are declared in `settings.gradle`:

- **(root)** — the MapTool app itself. Sources under `src/main/java/net/rptools/maptool/{client,server,model,events,transfer,util,common}`. The `client` package is the Swing/JavaFX UI, macros, scripting, and asset handling; `server` is the in-process game server; `model` is the campaign/zone/token/grid domain.
- **`common`** — shared utilities (`net.rptools.lib.*`) usable by tooling and the app.
- **`dicelib`** — dice-roll expression parser (`net.rptools.dicelib.expression`). Used by the macro engine for roll evaluation.
- **`clientserver`** — transport layer (`net.rptools.clientserver.simple.{connection,server,webrtc}`). Provides socket and WebRTC connection abstractions; the MapTool `server` package builds on top.
- **`messages`** — Protobuf definitions only. `.proto` files in `messages/src/main/proto/` are compiled to Java under `net.rptools.maptool.server.proto`. **All wire-protocol changes go here.** `message.proto` is the top-level oneof envelope listing every message type.
- **`tools`** — small standalone utilities (e.g. `CreateGridFile`, `VisibilityInspector`); not shipped with the app.

Cross-module rule: anything that needs to be visible to the wire protocol must be a Protobuf DTO (suffix `Dto` or `Msg` per the convention noted in `message.proto`).

## Architectural Notes That Are Not Obvious From the Code

### IntelliJ GUI Forms are instrumented at compile time
`build.gradle` defines an `instrumentForms` task that runs IntelliJ's `javac2` over `.form` files alongside compiled classes. `compileJava` writes to `build/classes/java/main-uninstrumented` and `instrumentForms` syncs the instrumented output into the real classes dir. **Do not rely on `compileJava`'s output directly** — downstream tasks must depend on `classes` (which depends on `instrumentForms`). When editing a Swing dialog backed by a `.form` file, the bound fields are wired in by this step, not by hand.

### Single-process client+server
A MapTool instance can host a server (`net.rptools.maptool.server.MapToolServer`) and connect a client to it in the same JVM. `MapTool.java` / `MapToolClient.java` orchestrate this. When debugging connection issues, remember that "host a game" means an in-process loopback through the same `clientserver` connection abstraction used for remote peers.

### Protobuf is the source of truth for messages
Any new client↔server message: add a proto in `messages/src/main/proto/`, add it to the `oneof` in `message.proto`, then implement handlers in `ClientMessageHandler` / `ServerMessageHandler`. The generated Java sits under `messages/build/generated/sources/proto/main/java` (already on the IDE source path via `messages/build.gradle`).

### Macro / scripting surface
- `client/functions/` — every public macro function (`*Function.java`) registered with the parser. Adding a function means subclassing the parser's `Function` and registering it in `MapToolExpressionParser` / `MapToolLineParser`.
- `client/macro/` — slash-command macros (`MacroManager`, `MacroDefinition`).
- `client/script/javascript/` — GraalVM JS scripting integration.
- `dicelib` — roll expressions (`d20`, `2d6kh1`, etc.) consumed by the macro engine.

### Versioning is git-driven
`build.gradle` reads the current git tag/hash via the `git-version` plugin to produce `appSemVer`, `tagVersion`, `revision`, and the Sentry `environment`. Building from a source tarball (no `.git`) requires `-PnoGit` plus `-PgitTag=...` `-PgitCommit=...`. Don't hard-code version strings.

## Code Style (enforced)

- Google Java Style Guide — enforced by Spotless (`googleJavaFormat 1.24.0`). Run `./gradlew spotlessApply` before pushing; `spotlessCheck` gates CI.
- License header (`spotless.license.java`) is auto-prepended to every `src/**/*.java` file. A few legacy files are excluded in `build.gradle` (`JTextAreaAppender`, `GifDecoder`, the `Flat*ContrastIJTheme` files, themes `Utils.java`).
- Per `doc/Code_Style_and_Guidelines.md`:
  - **No hard-coded user-facing strings.** Use `I18N.getText(propertyKey)` and add the key to `i18n.properties`. The `MapTool.show*()` methods (`showError`, `showWarning`, …) take property keys, not raw strings.
  - Prefer parameter names that differ from member fields so `this.` disambiguation isn't needed (relaxed for short setters).
  - Errors that aren't truly ignorable (e.g. `InterruptedException` on a timer) must be reported via `MapTool.showError(propertyKey, throwable)` — that path also logs to `~/.maptool*/log.txt`.
  - Use platform constants (`File.separator`, `AppActions.menuShortcut`) instead of magic strings.

## Test Layout

Tests live under `src/test/java/net/rptools/maptool/{client,model,transfer,util}` (and per-subproject `src/test/java`). JUnit 5 + Mockito; Mockito is loaded as a Java agent (`shared.gradle`) so inline mocks work on JDK 21 without warnings.
