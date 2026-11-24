# Rast

Rast is a Paper 1.21.4 plugin that applies per-player chunk and simulation settings only to clients whose reported brand matches the configured Horizon brand. Ordinary Java clients are left unchanged.

## Horizon compatibility

The Horizon native client has a built-in older-server adapter. Its current documentation lists Java 1.21.4 as a recognized server version; the adapter is enabled by default. Set `HORIZON_OLDER_SERVERS=0` before launching Horizon only if you need to turn that adapter off.

Horizon translates protocol packets and registry IDs between its native game version and the older Java server. Its 1.21.4 bridge covers older chunk data, tooltip flags, nested item stacks, chat, team rules and entity metadata, but documented gaps remain in older item components, some inline registry data, picking, movement, combat and particles. A Paper plugin cannot replace that Rust-side adapter or guarantee support for packet layouts, items, entities, or interactions that Horizon itself does not yet translate.

## What Rast does

- Detects the reported client brand after login. The default accepted brand is `horizon`; matching is case-insensitive and configurable.
- Applies personal Paper view distance, chunk send distance, and simulation distance only to recognized Horizon players.
- Rechecks server performance every five seconds.
- Switches to the reduced profile only after sustained pressure: one-minute TPS below the configured threshold or JVM heap usage above its configured limit.
- Requires several consecutive checks before switching profiles, avoiding rapid profile changes from short TPS or memory spikes.
- Restores the normal profile after sustained recovery.
- Remembers each Horizon player's original per-player distances and restores them on plugin disable or `/rast reload`.
- Ignores ordinary Java clients and clients whose brand was not received.
- `/rast status` shows detected Horizon player count, TPS, heap usage and active profile.
- `/rast reload` reloads configuration, restores previous settings, and re-detects online players.

The brand is client-reported and can be spoofed; it is a compatibility selector, not authentication. This is per-player chunk delivery/simulation tuning, not a rewrite of Paper's chunk engine or a universal block/packet translator.

## Configuration

Edit `plugins/Rast/config.yml`:

- `accepted-client-brands`: case-insensitive substrings used to recognize Horizon.
- `profiles.normal`: normal per-player distances.
- `profiles.low-tps`: reduced distances used under server resource pressure.
- `adaptive.tps-threshold`: one-minute TPS threshold.
- `adaptive.max-heap-usage`: JVM heap usage ratio that triggers the reduced profile (default 0.88).
- `adaptive.stable-checks`: consecutive five-second checks required before changing profile (default 3).
- `adaptive.enabled`: enable or disable adaptive switching.
- `horizon-optimization.enabled`: master switch.

All distance values are clamped to 2..32.

## Build

Requires JDK 21 and Maven 3.9+.

    mvn --batch-mode clean verify

Output: `target/Rast-1.0.0.jar`. GitHub Actions builds and uploads the JAR.
