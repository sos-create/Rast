package dev.rast;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class RastPlugin extends JavaPlugin {
    private BukkitTask mergeTask;
    private long totalMerged;
    private long lastPassMerged;
    private int lastPassComparisons;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        startMergeTask();

        if (getConfig().getBoolean("diagnostics.log-startup-summary", true)) {
            getLogger().info("Rast enabled. Paper " + Bukkit.getBukkitVersion()
                    + "; safe item merging: "
                    + getConfig().getBoolean("item-merge.enabled", true));
        }
    }

    @Override
    public void onDisable() {
        if (mergeTask != null) {
            mergeTask.cancel();
            mergeTask = null;
        }
    }

    private void startMergeTask() {
        if (mergeTask != null) {
            mergeTask.cancel();
            mergeTask = null;
        }

        if (!getConfig().getBoolean("item-merge.enabled", true)) {
            return;
        }

        long interval = Math.max(20L, getConfig().getLong("item-merge.interval-ticks", 40L));
        mergeTask = Bukkit.getScheduler().runTaskTimer(this, this::mergeLoadedItems, interval, interval);
    }

    private void mergeLoadedItems() {
        double radius = Math.max(0.5, Math.min(4.0,
                getConfig().getDouble("item-merge.radius", 1.25)));
        double radiusSquared = radius * radius;
        int comparisonBudget = Math.max(100, getConfig().getInt(
                "item-merge.max-comparisons-per-chunk", 1200));
        Set<String> disabledWorlds = new HashSet<>(
                getConfig().getStringList("item-merge.disabled-worlds"));

        long mergedThisPass = 0;
        int comparisonsThisPass = 0;

        for (World world : Bukkit.getWorlds()) {
            if (disabledWorlds.contains(world.getName())) {
                continue;
            }

            for (Chunk chunk : world.getLoadedChunks()) {
                List<Item> items = new ArrayList<>();
                for (Entity entity : chunk.getEntities()) {
                    if (entity instanceof Item item && item.isValid() && !item.isDead()) {
                        items.add(item);
                    }
                }
                if (items.size() < 2) {
                    continue;
                }

                Map<Cell, List<Item>> cells = new HashMap<>();
                for (Item item : items) {
                    Cell cell = new Cell(
                            (int) Math.floor(item.getLocation().getX() / radius),
                            (int) Math.floor(item.getLocation().getZ() / radius));
                    cells.computeIfAbsent(cell, ignored -> new ArrayList<>()).add(item);
                }

                int comparisons = 0;
                outer:
                for (Item source : items) {
                    if (!source.isValid() || source.isDead()) {
                        continue;
                    }

                    int cellX = (int) Math.floor(source.getLocation().getX() / radius);
                    int cellZ = (int) Math.floor(source.getLocation().getZ() / radius);

                    for (int dx = -1; dx <= 1; dx++) {
                        for (int dz = -1; dz <= 1; dz++) {
                            List<Item> nearby = cells.get(new Cell(cellX + dx, cellZ + dz));
                            if (nearby == null) {
                                continue;
                            }

                            for (Item target : nearby) {
                                if (source == target || !target.isValid() || target.isDead()
                                        || source.getEntityId() >= target.getEntityId()) {
                                    continue;
                                }

                                comparisons++;
                                if (comparisons >= comparisonBudget) {
                                    break outer;
                                }

                                if (source.getWorld() != target.getWorld()
                                        || source.getLocation().distanceSquared(target.getLocation()) > radiusSquared) {
                                    continue;
                                }

                                ItemStack sourceStack = source.getItemStack();
                                ItemStack targetStack = target.getItemStack();
                                if (!sourceStack.isSimilar(targetStack)) {
                                    continue;
                                }

                                int combined = sourceStack.getAmount() + targetStack.getAmount();
                                if (combined > targetStack.getMaxStackSize()) {
                                    continue;
                                }

                                targetStack.setAmount(combined);
                                target.setItemStack(targetStack);
                                target.setPickupDelay(Math.min(source.getPickupDelay(), target.getPickupDelay()));
                                source.remove();
                                mergedThisPass++;
                            }
                        }
                    }
                }
                comparisonsThisPass += comparisons;
            }
        }

        lastPassMerged = mergedThisPass;
        totalMerged += mergedThisPass;
        lastPassComparisons = comparisonsThisPass;
    }

    private record Cell(int x, int z) { }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("rast")) {
            return false;
        }
        if (!sender.hasPermission("rast.admin")) {
            sender.sendMessage("§cУ вас нет разрешения rast.admin.");
            return true;
        }

        if (args.length == 0 || args[0].equalsIgnoreCase("status")) {
            sendStatus(sender);
            return true;
        }

        if (args[0].equalsIgnoreCase("reload")) {
            reloadConfig();
            startMergeTask();
            sender.sendMessage("§aRast: настройки перезагружены.");
            return true;
        }

        sender.sendMessage("§eИспользование: /rast <status|reload>");
        return true;
    }

    private void sendStatus(CommandSender sender) {
        int chunks = 0;
        int entities = 0;
        int itemEntities = 0;

        for (World world : Bukkit.getWorlds()) {
            chunks += world.getLoadedChunks().length;
            for (Chunk chunk : world.getLoadedChunks()) {
                for (Entity entity : chunk.getEntities()) {
                    entities++;
                    if (entity instanceof Item) {
                        itemEntities++;
                    }
                }
            }
        }

        double[] tps = Bukkit.getServer().getTPS();
        double mspt = Bukkit.getServer().getAverageTickTime();

        sender.sendMessage("§6§lRast §7— диагностика сервера");
        sender.sendMessage("§7Версия: §f" + Bukkit.getBukkitVersion());
        sender.sendMessage("§7TPS (1m/5m/15m): §f" + formatTps(tps));
        sender.sendMessage("§7Средний MSPT: §f" + String.format(Locale.US, "%.2f", mspt));
        sender.sendMessage("§7Игроки: §f" + Bukkit.getOnlinePlayers().size()
                + " §7| Чанки: §f" + chunks);
        sender.sendMessage("§7Сущности: §f" + entities + " §7| Предметы: §f" + itemEntities);
        sender.sendMessage("§7Объединено предметов за последний проход: §f" + lastPassMerged);
        sender.sendMessage("§7Всего объединено: §f" + totalMerged
                + " §7| Проверок за проход: §f" + lastPassComparisons);
        sender.sendMessage("§7Объединение предметов: "
                + (getConfig().getBoolean("item-merge.enabled", true) ? "§aвключено" : "§cвыключено"));
    }

    private String formatTps(double[] tps) {
        if (tps == null || tps.length < 3) {
            return "н/д";
        }
        return String.format(Locale.US, "%.2f / %.2f / %.2f",
                Math.min(20.0, tps[0]), Math.min(20.0, tps[1]), Math.min(20.0, tps[2]));
    }
}
