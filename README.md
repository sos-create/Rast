# Rast

Rast is a Paper 1.21.4 plugin for Horizon clients. It does not alter the settings of ordinary Java clients.

## Horizon compatibility

The current Horizon native client identifies itself with the client brand "horizon". Rast reads the brand supplied by the client and applies settings only to matching players. This is a convenience filter, not authentication: a modified client can spoof its brand.

Horizon’s connection to older Java servers is still experimental. For a Paper 1.21.4 server, the Horizon client must have its older-server adapter enabled with HORIZON_OLDER_SERVERS=1 before starting the game. Rast does not replace that protocol adapter and cannot repair packet translations that Horizon itself does not yet support.

## What Rast optimizes

- Applies personal Paper view distance, chunk send distance, and simulation distance to Horizon players only.
- Rechecks the profile every five seconds.
- Uses a normal profile while the server is healthy.
- When the server's one-minute TPS falls below the configured threshold, switches only Horizon players to a lower chunk/simulation profile. Ordinary Java players are not modified.
- Re-evaluates the profile automatically as TPS recovers.
- /rast reload reloads the configuration and re-detects online players.

This is adaptive per-player chunk delivery and simulation tuning, not a rewrite of Paper's chunk generator, networking stack, or Horizon's Rust protocol adapter. Bukkit/Paper does not expose a supported API for replacing Horizon's custom chunk wire format or injecting new block-registry translations.

## Configuration

Edit plugins/Rast/config.yml:

- accepted-client-brands: brand substrings accepted as Horizon.
- profiles.normal: normal personal distances.
- profiles.low-tps: reduced distances used under server load.
- adaptive.tps-threshold: one-minute TPS threshold for the reduced profile.
- adaptive.enabled: enable or disable automatic profile switching.

All distance values are clamped to 2..32.

## Build

Requires JDK 21 and Maven 3.9+.

    mvn --batch-mode clean verify

Output: target/Rast-1.0.0.jar. GitHub Actions builds and uploads the JAR.
