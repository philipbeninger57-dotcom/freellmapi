/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  org.bukkit.entity.Player
 */
package de.kyrosmp.anticheat.flag;

import de.kyrosmp.anticheat.core.PlayerData;
import org.bukkit.entity.Player;

public interface FlagSink {
    public String flag(Player var1, PlayerData var2, String var3, String var4, String var5);

    public void action(String var1, String var2);
}

