# Flowscope

A handheld lens for NeoForge that shows how fast items and fluids flow into and out of a chest, tank or machine. Right-click a block, and a live screen shows what went in, what went out and the net change per minute, with a 60-second graph and a per-item breakdown.

- Read-only: never inserts, extracts or changes the watched block.
- No work when idle: nothing ticks or scans unless a player is recording.
- Works with any block that exposes NeoForge's standard item or fluid capability. No other mods needed.

## Versions
| Minecraft | NeoForge | Java |
|---|---|---|
| 26.1.2 | 26.1.2.126+ | 25 |

Install on both client and server.

## How to use
- Craft the Flowscope: glass pane on top, redstone in the middle, copper ingots on the sides and bottom.
- Right-click a block to start recording it. Sneak + right-click records only the clicked side.
- Closing the screen keeps the recording running. Use the Flowscope again to reopen it.
- A recording stops after 5 minutes (configurable), on Stop, or when you record another block.

## What it measures
When a block reports its changes (most machines do), Flowscope counts each one. Otherwise it compares snapshots about once per second and labels the result *approximate*, because items added and removed between two reads cancel out. It measures storage changes, not exact pipe transfers.

## Server config
`serverconfig/flowscope-server.toml` in the world folder. Main options: `sampleIntervalTicks`, `maxSessionSeconds`, `maxReadsPerTick`, `maxVisibleSlots`, `maxInteractionDistance`, `enablePerItemBreakdown`. Every value has a hard upper limit.

## Building
Requires a JDK 21 or newer to run Gradle. Gradle downloads a Java 25 toolchain for compiling if none is installed.

```
gradlew build
```

The JAR is written to `build/libs/`. `gradlew runClient` and `gradlew runServer` start a development game with only this mod.

## Modpacks
You're welcome to include Flowscope in any modpack.

## License
MIT, see [LICENSE](LICENSE).
