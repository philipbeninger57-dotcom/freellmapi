package de.kyrosmp.anticheat.gui;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.Map;

public final class MenuHolder implements InventoryHolder {
    private final Map<Integer, MenuHolder.ClickHandler> handlers = new HashMap<>();
    private final Inventory inventory;

    public MenuHolder(int size, String title) {
        this.inventory = Bukkit.createInventory(this, size, title);
    }

    public void set(int slot, ItemStack item, MenuHolder.ClickHandler handler) {
        this.inventory.setItem(slot, item);
        if (handler != null) {
            this.handlers.put(slot, handler);
        } else {
            this.handlers.remove(slot);
        }
    }

    public MenuHolder.ClickHandler handler(int slot) {
        return this.handlers.get(slot);
    }

    public Inventory getInventory() {
        return this.inventory;
    }

    @FunctionalInterface
    public interface ClickHandler {
        void onClick(Player player, ClickType click);
    }
}
