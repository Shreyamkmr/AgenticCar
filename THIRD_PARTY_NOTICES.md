# Third-party notices

Agentic Car's own code is MIT-licensed (see [LICENSE](LICENSE)). The APK also contains the
components below, each under its own license.

## cloudflared (bundled binary, modified)

- **Project:** [cloudflare/cloudflared](https://github.com/cloudflare/cloudflared), release `2026.10.0`
- **License:** Apache License 2.0, full text in [tools/cloudflared/LICENSE-cloudflared.txt](tools/cloudflared/LICENSE-cloudflared.txt)
- **Copyright:** Cloudflare, Inc.
- **Modification:** one file was added, [`tools/cloudflared/android_dns.go`](tools/cloudflared/android_dns.go),
  which routes DNS lookups to servers given in the `CLOUDFLARED_DNS_SERVERS` environment variable
  (Android has no `/etc/resolv.conf`). No upstream file was changed. The binary is built by
  [`tools/cloudflared/build.ps1`](tools/cloudflared/build.ps1) and shipped as
  `lib/arm64-v8a/libcloudflared.so`.
- Its Go module dependencies are distributed under their own (permissive) licenses, as listed in
  the upstream repository.

## Libraries

| Component | License |
|---|---|
| [MCP Kotlin SDK](https://github.com/modelcontextprotocol/kotlin-sdk) | MIT |
| [Ktor](https://github.com/ktorio/ktor) | Apache-2.0 |
| [Netty](https://github.com/netty/netty) | Apache-2.0 |
| [Kotlin standard library, kotlinx.coroutines, kotlinx.serialization](https://github.com/JetBrains) | Apache-2.0 |
| AndroidX (Compose, Room, Lifecycle, Activity, Core) | Apache-2.0 |
| [Dagger / Hilt](https://github.com/google/dagger) | Apache-2.0 |

## Development-only content

`.claude/skills/` contains AI coding-assistant guidance used while developing this project; it is
not part of the app. Each skill keeps its own license file: `compose-kotlin-agent-skills` and its
sub-skills are MIT ([haidrrrry/compose-kotlin-agent-skills](https://github.com/haidrrrry/compose-kotlin-agent-skills)),
and `jetpack-compose-performance` and `android-permissions-security` are from
[android/skills](https://github.com/android/skills) under the terms in their `LICENSE.txt`.
