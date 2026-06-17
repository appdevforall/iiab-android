# IIAB Controller — Architecture Overview

> Companion to `TECH_DEBT_PLAN.md`. This document explains **how the `controller/` module works today** so that technical-debt decisions can be made with the design in full view. Scope: 34 Java files, ~11,707 LOC, `app/src/main/java/org/iiab/controller/`. Status per `controller/README.md`: proof-of-concept.

## 1. What the app does

The Controller is a rootless Android port of **Internet-in-a-Box (IIAB)**. It provisions and runs a full Debian/IIAB Linux server *inside the phone* using native binaries (`proot`, `aria2c`, `tar`, `xz`, `rsync`) shipped as `.so` libraries, with Termux as the execution substrate. The user can deploy content modules, monitor the embedded server, share content peer-to-peer over the local network, and expose a captive portal — all without rooting the device.

## 2. Top-level components

| Layer | Class(es) | Responsibility |
|-------|-----------|----------------|
| App bootstrap | `IIABApplication` | Installs Conscrypt as security provider. No DI, no state container. |
| Shell / hub | `MainActivity` (2,209 LOC) | Hosts the `ViewPager2`/`TabLayout` + 4 fragments; **also** owns the OTA updater, embedded multi-session Termux terminal, PRoot server lifecycle, connectivity polling, broadcast receivers, permissions, theming, and a runtime-generated bash CLI. |
| Navigation | `MainPagerAdapter` | Wires the four fragments into the pager. |
| First-run | `SetupActivity` | Permission + locale wizard. |
| Tab 1 — Deploy | `DeployFragment` (2,754 LOC), `InstallationPlanner`, `PRootEngine`, `TarExtractor`, `ApkServer`, `ModuleRegistry`, `Aria2Manager`, `Aria2NetworkProfiler` | Download → verify → extract → run rootfs/modules under proot. |
| Tab 2 — Dashboard | `DashboardFragment` (762 LOC), `DashboardManager`, `ResourceGaugeView`, `MultiResourceGaugeView` | Live status: CPU/mem/disk/battery/network gauges; pings `localhost:8085` + each module to drive a state machine. |
| Tab 3 — Sync | `SyncFragment` (810 LOC), `SyncHandshakeHelper`, `RsyncManager`, `ApkServer` | Peer-to-peer content sharing via an rsync daemon + QR handshake; APK distribution server. |
| Tab 4 — Usage | `UsageFragment`, `AppListActivity` | Config/log/server-control panel; per-app VPN allowlist. |
| Connectivity | `IIABAdbManager`, `AdbPairingReceiver`, `TermuxCallbackReceiver` | Wireless-debugging ADB client + PIN pairing; Termux exit-code callbacks. |
| Background | `WatchdogService` (foreground service), `IIABWatchdog`, `ServiceReceiver` | Hold CPU + WiFi locks to protect long transfers; heartbeat "blackbox" log. |
| Portal / sharing | `PortalActivity` (WebView + SOCKS proxy), `QrActivity` | Captive portal and QR sharing. |
| Cross-cutting | `Preferences`, `LogManager`, `BiometricHelper`, `BatteryUtils`, `ProgressButton` | Settings, logging, auth gate, battery-optimization prompts, custom views. |

## 3. How data and control flow

1. **Provisioning (Deploy):** `DeployFragment` asks `InstallationPlanner` to scrape the Kiwix catalog and project storage needs, then drives `Aria2Manager` to download a rootfs/ZIM, `TarExtractor` to unpack it, and `PRootEngine` to execute bootstrap/Ansible inside the container. State is tracked by ~7 boolean flags reconciled manually.
2. **Runtime monitoring (Dashboard):** every 5 s, `DashboardFragment` reads `/proc` + battery + network and spawns a worker thread that HTTP-pings the local server and each module, feeding `evaluateSystemState`.
3. **Sharing (Sync):** `SyncFragment` discovers a LAN interface, starts `RsyncManager`'s daemon, and encodes IP/port/user/password into a QR via `SyncHandshakeHelper`; a peer scans it and pulls content.
4. **Liveness:** `WatchdogService` holds a `PARTIAL_WAKE_LOCK` + high-perf WiFi lock so transfers survive screen-off; `IIABWatchdog` writes a rotating heartbeat log.

## 4. The architectural shape (and why debt accumulates)

There is **no layering**: no ViewModel, no Repository/data layer, no dependency injection, no domain model. Two **God classes** (`MainActivity`, `DeployFragment`) concentrate ~8 responsibilities each. Shared state lives in **public mutable fields on `MainActivity`** that fragments read and write directly — even from background threads. Concurrency is ~30 hand-rolled `new Thread()` calls with results posted via `runOnUiThread`, no executor, no cancellation, no lifecycle awareness. Native processes are invoked by concatenating shell strings passed to `sh -c`. There are **zero automated tests**.

This shape is *why* every new feature deepens the debt: there is no seam to add behavior without growing a God class, and no test to make a refactor safe. The remediation strategy in `TECH_DEBT_PLAN.md` therefore puts a **safety net (tests + CI) first**, closes **security holes** second, and only then decomposes the monoliths.

## 5. Deliberate constraints to respect

Some "smells" are load-bearing and must not be naively removed:

- **`targetSdk 28`** is intentional — it exempts the app from Android 10–14 runtime enforcement so proot's W^X memory model works. Raising it (required for Play Store) is a *project*, not a cleanup (see `TECH_DEBT_PLAN.md` §Phase 4).
- **`usesCleartextTraffic="true"`** is currently required for the local rsync/APK/HTTP servers. The goal is to *scope* it via a network-security-config, not to flip it off.
- **`MANAGE_EXTERNAL_STORAGE`** and **`PURPOSE`-broad keystore keys** exist for real reasons but are over-broad; tighten, don't delete.
- The build's **SHA256 audit of native binaries at build time** is a genuine strength — preserve it.
