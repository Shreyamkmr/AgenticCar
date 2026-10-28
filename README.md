<p align="center">
  <img src="docs/images/banner.svg" alt="Agentic Car: your car's ECU, recorded in full and served to Claude over MCP" width="100%">
</p>

<p align="center">
  <a href="https://github.com/Shreyamkmr/AgenticCar/releases/latest"><img alt="Latest release" src="https://img.shields.io/github/v/release/Shreyamkmr/AgenticCar?style=flat-square&color=ff8a65"></a>
  <img alt="Android 8.0+" src="https://img.shields.io/badge/Android-8.0%2B-3DDC84?style=flat-square&logo=android&logoColor=white">
  <img alt="Kotlin 2.4" src="https://img.shields.io/badge/Kotlin-2.4-7F52FF?style=flat-square&logo=kotlin&logoColor=white">
  <img alt="Jetpack Compose" src="https://img.shields.io/badge/Jetpack%20Compose-Material%203-4285F4?style=flat-square&logo=jetpackcompose&logoColor=white">
  <img alt="Model Context Protocol" src="https://img.shields.io/badge/MCP-Streamable%20HTTP-d97757?style=flat-square">
  <a href="LICENSE"><img alt="MIT license" src="https://img.shields.io/github/license/Shreyamkmr/AgenticCar?style=flat-square"></a>
</p>

**Agentic Car** turns an Android phone and a cheap ELM327 Bluetooth adapter into a full-fidelity car data logger, then lets Claude read it. While you drive, it records **every signal your car's ECU supports**, compressed to about a byte per reading. Later, from your desk or anywhere, you switch on the phone's built-in **MCP server** and ask Claude about your trips.

> 💬 *"How did the engine warm up on this morning's drive?"*
>
> 💬 *"Were there any harsh braking events last week, and at what speed?"*
>
> 💬 *"My long-term fuel trim looks high: plot it against load and tell me if it points to a vacuum leak."*
>
> 💬 *"How much harder does the engine work on the hills of my commute? Map the route and show where it struggles."*

<p align="center">
  <img src="docs/images/dashboard.png" alt="Dashboard: live RPM and speed gauges, coolant, load and voltage, and the recording card showing 47 signals, the VIN and trouble codes" width="360">
</p>

---

## Contents

- [Highlights](#highlights)
- [How it works](#how-it-works)
- [Quick start](#quick-start)
- [Connect Claude](#connect-claude)
- [MCP tools](#mcp-tools)
- [What gets recorded](#what-gets-recorded)
- [Storage format](#storage-format)
- [Security model](#security-model)
- [Build from source](#build-from-source)
- [Project layout](#project-layout)
- [Limitations and roadmap](#limitations-and-roadmap)
- [License](#license)

## Highlights

| | |
|---|---|
| 🔍 **Everything the ECU offers** | Asks the car which Mode 01 PIDs it supports and polls all of them: 120+ decoded signals from 90+ standard PIDs, plus raw storage for anything unknown. Also reads the VIN and stored, pending and permanent trouble codes. |
| ⚡ **Built for slow adapters** | Tiered polling (250 ms for driving inputs, up to 5 min for constants) and up to **6 PIDs per request** on CAN cars, detected automatically. |
| 🗜️ **Tiny, lossless-where-it-matters storage** | One-minute blocks per signal, delta + varint encoded at the ECU's native resolution with a small noise deadband: **~1 byte per reading**. Exact min/max/mean are kept for every minute. |
| 🧠 **Made for AI analysis** | 9 MCP tools: trip summaries with derived insights, resampled time series, GPS routes, detected events (harsh braking, over-rev, overheating, DTC changes) and diagnostics. |
| ⛰️ **GPS, elevation and road grade** | Optional 1 Hz GPS track with sea-level altitude and a noise-resistant road grade, so Claude can compare engine load, RPM and fuel use on climbs, flats and descents. |
| 🌍 **Reach it from anywhere** | Wi-Fi with a short typeable code, or a public HTTPS URL through a **Cloudflare Tunnel that runs on the phone itself**: no PC or port forwarding needed. |
| 🔐 **Secure by default** | Secret-path tokens, the Wi-Fi code is refused through any tunnel, public access is off until you switch it on, and the server never listens on the mobile network. |
| 🧪 **Test without a car** | A built-in simulated ELM327 and ECU (debug builds) replays a realistic drive: discovery, multi-frame replies, a harsh brake, fault codes and a VIN. |

## How it works

```mermaid
flowchart TB
    subgraph Car
        direction LR
        ECU[Engine ECU] <-->|OBD-II / CAN| ELM[ELM327 Bluetooth adapter]
    end

    subgraph Phone["Android phone · foreground service"]
        direction LR
        SES["ObdSession<br/>discovery, tiered polling,<br/>6-PID batches"] --> REC["TelemetryRecorder<br/>trips, events,<br/>compression"]
        REC -->|1 s batches| DB[("Room / SQLite<br/>trips · signal_blocks · events")]
        DB --> MCP["MCP server<br/>Ktor + Netty<br/>Streamable HTTP"]
        MCP --- CF["cloudflared<br/>Android-patched"]
    end

    subgraph Clients["Claude"]
        direction LR
        CC[Claude Code]
        WEB["claude.ai<br/>web #amp; mobile"]
    end

    ELM <-->|Bluetooth SPP| SES
    MCP -->|"Wi-Fi · /mcp/#lt;code#gt;"| CC
    CF -->|"HTTPS · /mcp/#lt;long token#gt;"| WEB
```

1. **Connect.** The app opens a Bluetooth SPP link to the adapter, initialises it (`ATZ`, `ATE0`, `ATL0`, `ATS0`, `ATH0`, `AT SP 0`) and walks the `0100`/`0120`/… bitmaps to find every supported PID.
2. **Record.** A trip starts when the engine runs (RPM > 400 or charging voltage > 13.5 V) and ends 30 s after it stops. Each reading is compressed into its signal's open one-minute block; a single writer commits batches to Room every second.
3. **Serve.** The MCP server reads from Room, so recorded trips are available with or without the car connected.

## Quick start

1. **Install** the APK from the [latest release](https://github.com/Shreyamkmr/AgenticCar/releases/latest) (allow installs from your browser or file manager).
2. **Pair** your ELM327 adapter in Android's Bluetooth settings (PIN is usually `1234` or `0000`).
3. **Open Agentic Car**, grant *Nearby devices* and *Notifications* (and optionally *Location* for the GPS track), pick the adapter and tap **Connect**. With the ignition on, it discovers the car's signals and starts a trip when the engine runs.
4. **No car handy?** Pick **Simulated ELM327** in the adapter list instead.

The app records in a foreground service, so you can lock the phone and drive.

## Connect Claude

Switch on **MCP server** in the app. The card shows the server's address.

### Claude Code (same Wi-Fi)

```bash
claude mcp add --transport http agentic-car http://<phone-ip>:8080/mcp/<code>
```

`<code>` is the short 6-character token shown in the app. It is easy to type, and it is refused for any request that arrives through a tunnel.

> **Tip:** reserve the phone's IP address in your router's DHCP settings so the URL never changes.

### claude.ai, web and mobile (anywhere)

1. In the app, open **MCP server → Set up** (Public access).
2. Choose a tunnel:
   - **Quick tunnel**: free, no account. The `*.trycloudflare.com` URL changes every time the tunnel starts.
   - **Cloudflare named tunnel**: free account plus your own domain, for a fixed URL. In Zero Trust → Networks → Tunnels, add a published application pointing to `http://127.0.0.1:8080`, then paste the tunnel token and hostname into the app.
3. Switch **Public access** on, tap **Copy URL**, and add it in claude.ai under **Settings → Connectors → Add custom connector**.

Public access starts the bundled `cloudflared` on the phone, so it keeps working on mobile data. Switch it off and tunnelled requests are refused immediately.

## MCP tools

| Tool | What it returns |
|---|---|
| `get_vehicle_info` | VIN, OBD protocol, adapter, fuel type and every supported signal (key, name, unit, polling tier). |
| `get_latest_metrics` | Live values of every signal, trip status and recorder statistics. |
| `list_trips` | Trips, newest first: duration, distance, fuel used and L/100 km, max speed/RPM/coolant, idle share, average moving speed. |
| `get_trip_summary` | Min/max/mean of every signal, event counts, and insights: time in speed and RPM bands, warm-up time to 80 °C, charging and resting voltage, plus elevation (range, climb, descent) and engine load, RPM, fuel use and speed per road-grade band. Also works over any absolute time window. |
| `get_signal_series` | Chosen signals resampled onto a shared time axis (mean, min or max per bucket), with zoom via `from_s`/`to_s`. |
| `get_route` | The GPS track as rows of time, latitude, longitude, altitude, speed and road grade, with GPS distance and bounding box. |
| `get_events` | Harsh braking/acceleration, over-rev, overheating, low charging voltage, MIL changes, and stored/pending/permanent/cleared DTCs. |
| `get_diagnostics` | Current trouble codes, check-engine light, DTC count, distance and time with MIL on, and since codes were cleared. |
| `get_storage_stats` | Trips, readings received vs samples stored, bytes per reading, database and WAL size. |

The server also sends instructions describing the data model and a suggested workflow, so Claude knows to start with `get_vehicle_info` and `list_trips`.

## What gets recorded

| Tier | Period | Examples |
|---|---|---|
| Fast | 250 ms | RPM, speed, engine and absolute load, throttle, pedal, MAF, MAP, timing advance, fuel rate, torque |
| Medium | 1 s | Fuel trims, O2 and wideband sensors, coolant, intake and oil temperature, rail pressure, EGR, module and battery voltage |
| Slow | 5 s | Fuel level, catalyst temperatures, barometric and ambient readings, evap pressure, run time, odometer, MIL status |
| Static | 5 min | Fuel type, OBD standard, reference torque, distance and time since codes cleared |
| Diagnostics | 60 s | Stored (`03`), pending (`07`) and permanent (`0A`) trouble codes |

PIDs the car advertises but never answers are dropped after five attempts. Undecodable PIDs are stored raw as `pid_xx_raw`.

### GPS and road grade

With location permission, the phone's GPS adds `gps_latitude`, `gps_longitude`, `gps_altitude` (above sea level on Android 14+), `gps_speed`, `gps_bearing` and `gps_accuracy` at 1 Hz, plus a derived `road_grade`. GPS altitude is only good to ±5-15 m, so grade is never taken from consecutive fixes: altitude is smoothed over distance travelled (40 m), the slope is measured across at least 100 m, and fixes while stopped or with poor accuracy are ignored. On a constant slope this stays within about 1 % even with ±2 m altitude noise, and braking does not bias it. A position costs about 2 bytes.

## Storage format

Each signal of each trip is stored as **one row per minute** in `signal_blocks`:

- **Exact statistics**: reading count, min, max and mean of *every* reading, so summaries never decode samples.
- **Compressed samples**: values are quantised to the signal's native OBD resolution (lossless for linear PIDs), then stored as zig-zag varint deltas of time (10 ms ticks) and value. A per-signal deadband skips changes no larger than sensor noise, and every signal is still stored at least every 15 s.
- **Step-hold semantics**: a value holds until the next stored point, with error bounded by the deadband.

Measured with the simulator (47 signals):

| | |
|---|---|
| Readings received | 56,805 |
| Samples stored | 22,631 (40 %) |
| Sample payload | 58.6 KB (**1.03 bytes per reading**) |
| Database file | 128 KB, including indexes, trips and events |

That is roughly **0.5 MB per hour of driving** with every signal captured.

## Security model

- **Secret-path tokens.** The server only answers at `/mcp/<token>`; every other path returns 404.
  - A **short code** for Wi-Fi clients, refused when a request carries tunnel or proxy headers (`CF-Connecting-IP`, `X-Forwarded-For`, …), so it can never be brute-forced from the internet.
  - A **256-bit public token** for the tunnel, accepted through it only while *Public access* is on.
  - **New tokens** revokes every client at once.
- **No public listener.** The server binds only to `127.0.0.1` (for the on-phone tunnel) and the Wi-Fi address, never `0.0.0.0`, so it is unreachable over the mobile network.
- **Read-only tools.** MCP clients can read telemetry but cannot change anything on the phone or the car.
- **Secrets stay on the device.** Tokens are generated with `SecureRandom` and stored in app-private preferences; the Cloudflare tunnel token is passed to `cloudflared` through its environment, never its command line.

> Treat the public URL like a password: anyone who has it can read your car data. Tunnel providers may log URLs.

## Build from source

Requirements: Android Studio (or JDK 17+) and the Android SDK (compile SDK 37).

```bash
git clone https://github.com/Shreyamkmr/AgenticCar.git
cd AgenticCar
./gradlew assembleDebug        # gradlew.bat on Windows
./gradlew testDebugUnitTest    # parser, codec and simulator tests
```

### The bundled cloudflared

The public tunnel needs `app/src/main/jniLibs/arm64-v8a/libcloudflared.so`, which is not committed (25 MB). Build it with:

```powershell
powershell -ExecutionPolicy Bypass -File tools\cloudflared\build.ps1
```

The script downloads the official [cloudflared](https://github.com/cloudflare/cloudflared) release source and a checksum-verified Go toolchain, adds one file ([`android_dns.go`](tools/cloudflared/android_dns.go)) and cross-compiles a static `linux/arm64` binary in about a minute. The patch is needed because Android has no `/etc/resolv.conf`: it routes all DNS lookups to the resolvers the app passes in. CA certificates are found through `SSL_CERT_DIR`, with no code change. Without the binary the app still builds and works; only public access is unavailable.

## Project layout

```
app/src/main/java/com/example/agenticcar/
├── data/
│   ├── db/            Room entities, DAO, database (WAL)
│   ├── recording/     TelemetryRecorder, BlockCodec, TelemetryWriter
│   └── network/       Wi-Fi and hotspot address monitor
├── protocol/
│   ├── obd/           PID catalog, ELM327 parser (multi-frame, DTC, VIN)
│   └── mcp/           MCP tools, queries, server, tokens, Cloudflare tunnel
├── service/           Foreground service, ObdSession poller, Bluetooth and simulated links
└── ui/dashboard/      Compose dashboard and Public access screen
tools/cloudflared/     Android DNS patch and build script for the bundled tunnel
```

Stack: Kotlin 2.4 · Jetpack Compose (Material 3) · Hilt · Room · Coroutines/Flow · Ktor 3 + Netty · [MCP Kotlin SDK](https://github.com/modelcontextprotocol/kotlin-sdk) · cloudflared.

## Limitations and roadmap

- Tested end to end with the built-in simulator; real-car results depend on the vehicle and adapter. Cheap ELM327 clones vary in what they support.
- Mode 01 live data, Mode 03/07/0A trouble codes and Mode 09 VIN only; freeze-frame (Mode 02) and manufacturer-specific PIDs are not read yet.
- Quick-tunnel URLs change on every start; use a named tunnel for a fixed URL.
- The bundled tunnel binary is arm64-only.
- Road grade comes from GPS altitude, so short or gentle slopes are smoothed out; a phone barometer would sharpen it.
- Ideas: freeze-frame capture, CSV/Parquet export, data retention settings, barometer-assisted altitude.

Issues and pull requests are welcome.

## License

[MIT](LICENSE) © Shreyam. The APK bundles a modified build of Cloudflare's `cloudflared` (Apache-2.0); see [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

Not affiliated with Anthropic or Cloudflare. Reading OBD-II data is passive, but don't operate the app while driving.
