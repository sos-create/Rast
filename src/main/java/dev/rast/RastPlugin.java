package dev.rast;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

public final class RastPlugin extends JavaPlugin {
    private BukkitTask mergeTask;
    private long totalMerged;
    private long totalTransfers;
    private long lastPassMerged;
    private long lastPassTransfers;
    private long lastPassDurationMs;
    private int lastPassComparisons;
    private int lastPassChunks;
    private int consecutiveTaskFailures;
    private int chunkCursor;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        startMergeTask();
        if (getConfig().getBoolean("diagnostics.log-startup-summary", true)) {
            getLogger().info("Rast enabled on " + Bukkit.getBukkitVersion()
                    + "; item merging=" + getConfig().getBoolean("item-merge.enabled", true));
        }
    }

    @Override
    public void onDisable() {
        stopMergeTask();
    }

    private void stopMergeTask() {
        if (mergeTask != null) {
            mergeTask.cancel();
            mergeTask = null;
        }
    }

    private void startMergeTask() {
        stopMergeTask();
        consecutiveTaskFailures = 0;
        if (!getConfig().getBoolean("item-merge.enabled", true)) {
            return;
        }
        long interval = Math.max(20L, Math.min(1200L,
                getConfig().getLong("item-merge.interval-ticks", 40L)));
        mergeTask = Bukkit.getScheduler().runTaskTimer(this, this::runMergeTaskSafely, interval, interval);
    }

    private void runMergeTaskSafely() {
        try {
            mergeLoadedItems();
            consecutiveTaskFailures = 0;
        } catch (RuntimeException exception) {
            consecutiveTaskFailures++;
            getLogger().log(Level.SEVERE, "Item merge pass failed ("
                    + consecutiveTaskFailures + "/3). The next pass will retry.", exception);
            if (consecutiveTaskFailures >= 3) {
                stopMergeTask();
                getLogger().severe("Item merging was paused after 3 consecutive failures. "
                        + "Other server functions remain available; fix the error and run /rast reload.");
            }
        }
    }

    private void mergeLoadedItems() {
        long started = System.nanoTime();
        double radius = Math.max(0.5, Math.min(4.0,
                getConfig().getDouble("item-merge.radius", 1.25)));
        double radiusSquared = radius * radius;
        int comparisonBudget = Math.max(100, getConfig().getInt(
                "item-merge.max-comparisons-per-chunk", 1200));
        int chunkBudget = Math.max(1, getConfig().getInt("item-merge.max-chunks-per-pass", 32));
        int entityScanBudget = Math.max(128, getConfig().getInt(
                "item-merge.max-entities-per-chunk", 4096));
        int itemBudget = Math.max(16, getConfig().getInt(
                "item-merge.max-items-per-chunk", 512));
        Set<String> disabledWorlds = new HashSet<>(
                getConfig().getStringList("item-merge.disabled-worlds"));

        List<Chunk> loaded = new ArrayList<>();
        for (World world : Bukkit.getWorlds()) {
            if (!disabledWorlds.contains(world.getName())) {
                for (Chunk chunk : world.getLoadedChunks()) {
                    loaded.add(chunk);
                }
            }
        }

        if (loaded.isEmpty()) {
            lastPassMerged = 0;
            lastPassTransfers = 0;
            lastPassComparisons = 0;
            lastPassChunks = 0;
            lastPassDurationMs = (System.nanoTime() - started) / 1_000_000L;
            chunkCursor = 0;
            return;
        }

        int start = Math.floorMod(chunkCursor, loaded.size());
        int count = Math.min(chunkBudget, loaded.size());
        long mergedThisPass = 0;
        long transfersThisPass = 0;
        int comparisonsThisPass = 0;

        for (int offset = 0; offset < count; offset++) {
            Chunk chunk = loaded.get((start + offset) % loaded.size());
            List<Item> items = new ArrayList<>();
            Entity[] entities = chunk.getEntities();
            int scanned = 0;
            for (Entity entity : entities) {
                if (++scanned > entityScanBudget || items.size() >= itemBudget) {
                    break;
                }
                if (entity instanceof Item item && item.isValid() && !item.isDead()) {
                    items.add(item);
                }
            }
            if (items.size() < 2) {
                continue;
            }

            Map<Cell, List<Item>> cells = new HashMap<>();
            for (Item item : items) {
                if (isMergeEligible(item)) {
                    Cell cell = cellFor(item, radius);
                    cells.computeIfAbsent(cell, ignored -> new ArrayList<>()).add(item);
                }
            }

            int comparisons = 0;
            outer:
            for (Item source : items) {
                if (!isMergeEligible(source)) {
                    continue;
                }
                Cell cell = cellFor(source, radius);

                neighborCells:
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        List<Item> nearby = cells.get(new Cell(cell.x + dx, cell.z + dz));
                        if (nearby == null) {
                            continue;
                        }

                        for (Item target : nearby) {
                            if (source == target || !isMergeEligible(target)
                                    || source.getEntityId() >= target.getEntityId()) {
                                continue;
                            }

                            comparisons++;
                            if (comparisons > comparisonBudget) {
                                break outer;
                            }

                            if (source.getLocation().distanceSquared(target.getLocation()) > radiusSquared
                                    || !samePickupRules(source, target)) {
                                continue;
                            }

                            ItemStack sourceStack = source.getItemStack();
                            ItemStack targetStack = target.getItemStack();
                            if (!sourceStack.isSimilar(targetStack)) {
                                continue;
                            }

                            int capacity = targetStack.getMaxStackSize() - targetStack.getAmount();
                            int moved = Math.min(capacity, sourceStack.getAmount());
                            if (moved <= 0) {
                                continue;
                            }

                            targetStack.setAmount(targetStack.getAmount() + moved);
                            target.setItemStack(targetStack);
                            sourceStack.setAmount(sourceStack.getAmount() - moved);
                            transfersThisPass++;

                            target.setPickupDelay(Math.max(source.getPickupDelay(), target.getPickupDelay()));
                            if (source.willAge() && target.willAge()) {
                                target.setTicksLived(Math.max(source.getTicksLived(), target.getTicksLived()));
                            }

                            if (sourceStack.getAmount() <= 0) {
                                source.remove();
                                mergedThisPass++;
                                break neighborCells;
                            }
                            source.setItemStack(sourceStack);
                        }
                    }
                }
            }
            comparisonsThisPass += comparisons;
        }

        chunkCursor = (start + count) % loaded.size();
        lastPassMerged = mergedThisPass;
        lastPassTransfers = transfersThisPass;
        totalMerged += mergedThisPass;
        totalTransfers += transfersThisPass;
        lastPassComparisons = comparisonsThisPass;
        lastPassChunks = count;
        lastPassDurationMs = (System.nanoTime() - started) / 1_000_000L;
    }

    private static boolean isMergeEligible(Item item) {
        return item.isValid() && !item.isDead()
                && item.getPersistentDataContainer().getKeys().isEmpty();
    }

    private static Cell cellFor(Item item, double radius) {
        return new Cell(
                (int) Math.floor(item.getLocation().getX() / radius),
                (int) Math.floor(item.getLocation().getZ() / radius));
    }

    private static boolean samePickupRules(Item first, Item second) {
        UUID firstOwner = first.getOwner();
        UUID secondOwner = second.getOwner();
        UUID firstThrower = first.getThrower();
        UUID secondThrower = second.getThrower();
        return Objects.equals(firstOwner, secondOwner)
                && Objects.equals(firstThrower, secondThrower)
                && first.getScoreboardTags().equals(second.getScoreboardTags())
                && first.canPlayerPickup() == second.canPlayerPickup()
                && first.canMobPickup() == second.canMobPickup()
                && first.willAge() == second.willAge()
                && first.isUnlimitedLifetime() == second.isUnlimitedLifetime()
                && first.getHealth() == second.getHealth();
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
            chunkCursor = 0;
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
            Chunk[] worldChunks = world.getLoadedChunks();
            chunks += worldChunks.length;
            for (Chunk chunk : worldChunks) {
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
                + " §7| Загруженные чанки: §f" + chunks);
        sender.sendMessage("§7Сущности: §f" + entities + " §7| Предметы: §f" + itemEntities);
        sender.sendMessage("§7Последний проход: §f" + lastPassDurationMs + " мс"
                + " §7| чанков: §f" + lastPassChunks);
        sender.sendMessage("§7Переносов предметов: §f" + lastPassTransfers
                + " §7| удалено исходных сущностей: §f" + lastPassMerged);
        sender.sendMessage("§7Всего переносов: §f" + totalTransfers
                + " §7| всего удалено сущностей: §f" + totalMerged
                + " §7| сравнений: §f" + lastPassComparisons);
        sender.sendMessage("§7Объединение предметов: "
                + (getConfig().getBoolean("item-merge.enabled", true) ? "§aвключено" : "§cвыключено"));
        sender.sendMessage("§7Ошибок фоновой задачи подряд: §f" + consecutiveTaskFailures);
    }

    private static String formatTps(double[] tps) {
        if (tps == null || tps.length < 3) {
            return "н/д";
        }
        return String.format(Locale.US, "%.2f / %.2f / %.2f",
                Math.min(20.0, tps[0]), Math.min(20.0, tps[1]), Math.min(20.0, tps[2]));
    }
}
