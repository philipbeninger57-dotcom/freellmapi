package de.kyrosmp.anticheat.core;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

public final class CheckRegistry {
    private final Map<String, CheckInfo> checks = new LinkedHashMap<>();

    public static CheckRegistry defaults() {
        CheckRegistry r = new CheckRegistry();
        r.register(
                new CheckInfo(
                        "Fly A",
                        CheckCategory.MOVEMENT,
                        "movement-vertical",
                        "checks.fly",
                        "Jump height above the physical maximum (Jump Boost, jump_strength and"
                            + " gravity attributes), plus rising, hovering or descending too slowly"
                            + " after the jump phase; ground detection uses the real hitbox (blocks"
                            + " and hard entities) and vanilla Y-first collisions. During"
                            + " teleport/knockback grace only rising long after the impulse and"
                            + " hovering are judged.",
                        "Not checked during elytra (see ElytraFly A), levitation, slow falling,"
                                + " liquids, climbables and vehicles."));
        r.register(
                new CheckInfo(
                        "Speed A",
                        CheckCategory.MOVEMENT,
                        "movement-horizontal",
                        "checks.speed",
                        "Horizontal distance per move against a limit from sprinting, walk speed,"
                                + " movement_speed attribute (includes Speed, Soul Speed and item"
                                + " modifiers since 1.21) or Speed effect, jumping, ceilings,"
                                + " ice/slime, bounces and ping.",
                        "Not checked in liquids (Dolphin's Grace, Depth Strider), during elytra,"
                                + " vehicles and knockback grace. Pistons/slime launchers pushing"
                                + " sideways are not modelled."));
        r.register(
                new CheckInfo(
                        "Timer A",
                        CheckCategory.MOVEMENT,
                        "movement-horizontal",
                        "checks.timer",
                        "Movement-event balance: every position event costs 50 ms, elapsed time is"
                                + " credited. A pause only grants credit for the time really lost"
                                + " (max. 1 s, 10 s if the server itself stalled) and it expires,"
                                + " so pausing on purpose gains nothing. Also judged during"
                                + " knockback, elytra and teleport grace. Alert-only by default;"
                                + " with checks.timer.prevent: true the surplus moves are rejected"
                                + " (no ground setback) until real time pays the debt back.",
                        "Only counts position changes that fire PlayerMoveEvent, not every packet;"
                                + " event-rate evidence is approximate."));
        r.register(
                new CheckInfo(
                        "NoFall A",
                        CheckCategory.MOVEMENT,
                        "movement-vertical",
                        "checks.nofall",
                        "Own fall tracking: landing after more than 4.5 blocks (+ Jump Boost, +"
                            + " raised safe_fall_distance) without a fall damage event; after wind"
                            + " charges only the part below the impulse height counts (vanilla"
                            + " 1.21).",
                        "Water, slime, hay, honey, beds, climbables, mace, fall_damage_multiplier"
                                + " 0, the fall damage gamerule and invulnerability are excluded."
                                + " Classic ground spoofing is mainly caught by NoFall B."));
        r.register(
                new CheckInfo(
                        "NoFall B",
                        CheckCategory.MOVEMENT,
                        "movement-vertical",
                        "checks.nofall",
                        "Ground spoof: the client claims ground although no colliding block is"
                                + " nearby.",
                        "Rejected block placements (ghost blocks) get a 1.5 s grace period."));
        r.register(
                new CheckInfo(
                        "BadPackets A",
                        CheckCategory.PACKET,
                        "packet",
                        "checks.badpackets",
                        "Movement packet with NaN/infinite values or pitch beyond ±90°.",
                        "Vanilla already disconnects most invalid packets before PlayerMoveEvent;"
                                + " this rarely triggers."));
        r.register(
                new CheckInfo(
                        "BoatFly A",
                        CheckCategory.VEHICLE,
                        "vehicle",
                        "checks.boatfly",
                        "VehicleMoveEvent: a boat without ground or liquid contact rises (without"
                            + " the deceleration of a ballistic arc) or hovers for more than 250"
                            + " ms.",
                        "Boats only; other vehicles are not checked. Short climbs of a few blocks"
                                + " can stay below the buffer."));
        r.register(
                new CheckInfo(
                        "Reach A",
                        CheckCategory.COMBAT,
                        "combat-reach",
                        "checks.reach",
                        "Distance eye → closest hitbox point, lag-compensated over the target's"
                            + " recent server positions (players) or its last two ticks of motion"
                            + " (mobs with AI, max. 0.6), against max(entity_interaction_range,"
                            + " item attack_range) + tolerance.",
                        "Direct melee hits only; the compensation window is ping + 200 ms (max. 600"
                                + " ms)."));
        r.register(
                new CheckInfo(
                        "KillAura A",
                        CheckCategory.COMBAT,
                        "combat-aura",
                        "checks.killaura",
                        "Hits on targets outside the field of view (angle between look direction"
                                + " and target above max-angle).",
                        "Only from 1.2 blocks distance; rotation is the last one reported by the"
                                + " client."));
        r.register(
                new CheckInfo(
                        "KillAura B",
                        CheckCategory.COMBAT,
                        "combat-aura",
                        "checks.killaura",
                        "Many switches between widely separated targets (default >= 30° apart) with"
                                + " very short intervals (default 6 switches, each <140 ms).",
                        "The server processes packets per tick, hence the deliberately high switch"
                            + " count. Switching between targets close together (mob crowds) is not"
                            + " counted."));
        r.register(
                new CheckInfo(
                        "Jesus A",
                        CheckCategory.MOVEMENT,
                        "movement-liquid",
                        "checks.jesus",
                        "Walking on liquid: dry feet within 0.2 above a liquid without ground – at"
                                + " constant height (level mode) or for 20+ moves in a row with any"
                                + " vertical pattern (sustained mode, 3.4).",
                        "Dry-foot liquid movement heuristic; prevention is enabled by default and"
                                + " remains subject to exemptions."));
        r.register(
                new CheckInfo(
                        "Velocity A",
                        CheckCategory.COMBAT,
                        "combat-velocity",
                        "checks.velocity",
                        "After horizontal knockback, no noticeable movement in the knockback"
                                + " direction (below 10 %).",
                        "Only detects ignoring knockback almost entirely; partial reduction cannot"
                                + " be told apart from legitimate counter-movement without client"
                                + " simulation."));
        r.register(
                new CheckInfo(
                        "AutoClicker A",
                        CheckCategory.COMBAT,
                        "combat-clicks",
                        "checks.autoclicker",
                        "Sustained high click rate (default above 30 CPS for 3 consecutive"
                                + " seconds); swings from item drops are ignored.",
                        "Deliberately no pattern or regularity analysis – that would not be"
                                + " reliable server-side."));
        r.register(
                new CheckInfo(
                        "Scaffold A",
                        CheckCategory.BLOCK,
                        "block-place",
                        "checks.scaffold",
                        "The look ray does not hit the clicked block (building without a matching"
                                + " look direction).",
                        "New in 3.2; the client rotation can lag one tick behind, hence tolerance"
                                + " and buffer."));
        r.register(
                new CheckInfo(
                        "FastPlace A",
                        CheckCategory.BLOCK,
                        "block-place",
                        "checks.fastplace",
                        "More block placements per second than possible with fast clicking (default"
                                + " above 20).",
                        "-"));
        r.register(
                new CheckInfo(
                        "KillAura C",
                        CheckCategory.COMBAT,
                        "combat-aura",
                        "checks.killaura",
                        "Hits through solid blocks: every line of sight from the eye to the target"
                                + " hitbox (closest point, centre, 8 corners) is blocked by a"
                                + " collidable block. Vanilla clients cannot hit through blocks.",
                        "Moving targets are judged at every position of the last ping + 200 ms;"
                            + " skipped above 400 ms ping. A line only counts as blocked if both"
                            + " the collision and the outline shape block it (fences, walls, plants"
                            + " never count as cover, 3.5)."));
        r.register(
                new CheckInfo(
                        "XRay A",
                        CheckCategory.BLOCK,
                        "block-mining",
                        "checks.xray",
                        "Mining statistics: rare-ore veins reached only through the player's own"
                            + " digging, per stone-type block mined, over a 20-minute window. Alert"
                            + " only.",
                        "Does not hide ores – enable Paper's native anti-xray for that"
                            + " (docs/ANTI-XRAY.md). Lucky branch mining or TNT/bed mining are not"
                            + " distinguished; evidence for staff review only."));
        r.register(
                new CheckInfo(
                        "Criticals A",
                        CheckCategory.COMBAT,
                        "combat-criticals",
                        "checks.criticals",
                        "Critical hits while the server sees the player resting on a block, with"
                                + " less than 0.2 blocks of vertical movement in the last second."
                                + " Configurable prevention removes the critical bonus.",
                        "Skipped under a ceiling (head-hitter jumps), in liquids and on climbables;"
                            + " a single hit after stepping off a carpet edge can look the same,"
                            + " hence the buffer."));
        r.register(
                new CheckInfo(
                        "ElytraFly A",
                        CheckCategory.VEHICLE,
                        "vehicle-elytra",
                        "checks.elytrafly",
                        "While gliding without boost: hovering (no motion, nothing to collide"
                            + " with), climbing above the physical ceiling of the glide (24·v² + 6"
                            + " blocks from any state), or faster than max-speed.",
                        "Instant prevention is enabled by default; buffered mode uses the"
                            + " configured incident threshold and time window, to the last verified"
                            + " glide position. Level flight at plausible speed cannot be told"
                            + " apart from gliding server-side; each firework boost suspends the"
                            + " climb and speed rules for 3.5 s."));
        r.register(
                new CheckInfo(
                        "InventoryMove A",
                        CheckCategory.INVENTORY,
                        "inventory",
                        "checks.inventorymove",
                        "Inventory clicks during sustained fast ground movement. Only movement"
                            + " samples taken after the opening grace (open-grace-ms, default 25"
                            + " ms) count, each sample once; from the second sample on further"
                            + " clicks are refused.",
                        "Ice/slime, knockback, liquids and vehicles are excluded; new in 3.2."));
        r.register(
                new CheckInfo(
                        "Fly B",
                        CheckCategory.MOVEMENT,
                        "movement-vertical",
                        "checks.fly.prediction",
                        "Event-based gravity prediction: every airborne move must follow v' = (v -"
                            + " gravity) * 0.98 from the previous one (Paper's merged move events,"
                            + " jumps, Jump Boost, jump_strength/gravity attributes and server-sent"
                            + " knockback/explosion impulses included). Climbing faster than 0.2"
                            + " blocks per tick on ladders, vines and scaffolding is covered as"
                            + " well. The first impossible tick is rejected; a repeat within one"
                            + " second returns the player to validated ground.",
                        "Not judged during teleport grace, elytra, riptide, swimming, vehicles,"
                            + " levitation, slow falling, liquids, bounce blocks, moving pistons,"
                            + " entity platforms (boats, shulkers, happy ghasts) and right after"
                            + " cancelled block placements."));
        r.register(
                new CheckInfo(
                        "Fly C",
                        CheckCategory.MOVEMENT,
                        "movement-vertical",
                        "checks.fly.hover",
                        "Correction-only heartbeat on the player's own scheduler: an unsupported"
                                + " stationary position longer than still-ms can be returned to"
                                + " validated ground. Silence never creates a cheat flag or adds"
                                + " suspicion.",
                        "Skipped during teleport grace, after vehicles, elytra, riptide, liquids,"
                            + " bounce blocks, cancelled block placements and with entity platforms"
                            + " nearby. Client/network stalls may also require a position"
                            + " correction; this is not cheat evidence."));
        r.register(
                new CheckInfo(
                        "Step A",
                        CheckCategory.MOVEMENT,
                        "movement-vertical",
                        "checks.step",
                        "Ground-to-ground move higher than the step_height attribute (default 0.6)"
                                + " or the jump velocity allows.",
                        "Pistons, bounce blocks, entity platforms and knockback impulses are taken"
                                + " into account."));
        r.register(
                new CheckInfo(
                        "Speed B",
                        CheckCategory.MOVEMENT,
                        "movement-horizontal",
                        "checks.speed.prediction",
                        "Momentum prediction: the previous move carried over with vanilla friction"
                            + " (ground 0.546, ice/air 0.91) plus the largest input acceleration"
                            + " (movement_speed attribute incl. sprint) and the 0.2 sprint-jump"
                            + " boost. Accepted moves never raise the next limit beyond vanilla, so"
                            + " speed cannot be built up step by step.",
                        "Not judged in liquids, on elytra, in vehicles, during teleport grace, near"
                            + " moving pistons or entity platforms; knockback/explosion impulses"
                            + " and entity pushing are allowed for."));
        r.register(
                new CheckInfo(
                        "NoSlow A",
                        CheckCategory.MOVEMENT,
                        "movement-horizontal",
                        "checks.noslow",
                        "Walking on the ground at normal speed while eating, drinking, blocking"
                                + " with a shield or drawing a bow (vanilla slows to 20 %, or the"
                                + " item's use_effects speed).",
                        "Judged only on pure ground movement after 3 ticks of item use and not in"
                                + " the last 3 ticks before the use completes."));
        r.register(
                new CheckInfo(
                        "Nuker A",
                        CheckCategory.BLOCK,
                        "block-mining",
                        "checks.nuker",
                        "More dig starts per second than a client can produce (vanilla insta-mining"
                                + " is at most one block per tick). Excess digs are cancelled.",
                        "Only genuine client digs (BlockDamageEvent) are counted; breaks simulated"
                                + " by other plugins are never touched."));
        r.register(
                new CheckInfo(
                        "GhostHand A",
                        CheckCategory.BLOCK,
                        "block-mining",
                        "checks.ghosthand",
                        "Digging a block that is completely hidden: none of 15 lines from the eye"
                            + " to the block reaches it without crossing another block's outline"
                            + " (what the vanilla crosshair targets). The dig is cancelled.",
                        "Fluids are ignored like in vanilla; an eye inside an outline (tall grass,"
                                + " doors) is not judged."));
        r.register(
                new CheckInfo(
                        "Scaffold B",
                        CheckCategory.BLOCK,
                        "block-place",
                        "checks.scaffold.face",
                        "Block placed against a face whose plane the eye is clearly behind - a"
                            + " crosshair ray can only hit a face from the side it points to. The"
                            + " placement is cancelled.",
                        "Tolerance covers the last movement (the crosshair is cast from the"
                                + " interpolated camera); non-full faces use their most lenient"
                                + " plane."));
        r.register(
                new CheckInfo(
                        "NoSlow B",
                        CheckCategory.MOVEMENT,
                        "movement-horizontal",
                        "checks.noslow",
                        "Walking on the ground at normal speed while sneaking (vanilla scales the"
                                + " input by the sneaking_speed attribute: 0.3, more with Swift"
                                + " Sneak). Uses the same momentum prediction as Speed B.",
                        "Judged only on pure ground movement with sneaking reported for two moves"
                                + " in a row; entity pushing and knockback are allowed for."));
        r.register(
                new CheckInfo(
                        "Sprint A",
                        CheckCategory.MOVEMENT,
                        "movement-horizontal",
                        "checks.sprint",
                        "Sprint start with 6 or fewer food points (impossible for a vanilla"
                                + " client). The start is refused and Speed B stops granting sprint"
                                + " speed to hungry players that are not sprinting server-side.",
                        "Starts within ping + 300 ms of the food level dropping are not judged;"
                                + " flight-allowed, creative and vehicle players are excluded."));
        r.register(
                new CheckInfo(
                        "KillAura D",
                        CheckCategory.COMBAT,
                        "combat-aura",
                        "checks.killaura.noswing",
                        "Melee attack without the arm swing a vanilla client sends right after"
                                + " every attack (checked 5 ticks later). While the buffer is high,"
                                + " further hits are cancelled (uses checks.killaura.cancel-hit).",
                        "Spear attacks are skipped. Plugins that make players attack via the API"
                                + " (Player#attack) without a swing can trigger it; disable"
                                + " checks.killaura.noswing in that case."));
        return r;
    }

    public void register(CheckInfo info) {
        if (this.checks.putIfAbsent(key(info.name()), info) != null) {
            throw new IllegalStateException("Check registered twice: " + info.name());
        }
    }

    public CheckInfo get(String name) {
        CheckInfo info = this.checks.get(key(name));
        return info != null
                ? info
                : new CheckInfo(
                        name,
                        CheckCategory.OTHER,
                        "unregistered:" + name,
                        "checks.unknown",
                        "Unregistered check",
                        "Not registered – please add it to CheckRegistry.");
    }

    public boolean isRegistered(String name) {
        return this.checks.containsKey(key(name));
    }

    public Collection<CheckInfo> all() {
        return Collections.unmodifiableCollection(this.checks.values());
    }

    private static String key(String name) {
        return name.toLowerCase(Locale.ROOT);
    }
}
