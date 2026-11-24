# Rast

Rast is a conservative Paper 1.21.4 server-side utility plugin. It is not a universal server optimizer and does not modify the Minecraft network protocol.

## Current features

- Rotating processing of already-loaded chunks; Rast never force-loads chunks.
- Configurable limits for chunks, scanned entities, candidate item entities and comparisons.
- Conservative dropped-item merging with identical item metadata, owner, thrower, scoreboard tags and pickup/lifetime rules.
- Items carrying custom persistent-data keys are excluded from merging.
- Partial transfers are supported; stacks are never overfilled and pickup delay is never shortened.
- A failed background pass is logged and retried. After three consecutive failures, only item merging is paused; `/rast reload` restarts it.
- `/rast status` reports TPS, average MSPT, loaded entities, last-pass duration and merge counters.
- `/rast reload` reloads configuration and restarts the task.

## Build

Requires JDK 21 and Maven 3.9+.

    mvn --batch-mode clean verify

Output: `target/Rast-1.0.0.jar`.

GitHub Actions builds on pushes and pull requests to `main`, and uploads the JAR as the `Rast-1.0.0` artifact.

## Install and verify

1. Stop the Paper 1.21.4 server.
2. Copy `Rast-1.0.0.jar` into `plugins/`.
3. Start the server and check the console for the Rast startup message.
4. Run `/rast status` as an operator.
5. Test item merging in a disposable test world before enabling it on a production server.

## Configuration

All limits are in `plugins/Rast/config.yml`. Lower `max-chunks-per-pass`, `max-entities-per-chunk`, or `max-items-per-chunk` if the server is under heavy load. Increase them gradually only after measuring MSPT.

## Horizon compatibility

Rast runs inside Paper and optimizes Paper-side work only. A Bukkit/Paper plugin cannot translate a different client network protocol. Horizon clients need native support for the Java 1.21.4 protocol or a separate compatible proxy/translator. This plugin does not claim to provide that bridge.
