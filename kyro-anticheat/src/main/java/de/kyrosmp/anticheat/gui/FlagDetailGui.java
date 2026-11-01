package de.kyrosmp.anticheat.gui;

import de.kyrosmp.anticheat.flag.FlagRecord;
import de.kyrosmp.anticheat.flag.FlagStatus;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Locale;
import java.util.Optional;

public final class FlagDetailGui {
    private final GuiContext ctx;

    FlagDetailGui(GuiContext ctx) {
        this.ctx = ctx;
    }

    public void open(Player viewer, String flagId, PlayerFlagsGui.State back) {
        this.ctx.load(viewer, this.ctx.store().flag(flagId), opt -> this.render(viewer, opt, back));
    }

    private void render(Player viewer, Optional<FlagRecord> opt, PlayerFlagsGui.State back) {
        if (opt.isEmpty()) {
            viewer.sendMessage("§8[§bKYRO§8] §7§cThis flag no longer exists.");
            this.ctx.playerFlags().open(viewer, back);
        } else {
            FlagRecord f = opt.get();
            long now = System.currentTimeMillis();
            MenuHolder menu = new MenuHolder(27, "§8Flag §7#" + f.shortId());

            for (int i = 0; i < 27; i++) {
                menu.set(i, Items.filler(), null);
            }

            ArrayList<String> lore = new ArrayList<>();
            lore.add("§7ID: §f" + f.flagId());
            lore.add("§7Player: §f" + f.playerName() + " §8(" + f.playerUuid() + ")");
            lore.add("§7Category: §f" + f.category().displayName());
            lore.add("§7Check: §f" + f.checkName());
            lore.add("§7VL: §c" + GuiText.vl(f.vl()));
            lore.add(
                    "§7Date: §f"
                            + this.ctx.text().time(f.createdAt())
                            + " §8("
                            + GuiText.ago(now, f.createdAt())
                            + ")");
            lore.add(
                    "§7Period: "
                            + (f.isCurrent(now, this.ctx.currentWindowMs())
                                    ? "§cCurrent"
                                    : "§8Historic"));
            lore.add(
                    "§7Status: "
                            + f.status().colored()
                            + (f.reviewedBy() != null
                                    ? " §8by §f"
                                            + f.reviewedBy()
                                            + " §8on §f"
                                            + this.ctx.text().time(f.reviewedAt())
                                    : ""));
            lore.add("§7Confidence: " + f.confidence().colored() + " §8(not proof)");
            lore.add("§7Ping: §f" + (f.ping() < 0 ? "unknown" : f.ping() + " ms"));
            lore.add(
                    "§7Server-TPS: §f"
                            + (f.tps() < 0.0
                                    ? "unknown"
                                    : String.format(Locale.ROOT, "%.1f", f.tps())));
            lore.add(
                    "§7Triggers: §f"
                            + f.mergedCount()
                            + (f.mergedCount() > 1 ? " §8(within the merge window)" : ""));
            if (f.hasPosition()) {
                lore.add(
                        String.format(
                                Locale.ROOT,
                                "§7Position: §f%s %.1f / %.1f / %.1f",
                                f.world(),
                                f.x(),
                                f.y(),
                                f.z()));
            }

            lore.add(" ");
            lore.add("§bReason:");
            lore.addAll(GuiText.wrap(f.reason(), 40, "§7"));
            lore.add(" ");
            lore.add("§bMeasurements:");
            lore.addAll(GuiText.details(f.details(), 40, "§7"));
            lore.add(" ");
            lore.add("§bActions taken:");
            if (f.actions().isBlank()) {
                lore.add("§7none (alert only)");
            } else {
                lore.addAll(GuiText.details(f.actions(), 40, "§7"));
            }

            Material icon = Items.material(f.category().iconMaterial(), Material.PAPER);
            menu.set(
                    4,
                    Items.of(icon, "§c" + f.checkName() + " §8— §fVL " + GuiText.vl(f.vl()), lore),
                    null);
            boolean manage = viewer.hasPermission("kyroac.manage");
            if (manage) {
                this.statusButton(
                        menu,
                        11,
                        Material.YELLOW_CONCRETE,
                        "§eMark as reviewed",
                        f,
                        FlagStatus.REVIEWED,
                        back);
                this.statusButton(
                        menu,
                        13,
                        Material.LIME_CONCRETE,
                        "§aMark as resolved",
                        f,
                        FlagStatus.RESOLVED,
                        back);
                this.statusButton(
                        menu,
                        15,
                        Material.WHITE_CONCRETE,
                        "§fReset to new",
                        f,
                        FlagStatus.NEW,
                        back);
            } else {
                menu.set(
                        13,
                        Items.of(Material.BARRIER, "§cNo permission", "§7Requires kyroac.manage"),
                        null);
            }

            Player target = Bukkit.getPlayer(f.playerUuid());
            if (target != null && viewer.hasPermission("kyroac.teleport")) {
                menu.set(
                        17,
                        Items.of(
                                Material.ENDER_PEARL, "§bTeleport to player", "§7Player is online"),
                        (v, c) -> {
                            Player t = Bukkit.getPlayer(f.playerUuid());
                            if (t != null && v.hasPermission("kyroac.teleport")) {
                                this.ctx.teleportTo(v, t, false);
                            }
                        });
            }

            if (f.previousFlagId() != null) {
                menu.set(
                        9,
                        Items.of(
                                Material.ARROW,
                                "§fPrevious incident",
                                "§7#"
                                        + f.previousFlagId()
                                                .substring(
                                                        0,
                                                        Math.min(8, f.previousFlagId().length())),
                                "§7Click: open"),
                        (v, c) -> this.open(v, f.previousFlagId(), back));
            }

            menu.set(
                    22,
                    Items.of(Material.OAK_DOOR, "§fBack to flag list"),
                    (v, c) -> this.ctx.playerFlags().open(v, back));
            viewer.openInventory(menu.getInventory());
        }
    }

    private void statusButton(
            MenuHolder menu,
            int slot,
            Material material,
            String name,
            FlagRecord f,
            FlagStatus target,
            PlayerFlagsGui.State back) {
        boolean current = f.status() == target;
        menu.set(
                slot,
                Items.of(
                        current ? Material.GRAY_CONCRETE : material,
                        name + (current ? " §8(current)" : ""),
                        "§7The record is kept.",
                        "§7The change is logged."),
                current
                        ? null
                        : (v, c) -> {
                            if (v.hasPermission("kyroac.manage")) {
                                this.ctx.load(
                                        v,
                                        this.ctx
                                                .store()
                                                .updateStatus(
                                                        f.flagId(),
                                                        target,
                                                        v.getUniqueId(),
                                                        v.getName(),
                                                        System.currentTimeMillis()),
                                        ok -> this.open(v, f.flagId(), back));
                            }
                        });
    }
}
