package de.kyrosmp.anticheat.gui;

import de.kyrosmp.anticheat.core.CheckCategory;
import de.kyrosmp.anticheat.flag.FlagRecord;
import de.kyrosmp.anticheat.flag.FlagStatus;
import de.kyrosmp.anticheat.storage.FlagQuery;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class PlayerFlagsGui {
    static final int PAGE_SIZE = 45;
    private final GuiContext ctx;

    PlayerFlagsGui(GuiContext ctx) {
        this.ctx = ctx;
    }

    public void open(Player viewer, PlayerFlagsGui.State state) {
        FlagQuery q =
                new FlagQuery(state.category(), state.onlyNew(), state.sort(), state.page(), 45);
        this.ctx.load(
                viewer,
                this.ctx.store().playerFlags(state.player(), q),
                rows -> this.render(viewer, state, rows));
    }

    private void render(Player viewer, PlayerFlagsGui.State state, List<FlagRecord> rows) {
        long now = System.currentTimeMillis();
        boolean hasNext = rows.size() > 45;
        boolean canManage = viewer.hasPermission("kyroac.manage");
        MenuHolder menu =
                new MenuHolder(
                        54, "§8Flags: " + state.name() + " §7(page " + (state.page() + 1) + ")");
        int slot = 0;

        for (FlagRecord f : rows) {
            if (slot >= 45) {
                break;
            }

            ArrayList<String> lore = new ArrayList<>();
            lore.add(
                    "§7Time: §f"
                            + this.ctx.text().time(f.createdAt())
                            + " §8("
                            + GuiText.ago(now, f.createdAt())
                            + ")");
            lore.add(
                    "§7Status: "
                            + f.status().colored()
                            + " §8| §7Confidence: "
                            + f.confidence().colored());
            lore.add("§7Category: §f" + f.category().displayName());
            lore.add(
                    "§7Ping: §f"
                            + (f.ping() < 0 ? "unknown" : f.ping() + " ms")
                            + (f.mergedCount() > 1
                                    ? " §8| §7×" + f.mergedCount() + " merged"
                                    : ""));
            lore.add(f.isCurrent(now, this.ctx.currentWindowMs()) ? "§cCurrent" : "§8Historic");
            lore.addAll(GuiText.wrap(f.reason(), 38, "§7"));
            lore.add(" ");
            lore.add("§eLeft-click: §7details");
            if (canManage && f.status() == FlagStatus.NEW) {
                lore.add("§eRight-click: §7mark as reviewed");
            }

            Material icon = Items.material(f.category().iconMaterial(), Material.PAPER);
            String name =
                    f.status().colored().substring(0, 2)
                            + f.checkName()
                            + " §8— §fVL "
                            + GuiText.vl(f.vl());
            menu.set(
                    slot++,
                    Items.of(icon, name, lore),
                    (v, click) -> {
                        if (click.isRightClick()
                                && f.status() == FlagStatus.NEW
                                && v.hasPermission("kyroac.manage")) {
                            this.ctx.load(
                                    v,
                                    this.ctx
                                            .store()
                                            .updateStatus(
                                                    f.flagId(),
                                                    FlagStatus.REVIEWED,
                                                    v.getUniqueId(),
                                                    v.getName(),
                                                    System.currentTimeMillis()),
                                    ok -> this.open(v, state));
                        } else {
                            this.ctx.flagDetail().open(v, f.flagId(), state);
                        }
                    });
        }

        if (rows.isEmpty()) {
            menu.set(
                    22,
                    Items.of(
                            Material.LIME_DYE,
                            "§aNo flags",
                            "§7There are no entries",
                            "§7for the current filters."),
                    null);
        }

        for (int i = 45; i < 54; i++) {
            menu.set(i, Items.filler(), null);
        }

        menu.set(
                45,
                Items.of(Material.OAK_DOOR, "§fBack to overview"),
                (v, c) -> this.ctx.sus().open(v, state.back()));
        if (state.page() > 0) {
            menu.set(
                    46,
                    Items.of(Material.ARROW, "§f« Previous page"),
                    (v, c) -> this.open(v, state.withPage(state.page() - 1)));
        }

        menu.set(
                47,
                Items.of(
                        Material.HOPPER,
                        "§bCategory: §f"
                                + (state.category() == null
                                        ? "All"
                                        : state.category().displayName()),
                        "§7Click: next category"),
                (v, c) -> this.open(v, state.withCategory(SusGui.nextCategory(state.category()))));
        menu.set(
                48,
                Items.of(
                        state.onlyNew() ? Material.RED_DYE : Material.GRAY_DYE,
                        "§bOnly unreviewed: " + (state.onlyNew() ? "§aOn" : "§cOff"),
                        "§7Click: toggle"),
                (v, c) -> this.open(v, state.toggleNew()));
        Player target = Bukkit.getPlayer(state.player());
        menu.set(
                49,
                Items.head(
                        state.player(),
                        "§b" + state.name(),
                        this.summaryLore(state.player(), target != null, now)),
                null);
        menu.set(
                50,
                Items.of(Material.CLOCK, "§bSort: §f" + state.sort().label(), "§7Click: toggle"),
                (v, c) -> this.open(v, state.nextSort()));
        if (target != null && viewer.hasPermission("kyroac.teleport")) {
            menu.set(
                    51,
                    Items.of(Material.ENDER_PEARL, "§bTeleport to player"),
                    (v, c) -> {
                        Player t = Bukkit.getPlayer(state.player());
                        if (t != null && v.hasPermission("kyroac.teleport")) {
                            this.ctx.teleportTo(v, t, false);
                        }
                    });
        }

        if (target != null && viewer.hasPermission("kyroac.spectate")) {
            menu.set(
                    52,
                    Items.of(
                            Material.ENDER_EYE,
                            "§bSpectate",
                            "§7Switches to spectator mode",
                            "§7and teleports to the player.",
                            "§8Return with /gamemode"),
                    (v, c) -> {
                        Player t = Bukkit.getPlayer(state.player());
                        if (t != null && v.hasPermission("kyroac.spectate")) {
                            this.ctx.teleportTo(v, t, true);
                        }
                    });
        }

        if (hasNext) {
            menu.set(
                    53,
                    Items.of(Material.ARROW, "§fNext page »"),
                    (v, c) -> this.open(v, state.withPage(state.page() + 1)));
        }

        viewer.openInventory(menu.getInventory());
    }

    private List<String> summaryLore(UUID player, boolean online, long now) {
        ArrayList<String> lore = new ArrayList<>();
        lore.add("§7Player: " + (online ? "§aOnline" : "§8Offline"));
        Map<String, Double> vls = this.ctx.levels().snapshot(player, now);
        if (vls.isEmpty()) {
            lore.add("§7Current VL: §anone");
        } else {
            lore.add("§7Current VL:");
            vls.entrySet().stream()
                    .limit(6L)
                    .forEach(
                            e ->
                                    lore.add(
                                            "§8 • §f"
                                                    + e.getKey()
                                                    + " §c"
                                                    + GuiText.vl(e.getValue())));
        }

        return lore;
    }

    public static record State(
            UUID player,
            String name,
            CheckCategory category,
            boolean onlyNew,
            FlagQuery.Sort sort,
            int page,
            SusGui.State back) {
        public static PlayerFlagsGui.State initial(UUID player, String name, SusGui.State back) {
            return new PlayerFlagsGui.State(
                    player, name, null, false, FlagQuery.Sort.DATE, 0, back);
        }

        PlayerFlagsGui.State withPage(int p) {
            return new PlayerFlagsGui.State(
                    this.player,
                    this.name,
                    this.category,
                    this.onlyNew,
                    this.sort,
                    Math.max(0, p),
                    this.back);
        }

        PlayerFlagsGui.State withCategory(CheckCategory c) {
            return new PlayerFlagsGui.State(
                    this.player, this.name, c, this.onlyNew, this.sort, 0, this.back);
        }

        PlayerFlagsGui.State toggleNew() {
            return new PlayerFlagsGui.State(
                    this.player, this.name, this.category, !this.onlyNew, this.sort, 0, this.back);
        }

        PlayerFlagsGui.State nextSort() {
            return new PlayerFlagsGui.State(
                    this.player,
                    this.name,
                    this.category,
                    this.onlyNew,
                    this.sort.next(),
                    0,
                    this.back);
        }
    }
}
