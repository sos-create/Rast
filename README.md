# Rast

Rast is a Paper 1.21.4 plugin that applies per-player chunk-distance settings only to recognized Horizon clients.

## What it does

- Reads the client brand reported by the Minecraft client.
- Waits briefly for the brand to arrive after login.
- Applies personal view distance, chunk send distance, and simulation distance only when the brand contains one of the configured identifiers.
- Leaves ordinary Java clients untouched.
- Does not force-load chunks and does not change global server distances.
- Rechecks settings once after login in case the server overwrites the initial per-player values.
- `/rast reload` reloads the configuration and rechecks online players.

## Important identification limitation

A client brand is supplied by the client and can be changed or spoofed. It identifies a client implementation, not cryptographically the launcher. The default accepted value is `horizon`; confirm the exact brand reported by the Horizon build and change `accepted-client-brands` if necessary. A proxy may also replace or hide the brand.

## Configuration

Edit `plugins/Rast/config.yml`:

- `view-distance`: per-player view distance.
- `send-view-distance`: maximum chunk send distance for that player.
- `simulation-distance`: per-player simulation distance.
- `accepted-client-brands`: client brand substrings that enable these settings.

All three distance values are clamped to 2..32. These are per-player Paper settings; they do not translate an incompatible network protocol or convert blocks from newer Minecraft versions to 1.21.4.

## Build

Requires JDK 21 and Maven 3.9+.

    mvn --batch-mode clean verify

Output: `target/Rast-1.0.0.jar`. GitHub Actions builds and uploads the JAR.
