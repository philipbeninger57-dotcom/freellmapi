package de.kyrosmp.anticheat;

import de.kyrosmp.anticheat.checks.action.BlockBreakChecks;
import de.kyrosmp.anticheat.checks.action.BlockPlaceChecks;
import de.kyrosmp.anticheat.checks.action.InventoryMoveCheck;
import de.kyrosmp.anticheat.checks.action.XRayCheck;
import de.kyrosmp.anticheat.checks.combat.AutoClickerCheck;
import de.kyrosmp.anticheat.checks.combat.CombatChecks;
import de.kyrosmp.anticheat.checks.combat.CriticalsCheck;
import de.kyrosmp.anticheat.checks.combat.NoSwingCheck;
import de.kyrosmp.anticheat.checks.combat.VelocityCheck;
import de.kyrosmp.anticheat.checks.movement.ElytraCheck;
import de.kyrosmp.anticheat.checks.movement.HoverCheck;
import de.kyrosmp.anticheat.checks.movement.MovementChecks;
import de.kyrosmp.anticheat.checks.movement.PredictionChecks;
import de.kyrosmp.anticheat.checks.movement.SprintCheck;
import de.kyrosmp.anticheat.checks.vehicle.BoatFlyCheck;
import de.kyrosmp.anticheat.command.KyroCommand;
import de.kyrosmp.anticheat.command.SusCommand;
import de.kyrosmp.anticheat.config.ConfigValidator;
import de.kyrosmp.anticheat.core.AlertManager;
import de.kyrosmp.anticheat.core.AttributeSet;
import de.kyrosmp.anticheat.core.BedrockDetector;
import de.kyrosmp.anticheat.core.CheckConfig;
import de.kyrosmp.anticheat.core.CheckRegistry;
import de.kyrosmp.anticheat.core.ExemptionManager;
import de.kyrosmp.anticheat.core.PlayerData;
import de.kyrosmp.anticheat.core.PlayerDataStore;
import de.kyrosmp.anticheat.core.PreventionConfig;
import de.kyrosmp.anticheat.core.Punisher;
import de.kyrosmp.anticheat.core.StrictConfig;
import de.kyrosmp.anticheat.core.ViolationLevels;
import de.kyrosmp.anticheat.debug.DebugManager;
import de.kyrosmp.anticheat.flag.FlagService;
import de.kyrosmp.anticheat.flag.FlagThrottle;
import de.kyrosmp.anticheat.flag.RecentFlagIndex;
import de.kyrosmp.anticheat.gui.GuiContext;
import de.kyrosmp.anticheat.gui.GuiListener;
import de.kyrosmp.anticheat.gui.GuiText;
import de.kyrosmp.anticheat.listener.CombatListener;
import de.kyrosmp.anticheat.listener.HoverListener;
import de.kyrosmp.anticheat.listener.KnockbackListener;
import de.kyrosmp.anticheat.listener.MiningListener;
import de.kyrosmp.anticheat.listener.MovementListener;
import de.kyrosmp.anticheat.storage.FlagStore;

import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

public final class KyroAntiCheat extends JavaPlugin {
    private final PlayerDataStore data = new PlayerDataStore();
    private final CheckRegistry registry = CheckRegistry.defaults();
    private volatile CheckConfig checkConfig = CheckConfig.defaults();
    private volatile PreventionConfig prevention = PreventionConfig.defaults();
    private volatile StrictConfig strict = StrictConfig.defaults();
    private volatile BlockBreakChecks digChecks;
    private ExemptionManager exemptions;
    private AlertManager alerts;
    private Punisher punisher;
    private ViolationLevels levels;
    private FlagThrottle throttle;
    private FlagStore store;
    private FlagService flags;
    private DebugManager debug;
    private GuiContext gui;
    private AttributeSet attributes = AttributeSet.NONE;
    private volatile MovementListener.Checks moveChecks;
    private volatile CombatListener.Checks combatChecks;
    private String storageFile;
    private volatile XRayCheck xrayCheck;
    private volatile int retentionDays;

    public void onEnable() {
        this.saveDefaultConfig();
        this.reloadConfig();
        ConfigValidator.validate(this.getConfig())
                .forEach(m -> this.getLogger().warning("Config: " + m));
        List<String> missing = ConfigValidator.missingKeys(this.getConfig());
        if (!missing.isEmpty()) {
            this.getLogger()
                    .info(
                            missing.size()
                                    + " config key(s) not set, built-in defaults are used"
                                    + " (config.yml was not changed): "
                                    + String.join(", ", missing));
        }

        List<String> obsolete;
        if (!(obsolete = ConfigValidator.obsoleteKeys(this.getConfig())).isEmpty()) {
            this.getLogger()
                    .info("Obsolete config keys without effect: " + String.join(", ", obsolete));
        }

        FileConfiguration c = this.getConfig();
        this.retentionDays = Math.max(0, Math.min(36500, c.getInt("storage.retention-days", 0)));
        this.checkConfig = CheckConfig.from(c);
        this.prevention = PreventionConfig.from(c);
        this.strict = StrictConfig.from(c);
        this.logBedrockPolicy();
        this.attributes = AttributeSet.resolve();
        this.exemptions = new ExemptionManager(this.checkConfig);
        this.alerts = new AlertManager(this, this.getLogger());
        this.alerts.setConsole(c.getBoolean("alerts.console", true));
        this.levels = new ViolationLevels(c.getDouble("violations.decay-per-minute", 1.0));
        this.throttle =
                new FlagThrottle(
                        c.getLong("flags.duplicate-window-ms", 40L),
                        c.getLong("flags.merge-window-ms", 1200L));
        this.debug = new DebugManager(this, c.getInt("debug.max-messages-per-second", 2));
        this.store = new FlagStore(this.getLogger(), c.getInt("storage.queue-capacity", 10000));
        this.storageFile = c.getString("storage.file", "flags.db");
        this.store.start(new File(this.getDataFolder(), this.storageFile));
        this.flags =
                new FlagService(
                        this.registry,
                        this.levels,
                        this.throttle,
                        new RecentFlagIndex(512),
                        this.store,
                        this.alerts,
                        this.debug,
                        FlagService.Settings.from(c));
        this.punisher =
                new Punisher(
                        this, this.getLogger(), this.flags, this.alerts::send, this.checkConfig);
        this.punisher.configurePrevention(this.prevention);
        this.punisher.configureStrict(this.strict);
        this.gui = GuiContext.create(this, this.store, this.levels, this.flags, guiText(c));
        this.buildChecks();
        PluginManager pm = this.getServer().getPluginManager();
        pm.registerEvents(
                new MovementListener(
                                this.data,
                                this.exemptions,
                                this.flags,
                                this.debug,
                                () -> this.moveChecks,
                                () -> this.checkConfig,
                                this.attributes)
                        .withStrict(() -> this.strict),
                this);
        pm.registerEvents(
                new CombatListener(this.data, () -> this.combatChecks, () -> this.checkConfig)
                        .withPolicy(() -> this.strict, () -> this.prevention),
                this);
        pm.registerEvents(new GuiListener(this.gui), this);
        pm.registerEvents(
                new MiningListener(this.data, this.exemptions, () -> this.xrayCheck)
                        .withDigChecks(() -> this.digChecks, () -> this.checkConfig),
                this);
        pm.registerEvents(new HoverListener(this::startHoverWatch), this);

        for (Player online : Bukkit.getOnlinePlayers()) {
            this.startHoverWatch(online);
        }

        this.gui.setTeleportGeneration(
                id -> {
                    PlayerData pd = this.data(id);
                    return pd == null ? -1L : pd.teleportsApplied;
                });
        this.configureInspections(c);

        try {
            pm.registerEvents(
                    new KnockbackListener(
                            this.data, () -> this.moveChecks.velocity(), () -> this.checkConfig),
                    this);
        } catch (Throwable ex) {
            this.getLogger()
                    .warning(
                            "Knockback tracking unavailable on this server version: "
                                    + ex.getClass().getSimpleName());
        }

        this.bind("kyro", new KyroCommand(this));
        SusCommand sus = new SusCommand(this);
        this.bind("sus", sus);
        this.bind("acalerts", sus);
        Bukkit.getAsyncScheduler()
                .runAtFixedRate(
                        this,
                        task -> this.flags.cleanup(System.currentTimeMillis()),
                        1L,
                        1L,
                        TimeUnit.MINUTES);
        Bukkit.getAsyncScheduler()
                .runAtFixedRate(
                        this,
                        task -> {
                            int days = this.retentionDays;
                            if (days > 0 && this.store.isAvailable()) {
                                this.store.pruneBefore(
                                        System.currentTimeMillis()
                                                - TimeUnit.DAYS.toMillis((long) days));
                            }
                        },
                        1L,
                        1L,
                        TimeUnit.HOURS);
        Bukkit.getGlobalRegionScheduler()
                .runAtFixedRate(this, task -> this.data.cleanupOffline(), 1200L, 1200L);
        this.getLogger()
                .info(
                        "KYRO AntiCheat "
                                + this.getPluginMeta().getVersion()
                                + " enabled - "
                                + (isFolia() ? "Folia" : "Paper")
                                + ", "
                                + this.registry.all().size()
                                + " checks, persistent flag history. AutoTotem is not included"
                                + " (handled by another plugin).");
    }

    private void startHoverWatch(Player p) {
        try {
            p.getScheduler()
                    .runAtFixedRate(
                            this,
                            task -> {
                                if (!p.isOnline()) {
                                    task.cancel();
                                } else {
                                    PlayerData beat = this.data.peek(p.getUniqueId());
                                    if (beat != null) {
                                        beat.heartbeat(System.nanoTime());
                                    }
                                    MovementListener.Checks checks = this.moveChecks;
                                    HoverCheck hover = checks == null ? null : checks.hover();
                                    if (hover != null && !this.exemptions.bypass(p)) {
                                        PlayerData d = this.data.peek(p.getUniqueId());
                                        if (d != null
                                                && (!this.checkConfig.bedrockExempt()
                                                        || !BedrockDetector.isBedrock(p, d))) {
                                            hover.tick(p, d, System.currentTimeMillis());
                                        }
                                    }
                                }
                            },
                            null,
                            20L,
                            5L);
        } catch (RuntimeException ex) {
            this.getLogger()
                    .fine("Hover watch unavailable for " + p.getName() + ": " + ex.getMessage());
        }
    }

    private void configureInspections(FileConfiguration c) {
        this.gui
                .inspections()
                .configure(
                        c.getBoolean("gui.inspection.enabled", true),
                        c.getDouble("gui.inspection.max-distance", 100.0),
                        c.getLong("gui.inspection.check-interval-ticks", 10L));
    }

    private void logBedrockPolicy() {
        if (this.checkConfig.bedrockExempt()) {
            this.getLogger()
                    .info(
                            "bedrock.exempt=true: Geyser/Floodgate Bedrock players are exempt from"
                                    + " checks. This compatibility setting does not provide Bedrock"
                                    + " cheat detection.");
        }
    }

    public void onDisable() {
        if (this.gui != null) {
            this.gui.inspections().shutdown();
        }

        Bukkit.getAsyncScheduler().cancelTasks(this);
        Bukkit.getGlobalRegionScheduler().cancelTasks(this);
        if (this.store != null) {
            this.store.shutdown(this.getConfig().getLong("storage.shutdown-timeout-seconds", 10L));
        }
    }

    private void buildChecks() {
        CheckConfig cc = this.checkConfig;
        StrictConfig sc = this.strict;
        PreventionConfig prevention = this.prevention;
        this.moveChecks =
                new MovementListener.Checks(
                        new MovementChecks(this.flags, this.punisher, cc)
                                .withPrevention(prevention)
                                .withStrict(sc),
                        new BoatFlyCheck(this.flags, this.punisher, cc).withPrevention(prevention),
                        new VelocityCheck(this, this.flags, cc),
                        new BlockPlaceChecks(this.flags, cc, this.attributes)
                                .withPrevention(prevention)
                                .withStrict(sc),
                        new ElytraCheck(this.flags, this.punisher, cc).withPrevention(prevention),
                        new PredictionChecks(this.flags, this.punisher, cc, sc)
                                .withPrevention(prevention),
                        new HoverCheck(this.flags, this.punisher, cc, sc)
                                .withPrevention(prevention),
                        new SprintCheck(this.flags, sc, prevention));
        this.combatChecks =
                new CombatListener.Checks(
                        new CombatChecks(this.flags, cc, this.attributes)
                                .withPrevention(prevention)
                                .withStrict(sc),
                        new AutoClickerCheck(this.flags, cc).withPolicy(sc, prevention),
                        new InventoryMoveCheck(this.flags, cc).withStrict(sc),
                        new CriticalsCheck(this.flags, cc),
                        new NoSwingCheck(this, this.flags, cc, sc, prevention));
        this.xrayCheck = new XRayCheck(this.flags, cc);
        this.digChecks = new BlockBreakChecks(this.flags, sc).withPrevention(prevention);
    }

    public List<String> reloadAll() {
        this.reloadConfig();
        ArrayList<String> messages = new ArrayList<>(ConfigValidator.validate(this.getConfig()));
        FileConfiguration c = this.getConfig();
        this.retentionDays = Math.max(0, Math.min(36500, c.getInt("storage.retention-days", 0)));
        this.checkConfig = CheckConfig.from(c);
        this.exemptions.configure(this.checkConfig);
        this.prevention = PreventionConfig.from(c);
        this.strict = StrictConfig.from(c);
        this.punisher.configurePrevention(this.prevention);
        this.punisher.configureStrict(this.strict);
        this.logBedrockPolicy();
        this.punisher.configure(this.checkConfig);
        this.alerts.setConsole(c.getBoolean("alerts.console", true));
        this.levels.setDecayPerMinute(c.getDouble("violations.decay-per-minute", 1.0));
        this.throttle.configure(
                c.getLong("flags.duplicate-window-ms", 40L),
                c.getLong("flags.merge-window-ms", 1200L));
        this.debug.configure(c.getInt("debug.max-messages-per-second", 2));
        this.flags.reconfigure(FlagService.Settings.from(c));
        this.gui.setText(guiText(c));
        this.configureInspections(c);
        this.buildChecks();
        if (!c.getString("storage.file", "flags.db").equals(this.storageFile)) {
            messages.add("storage.file only takes effect after a restart.");
        }

        for (String key : ConfigValidator.obsoleteKeys(c)) {
            messages.add(key + " is obsolete and has no effect.");
        }

        messages.forEach(m -> this.getLogger().warning("Config: " + m));
        return messages;
    }

    private void bind(String name, TabExecutor executor) {
        PluginCommand cmd = this.getCommand(name);
        if (cmd == null) {
            this.getLogger().severe("Command /" + name + " is missing in plugin.yml");
        } else {
            cmd.setExecutor(executor);
            cmd.setTabCompleter(executor);
        }
    }

    public static boolean isFolia() {
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            return true;
        } catch (ClassNotFoundException ignored) {
            return false;
        }
    }

    private static GuiText guiText(FileConfiguration c) {
        return new GuiText(
                c.getString("gui.date-format", "dd.MM.yyyy HH:mm:ss"),
                c.getString("gui.timezone", "system"));
    }

    public PlayerData data(UUID id) {
        return this.data.peek(id);
    }

    public CheckRegistry registry() {
        return this.registry;
    }

    public ExemptionManager exemptions() {
        return this.exemptions;
    }

    public ViolationLevels levels() {
        return this.levels;
    }

    public FlagStore store() {
        return this.store;
    }

    public FlagService flags() {
        return this.flags;
    }

    public DebugManager debug() {
        return this.debug;
    }

    public GuiContext gui() {
        return this.gui;
    }

    public AlertManager alerts() {
        return this.alerts;
    }

    public CheckConfig checkConfig() {
        return this.checkConfig;
    }
}
