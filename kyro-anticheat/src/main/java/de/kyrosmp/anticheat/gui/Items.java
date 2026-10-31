/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  org.bukkit.Bukkit
 *  org.bukkit.Material
 *  org.bukkit.inventory.ItemFlag
 *  org.bukkit.inventory.ItemStack
 *  org.bukkit.inventory.meta.ItemMeta
 *  org.bukkit.inventory.meta.SkullMeta
 */
package de.kyrosmp.anticheat.gui;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

final class Items {
    private Items() {
    }

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

    static ItemStack of(Material material, String name, String ... lore) {
        return Items.of(material, name, Arrays.asList(lore));
    }

    static ItemStack head(UUID owner, String name, List<String> lore) {
        ItemStack item = Items.of(Material.PLAYER_HEAD, name, lore);
        ItemMeta itemMeta = item.getItemMeta();
        if (itemMeta instanceof SkullMeta) {
            SkullMeta skull = (SkullMeta)itemMeta;
            skull.setOwningPlayer(Bukkit.getOfflinePlayer((UUID)owner));
            item.setItemMeta((ItemMeta)skull);
        }
        return item;
    }

    static Material material(String name, Material fallback) {
        Material m = Material.matchMaterial((String)name);
        return m == null ? fallback : m;
    }

    static ItemStack filler() {
        return Items.of(Material.GRAY_STAINED_GLASS_PANE, " ", new String[0]);
    }
}

