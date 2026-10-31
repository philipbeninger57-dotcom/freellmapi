/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  org.bukkit.Bukkit
 *  org.bukkit.Material
 *  org.bukkit.entity.Player
 */
package de.kyrosmp.anticheat.gui;

import de.kyrosmp.anticheat.core.CheckCategory;
import de.kyrosmp.anticheat.flag.FlagRecord;
import de.kyrosmp.anticheat.flag.FlagStatus;
import de.kyrosmp.anticheat.gui.GuiContext;
import de.kyrosmp.anticheat.gui.GuiText;
import de.kyrosmp.anticheat.gui.Items;
import de.kyrosmp.anticheat.gui.MenuHolder;
import de.kyrosmp.anticheat.gui.SusGui;
import de.kyrosmp.anticheat.storage.FlagQuery;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;

public final class PlayerFlagsGui {
    static final int PAGE_SIZE = 45;
    private final GuiContext ctx;

    PlayerFlagsGui(GuiContext ctx) {
        this.ctx = ctx;
    }

    public void open(Player viewer, State state) {
        FlagQuery q = new FlagQuery(state.category(), state.onlyNew(), state.sort(), state.page(), 45);
        this.ctx.load(viewer, this.ctx.store().playerFlags(state.player(), q), rows -> this.render(viewer, state, (List<FlagRecord>)rows));
    }

    private void render(Player viewer, State state, List<FlagRecord> rows) {
        long now = System.currentTimeMillis();
        boolean hasNext = rows.size() > 45;
        boolean canManage = viewer.hasPermission("kyroac.manage");
        MenuHolder menu = new MenuHolder(54, "\u00a78Flags: " + state.name() + " \u00a77(page " + (state.page() + 1) + ")");
        int slot = 0;
        for (FlagRecord f : rows) {
            if (slot >= 45) break;
            ArrayList<String> lore = new ArrayList<String>();
            lore.add("\u00a77Time: \u00a7f" + this.ctx.text().time(f.createdAt()) + " \u00a78(" + GuiText.ago(now, f.createdAt()) + ")");
            lore.add("\u00a77Status: " + f.status().colored() + " \u00a78| \u00a77Confidence: " + f.confidence().colored());
            lore.add("\u00a77Category: \u00a7f" + f.category().displayName());
            lore.add("\u00a77Ping: \u00a7f" + (String)(f.ping() < 0 ? "unknown" : f.ping() + " ms") + (String)(f.mergedCount() > 1 ? " \u00a78| \u00a77\u00d7" + f.mergedCount() + " merged" : ""));
            lore.add(f.isCurrent(now, this.ctx.currentWindowMs()) ? "\u00a7cCurrent" : "\u00a78Historic");
            lore.addAll(GuiText.wrap(f.reason(), 38, "\u00a77"));
            lore.add(" ");
            lore.add("\u00a7eLeft-click: \u00a77details");
            if (canManage && f.status() == FlagStatus.NEW) {
                lore.add("\u00a7eRight-click: \u00a77mark as reviewed");
            }
            Material icon = Items.material(f.category().iconMaterial(), Material.PAPER);
            String name = f.status().colored().substring(0, 2) + f.checkName() + " \u00a78\u2014 \u00a7fVL " + GuiText.vl(f.vl());
            menu.set(slot++, Items.of(icon, name, lore), (v, click) -> {
                if (click.isRightClick() && f.status() == FlagStatus.NEW && v.hasPermission("kyroac.manage")) {
                    this.ctx.load(v, this.ctx.store().updateStatus(f.flagId(), FlagStatus.REVIEWED, v.getUniqueId(), v.getName(), System.currentTimeMillis()), ok -> this.open(v, state));
                } else {
                    this.ctx.flagDetail().open(v, f.flagId(), state);
                }
            });
        }
        if (rows.isEmpty()) {
            menu.set(22, Items.of(Material.LIME_DYE, "\u00a7aNo flags", "\u00a77There are no entries", "\u00a77for the current filters."), null);
        }
        for (int i = 45; i < 54; ++i) {
            menu.set(i, Items.filler(), null);
        }
        menu.set(45, Items.of(Material.OAK_DOOR, "\u00a7fBack to overview", new String[0]), (v, c) -> this.ctx.sus().open(v, state.back()));
        if (state.page() > 0) {
            menu.set(46, Items.of(Material.ARROW, "\u00a7f\u00ab Previous page", new String[0]), (v, c) -> this.open(v, state.withPage(state.page() - 1)));
        }
        menu.set(47, Items.of(Material.HOPPER, "\u00a7bCategory: \u00a7f" + (state.category() == null ? "All" : state.category().displayName()), "\u00a77Click: next category"), (v, c) -> this.open(v, state.withCategory(SusGui.nextCategory(state.category()))));
        menu.set(48, Items.of(state.onlyNew() ? Material.RED_DYE : Material.GRAY_DYE, "\u00a7bOnly unreviewed: " + (state.onlyNew() ? "\u00a7aOn" : "\u00a7cOff"), "\u00a77Click: toggle"), (v, c) -> this.open(v, state.toggleNew()));
        Player target = Bukkit.getPlayer((UUID)state.player());
        menu.set(49, Items.head(state.player(), "\u00a7b" + state.name(), this.summaryLore(state.player(), target != null, now)), null);
        menu.set(50, Items.of(Material.CLOCK, "\u00a7bSort: \u00a7f" + state.sort().label(), "\u00a77Click: toggle"), (v, c) -> this.open(v, state.nextSort()));
        if (target != null && viewer.hasPermission("kyroac.teleport")) {
            menu.set(51, Items.of(Material.ENDER_PEARL, "\u00a7bTeleport to player", new String[0]), (v, c) -> {
                Player t = Bukkit.getPlayer((UUID)state.player());
                if (t != null && v.hasPermission("kyroac.teleport")) {
                    this.ctx.teleportTo(v, t, false);
                }
            });
        }
        if (target != null && viewer.hasPermission("kyroac.spectate")) {
            menu.set(52, Items.of(Material.ENDER_EYE, "\u00a7bSpectate", "\u00a77Switches to spectator mode", "\u00a77and teleports to the player.", "\u00a78Return with /gamemode"), (v, c) -> {
                Player t = Bukkit.getPlayer((UUID)state.player());
                if (t != null && v.hasPermission("kyroac.spectate")) {
                    this.ctx.teleportTo(v, t, true);
                }
            });
        }
        if (hasNext) {
            menu.set(53, Items.of(Material.ARROW, "\u00a7fNext page \u00bb", new String[0]), (v, c) -> this.open(v, state.withPage(state.page() + 1)));
        }
        viewer.openInventory(menu.getInventory());
    }

    private List<String> summaryLore(UUID player, boolean online, long now) {
        ArrayList<String> lore = new ArrayList<String>();
        lore.add("\u00a77Player: " + (online ? "\u00a7aOnline" : "\u00a78Offline"));
        Map<String, Double> vls = this.ctx.levels().snapshot(player, now);
        if (vls.isEmpty()) {
            lore.add("\u00a77Current VL: \u00a7anone");
        } else {
            lore.add("\u00a77Current VL:");
            vls.entrySet().stream().limit(6L).forEach(e -> lore.add("\u00a78 \u2022 \u00a7f" + (String)e.getKey() + " \u00a7c" + GuiText.vl((Double)e.getValue())));
        }
        return lore;
    }

    public record State(UUID player, String name, CheckCategory category, boolean onlyNew, FlagQuery.Sort sort, int page, SusGui.State back) {
        public static State initial(UUID player, String name, SusGui.State back) {
            return new State(player, name, null, false, FlagQuery.Sort.DATE, 0, back);
        }

        State withPage(int p) {
            return new State(this.player, this.name, this.category, this.onlyNew, this.sort, Math.max(0, p), this.back);
        }

        State withCategory(CheckCategory c) {
            return new State(this.player, this.name, c, this.onlyNew, this.sort, 0, this.back);
        }

        State toggleNew() {
            return new State(this.player, this.name, this.category, !this.onlyNew, this.sort, 0, this.back);
        }

        State nextSort() {
            return new State(this.player, this.name, this.category, this.onlyNew, this.sort.next(), 0, this.back);
        }
    }
}

