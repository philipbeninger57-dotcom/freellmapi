package de.kyrosmp.anticheat.gui;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

final class Items {
    private Items() {}

    static ItemStack of(Material material, String name, List<String> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            meta.setLore(lore);
            meta.addItemFlags(ItemFlag.values());
            item.setItemMeta(meta);
        }

        return item;
    }

    static ItemStack of(Material material, String name, String... lore) {
        return of(material, name, Arrays.asList(lore));
    }

    static ItemStack head(UUID owner, String name, List<String> lore) {
        ItemStack item = of(Material.PLAYER_HEAD, name, lore);
        if (item.getItemMeta() instanceof SkullMeta skull) {
            skull.setOwningPlayer(Bukkit.getOfflinePlayer(owner));
            item.setItemMeta(skull);
        }

        return item;
    }

    static Material material(String name, Material fallback) {
        Material m = Material.matchMaterial(name);
        return m == null ? fallback : m;
    }

    static ItemStack filler() {
        return of(Material.GRAY_STAINED_GLASS_PANE, " ");
    }
}
