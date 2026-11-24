# Rast

Rast is a Paper 1.21.4 server-side performance utility plugin.

## Current scope

- Periodically combines nearby dropped item stacks when their item type and metadata match and the combined amount fits in one legal stack.
- Scans loaded chunks only; it never force-loads chunks.
- Uses a per-chunk comparison budget to bound work.
- Reports TPS, average MSPT, players, loaded chunks, entities and item entities.
- Does not change client rendering, Horizon's Rust-side optimizations, world rules, or vanilla protocol behavior.

## Build

Requires JDK 21 and Maven 3.9+.

```sh
mvn -B clean package
```

The plugin jar is created at `target/Rast-1.0.0.jar`.

## Install

1. Build the project or download the JAR from a successful GitHub Actions run.
2. Put `Rast-1.0.0.jar` in the Paper 1.21.4 `plugins` folder.
3. Restart the server.
4. Use `/rast status` to inspect the server and `/rast reload` to reload settings.

## Configuration

See `src/main/resources/config.yml`. Item merging can be disabled or tuned. Only loaded chunks are scanned. The merge operation requires identical item metadata and refuses to exceed the stack's maximum size.

## Horizon compatibility note

Rast is a server optimization plugin, not a protocol translator. Horizon currently targets Minecraft Java Edition 26.3; joining a 1.21.4 server requires Horizon's older-version protocol adapter. A Paper plugin cannot implement that client-side protocol adapter by itself.
