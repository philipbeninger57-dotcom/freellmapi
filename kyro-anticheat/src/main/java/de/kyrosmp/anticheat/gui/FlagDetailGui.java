/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  org.bukkit.Bukkit
 *  org.bukkit.Material
 *  org.bukkit.entity.Player
 */
package de.kyrosmp.anticheat.gui;

import de.kyrosmp.anticheat.flag.FlagRecord;
import de.kyrosmp.anticheat.flag.FlagStatus;
import de.kyrosmp.anticheat.gui.GuiContext;
import de.kyrosmp.anticheat.gui.GuiText;
import de.kyrosmp.anticheat.gui.Items;
import de.kyrosmp.anticheat.gui.MenuHolder;
import de.kyrosmp.anticheat.gui.PlayerFlagsGui;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;

public final class FlagDetailGui {
    private final GuiContext ctx;

    FlagDetailGui(GuiContext ctx) {
        this.ctx = ctx;
    }

    public void open(Player viewer, String flagId, PlayerFlagsGui.State back) {
        this.ctx.load(viewer, this.ctx.store().flag(flagId), opt -> this.render(viewer, (Optional<FlagRecord>)opt, back));
    }

    private void render(Player viewer, Optional<FlagRecord> opt, PlayerFlagsGui.State back) {
        if (opt.isEmpty()) {
            viewer.sendMessage("\u00a78[\u00a7bKYRO\u00a78] \u00a77\u00a7cThis flag no longer exists.");
            this.ctx.playerFlags().open(viewer, back);
            return;
        }
        FlagRecord f = opt.get();
        long now = System.currentTimeMillis();
        MenuHolder menu = new MenuHolder(27, "\u00a78Flag \u00a77#" + f.shortId());
        for (int i = 0; i < 27; ++i) {
            menu.set(i, Items.filler(), null);
        }
        ArrayList<String> lore = new ArrayList<String>();
        lore.add("\u00a77ID: \u00a7f" + f.flagId());
        lore.add("\u00a77Player: \u00a7f" + f.playerName() + " \u00a78(" + String.valueOf(f.playerUuid()) + ")");
        lore.add("\u00a77Category: \u00a7f" + f.category().displayName());
        lore.add("\u00a77Check: \u00a7f" + f.checkName());
        lore.add("\u00a77VL: \u00a7c" + GuiText.vl(f.vl()));
        lore.add("\u00a77Date: \u00a7f" + this.ctx.text().time(f.createdAt()) + " \u00a78(" + GuiText.ago(now, f.createdAt()) + ")");
        lore.add("\u00a77Period: " + (f.isCurrent(now, this.ctx.currentWindowMs()) ? "\u00a7cCurrent" : "\u00a78Historic"));
        lore.add("\u00a77Status: " + f.status().colored() + (String)(f.reviewedBy() != null ? " \u00a78by \u00a7f" + f.reviewedBy() + " \u00a78on \u00a7f" + this.ctx.text().time(f.reviewedAt()) : ""));
        lore.add("\u00a77Confidence: " + f.confidence().colored() + " \u00a78(not proof)");
        lore.add("\u00a77Ping: \u00a7f" + (String)(f.ping() < 0 ? "unknown" : f.ping() + " ms"));
        lore.add("\u00a77Server-TPS: \u00a7f" + (f.tps() < 0.0 ? "unknown" : String.format(Locale.ROOT, "%.1f", f.tps())));
        lore.add("\u00a77Triggers: \u00a7f" + f.mergedCount() + (f.mergedCount() > 1 ? " \u00a78(within the merge window)" : ""));
        if (f.hasPosition()) {
            lore.add(String.format(Locale.ROOT, "\u00a77Position: \u00a7f%s %.1f / %.1f / %.1f", f.world(), f.x(), f.y(), f.z()));
        }
        lore.add(" ");
        lore.add("\u00a7bReason:");
        lore.addAll(GuiText.wrap(f.reason(), 40, "\u00a77"));
        lore.add(" ");
        lore.add("\u00a7bMeasurements:");
        lore.addAll(GuiText.details(f.details(), 40, "\u00a77"));
        lore.add(" ");
        lore.add("\u00a7bActions taken:");
        if (f.actions().isBlank()) {
            lore.add("\u00a77none (alert only)");
        } else {
            lore.addAll(GuiText.details(f.actions(), 40, "\u00a77"));
        }
        Material icon = Items.material(f.category().iconMaterial(), Material.PAPER);
        menu.set(4, Items.of(icon, "\u00a7c" + f.checkName() + " \u00a78\u2014 \u00a7fVL " + GuiText.vl(f.vl()), lore), null);
        boolean manage = viewer.hasPermission("kyroac.manage");
        if (manage) {
            this.statusButton(menu, 11, Material.YELLOW_CONCRETE, "\u00a7eMark as reviewed", f, FlagStatus.REVIEWED, back);
            this.statusButton(menu, 13, Material.LIME_CONCRETE, "\u00a7aMark as resolved", f, FlagStatus.RESOLVED, back);
            this.statusButton(menu, 15, Material.WHITE_CONCRETE, "\u00a7fReset to new", f, FlagStatus.NEW, back);
        } else {
            menu.set(13, Items.of(Material.BARRIER, "\u00a7cNo permission", "\u00a77Requires kyroac.manage"), null);
        }
        Player target = Bukkit.getPlayer((UUID)f.playerUuid());
        if (target != null && viewer.hasPermission("kyroac.teleport")) {
            menu.set(17, Items.of(Material.ENDER_PEARL, "\u00a7bTeleport to player", "\u00a77Player is online"), (v, c) -> {
                Player t = Bukkit.getPlayer((UUID)f.playerUuid());
                if (t != null && v.hasPermission("kyroac.teleport")) {
                    this.ctx.teleportTo(v, t, false);
                }
            });
        }
        if (f.previousFlagId() != null) {
            menu.set(9, Items.of(Material.ARROW, "\u00a7fPrevious incident", "\u00a77#" + f.previousFlagId().substring(0, Math.min(8, f.previousFlagId().length())), "\u00a77Click: open"), (v, c) -> this.open(v, f.previousFlagId(), back));
        }
        menu.set(22, Items.of(Material.OAK_DOOR, "\u00a7fBack to flag list", new String[0]), (v, c) -> this.ctx.playerFlags().open(v, back));
        viewer.openInventory(menu.getInventory());
    }

    private void statusButton(MenuHolder menu, int slot, Material material, String name, FlagRecord f, FlagStatus target, PlayerFlagsGui.State back) {
        boolean current = f.status() == target;
        menu.set(slot, Items.of(current ? Material.GRAY_CONCRETE : material, name + (current ? " \u00a78(current)" : ""), "\u00a77The record is kept.", "\u00a77The change is logged."), current ? null : (v, c) -> {
            if (!v.hasPermission("kyroac.manage")) {
                return;
            }
            this.ctx.load(v, this.ctx.store().updateStatus(f.flagId(), target, v.getUniqueId(), v.getName(), System.currentTimeMillis()), ok -> this.open(v, f.flagId(), back));
        });
    }
}

