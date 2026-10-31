package de.kyrosmp.anticheat.checks.action;

import de.kyrosmp.anticheat.core.*;
import de.kyrosmp.anticheat.flag.FlagSink;
import java.lang.reflect.Proxy;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ActionPreventionTest {
    final FlagSink sink=new FlagSink() {
        public String flag(Player p,PlayerData d,String c,String r,String details) { return null; }
        public void action(String id,String a) { }
    };
    final PlayerData d=new PlayerData(UUID.randomUUID(),"test");
    final BlockPlaceChecks checks=new BlockPlaceChecks(sink,CheckConfig.defaults());
    @Test void confirmedRateViolationKeepsCancellingWhileAlertsAreMerged() {
        for(int i=0;i<22;i++) checks.onPlace(null,d,null,null,20_000+i);
        for(int i=22;i<80;i++) assertTrue(checks.onPlace(null,d,null,null,20_000+i));
    }
    @Test void ordinaryTwentyHzPlacementsAreNotCancelledAtOneSecondBoundary() {
        for(int i=0;i<100;i++) assertFalse(checks.onPlace(null,d,null,null,20_000+i*50));
    }
    @Test void actionPolicyOffDoesNotCancelConfirmedPlacementRate() {
        checks.withPrevention(new PreventionConfig(true,true,true,true,false));
        for(int i=0;i<80;i++) assertFalse(checks.onPlace(null,d,null,null,20_000+i));
    }
    Block block(int x) {
        return (Block)Proxy.newProxyInstance(Block.class.getClassLoader(),new Class<?>[]{Block.class},(p,m,a)->switch(m.getName()) {
            case "getX" -> x;
            case "getY", "getZ" -> 0;
            case "getType" -> Material.STONE;
            default -> null;
        });
    }
    @Test void serverRangeViolationCancelsWithoutWaitingForScaffoldFlags() {
        checks.solid = b -> true; // Geometry is controlled without a running Paper registry.
        Player player=(Player)Proxy.newProxyInstance(Player.class.getClassLoader(),new Class<?>[]{Player.class},(p,m,a)->switch(m.getName()) {
            case "getEyeLocation" -> new Location(null,0,1,0);
            case "getPing" -> 50;
            default -> null;
        });
        assertTrue(checks.onPlace(player,d,block(11),block(10),20_000));
    }
}
