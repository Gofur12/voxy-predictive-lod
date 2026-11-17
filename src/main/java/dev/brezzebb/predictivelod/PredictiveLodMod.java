package dev.brezzebb.predictivelod;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.ChunkStatus;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;

public final class PredictiveLodMod implements ModInitializer {
    private static final Queue<ChunkPos> QUEUE = new ArrayDeque<>();
    private static final Set<Long> SEEN = new HashSet<>();

    // Conservative defaults for mobile devices.
    private static final int AUTO_RADIUS = 4;
    private static final int QUEUE_LIMIT = 64;
    private static final int AUTO_SCAN_INTERVAL_TICKS = 100; // 5 seconds
    private static final int AUTO_GENERATE_INTERVAL_TICKS = 100; // 1 chunk / 5 seconds
    private static final double MEMORY_PAUSE_RATIO = 0.85;
    private static final double MEMORY_RESUME_RATIO = 0.72;

    private static ServerWorld targetWorld;
    private static UUID targetPlayer;
    private static int generated;
    private static int failed;
    private static int tickCounter;
    private static boolean running;
    private static boolean automatic;
    private static boolean memoryPaused;

    @Override
    public void onInitialize() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(CommandManager.literal("vpregen")
                .then(CommandManager.literal("start")
                    .then(CommandManager.argument("radius", IntegerArgumentType.integer(1, 32))
                        .executes(ctx -> start(ctx.getSource(),
                            IntegerArgumentType.getInteger(ctx, "radius")))))
                .then(CommandManager.literal("stop")
                    .executes(ctx -> stop(ctx.getSource())))
                .then(CommandManager.literal("status")
                    .executes(ctx -> status(ctx.getSource()))));
        });

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            if (!running) beginAutomatic(handler.player);
        });
        ServerTickEvents.END_SERVER_TICK.register(PredictiveLodMod::tick);
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> reset());
    }

    private static void beginAutomatic(ServerPlayerEntity player) {
        clearState();
        targetWorld = player.getServerWorld();
        targetPlayer = player.getUuid();
        automatic = true;
        running = true;
        enqueueAround(player.getChunkPos(), AUTO_RADIUS);
        player.sendMessage(Text.literal(
            "[Voxy Predictive LOD] Mobile-safe auto-follow ON: radius "
                + AUTO_RADIUS + ", maximum 1 chunk / 5 seconds. "
                + "It pauses at high Java heap use. /vpregen stop disables it."), false);
    }

    private static int start(ServerCommandSource source, int radius) {
        final ServerPlayerEntity player;
        try {
            player = source.getPlayerOrThrow();
        } catch (Exception exception) {
            source.sendError(Text.literal("[Voxy Predictive LOD] Run this command in-game."));
            return 0;
        }

        clearState();
        targetWorld = player.getServerWorld();
        targetPlayer = player.getUuid();
        automatic = false;
        running = true;
        enqueueAround(player.getChunkPos(), radius);
        source.sendFeedback(() -> Text.literal(
            "[Voxy Predictive LOD] Manual mode started. Radius: " + radius
                + ". One chunk is generated per 5 seconds; heap protection is active."), false);
        return 1;
    }

    private static int stop(ServerCommandSource source) {
        int remaining = QUEUE.size();
        QUEUE.clear();
        running = false;
        automatic = false;
        targetWorld = null;
        targetPlayer = null;
        source.sendFeedback(() -> Text.literal(
            "[Voxy Predictive LOD] Stopped. Generated: " + generated
                + ", failed: " + failed + ", cancelled: " + remaining), false);
        return 1;
    }

    private static int status(ServerCommandSource source) {
        Runtime runtime = Runtime.getRuntime();
        long used = runtime.totalMemory() - runtime.freeMemory();
        long max = runtime.maxMemory();
        int percent = max > 0 ? (int) (used * 100L / max) : -1;
        source.sendFeedback(() -> Text.literal(
            "[Voxy Predictive LOD] " + (running ? (automatic ? "Auto-follow" : "Manual") : "Idle")
                + (memoryPaused ? " (paused: high heap)" : "")
                + " | generated: " + generated + " | failed: " + failed
                + " | queued: " + QUEUE.size() + " | Java heap: " + percent + "%"), false);
        return 1;
    }

    private static void tick(MinecraftServer server) {
        if (!running || targetWorld == null || targetPlayer == null) return;
        tickCounter++;

        if (automatic && tickCounter % AUTO_SCAN_INTERVAL_TICKS == 0) {
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(targetPlayer);
            if (player == null || player.getServerWorld() != targetWorld) {
                running = false;
                targetWorld = null;
                targetPlayer = null;
                System.out.println("[Voxy Predictive LOD] Auto-follow stopped: tracked player left this dimension.");
                return;
            }
            enqueueAround(player.getChunkPos(), AUTO_RADIUS);
        }

        if (automatic && tickCounter % AUTO_GENERATE_INTERVAL_TICKS != 0) return;
        if (!automatic && tickCounter % AUTO_GENERATE_INTERVAL_TICKS != 0) return;

        Runtime runtime = Runtime.getRuntime();
        long max = runtime.maxMemory();
        long used = runtime.totalMemory() - runtime.freeMemory();
        if (max > 0) {
            double ratio = (double) used / (double) max;
            if (!memoryPaused && ratio >= MEMORY_PAUSE_RATIO) {
                memoryPaused = true;
                System.out.println("[Voxy Predictive LOD] Paused generation: Java heap reached "
                    + (int) (ratio * 100) + "%.");
            } else if (memoryPaused && ratio <= MEMORY_RESUME_RATIO) {
                memoryPaused = false;
                System.out.println("[Voxy Predictive LOD] Resumed generation: Java heap is "
                    + (int) (ratio * 100) + "%.");
            }
        }
        if (memoryPaused || QUEUE.isEmpty()) return;

        ChunkPos pos = QUEUE.poll();
        if (pos == null) return;
        try {
            // FULL generation is synchronous in vanilla; deliberately do only one at a time.
            targetWorld.getChunkManager().getChunk(pos.x, pos.z, ChunkStatus.FULL, true);
            generated++;
        } catch (RuntimeException exception) {
            failed++;
            System.err.println("[Voxy Predictive LOD] Failed generating " + pos + ": " + exception);
        }
    }

    private static void enqueueAround(ChunkPos center, int radius) {
        for (int ring = 0; ring <= radius && QUEUE.size() < QUEUE_LIMIT; ring++) {
            for (int x = -ring; x <= ring && QUEUE.size() < QUEUE_LIMIT; x++) {
                for (int z = -ring; z <= ring && QUEUE.size() < QUEUE_LIMIT; z++) {
                    if (Math.max(Math.abs(x), Math.abs(z)) != ring) continue;
                    ChunkPos pos = new ChunkPos(center.x + x, center.z + z);
                    if (!SEEN.contains(pos.toLong()) && QUEUE.size() < QUEUE_LIMIT) {
                        SEEN.add(pos.toLong());
                        QUEUE.add(pos);
                    }
                }
            }
        }
    }

    private static void clearState() {
        QUEUE.clear();
        SEEN.clear();
        generated = 0;
        failed = 0;
        tickCounter = 0;
        memoryPaused = false;
        targetWorld = null;
        targetPlayer = null;
    }

    private static void reset() {
        clearState();
        running = false;
        automatic = false;
    }
}
