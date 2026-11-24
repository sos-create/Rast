package dev.rast;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class RastPlugin extends JavaPlugin implements Listener {
    private static final long BRAND_CHECK_PERIOD_TICKS = 10L;
    private static final int BRAND_CHECK_ATTEMPTS = 30;
    private static final long PERFORMANCE_CHECK_PERIOD_TICKS = 100L;

    private final Map<UUID, BukkitTask> brandChecks = new ConcurrentHashMap<>();
    private final Map<UUID, PlayerState> horizonPlayers = new ConcurrentHashMap<>();
    private BukkitTask performanceTask;
    private boolean resourcePressure;
    private int pressureSamples;
    private int recoverySamples;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getServer().getPluginManager().registerEvents(this, this);
        for (Player player : Bukkit.getOnlinePlayers()) {
            beginBrandDetection(player);
        }
        performanceTask = Bukkit.getScheduler().runTaskTimer(
                this, this::refreshHorizonPlayers, PERFORMANCE_CHECK_PERIOD_TICKS,
                PERFORMANCE_CHECK_PERIOD_TICKS);
        getLogger().info("Rast enabled. Horizon-only optimization: "
                + (isOptimizationEnabled() ? "enabled" : "disabled"));
    }

    @Override
    public void onDisable() {
        brandChecks.values().forEach(BukkitTask::cancel);
        brandChecks.clear();
        if (performanceTask != null) {
            performanceTask.cancel();
            performanceTask = null;
        }
        restoreAllProfiles();
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        beginBrandDetection(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        cancel(brandChecks.remove(id));
        horizonPlayers.remove(id);
    }

    private boolean isOptimizationEnabled() {
        return getConfig().getBoolean("horizon-optimization.enabled", true);
    }

    private void beginBrandDetection(Player player) {
        UUID id = player.getUniqueId();
        cancel(brandChecks.remove(id));
        if (!isOptimizationEnabled()) {
            return;
        }

        BukkitTask task = Bukkit.getScheduler().runTaskTimer(this, new Runnable() {
            private int attempts;

            @Override
            public void run() {
                if (!player.isOnline() || !isOptimizationEnabled()) {
                    cancel(brandChecks.remove(id));
                    return;
                }
                attempts++;
                String brand = player.getClientBrandName();
                if (brand != null && !brand.isBlank()) {
                    cancel(brandChecks.remove(id));
                    if (isHorizonBrand(brand)) {
                        applyCurrentProfile(player, true);
                        getLogger().info("Horizon client recognized: " + player.getName()
                                + " (brand: " + brand + ")");
                    } else {
                        getLogger().fine("Non-Horizon client ignored: " + player.getName()
                                + " (brand: " + brand + ")");
                    }
                    return;
                }
                if (attempts >= BRAND_CHECK_ATTEMPTS) {
                    cancel(brandChecks.remove(id));
                    getLogger().fine("Client brand was not received for " + player.getName()
                            + "; no per-player settings were changed.");
                }
            }
        }, 1L, BRAND_CHECK_PERIOD_TICKS);
        brandChecks.put(id, task);
    }

    private boolean isHorizonBrand(String brand) {
        String normalized = brand.toLowerCase(Locale.ROOT).trim();
        List<String> accepted = getConfig().getStringList(
                "horizon-optimization.accepted-client-brands");
        for (String candidate : accepted) {
            if (candidate != null && !candidate.isBlank()
                    && normalized.contains(candidate.toLowerCase(Locale.ROOT).trim())) {
                return true;
            }
        }
        return false;
    }

    private void refreshHorizonPlayers() {
        if (!isOptimizationEnabled()) {
            return;
        }
        updateResourcePressure();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (horizonPlayers.containsKey(player.getUniqueId())) {
                applyCurrentProfile(player, false);
            }
        }
    }

    private void updateResourcePressure() {
        if (!isAdaptiveModeEnabled()) {
            resourcePressure = false;
            pressureSamples = 0;
            recoverySamples = 0;
            return;
        }

        if (isResourcePressureDetected()) {
            pressureSamples++;
            recoverySamples = 0;
            if (!resourcePressure && pressureSamples >= requiredSamples()) {
                resourcePressure = true;
                getLogger().warning("Resource pressure detected; reduced Horizon-only chunk profile enabled.");
            }
        } else {
            recoverySamples++;
            pressureSamples = 0;
            if (resourcePressure && recoverySamples >= requiredSamples()) {
                resourcePressure = false;
                getLogger().info("Server resources recovered; normal Horizon-only chunk profile restored.");
            }
        }
    }

    private boolean isResourcePressureDetected() {
        double tpsThreshold = getConfig().getDouble(
                "horizon-optimization.adaptive.tps-threshold", 18.0);
        double memoryThreshold = getConfig().getDouble(
                "horizon-optimization.adaptive.max-heap-usage", 0.88);
        double[] tps = Bukkit.getTPS();
        boolean lowTps = tps.length > 0 && tps[0] < tpsThreshold;

        Runtime runtime = Runtime.getRuntime();
        long maxHeap = runtime.maxMemory();
        long usedHeap = runtime.totalMemory() - runtime.freeMemory();
        double heapUsage = maxHeap <= 0 ? 0.0 : (double) usedHeap / maxHeap;
        boolean highMemory = heapUsage >= Math.max(0.50, Math.min(0.99, memoryThreshold));
        return lowTps || highMemory;
    }

    private int requiredSamples() {
        return clamp(getConfig().getInt(
                "horizon-optimization.adaptive.stable-checks", 3), 1, 12);
    }

    private void applyCurrentProfile(Player player, boolean force) {
        if (!player.isOnline() || !isOptimizationEnabled()) {
            return;
        }

        UUID id = player.getUniqueId();
        String profileName = isAdaptiveModeEnabled() && resourcePressure ? "low-tps" : "normal";
        AppliedProfile profile = readProfile(profileName);
        PlayerState state = horizonPlayers.get(id);

        if (state == null) {
            PlayerSettings original = new PlayerSettings(
                    player.getViewDistance(),
                    player.getSendViewDistance(),
                    player.getSimulationDistance());
            state = new PlayerState(original, null);
        } else if (!force && profile.equals(state.applied())) {
            return;
        }

        player.setViewDistance(profile.viewDistance());
        player.setSendViewDistance(profile.sendViewDistance());
        player.setSimulationDistance(profile.simulationDistance());
        horizonPlayers.put(id, new PlayerState(state.original(), profile));
    }

    private void restoreAllProfiles() {
        for (Map.Entry<UUID, PlayerState> entry : horizonPlayers.entrySet()) {
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player == null || !player.isOnline()) {
                continue;
            }
            PlayerSettings original = entry.getValue().original();
            player.setViewDistance(original.viewDistance());
            player.setSendViewDistance(original.sendViewDistance());
            player.setSimulationDistance(original.simulationDistance());
        }
        horizonPlayers.clear();
    }

    private boolean isAdaptiveModeEnabled() {
        return getConfig().getBoolean("horizon-optimization.adaptive.enabled", true);
    }

    private AppliedProfile readProfile(String name) {
        String path = "horizon-optimization.profiles." + name + ".";
        return new AppliedProfile(
                clamp(getConfig().getInt(path + "view-distance", 8), 2, 32),
                clamp(getConfig().getInt(path + "send-view-distance", 8), 2, 32),
                clamp(getConfig().getInt(path + "simulation-distance", 4), 2, 32));
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static void cancel(BukkitTask task) {
        if (task != null) {
            task.cancel();
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("rast")) {
            return false;
        }
        if (!sender.hasPermission("rast.admin")) {
            sender.sendMessage("§cНет разрешения rast.admin.");
            return true;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            restoreAllProfiles();
            reloadConfig();
            resourcePressure = false;
            pressureSamples = 0;
            recoverySamples = 0;
            for (Player player : Bukkit.getOnlinePlayers()) {
                beginBrandDetection(player);
            }
            sender.sendMessage("§aRast: настройки перечитаны; Horizon-клиенты будут перенастроены.");
            return true;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("status")) {
            double[] tps = Bukkit.getTPS();
            double oneMinuteTps = tps.length == 0 ? 20.0 : tps[0];
            Runtime runtime = Runtime.getRuntime();
            long used = runtime.totalMemory() - runtime.freeMemory();
            long max = runtime.maxMemory();
            double heapPercent = max <= 0 ? 0.0 : used * 100.0 / max;
            sender.sendMessage("§6Rast §7| §fHorizon: "
                    + (isOptimizationEnabled() ? "§aвключён" : "§cвыключен")
                    + " §7| §fигроков: §b" + horizonPlayers.size());
            sender.sendMessage("§7TPS (1 мин): §f" + String.format(Locale.ROOT, "%.2f", oneMinuteTps)
                    + " §7| Heap: §f" + String.format(Locale.ROOT, "%.1f%%", heapPercent)
                    + " §7| Профиль: " + (resourcePressure ? "§eсниженный" : "§aобычный"));
            return true;
        }
        sender.sendMessage("§eИспользование: /rast reload §7или §e/rast status");
        return true;
    }

    private record AppliedProfile(int viewDistance, int sendViewDistance, int simulationDistance) {
    }

    private record PlayerSettings(int viewDistance, int sendViewDistance, int simulationDistance) {
    }

    private record PlayerState(PlayerSettings original, AppliedProfile applied) {
    }
}