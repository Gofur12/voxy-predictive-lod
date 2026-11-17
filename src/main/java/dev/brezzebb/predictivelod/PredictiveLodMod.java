package dev.brezzebb.predictivelod;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.ChunkStatus;

import java.util.ArrayDeque;
import java.util.Queue;

public final class PredictiveLodMod implements ModInitializer {
    private static final Queue<ChunkPos> QUEUE = new ArrayDeque<>();
    private static ServerWorld targetWorld;
    private static int generated;
    private static int failed;
    private static int total;
    private static int chunksPerTick = 1;
    private static boolean running;

    @Override
    public void onInitialize() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(CommandManager.literal("vpregen")
                .then(CommandManager.literal("start")
                    .then(CommandManager.argument("radius", IntegerArgumentType.integer(1, 32))
                        .executes(ctx -> start(ctx.getSource(),
                            IntegerArgumentType.getInteger(ctx, "radius"), 1))
                        .then(CommandManager.argument("chunksPerTick", IntegerArgumentType.integer(1, 4))
                            .executes(ctx -> start(ctx.getSource(),
                                IntegerArgumentType.getInteger(ctx, "radius"),
                                IntegerArgumentType.getInteger(ctx, "chunksPerTick"))))))
                .then(CommandManager.literal("stop")
                    .executes(ctx -> stop(ctx.getSource())))
                .then(CommandManager.literal("status")
                    .executes(ctx -> status(ctx.getSource()))));
        });

        ServerTickEvents.END_SERVER_TICK.register(PredictiveLodMod::tick);
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> reset());
    }

    private static int start(ServerCommandSource source, int radius, int rate) {
        if (running) {
            source.sendError(Text.literal("[Voxy Predictive LOD] Generation is already running."));
            return 0;
        }

        final ServerPlayerEntity player;
        try {
            player = source.getPlayerOrThrow();
        } catch (Exception exception) {
            source.sendError(Text.literal("[Voxy Predictive LOD] Run this command as a player in-game."));
            return 0;
        }

        QUEUE.clear();
        targetWorld = player.getServerWorld();
        generated = 0;
        failed = 0;
        chunksPerTick = rate;

        ChunkPos center = player.getChunkPos();
        for (int ring = 0; ring <= radius; ring++) {
            for (int x = -ring; x <= ring; x++) {
                for (int z = -ring; z <= ring; z++) {
                    if (Math.max(Math.abs(x), Math.abs(z)) == ring) {
                        QUEUE.add(new ChunkPos(center.x + x, center.z + z));
                    }
                }
            }
        }

        total = QUEUE.size();
        running = true;
        source.sendFeedback(() -> Text.literal(
            "[Voxy Predictive LOD] Queued " + total + " chunks around your position. Rate: "
                + chunksPerTick + " chunk(s)/tick. Back up your world; this creates real chunks."),
            false);
        return 1;
    }

    private static int stop(ServerCommandSource source) {
        int remaining = QUEUE.size();
        QUEUE.clear();
        running = false;
        targetWorld = null;
        source.sendFeedback(() -> Text.literal(
            "[Voxy Predictive LOD] Stopped. Generated: " + generated + "/" + total
                + ", failed: " + failed + ", cancelled: " + remaining), false);
        return 1;
    }

    private static int status(ServerCommandSource source) {
        source.sendFeedback(() -> Text.literal(
            "[Voxy Predictive LOD] " + (running ? "Running" : "Idle")
                + " | generated: " + generated + "/" + total
                + " | failed: " + failed + " | queued: " + QUEUE.size()
                + " | rate: " + chunksPerTick + "/tick"), false);
        return 1;
    }

    private static void tick(MinecraftServer server) {
        if (!running || targetWorld == null) return;

        for (int i = 0; i < chunksPerTick && !QUEUE.isEmpty(); i++) {
            ChunkPos pos = QUEUE.poll();
            if (pos == null) break;
            try {
                targetWorld.getChunkManager().getChunk(pos.x, pos.z, ChunkStatus.FULL, true);
                generated++;
            } catch (Throwable throwable) {
                failed++;
                System.err.println("[Voxy Predictive LOD] Failed generating " + pos + ": " + throwable);
            }
        }

        if (generated + failed > 0 && (generated + failed) % 100 == 0) {
            System.out.println("[Voxy Predictive LOD] Progress: " + generated + "/" + total
                + ", failed: " + failed + ", queued: " + QUEUE.size());
        }

        if (QUEUE.isEmpty()) {
            running = false;
            targetWorld = null;
            System.out.println("[Voxy Predictive LOD] Generation complete. Run /voxy import current to import saved regions into Voxy.");
        }
    }

    private static void reset() {
        QUEUE.clear();
        targetWorld = null;
        running = false;
    }
}
