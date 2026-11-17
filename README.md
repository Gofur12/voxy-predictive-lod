# Voxy Predictive LOD

Standalone Fabric addon for Minecraft Java 1.20.1. It pre-generates terrain around the command location so Voxy can later import the saved region data as LOD.

> This is **real vanilla chunk generation**, not a renderer that predicts terrain without saving it. It changes the world save and runs normal world-generation features. Back up the world first.

## Commands

- `/vpregen start <radius>` — queue a square area around the player, in chunks (radius 1–32).
- `/vpregen start <radius> <chunksPerTick>` — control generation rate (1–4 chunks per server tick).
- `/vpregen status` — show progress.
- `/vpregen stop` — stop and clear the remaining queue.

When generation finishes, run Voxy's importer in the same world:

```
/voxy import current
```

That imports the generated `region/*.mca` data into Voxy's LOD store. The addon does not call Voxy internals directly, keeping it separate from the Voxy build.

## Compatibility

- Minecraft Java 1.20.1
- Fabric Loader + Fabric API
- Voxy Mobile BB build for 1.20.1 (for the import step)

## Build

Requires JDK 17 and Gradle 8.x:

```sh
gradle build
```

Output: `build/libs/voxy-predictive-lod-0.1.0-alpha.1.jar`.

## Safety/performance notes

Generation is CPU-, RAM-, and storage-intensive. Start with radius 4–8 and one chunk per tick. Larger areas can take a long time and substantially increase the world save size. Use only in a world you have backed up. This mod is not intended to bypass server world-generation rules or generate chunks on a remote server from a client.
