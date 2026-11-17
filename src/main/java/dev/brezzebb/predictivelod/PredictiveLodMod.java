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
    private static final int AUTO_RADIUS = 8;
    private static final int UPDATE_INTERVAL_TICKS = 20;
    private static final int GENERATE_INTERVAL_TICKS = 20;

    private static ServerWorld targetWorld;
    private static UUID targetPlayer;
    private static int generated;
    private static int failed;
    private static int total;
    private static int chunksPerTick = 1;
    private static int tickCounter;
    private static boolean running;
    private static boolean automatic;

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
        chunksPerTick = 1;
        enqueueAround(player.getChunkPos(), AUTO_RADIUS);
        total = SEEN.size();
        player.sendMessage(Text.literal(
            "[Voxy Predictive LOD] Auto mode ON: following you, radius "
                + AUTO_RADIUS + " chunks. Generation is throttled to 1 chunk/second. "
                + "Use /vpregen stop to pause."), false);
    }

    private static int start(ServerCommandSource source, int radius, int rate) {
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
        chunksPerTick = rate;
        running = true;
        enqueueAround(player.getChunkPos(), radius);
        total = SEEN.size();
        source.sendFeedback(() -> Text.literal(
            "[Voxy Predictive LOD] Manual generation started. Radius: " + radius
                + ", rate: " + rate + " chunk(s)/tick. Back up your world."), false);
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
            "[Voxy Predictive LOD] Paused. Generated: " + generated
                + ", failed: " + failed + ", queued/cancelled: " + remaining), false);
        return 1;
    }

    private static int status(ServerCommandSource source) {
        source.sendFeedback(() -> Text.literal(
            "[Voxy Predictive LOD] " + (running ? (automatic ? "Auto-follow" : "Manual") : "Idle")
                + " | generated: " + generated + " | failed: " + failed
                + " | queued: " + QUEUE.size() + " | seen: " + SEEN.size()), false);
        return 1;
    }

    private static void tick(MinecraftServer server) {
        if (!running || targetWorld == null || targetPlayer == null) return;

        tickCounter++;
        if (tickCounter % UPDATE_INTERVAL_TICKS == 0 && automatic) {
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(targetPlayer);
            if (player == null || player.getServerWorld() != targetWorld) {
                running = false;
                System.out.println("[Voxy Predictive LOD] Auto-follow paused: tracked player left the world.");
                return;
            }
            enqueueAround(player.getChunkPos(), AUTO_RADIUS);
            total = SEEN.size();
        }

        int interval = automatic ? GENERATE_INTERVAL_TICKS : 1;
        if (tickCounter % interval != 0) return;

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

        if (QUEUE.isEmpty() && !automatic) {
            running = false;
            targetWorld = null;
            targetPlayer = null;
            System.out.println("[Voxy Predictive LOD] Manual generation complete. Run /voxy import current.");
        }
    }

    private static void enqueueAround(ChunkPos center, int radius) {
        for (int ring = 0; ring <= radius; ring++) {
            for (int x = -ring; x <= ring; x++) {
                for (int z = -ring; z <= ring; z++) {
                    if (Math.max(Math.abs(x), Math.abs(z)) != ring) continue;
                    ChunkPos pos = new ChunkPos(center.x + x, center.z + z);
                    if (SEEN.add(pos.toLong())) QUEUE.add(pos);
                }
            }
        }
    }

    private static void clearState() {
        QUEUE.clear();
        SEEN.clear();
        generated = 0;
        failed = 0;
        total = 0;
        tickCounter = 0;
        targetWorld = null;
        targetPlayer = null;
    }

    private static void reset() {
        clearState();
        running = false;
        automatic = false;
    }
}
