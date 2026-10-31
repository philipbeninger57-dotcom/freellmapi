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
import de.kyrosmp.anticheat.core.Physics;
import de.kyrosmp.anticheat.gui.GuiContext;
import de.kyrosmp.anticheat.gui.GuiText;
import de.kyrosmp.anticheat.gui.Items;
import de.kyrosmp.anticheat.gui.MenuHolder;
import de.kyrosmp.anticheat.gui.PlayerFlagsGui;
import de.kyrosmp.anticheat.storage.PlayerSummary;
import de.kyrosmp.anticheat.storage.SummaryQuery;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;

public final class SusGui {
    static final int PAGE_SIZE = 45;
    private final GuiContext ctx;

    SusGui(GuiContext ctx) {
        this.ctx = ctx;
    }

    public void open(Player viewer) {
        this.open(viewer, State.initial());
    }

    public void open(Player viewer, State state) {
        HashSet<UUID> online = new HashSet<UUID>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            online.add(p.getUniqueId());
        }
        long now = System.currentTimeMillis();
        SummaryQuery q = new SummaryQuery(state.category(), state.onlyNew(), state.onlyCurrent(), now - this.ctx.currentWindowMs(), state.online(), online, state.sort(), state.page(), 45);
        this.ctx.load(viewer, this.ctx.store().summaries(q), rows -> this.render(viewer, state, (List<PlayerSummary>)rows, (Set<UUID>)online, now));
    }

    private void render(Player viewer, State state, List<PlayerSummary> rows, Set<UUID> online, long now) {
        boolean hasNext = rows.size() > 45;
        MenuHolder menu = new MenuHolder(54, "\u00a78KYRO AC SUS \u00a77(page " + (state.page() + 1) + ")");
        long currentMinutes = this.ctx.currentWindowMs() / 60000L;
        int slot = 0;
        for (PlayerSummary s : rows) {
            if (slot >= 45) break;
            boolean isOnline = online.contains(s.uuid());
            ArrayList<String> lore = new ArrayList<String>();
            lore.add("\u00a77Suspicion: " + Physics.suspicionLabel(s.newCount()) + " \u00a78(not proof)");
            lore.add("\u00a77Stored flags: \u00a7f" + s.flagCount() + " \u00a78(" + s.triggerCount() + " triggers)");
            lore.add("\u00a77Current flags: " + (s.currentCount() > 0 ? "\u00a7c" : "\u00a7f") + s.currentCount() + " \u00a78(last " + currentMinutes + " min)");
            lore.add("\u00a77Last check: \u00a7f" + (s.lastCheck() == null ? "-" : s.lastCheck()));
            lore.add("\u00a77Highest open VL: \u00a7c" + GuiText.vl(s.maxOpenVl()));
            lore.add("\u00a77Last flag: \u00a7f" + this.ctx.text().time(s.lastFlagAt()) + " \u00a78(" + GuiText.ago(now, s.lastFlagAt()) + ")");
            lore.add("\u00a77Status: " + s.status().colored() + (String)(s.newCount() > 0 ? " \u00a78(" + s.newCount() + " unreviewed)" : ""));
            lore.add("\u00a77Player: " + (isOnline ? "\u00a7aOnline" : "\u00a78Offline"));
            lore.add(" ");
            lore.add("\u00a7eLeft-click: \u00a77open flag history");
            if (isOnline) {
                lore.add("\u00a7eRight-click: \u00a77investigate \u00a78(teleport + spectator, flags reviewed)");
            }
            String color = s.status().colored().substring(0, 2);
            menu.set(slot++, Items.head(s.uuid(), color + s.name(), lore), (v, click) -> {
                if (click.isRightClick()) {
                    this.ctx.investigate(v, s.uuid(), s.name());
                } else {
                    this.ctx.playerFlags().open(v, PlayerFlagsGui.State.initial(s.uuid(), s.name(), state));
                }
            });
        }
        if (rows.isEmpty()) {
            menu.set(22, Items.of(Material.LIME_DYE, "\u00a7aNo suspects", "\u00a77There are no entries", "\u00a77for the current filters.", "\u00a78Tip: turn off the \"Only unreviewed\" filter"), null);
        }
        for (int i = 45; i < 54; ++i) {
            menu.set(i, Items.filler(), null);
        }
        if (state.page() > 0) {
            menu.set(45, Items.of(Material.ARROW, "\u00a7f\u00ab Previous page", new String[0]), (v, c) -> this.open(v, state.withPage(state.page() - 1)));
        }
        menu.set(46, Items.of(Material.HOPPER, "\u00a7bCategory: \u00a7f" + (state.category() == null ? "All" : state.category().displayName()), "\u00a77Click: next category", "\u00a77Counters refer to this category."), (v, c) -> this.open(v, state.withCategory(SusGui.nextCategory(state.category()))));
        menu.set(47, Items.of(state.onlyNew() ? Material.RED_DYE : Material.GRAY_DYE, "\u00a7bOnly unreviewed: " + (state.onlyNew() ? "\u00a7aOn" : "\u00a7cOff"), "\u00a77Click: toggle"), (v, c) -> this.open(v, state.toggleNew()));
        menu.set(48, Items.of(state.onlyCurrent() ? Material.CLOCK : Material.GRAY_DYE, "\u00a7bOnly current cases: " + (state.onlyCurrent() ? "\u00a7aOn" : "\u00a7cOff"), "\u00a77Last flag within the last " + currentMinutes + " min", "\u00a77Click: toggle"), (v, c) -> this.open(v, state.toggleCurrent()));
        menu.set(49, Items.of(Material.BOOK, "\u00a7bRefresh", "\u00a77Database: " + (this.ctx.store().isAvailable() ? "\u00a7aOK" : "\u00a7cunavailable"), "\u00a77Click: reload"), (v, c) -> this.open(v, state));
        menu.set(50, Items.of(Material.COMPASS, "\u00a7bPlayers: \u00a7f" + state.online().label(), "\u00a77Click: toggle"), (v, c) -> this.open(v, state.nextOnline()));
        menu.set(51, Items.of(Material.COMPARATOR, "\u00a7bSort: \u00a7f" + state.sort().label(), "\u00a77Click: toggle"), (v, c) -> this.open(v, state.nextSort()));
        if (hasNext) {
            menu.set(53, Items.of(Material.ARROW, "\u00a7fNext page \u00bb", new String[0]), (v, c) -> this.open(v, state.withPage(state.page() + 1)));
        }
        viewer.openInventory(menu.getInventory());
    }

    static CheckCategory nextCategory(CheckCategory current) {
        CheckCategory[] all = CheckCategory.values();
        if (current == null) {
            return all[0];
        }
        int next = current.ordinal() + 1;
        return next >= all.length ? null : all[next];
    }

    public record State(CheckCategory category, boolean onlyNew, boolean onlyCurrent, SummaryQuery.OnlineFilter online, SummaryQuery.Sort sort, int page) {
        public static State initial() {
            return new State(null, true, false, SummaryQuery.OnlineFilter.ALL, SummaryQuery.Sort.LAST_FLAG, 0);
        }

        State withPage(int p) {
            return new State(this.category, this.onlyNew, this.onlyCurrent, this.online, this.sort, Math.max(0, p));
        }

        State withCategory(CheckCategory c) {
            return new State(c, this.onlyNew, this.onlyCurrent, this.online, this.sort, 0);
        }

        State toggleNew() {
            return new State(this.category, !this.onlyNew, this.onlyCurrent, this.online, this.sort, 0);
        }

        State toggleCurrent() {
            return new State(this.category, this.onlyNew, !this.onlyCurrent, this.online, this.sort, 0);
        }

        State nextOnline() {
            return new State(this.category, this.onlyNew, this.onlyCurrent, this.online.next(), this.sort, 0);
        }

        State nextSort() {
            return new State(this.category, this.onlyNew, this.onlyCurrent, this.online, this.sort.next(), 0);
        }
    }
}

