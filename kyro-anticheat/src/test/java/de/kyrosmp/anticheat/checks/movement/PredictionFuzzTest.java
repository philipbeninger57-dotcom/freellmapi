package de.kyrosmp.anticheat.checks.movement;

import de.kyrosmp.anticheat.core.CheckConfig;
import de.kyrosmp.anticheat.core.MoveContext;
import de.kyrosmp.anticheat.core.MoveGeometry;
import de.kyrosmp.anticheat.core.PlayerData;
import de.kyrosmp.anticheat.core.SetbackService;
import de.kyrosmp.anticheat.flag.FlagSink;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Randomised legitimate play (walking, sprinting, sneaking, turning, jumping, head hitters, ice,
 * Speed/Jump Boost changes, ledges, knockback and explosions applied after a random ping delay).
 * The prediction layer must never reject any of it.
 */
class PredictionFuzzTest {
    private final List<String> flags = new ArrayList<>();
    private final FlagSink sink = new FlagSink() {
        public String flag(Player p, PlayerData d, String c, String r, String details) { flags.add(c + " " + details); return null; }
        public void action(String id, String action) { }
    };
    private final SetbackService engine = new SetbackService() {
        public boolean setback(Player p, PlayerData d, String id) { return true; }
        public boolean setbackTo(Player p, PlayerData d, Location to, String id) { return true; }
        public void registerFlyPunishment(Player p, PlayerData d, long now, String id) { }
    };

    private record Pending(int applyAtTick, double vx, double vy, double vz, boolean additive) { }

    private String runSeed(long seed, int ticks) {
        Random rnd = new Random(seed);
        PredictionChecks checks = new PredictionChecks(sink, engine, CheckConfig.defaults(), PredictionChecksTest.EAGER);
        PlayerData d = new PlayerData(UUID.randomUUID(), "fuzz");
        VanillaSim sim = new VanillaSim(64);
        d.lastGround = true;
        d.resetPrediction(true);
        long now = 50_000L;
        double lx = sim.x, ly = sim.y, lz = sim.z;
        int ping = 20 + rnd.nextInt(280);
        int pingTicks = Math.max(1, ping / 50);
        List<Pending> pending = new ArrayList<>();
        int jumpAmp = -1;
        double attrClient = 0.13;
        double attrServer = 0.13;
        int attrSwitchAt = -1;
        double attrNext = 0.13;
        boolean ice = false;
        int rejects = 0;
        java.util.ArrayDeque<String> trace = new java.util.ArrayDeque<>();
        for (int t = 0; t < ticks; t++) {
            // ---- random player input changes
            if (rnd.nextInt(20) == 0) sim.yaw = rnd.nextFloat() * 360f - 180f;
            if (rnd.nextInt(6) == 0) sim.yaw += (rnd.nextFloat() - 0.5f) * 60f;
            if (rnd.nextInt(25) == 0) sim.forward = rnd.nextInt(4) == 0 ? 0.0 : (rnd.nextBoolean() ? 1.0 : -1.0);
            if (rnd.nextInt(25) == 0) sim.strafe = rnd.nextInt(3) - 1;
            if (rnd.nextInt(30) == 0) sim.jump = rnd.nextBoolean();
            if (rnd.nextInt(40) == 0) sim.sprint = sim.forward > 0 && rnd.nextBoolean();
            if (rnd.nextInt(50) == 0) sim.inputScale = rnd.nextInt(3) == 0 ? 0.3 : 1.0; // sneaking
            if (sim.onGround && rnd.nextInt(150) == 0) {
                // head hitters: a ceiling 2.0-2.5 blocks above the floor the player stands on
                sim.ceilingY = rnd.nextBoolean() ? sim.groundY + 2.0 + rnd.nextInt(3) * 0.25 : Double.POSITIVE_INFINITY;
            }
            if (sim.onGround && rnd.nextInt(200) == 0) {
                ice = !ice;
                sim.blockFriction = ice ? (rnd.nextBoolean() ? 0.98f : 0.989f) : 0.6f;
            }
            if (sim.onGround && rnd.nextInt(250) == 0) {
                jumpAmp = rnd.nextInt(3) - 1;
                sim.jumpPower = 0.42 + (jumpAmp < 0 ? 0 : 0.1 * (jumpAmp + 1));
            }
            if (attrSwitchAt < 0 && rnd.nextInt(200) == 0) {
                // the server changes the attribute now, the client follows after its ping
                attrNext = rnd.nextBoolean() ? 0.13 : 0.13 * 1.4;
                attrServer = attrNext;
                attrSwitchAt = t + pingTicks;
            }
            if (attrSwitchAt == t) {
                attrClient = attrNext;
                attrSwitchAt = -1;
            }
            double sprintFactor = sim.sprint ? 1.0 : 1.0 / 1.3;
            sim.attr = attrClient * sprintFactor;
            if (sim.onGround && rnd.nextInt(300) == 0) {
                // walk off a ledge: the floor drops by a random amount (the ceiling goes with it)
                double drop = 1 + rnd.nextInt(12);
                sim.groundY -= drop;
                sim.ceilingY = Double.POSITIVE_INFINITY;
            }
            // ---- server-sent motion arriving after the ping
            if (rnd.nextInt(90) == 0) {
                boolean explosion = rnd.nextInt(3) == 0;
                double vx = (rnd.nextDouble() - 0.5) * (explosion ? 2.0 : 0.9);
                double vz = (rnd.nextDouble() - 0.5) * (explosion ? 2.0 : 0.9);
                double vy = explosion ? rnd.nextDouble() * 1.2 : 0.36 + rnd.nextDouble() * 0.1;
                d.registerPredictionImpulse(vx, vy, vz, now);
                pending.add(new Pending(t + pingTicks, vx, vy, vz, explosion));
                trace.addLast(String.format("  t=%d REGISTER %s v=(%.3f,%.3f,%.3f) applyAt=%d", t, explosion ? "add" : "set", vx, vy, vz, t + pingTicks));
            }
            for (var it = pending.iterator(); it.hasNext();) {
                Pending p = it.next();
                if (p.applyAtTick() > t) continue;
                if (p.additive()) { sim.vx += p.vx(); sim.vy += p.vy(); sim.vz += p.vz(); }
                else { sim.vx = p.vx(); sim.vy = p.vy(); sim.vz = p.vz(); }
                trace.addLast(String.format("  t=%d APPLY %s v=(%.3f,%.3f,%.3f)", t, p.additive() ? "add" : "set", p.vx(), p.vy(), p.vz()));
                it.remove();
            }
            boolean groundBefore = sim.onGround;
            sim.tick();
            now += 50L;
            trace.addLast(String.format("  t=%d tick y=%.4f vy=%.4f ground=%s->%s h=%.4f floor=%.1f ceil=%.2f ice=%s jump=%s", t, sim.y, sim.vy, groundBefore, sim.onGround,
                    Math.hypot(sim.vx, sim.vz), sim.groundY, sim.ceilingY, ice, sim.jump));
            while (trace.size() > 160) trace.removeFirst();
            if (ice) d.lastSlipperySurface = now;
            double dx = sim.x - lx, dy = sim.y - ly, dz = sim.z - lz;
            if (dx * dx + dy * dy + dz * dz <= 1.0 / 256.0) continue;
            boolean toGround = sim.y - sim.groundY >= -1.0E-9 && sim.y - sim.groundY < 0.12;
            boolean strict = Math.abs(sim.y - sim.groundY) < 1.0E-9;
            boolean fromGround = ly - sim.groundY >= -1.0E-9 && ly - sim.groundY < 0.12;
            boolean ceiling = sim.ceilingY - (sim.y + 1.8) < 0.6;
            MoveGeometry geo = PredictionChecksTest.geometry(toGround, strict, fromGround, sim.y - sim.groundY < 0.6, false, false, ceiling);
            MoveContext m = MoveContext.builder().time(now, now * 1_000_000L).from(lx, ly, lz).to(sim.x, sim.y, sim.z)
                    .sprinting(sim.sprint).movementSpeedAttr(attrServer * sprintFactor).jumpAmplifier(jumpAmp)
                    .gravityAttr(sim.gravity).ping(ping).geometry(geo).build();
            trace.addLast(String.format("  t=%d EVENT dy=%.4f h=%.4f toGround=%s strict=%s prevGround=%s carryVy=%.4f carryH=%.4f prevStrict=%s lastStrict=%s",
                    t, dy, Math.hypot(dx, dz), toGround, strict, d.lastGround, d.predVy, d.predH, d.predPrevGround, d.predLastGroundStrict));
            if (checks.evaluate(null, d, m, d.lastGround, toGround, null, false)) {
                rejects++;
                return "seed " + seed + " tick " + t + ": " + (flags.isEmpty() ? "?" : flags.get(flags.size() - 1))
                        + (System.getProperty("fuzz.trace") != null ? "\n" + String.join("\n", trace) : "");
            }
            d.lastGround = toGround;
            lx = sim.x; ly = sim.y; lz = sim.z;
        }
        return rejects == 0 ? null : "seed " + seed;
    }

    @Test void randomisedLegitimatePlayIsNeverRejected() {
        List<String> failures = new ArrayList<>();
        long seeds = Long.getLong("fuzz.seeds", 400L);
        long first = Long.getLong("fuzz.start", 1L);
        for (long seed = first; seed < first + seeds; seed++) {
            String f = runSeed(seed, 2_000);
            if (f != null) failures.add(f);
            if (failures.size() >= 8) break;
        }
        assertTrue(failures.isEmpty(), String.join("\n", failures));
    }
}
