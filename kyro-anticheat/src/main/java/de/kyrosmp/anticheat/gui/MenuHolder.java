/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  org.bukkit.Bukkit
 *  org.bukkit.entity.Player
 *  org.bukkit.event.inventory.ClickType
 *  org.bukkit.inventory.Inventory
 *  org.bukkit.inventory.InventoryHolder
 *  org.bukkit.inventory.ItemStack
 */
package de.kyrosmp.anticheat.gui;

import java.util.HashMap;
import java.util.Map;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

public final class MenuHolder
implements InventoryHolder {
    private final Map<Integer, ClickHandler> handlers = new HashMap<Integer, ClickHandler>();
    private final Inventory inventory;

    public MenuHolder(int size, String title) {
        this.inventory = Bukkit.createInventory((InventoryHolder)this, (int)size, (String)title);
    }

    public void set(int slot, ItemStack item, ClickHandler handler) {
        this.inventory.setItem(slot, item);
        if (handler != null) {
            this.handlers.put(slot, handler);
        } else {
            this.handlers.remove(slot);
        }
    }

    public ClickHandler handler(int slot) {
        return this.handlers.get(slot);
    }

    public Inventory getInventory() {
        return this.inventory;
    }

    @FunctionalInterface
    public static interface ClickHandler {
        public void onClick(Player var1, ClickType var2);
    }
}

