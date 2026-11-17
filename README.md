# Voxy Predictive LOD

Fabric addon for Minecraft Java 1.20.1. It pre-generates real terrain around the player for later Voxy LOD import.

> This performs real vanilla chunk generation on the integrated server thread. It can still cause stutter and memory pressure. Back up your world.

## Mobile-safe automatic mode

- Starts when a player joins a world.
- Follows the player with a radius of 4 chunks.
- Scans for newly nearby chunks every 5 seconds.
- Generates at most 1 chunk every 5 seconds.
- Queue is capped at 64 pending chunks.
- Pauses generation when Java heap usage reaches 85%, and resumes below 72%.
- Already queued/generated coordinates are not enqueued again during the same session.

Commands:
- `/vpregen status` — show mode, queue, counters, and Java heap percentage.
- `/vpregen stop` — stop automatic generation.
- `/vpregen start <radius>` — manually generate around the player (radius 1–32), with the same conservative rate and heap protection.

After generation, run `/voxy import current` to import saved regions into Voxy.

## Important limitations

The generation call is synchronous because it uses vanilla's full chunk-generation path; it cannot safely be moved to an arbitrary background thread. The mod therefore throttles work rather than pretending it is asynchronous. Heap protection monitors Java heap, not Android native/GPU memory, and cannot guarantee that a shader or renderer will never crash. This mod does not directly invoke Voxy's renderer or LOD upload pipeline.

## Build

JDK 17 and Gradle 8.x. Output: `build/libs/voxy-predictive-lod-0.1.0-alpha.2.jar`.
