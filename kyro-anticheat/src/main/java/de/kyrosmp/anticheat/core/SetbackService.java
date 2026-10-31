/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  org.bukkit.Location
 *  org.bukkit.entity.Player
 */
package de.kyrosmp.anticheat.core;

import de.kyrosmp.anticheat.core.PlayerData;
import org.bukkit.Location;
import org.bukkit.entity.Player;

public interface SetbackService {
    public boolean setback(Player var1, PlayerData var2, String var3);

    public boolean setbackTo(Player var1, PlayerData var2, Location var3, String var4);

    public void registerFlyPunishment(Player var1, PlayerData var2, long var3, String var5);
}

