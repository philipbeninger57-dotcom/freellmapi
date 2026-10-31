package de.kyrosmp.anticheat.testutil;

import de.kyrosmp.anticheat.core.MoveContext;
import de.kyrosmp.anticheat.core.MoveGeometry;

import org.bukkit.entity.Player;

import java.lang.reflect.Proxy;
import java.util.UUID;

/** Builders for synthetic moves and players used by the check tests. */
public final class Moves {
    private Moves() {}

    /** Flat, solid ground with nothing special around. */
    public static MoveGeometry ground(boolean onGround) {
        return new MoveGeometry() {
            @Override
            public boolean ground() {
                return onGround;
            }

            @Override
            public boolean fromGrounded() {
                return onGround;
            }

            @Override
            public boolean restingAtPreviousXZ() {
                return false;
            }

            @Override
            public boolean liquidOrClimbable() {
                return false;
            }

            @Override
            public boolean bounceSurface() {
                return false;
            }

            @Override
            public boolean slipperySurface() {
                return false;
            }

            @Override
            public boolean ceiling() {
                return false;
            }

            @Override
            public boolean nearGround() {
                return onGround;
            }

            @Override
            public boolean safeNoDamageLanding() {
                return false;
            }

            @Override
            public boolean liquidUnderFeet() {
                return false;
            }

            @Override
            public boolean bodyWet() {
                return false;
            }

            @Override
            public boolean collisionNearby() {
                return false;
            }
        };
    }

    /** A horizontal ground move of the given length along +X starting at x. */
    public static MoveContext.Builder groundMove(double x, double horizontal, long now) {
        return MoveContext.builder()
                .time(now, now * 1_000_000L)
                .from(x, 64.0, 0.0)
                .to(x + horizontal, 64.0, 0.0)
                .geometry(ground(true));
    }

    /** Minimal online player: every method returns a neutral default. */
    public static Player player() {
        UUID id = UUID.randomUUID();
        return (Player)
                Proxy.newProxyInstance(
                        Player.class.getClassLoader(),
                        new Class<?>[] {Player.class},
                        (proxy, method, args) -> {
                            switch (method.getName()) {
                                case "isOnline":
                                    return true;
                                case "getUniqueId":
                                    return id;
                                case "getName":
                                    return "Tester";
                                case "hashCode":
                                    return id.hashCode();
                                case "equals":
                                    return proxy == args[0];
                                case "toString":
                                    return "Player[Tester]";
                                default:
                                    break;
                            }
                            Class<?> t = method.getReturnType();
                            if (t == boolean.class) return false;
                            if (t == int.class) return 0;
                            if (t == long.class) return 0L;
                            if (t == double.class) return 0.0;
                            if (t == float.class) return 0.0F;
                            return null;
                        });
    }
}
