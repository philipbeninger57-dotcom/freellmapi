package de.kyrosmp.anticheat.gui;

import de.kyrosmp.anticheat.core.CheckCategory;
import de.kyrosmp.anticheat.core.Physics;
import de.kyrosmp.anticheat.storage.PlayerSummary;
import de.kyrosmp.anticheat.storage.SummaryQuery;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public final class SusGui {
    static final int PAGE_SIZE = 45;
    private final GuiContext ctx;

    SusGui(GuiContext ctx) {
        this.ctx = ctx;
    }

    public void open(Player viewer) {
        this.open(viewer, SusGui.State.initial());
    }

    public void open(Player viewer, SusGui.State state) {
        HashSet<UUID> online = new HashSet<>();

        for (Player p : Bukkit.getOnlinePlayers()) {
            online.add(p.getUniqueId());
        }

        long now = System.currentTimeMillis();
        SummaryQuery q =
                new SummaryQuery(
                        state.category(),
                        state.onlyNew(),
                        state.onlyCurrent(),
                        now - this.ctx.currentWindowMs(),
                        state.online(),
                        online,
                        state.sort(),
                        state.page(),
                        45);
        this.ctx.load(
                viewer,
                this.ctx.store().summaries(q),
                rows -> this.render(viewer, state, rows, online, now));
    }

    private void render(
            Player viewer,
            SusGui.State state,
            List<PlayerSummary> rows,
            Set<UUID> online,
            long now) {
        boolean hasNext = rows.size() > 45;
        MenuHolder menu = new MenuHolder(54, "§8KYRO AC SUS §7(page " + (state.page() + 1) + ")");
        long currentMinutes = this.ctx.currentWindowMs() / 60000L;
        int slot = 0;

        for (PlayerSummary s : rows) {
            if (slot >= 45) {
                break;
            }

            boolean isOnline = online.contains(s.uuid());
            ArrayList<String> lore = new ArrayList<>();
            lore.add("§7Suspicion: " + Physics.suspicionLabel(s.newCount()) + " §8(not proof)");
            lore.add(
                    "§7Stored flags: §f"
                            + s.flagCount()
                            + " §8("
                            + s.triggerCount()
                            + " triggers)");
            lore.add(
                    "§7Current flags: "
                            + (s.currentCount() > 0 ? "§c" : "§f")
                            + s.currentCount()
                            + " §8(last "
                            + currentMinutes
                            + " min)");
            lore.add("§7Last check: §f" + (s.lastCheck() == null ? "-" : s.lastCheck()));
            lore.add("§7Highest open VL: §c" + GuiText.vl(s.maxOpenVl()));
            lore.add(
                    "§7Last flag: §f"
                            + this.ctx.text().time(s.lastFlagAt())
                            + " §8("
                            + GuiText.ago(now, s.lastFlagAt())
                            + ")");
            lore.add(
                    "§7Status: "
                            + s.status().colored()
                            + (s.newCount() > 0 ? " §8(" + s.newCount() + " unreviewed)" : ""));
            lore.add("§7Player: " + (isOnline ? "§aOnline" : "§8Offline"));
            lore.add(" ");
            lore.add("§eLeft-click: §7open flag history");
            if (isOnline) {
                lore.add("§eRight-click: §7investigate §8(teleport + spectator, flags reviewed)");
            }

            String color = s.status().colored().substring(0, 2);
            menu.set(
                    slot++,
                    Items.head(s.uuid(), color + s.name(), lore),
                    (v, click) -> {
                        if (click.isRightClick()) {
                            this.ctx.investigate(v, s.uuid(), s.name());
                        } else {
                            this.ctx
                                    .playerFlags()
                                    .open(
                                            v,
                                            PlayerFlagsGui.State.initial(
                                                    s.uuid(), s.name(), state));
                        }
                    });
        }

        if (rows.isEmpty()) {
            menu.set(
                    22,
                    Items.of(
                            Material.LIME_DYE,
                            "§aNo suspects",
                            "§7There are no entries",
                            "§7for the current filters.",
                            "§8Tip: turn off the \"Only unreviewed\" filter"),
                    null);
        }

        for (int i = 45; i < 54; i++) {
            menu.set(i, Items.filler(), null);
        }

        if (state.page() > 0) {
            menu.set(
                    45,
                    Items.of(Material.ARROW, "§f« Previous page"),
                    (v, c) -> this.open(v, state.withPage(state.page() - 1)));
        }

        menu.set(
                46,
                Items.of(
                        Material.HOPPER,
                        "§bCategory: §f"
                                + (state.category() == null
                                        ? "All"
                                        : state.category().displayName()),
                        "§7Click: next category",
                        "§7Counters refer to this category."),
                (v, c) -> this.open(v, state.withCategory(nextCategory(state.category()))));
        menu.set(
                47,
                Items.of(
                        state.onlyNew() ? Material.RED_DYE : Material.GRAY_DYE,
                        "§bOnly unreviewed: " + (state.onlyNew() ? "§aOn" : "§cOff"),
                        "§7Click: toggle"),
                (v, c) -> this.open(v, state.toggleNew()));
        menu.set(
                48,
                Items.of(
                        state.onlyCurrent() ? Material.CLOCK : Material.GRAY_DYE,
                        "§bOnly current cases: " + (state.onlyCurrent() ? "§aOn" : "§cOff"),
                        "§7Last flag within the last " + currentMinutes + " min",
                        "§7Click: toggle"),
                (v, c) -> this.open(v, state.toggleCurrent()));
        menu.set(
                49,
                Items.of(
                        Material.BOOK,
                        "§bRefresh",
                        "§7Database: "
                                + (this.ctx.store().isAvailable() ? "§aOK" : "§cunavailable"),
                        "§7Click: reload"),
                (v, c) -> this.open(v, state));
        menu.set(
                50,
                Items.of(
                        Material.COMPASS,
                        "§bPlayers: §f" + state.online().label(),
                        "§7Click: toggle"),
                (v, c) -> this.open(v, state.nextOnline()));
        menu.set(
                51,
                Items.of(
                        Material.COMPARATOR,
                        "§bSort: §f" + state.sort().label(),
                        "§7Click: toggle"),
                (v, c) -> this.open(v, state.nextSort()));
        if (hasNext) {
            menu.set(
                    53,
                    Items.of(Material.ARROW, "§fNext page »"),
                    (v, c) -> this.open(v, state.withPage(state.page() + 1)));
        }

        viewer.openInventory(menu.getInventory());
    }

    static CheckCategory nextCategory(CheckCategory current) {
        CheckCategory[] all = CheckCategory.values();
        if (current == null) {
            return all[0];
        } else {
            int next = current.ordinal() + 1;
            return next >= all.length ? null : all[next];
        }
    }

    public static record State(
            CheckCategory category,
            boolean onlyNew,
            boolean onlyCurrent,
            SummaryQuery.OnlineFilter online,
            SummaryQuery.Sort sort,
            int page) {
        public static SusGui.State initial() {
            return new SusGui.State(
                    null,
                    true,
                    false,
                    SummaryQuery.OnlineFilter.ALL,
                    SummaryQuery.Sort.LAST_FLAG,
                    0);
        }

        SusGui.State withPage(int p) {
            return new SusGui.State(
                    this.category,
                    this.onlyNew,
                    this.onlyCurrent,
                    this.online,
                    this.sort,
                    Math.max(0, p));
        }

        SusGui.State withCategory(CheckCategory c) {
            return new SusGui.State(c, this.onlyNew, this.onlyCurrent, this.online, this.sort, 0);
        }

        SusGui.State toggleNew() {
            return new SusGui.State(
                    this.category, !this.onlyNew, this.onlyCurrent, this.online, this.sort, 0);
        }

        SusGui.State toggleCurrent() {
            return new SusGui.State(
                    this.category, this.onlyNew, !this.onlyCurrent, this.online, this.sort, 0);
        }

        SusGui.State nextOnline() {
            return new SusGui.State(
                    this.category,
                    this.onlyNew,
                    this.onlyCurrent,
                    this.online.next(),
                    this.sort,
                    0);
        }

        SusGui.State nextSort() {
            return new SusGui.State(
                    this.category,
                    this.onlyNew,
                    this.onlyCurrent,
                    this.online,
                    this.sort.next(),
                    0);
        }
    }
}
