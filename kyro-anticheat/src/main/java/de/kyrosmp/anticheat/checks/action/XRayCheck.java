/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  org.bukkit.GameMode
 *  org.bukkit.Material
 *  org.bukkit.block.Block
 *  org.bukkit.block.BlockFace
 *  org.bukkit.entity.Player
 */
package de.kyrosmp.anticheat.checks.action;

import de.kyrosmp.anticheat.core.CheckConfig;
import de.kyrosmp.anticheat.core.BedrockDetector;
import de.kyrosmp.anticheat.core.PlayerData;
import de.kyrosmp.anticheat.flag.FlagSink;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;

public final class XRayCheck {
    private final FlagSink sink;
    private volatile CheckConfig cfg;
    private final Map<UUID, Stats> stats = new ConcurrentHashMap<UUID, Stats>();
    private static final BlockFace[] FACES = new BlockFace[]{BlockFace.UP, BlockFace.DOWN, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST};

    public Stats stats(UUID player) {
        return this.stats.get(player);
    }

    private Stats statsFor(UUID player, long now, long windowMs) {
        if (this.stats.size() > 1024) {
            this.stats.values().removeIf(st -> now - st.windowStart > windowMs);
        }
        return this.stats.computeIfAbsent(player, k -> new Stats());
    }

    public XRayCheck(FlagSink sink, CheckConfig cfg) {
        this.sink = sink;
        this.cfg = cfg;
    }

    public void configure(CheckConfig cfg) {
        this.cfg = cfg;
    }

    public static boolean isTrackedOre(Material m) {
        return m == Material.DIAMOND_ORE || m == Material.DEEPSLATE_DIAMOND_ORE || m == Material.EMERALD_ORE || m == Material.DEEPSLATE_EMERALD_ORE || m == Material.ANCIENT_DEBRIS;
    }

    public static boolean isHostRock(Material m) {
        return switch (m) {
            case Material.STONE, Material.DEEPSLATE, Material.TUFF, Material.GRANITE, Material.DIORITE, Material.ANDESITE, Material.NETHERRACK, Material.BASALT, Material.BLACKSTONE, Material.CALCITE, Material.COBBLED_DEEPSLATE -> true;
            default -> false;
        };
    }

    public void onBreak(Player p, PlayerData d, Block b, long now) {
        Material type;
        boolean ore;
        CheckConfig c = this.cfg;
        if (!c.xrayEnabled() || c.bedrockExempt() && BedrockDetector.isBedrock(p, d)) {
            return;
        }
        GameMode gm = p.getGameMode();
        if (gm == GameMode.CREATIVE || gm == GameMode.SPECTATOR) {
            return;
        }
        if (d.xrayWorld != b.getWorld()) {
            d.xrayWorld = b.getWorld();
            Arrays.fill(d.xrayMinedRing, Long.MIN_VALUE);
            Arrays.fill(d.xrayOreRing, Long.MIN_VALUE);
        }
        if (!(ore = XRayCheck.isTrackedOre(type = b.getType())) && !XRayCheck.isHostRock(type)) {
            XRayCheck.remember(d, b.getX(), b.getY(), b.getZ(), false);
            return;
        }
        boolean dugTo = false;
        if (ore) {
            dugTo = true;
            for (BlockFace f : FACES) {
                boolean open;
                Block n = b.getRelative(f);
                long key = XRayCheck.pack(n.getX(), n.getY(), n.getZ());
                if (XRayCheck.contains(d.xrayOreRing, key)) {
                    dugTo = false;
                    break;
                }
                boolean bl = open = n.isEmpty() || n.isPassable() || n.isLiquid();
                if (!open || XRayCheck.contains(d.xrayMinedRing, key)) continue;
                dugTo = false;
                break;
            }
        }
        XRayCheck.remember(d, b.getX(), b.getY(), b.getZ(), ore);
        String details = XRayCheck.record(this.statsFor(p.getUniqueId(), now, c.xrayWindowMs()), ore, dugTo, now, c);
        if (details != null) {
            this.sink.flag(p, d, "XRay A", "Finds hidden rare ores with very little mining", details + String.format(Locale.US, "; last=%s at y=%d", type.name().toLowerCase(Locale.ROOT), b.getY()));
        }
    }

    public static String record(Stats d, boolean ore, boolean dugToVein, long now, CheckConfig c) {
        if (now - d.windowStart > c.xrayWindowMs()) {
            d.windowStart = now;
            d.hiddenVeins = 0;
            d.stoneBlocks = 0;
            d.alerted = false;
        }
        if (!ore) {
            ++d.stoneBlocks;
        } else if (dugToVein) {
            ++d.hiddenVeins;
        }
        int veins = d.hiddenVeins;
        if (d.alerted || veins < c.xrayMinVeins()) {
            return null;
        }
        double perVein = (double)d.stoneBlocks / (double)veins;
        if (perVein >= c.xrayMaxBlocksPerVein()) {
            return null;
        }
        d.alerted = true;
        return String.format(Locale.US, "dug_to_veins=%d; rock_mined=%d; rock_per_vein=%.1f (limit %.0f); window=%dmin", veins, d.stoneBlocks, perVein, c.xrayMaxBlocksPerVein(), c.xrayWindowMs() / 60000L);
    }

    static long pack(int x, int y, int z) {
        return (long)(x & 0x3FFFFFF) << 38 | (long)(z & 0x3FFFFFF) << 12 | (long)y & 0xFFFL;
    }

    private static boolean contains(long[] ring, long key) {
        for (long v : ring) {
            if (v != key) continue;
            return true;
        }
        return false;
    }

    static void remember(PlayerData d, int x, int y, int z, boolean ore) {
        long key;
        d.xrayMinedRing[d.xrayMinedHead] = key = XRayCheck.pack(x, y, z);
        d.xrayMinedHead = (d.xrayMinedHead + 1) % d.xrayMinedRing.length;
        if (ore) {
            d.xrayOreRing[d.xrayOreHead] = key;
            d.xrayOreHead = (d.xrayOreHead + 1) % d.xrayOreRing.length;
        }
    }

    public static final class Stats {
        long windowStart;
        public int hiddenVeins;
        public int stoneBlocks;
        boolean alerted;
    }
}

