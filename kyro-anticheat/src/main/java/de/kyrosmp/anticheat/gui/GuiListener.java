package de.kyrosmp.anticheat.gui;

import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class GuiListener implements Listener {
    static final long CLICK_COOLDOWN_MS = 150L;
    private final GuiContext ctx;
    private final Map<UUID, Long> lastAction = new ConcurrentHashMap<>();

    public GuiListener(GuiContext ctx) {
        this.ctx = ctx;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onClick(InventoryClickEvent e) {
        if (e.getInventory().getHolder(false) instanceof MenuHolder holder) {
            e.setCancelled(true);
            if (e.getWhoClicked() instanceof Player viewer) {
                int raw = e.getRawSlot();
                if (raw >= 0 && raw < e.getInventory().getSize()) {
                    if (!viewer.hasPermission("kyroac.sus")) {
                        if (this.ctx != null) {
                            this.ctx.onViewer(
                                    viewer,
                                    () -> {
                                        if (viewer.getOpenInventory()
                                                        .getTopInventory()
                                                        .getHolder(false)
                                                == holder) {
                                            viewer.closeInventory();
                                        }
                                    });
                        }
                    } else {
                        MenuHolder.ClickHandler handler = holder.handler(raw);
                        if (handler != null) {
                            if (this.allowAction(
                                    viewer.getUniqueId(), System.currentTimeMillis())) {
                                ClickType click = e.getClick();
                                if (click.isLeftClick() || click.isRightClick()) {
                                    if (this.ctx != null) {
                                        this.ctx.onViewer(
                                                viewer,
                                                () -> {
                                                    if (viewer.hasPermission("kyroac.sus")
                                                            && viewer.getOpenInventory()
                                                                            .getTopInventory()
                                                                            .getHolder(false)
                                                                    == holder) {
                                                        handler.onClick(viewer, click);
                                                    }
                                                });
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    boolean allowAction(UUID viewer, long now) {
        Long last = this.lastAction.get(viewer);
        if (last != null && now - last < 150L) {
            return false;
        } else {
            this.lastAction.put(viewer, now);
            return true;
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrag(InventoryDragEvent e) {
        if (e.getInventory().getHolder(false) instanceof MenuHolder) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onMoveItem(InventoryMoveItemEvent e) {
        if (e.getSource().getHolder(false) instanceof MenuHolder
                || e.getDestination().getHolder(false) instanceof MenuHolder) {
            e.setCancelled(true);
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        if (this.ctx != null) {
            this.ctx.cancelLoads(e.getPlayer().getUniqueId());
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        this.lastAction.remove(e.getPlayer().getUniqueId());
        if (this.ctx != null) {
            this.ctx.cancelLoads(e.getPlayer().getUniqueId());
            this.ctx.cancelTeleports(e.getPlayer().getUniqueId());
            this.ctx.inspections().onQuit(e.getPlayer().getUniqueId());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGameMode(PlayerGameModeChangeEvent e) {
        if (this.ctx != null && e.getNewGameMode() != GameMode.SPECTATOR) {
            this.ctx.cancelTeleports(e.getPlayer().getUniqueId());
            this.ctx.inspections().end(e.getPlayer().getUniqueId(), "left spectator mode");
        }
    }
}
