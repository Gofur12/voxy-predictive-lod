# Voxy Predictive LOD

Standalone Fabric addon for Minecraft Java 1.20.1. It continuously pre-generates real terrain around the player so Voxy can later import saved region data as LOD.

> This is real vanilla chunk generation, not speculative rendering. It changes the world save, consumes CPU/RAM/storage, and can cause lag. Back up the world first.

## Automatic follow mode

- Automatically starts when a player joins a world.
- Maintains a moving square radius of 8 chunks around the tracked player.
- Adds newly encountered chunks as the player moves; already queued/generated positions are not added again during that session.
- Conservative default: one chunk per second.
- Use `/vpregen status` to inspect progress.
- Use `/vpregen stop` to pause automatic generation.

The current auto-follow session ends if the tracked player leaves the world or changes dimension. Rejoin to start a fresh session.

## Manual commands

- `/vpregen start <radius>` — generate a fixed square area around the player (radius 1–32).
- `/vpregen start <radius> <chunksPerTick>` — control manual generation rate (1–4 chunks per server tick).
- `/vpregen status` — show progress.
- `/vpregen stop` — stop and clear the remaining queue.

After terrain has been generated, run Voxy's importer in the same world:

```
/voxy import current
```

The addon does not call Voxy internals directly. Voxy import remains a separate step.

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

## Performance notes

Automatic mode generates at most one chunk per second and follows the player with an 8-chunk radius. Even throttled generation can cause stutter and substantially increase world size. It generates real chunks in the current world; it cannot generate remote multiplayer server chunks from a client.
