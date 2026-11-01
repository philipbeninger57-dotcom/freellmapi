package de.kyrosmp.anticheat.checks.action;

import de.kyrosmp.anticheat.core.BedrockDetector;
import de.kyrosmp.anticheat.core.CheckConfig;
import de.kyrosmp.anticheat.core.PlayerData;
import de.kyrosmp.anticheat.flag.FlagSink;

import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;

import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class XRayCheck {
    private final FlagSink sink;
    private volatile CheckConfig cfg;
    private final Map<UUID, XRayCheck.Stats> stats = new ConcurrentHashMap<>();
    private static final BlockFace[] FACES =
            new BlockFace[] {
                BlockFace.UP,
                BlockFace.DOWN,
                BlockFace.NORTH,
                BlockFace.SOUTH,
                BlockFace.EAST,
                BlockFace.WEST
            };

    public XRayCheck.Stats stats(UUID player) {
        return this.stats.get(player);
    }

    private XRayCheck.Stats statsFor(UUID player, long now, long windowMs) {
        if (this.stats.size() > 1024) {
            this.stats.values().removeIf(st -> now - st.windowStart > windowMs);
        }

        return this.stats.computeIfAbsent(player, k -> new XRayCheck.Stats());
    }

    public XRayCheck(FlagSink sink, CheckConfig cfg) {
        this.sink = sink;
        this.cfg = cfg;
    }

    public void configure(CheckConfig cfg) {
        this.cfg = cfg;
    }

    public static boolean isTrackedOre(Material m) {
        return m == Material.DIAMOND_ORE
                || m == Material.DEEPSLATE_DIAMOND_ORE
                || m == Material.EMERALD_ORE
                || m == Material.DEEPSLATE_EMERALD_ORE
                || m == Material.ANCIENT_DEBRIS;
    }

    public static boolean isHostRock(Material m) {
        return switch (m) {
            case STONE,
                    DEEPSLATE,
                    TUFF,
                    GRANITE,
                    DIORITE,
                    ANDESITE,
                    NETHERRACK,
                    BASALT,
                    BLACKSTONE,
                    CALCITE,
                    COBBLED_DEEPSLATE ->
                    true;
            default -> false;
        };
    }

    public void onBreak(Player p, PlayerData d, Block b, long now) {
        CheckConfig c = this.cfg;
        if (c.xrayEnabled() && (!c.bedrockExempt() || !BedrockDetector.isBedrock(p, d))) {
            GameMode gm = p.getGameMode();
            if (gm != GameMode.CREATIVE && gm != GameMode.SPECTATOR) {
                if (d.xrayWorld != b.getWorld()) {
                    d.xrayWorld = b.getWorld();
                    Arrays.fill(d.xrayMinedRing, Long.MIN_VALUE);
                    Arrays.fill(d.xrayOreRing, Long.MIN_VALUE);
                }

                Material type;
                boolean ore;
                if (!(ore = isTrackedOre(type = b.getType())) && !isHostRock(type)) {
                    remember(d, b.getX(), b.getY(), b.getZ(), false);
                } else {
                    boolean dugTo = false;
                    if (ore) {
                        dugTo = true;

                        for (BlockFace f : FACES) {
                            Block n = b.getRelative(f);
                            long key = pack(n.getX(), n.getY(), n.getZ());
                            if (contains(d.xrayOreRing, key)) {
                                dugTo = false;
                                break;
                            }

                            boolean open = n.isEmpty() || n.isPassable() || n.isLiquid();
                            if (open && !contains(d.xrayMinedRing, key)) {
                                dugTo = false;
                                break;
                            }
                        }
                    }

                    remember(d, b.getX(), b.getY(), b.getZ(), ore);
                    String details =
                            record(
                                    this.statsFor(p.getUniqueId(), now, c.xrayWindowMs()),
                                    ore,
                                    dugTo,
                                    now,
                                    c);
                    if (details != null) {
                        this.sink.flag(
                                p,
                                d,
                                "XRay A",
                                "Finds hidden rare ores with very little mining",
                                details
                                        + String.format(
                                                Locale.US,
                                                "; last=%s at y=%d",
                                                type.name().toLowerCase(Locale.ROOT),
                                                b.getY()));
                    }
                }
            }
        }
    }

    public static String record(
            XRayCheck.Stats d, boolean ore, boolean dugToVein, long now, CheckConfig c) {
        if (now - d.windowStart > c.xrayWindowMs()) {
            d.windowStart = now;
            d.hiddenVeins = 0;
            d.stoneBlocks = 0;
            d.alerted = false;
        }

        if (!ore) {
            d.stoneBlocks++;
        } else if (dugToVein) {
            d.hiddenVeins++;
        }

        int veins = d.hiddenVeins;
        if (!d.alerted && veins >= c.xrayMinVeins()) {
            double perVein = (double) d.stoneBlocks / (double) veins;
            if (perVein >= c.xrayMaxBlocksPerVein()) {
                return null;
            } else {
                d.alerted = true;
                return String.format(
                        Locale.US,
                        "dug_to_veins=%d; rock_mined=%d; rock_per_vein=%.1f (limit %.0f);"
                                + " window=%dmin",
                        veins,
                        d.stoneBlocks,
                        perVein,
                        c.xrayMaxBlocksPerVein(),
                        c.xrayWindowMs() / 60000L);
            }
        } else {
            return null;
        }
    }

    static long pack(int x, int y, int z) {
        return (long) (x & 67108863) << 38 | (long) (z & 67108863) << 12 | (long) y & 4095L;
    }

    private static boolean contains(long[] ring, long key) {
        for (long v : ring) {
            if (v == key) {
                return true;
            }
        }

        return false;
    }

    static void remember(PlayerData d, int x, int y, int z, boolean ore) {
        long key;
        d.xrayMinedRing[d.xrayMinedHead] = key = pack(x, y, z);
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
