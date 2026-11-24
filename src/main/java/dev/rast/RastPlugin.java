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
    private final Map<UUID, AppliedProfile> appliedProfiles = new ConcurrentHashMap<>();
    private BukkitTask performanceTask;

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
        getLogger().info("Rast enabled. Horizon-only adaptive chunk settings: "
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
        appliedProfiles.clear();
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        beginBrandDetection(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        cancel(brandChecks.remove(id));
        appliedProfiles.remove(id);
    }

    private boolean isOptimizationEnabled() {
        return getConfig().getBoolean("horizon-optimization.enabled", true);
    }

    private void beginBrandDetection(Player player) {
        UUID id = player.getUniqueId();
        cancel(brandChecks.remove(id));
        appliedProfiles.remove(id);
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
                    }
                    return;
                }
                if (attempts >= BRAND_CHECK_ATTEMPTS) {
                    cancel(brandChecks.remove(id));
                }
            }
        }, 1L, BRAND_CHECK_PERIOD_TICKS);
        brandChecks.put(id, task);
    }

    private boolean isHorizonBrand(String brand) {
        String normalized = brand.toLowerCase(Locale.ROOT);
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
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (appliedProfiles.containsKey(player.getUniqueId())) {
                applyCurrentProfile(player, false);
            }
        }
    }

    private void applyCurrentProfile(Player player, boolean force) {
        if (!player.isOnline() || !isOptimizationEnabled()) {
            return;
        }
        boolean underLoad = isAdaptiveModeEnabled() && isServerUnderLoad();
        String profileName = underLoad ? "low-tps" : "normal";
        AppliedProfile profile = readProfile(profileName);
        UUID id = player.getUniqueId();

        if (!force && profile.equals(appliedProfiles.get(id))) {
            return;
        }
        player.setViewDistance(profile.viewDistance());
        player.setSendViewDistance(profile.sendViewDistance());
        player.setSimulationDistance(profile.simulationDistance());
        appliedProfiles.put(id, profile);
        if (force) {
            getLogger().fine("Applied " + profileName + " Horizon chunk profile to "
                    + player.getName() + ".");
        }
    }

    private boolean isAdaptiveModeEnabled() {
        return getConfig().getBoolean("horizon-optimization.adaptive.enabled", true);
    }

    private boolean isServerUnderLoad() {
        double threshold = getConfig().getDouble(
                "horizon-optimization.adaptive.tps-threshold", 18.0);
        double[] tps = Bukkit.getTPS();
        return tps.length > 0 && tps[0] < threshold;
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
            reloadConfig();
            appliedProfiles.clear();
            for (Player player : Bukkit.getOnlinePlayers()) {
                beginBrandDetection(player);
            }
            sender.sendMessage("§aRast: настройки перечитаны; Horizon-клиенты будут перенастроены.");
            return true;
        }
        sender.sendMessage("§eИспользование: /rast reload");
        return true;
    }

    private record AppliedProfile(int viewDistance, int sendViewDistance, int simulationDistance) {
    }
}
