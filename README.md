# Rast

Rast is a Paper 1.21.4 server-side performance utility plugin.

## Implemented

- Rotating, bounded scan of already-loaded chunks; it does not force-load chunks.
- Configurable per-pass chunk budget and per-chunk item comparison budget.
- Conservative dropped-item merging: identical item metadata only, same owner and thrower, no overstacking, partial stack transfers supported, and pickup delay is never shortened.
- /rast status reports TPS, average MSPT, loaded chunks, entities, item entities and work performed by the last merge pass.
- /rast reload reloads configuration.
- No client-side changes and no modification of Minecraft protocol or Horizon client optimizations.

## Build

Requires JDK 21 and Maven 3.9+.

    mvn --batch-mode clean verify

Output: target/Rast-1.0.0.jar.

## Install

Copy the JAR into the Paper 1.21.4 plugins directory and restart the server. Use /rast status to inspect it.

## Horizon note

Rast optimizes the Paper server only. It does not translate Horizon's network protocol. Horizon client compatibility with a 1.21.4 server depends on Horizon's own older-protocol support or a separate protocol proxy.
