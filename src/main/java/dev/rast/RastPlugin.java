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
    private final Map<UUID, BukkitTask> brandChecks = new ConcurrentHashMap<>();
    private final Map<UUID, BukkitTask> appliedPlayers = new ConcurrentHashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getServer().getPluginManager().registerEvents(this, this);
        for (Player player : Bukkit.getOnlinePlayers()) {
            beginBrandDetection(player);
        }
        getLogger().info("Rast enabled: Horizon-only per-player chunk settings are "
                + (getConfig().getBoolean("horizon-optimization.enabled", true) ? "enabled" : "disabled") + ".");
    }

    @Override
    public void onDisable() {
        brandChecks.values().forEach(BukkitTask::cancel);
        appliedPlayers.values().forEach(BukkitTask::cancel);
        brandChecks.clear();
        appliedPlayers.clear();
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        beginBrandDetection(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        cancel(brandChecks.remove(id));
        cancel(appliedPlayers.remove(id));
    }

    private void beginBrandDetection(Player player) {
        if (!getConfig().getBoolean("horizon-optimization.enabled", true)) {
            return;
        }
        UUID id = player.getUniqueId();
        cancel(brandChecks.remove(id));
        BukkitTask task = Bukkit.getScheduler().runTaskTimer(this, new Runnable() {
            private int attempts;

            @Override
            public void run() {
                if (!player.isOnline() || !getConfig().getBoolean("horizon-optimization.enabled", true)) {
                    cancel(brandChecks.remove(id));
                    return;
                }
                attempts++;
                String brand = player.getClientBrandName();
                if (brand != null && !brand.isBlank()) {
                    cancel(brandChecks.remove(id));
                    if (isHorizonBrand(brand)) {
                        applyHorizonSettings(player);
                    }
                    return;
                }
                if (attempts >= 30) {
                    cancel(brandChecks.remove(id));
                }
            }
        }, 1L, 10L);
        brandChecks.put(id, task);
    }

    private boolean isHorizonBrand(String brand) {
        String normalized = brand.toLowerCase(Locale.ROOT);
        List<String> accepted = getConfig().getStringList("horizon-optimization.accepted-client-brands");
        for (String candidate : accepted) {
            if (candidate != null && !candidate.isBlank()
                    && normalized.contains(candidate.toLowerCase(Locale.ROOT).trim())) {
                return true;
            }
        }
        return false;
    }

    private void applyHorizonSettings(Player player) {
        int view = clamp(getConfig().getInt("horizon-optimization.view-distance", 8), 2, 32);
        int sendView = clamp(getConfig().getInt("horizon-optimization.send-view-distance", 8), 2, 32);
        int simulation = clamp(getConfig().getInt("horizon-optimization.simulation-distance", 4), 2, 32);

        player.setViewDistance(view);
        player.setSendViewDistance(sendView);
        player.setSimulationDistance(simulation);

        UUID id = player.getUniqueId();
        cancel(appliedPlayers.remove(id));
        BukkitTask task = Bukkit.getScheduler().runTaskLater(this, () -> {
            if (player.isOnline() && isHorizonBrand(player.getClientBrandName() == null
                    ? "" : player.getClientBrandName())) {
                player.setViewDistance(view);
                player.setSendViewDistance(sendView);
                player.setSimulationDistance(simulation);
            }
            appliedPlayers.remove(id);
        }, 40L);
        appliedPlayers.put(id, task);
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
            for (Player player : Bukkit.getOnlinePlayers()) {
                beginBrandDetection(player);
            }
            sender.sendMessage("§aRast: настройки перечитаны; проверка Horizon-клиентов запущена.");
            return true;
        }
        sender.sendMessage("§eИспользование: /rast reload");
        return true;
    }
}
