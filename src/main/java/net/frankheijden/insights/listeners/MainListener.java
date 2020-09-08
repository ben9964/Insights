package net.frankheijden.insights.listeners;

import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import io.papermc.lib.PaperLib;
import net.frankheijden.insights.Insights;
import net.frankheijden.insights.api.InsightsAPI;
import net.frankheijden.insights.builders.Scanner;
import net.frankheijden.insights.config.*;
import net.frankheijden.insights.entities.*;
import net.frankheijden.insights.enums.ScanType;
import net.frankheijden.insights.events.*;
import net.frankheijden.insights.managers.*;
import net.frankheijden.insights.tasks.UpdateCheckerTask;
import net.frankheijden.insights.utils.*;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;

import java.text.NumberFormat;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

public class MainListener implements Listener {

    private static final Insights plugin = Insights.getInstance();
    private static final SelectionManager selectionManager = SelectionManager.getInstance();
    private static final CacheManager cacheManager = CacheManager.getInstance();
    private static final FreezeManager freezeManager = FreezeManager.getInstance();
    private final InteractListener interactListener;
    private final List<Location> blockLocations;

    public MainListener(InteractListener interactListener) {
        this.interactListener = interactListener;
        this.blockLocations = new ArrayList<>();
    }

    public InteractListener getInteractListener() {
        return interactListener;
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        Block block = event.getBlock();
        String name = block.getType().name();

        if (selectionManager.isSelecting(player.getUniqueId())) {
            selectionManager.setPos1(player.getUniqueId(), block.getLocation(), true);
            event.setCancelled(true);
        }

        if (blockLocations.contains(block.getLocation())) {
            event.setCancelled(true);
            return;
        }

        int d = -getAmount(block.getType());

        Limit limit = InsightsAPI.getLimit(player, name);
        if (limit != null) {
            if (cacheManager.hasSelections(player.getLocation())) {
                handleBlockCache(event, player, block, null, d, limit);
            } else if (!isPassiveForPlayer(player, "block")) {
                sendBreakMessage(player, event.getBlock().getChunk(), d, limit);
            }
        } else if (TileUtils.isTile(event.getBlock()) && !isPassiveForPlayer(player, "tile")) {
            int generalLimit = plugin.getConfiguration().GENERAL_LIMIT;
            if (plugin.getConfiguration().GENERAL_ALWAYS_SHOW_NOTIFICATION || generalLimit > -1) {
                int current = event.getBlock().getLocation().getChunk().getTileEntities().length + d;
                tryNotifyRealtime(player, current, generalLimit);
            }
        }
    }

    private static final Set<Material> BEDS = Arrays.stream(Material.values())
            .filter(m -> m.name().endsWith("_BED") || m.name().equals("BED_BLOCK"))
            .collect(Collectors.toSet());
    private static final Set<Material> DOORS = Arrays.stream(Material.values())
            .filter(m -> m.name().contains("_DOOR"))
            .collect(Collectors.toSet());

    private int getAmount(Material m) {
        return BEDS.contains(m) || DOORS.contains(m) ? 2 : 1;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();

        if (selectionManager.isSelecting(player.getUniqueId())) {
            Block block = event.getClickedBlock();
            if (block == null) return;
            if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
            selectionManager.setPos2(player.getUniqueId(), block.getLocation(), true);
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockPistonExtend(BlockPistonExtendEvent event) {
        handlePistonEvent(event, event.getBlocks());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockPistonRetract(BlockPistonRetractEvent event) {
        handlePistonEvent(event, event.getBlocks());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockPhysics(BlockPhysicsEvent event) {
        if (blockLocations.contains(event.getBlock().getLocation())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        event.blockList().removeIf(b -> blockLocations.contains(b.getLocation()));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        event.blockList().removeIf(b -> blockLocations.contains(b.getLocation()));
    }

    private void handlePistonEvent(Cancellable cancellable, List<Block> blocks) {
        for (Block block : blocks) {
            if (blockLocations.contains(block.getLocation())) {
                cancellable.setCancelled(true);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockFromTo(BlockFromToEvent event) {
        Location f = event.getBlock().getLocation();
        Location t = event.getToBlock().getLocation();
        if (blockLocations.contains(f) || blockLocations.contains(t)) {
            event.setCancelled(true);
        }
    }

    private void sendBreakMessage(Player player, Chunk chunk, int d, Limit limit) {
        ChunkSnapshot chunkSnapshot = chunk.getChunkSnapshot();
        new BukkitRunnable() {
            @Override
            public void run() {
                int current = ChunkUtils.getAmountInChunk(chunk, chunkSnapshot, limit) + d;
                sendMessage(player, limit.getName(), current, limit.getLimit());
            }
        }.runTaskAsynchronously(plugin);
    }

    private void sendMessage(Player player, String name, int current, int limit) {
        if (player.hasPermission("insights.check.realtime") && plugin.getSqLite().hasRealtimeCheckEnabled(player)) {
            double progress = ((double) current)/((double) limit);
            if (progress > 1 || progress < 0) progress = 1;
            MessageUtils.sendSpecialMessage(player, "messages.realtime_check_custom", progress,
                    "%count%", NumberFormat.getIntegerInstance().format(current),
                    "%material%", StringUtils.capitalizeName(name),
                    "%limit%", NumberFormat.getIntegerInstance().format(limit));
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerEntityPlace(PlayerEntityPlaceEvent event) {
        Player player = event.getPlayer();
        Entity entity = event.getEntity();
        handleEntityPlace(event, player, entity.getLocation().getChunk(), entity);
    }

    private void handleEntityPlace(Cancellable cancellable, Player player, Chunk chunk, Entity entity) {
        String name = entity.getType().name();
        if (!canPlaceInRegion(entity.getLocation(), name) && !player.hasPermission("insights.regions.bypass." + name)) {
            cancellable.setCancelled(true);
            if (!isPassiveForPlayer(player, "region")) {
                MessageUtils.sendMessage(player, "messages.region_disallowed_block");
            }
            return;
        }

        Limit limit = InsightsAPI.getLimit(player, name);
        if (limit == null) return;
        int l = limit.getLimit();
        if (l < 0) return;
        int current = getEntityCount(chunk, limit.getEntities()) + 1;

        if (current > l && !player.hasPermission(limit.getPermission())) {
            cancellable.setCancelled(true);
            if (!isPassiveForPlayer(player, "entity")) {
                MessageUtils.sendMessage(player, "messages.limit_reached_custom",
                        "%limit%", NumberFormat.getIntegerInstance().format(l),
                        "%material%", StringUtils.capitalizeName(limit.getName()),
                        "%area%", "chunk");
            }
            return;
        }

        if (!isPassiveForPlayer(player, "entity")) {
            sendMessage(player, limit.getName(), current, l);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerEntityDestroy(PlayerEntityDestroyEvent event) {
        handleEntityDestroy(event.getPlayer(), event.getEntity());
    }

    public void handleEntityDestroy(Player player, Entity entity) {
        if (isPassiveForPlayer(player, "entity")) return;
        String name = entity.getType().name();

        Limit limit = InsightsAPI.getLimit(player, name);
        if (limit == null) return;
        int l = limit.getLimit();
        if (l < 0) return;
        int current = getEntityCount(entity.getLocation().getChunk(), limit.getEntities()) - 1;

        sendMessage(player, limit.getName(), current, l);
    }

    private int getEntityCount(Chunk chunk, Set<String> entityTypes) {
        int count = 0;
        for (Entity entity : chunk.getEntities()) {
            if (entityTypes.contains(entity.getType().name())) count++;
        }
        return count;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        Block block = event.getBlock();
        if (HookManager.getInstance().shouldCancel(block)) return;

        Player player = event.getPlayer();
        String name = block.getType().name();

        if (isNextToForbiddenLocation(block.getLocation())) {
            event.setCancelled(true);
            return;
        }

        if (!canPlaceInRegion(block.getLocation(), name) && !player.hasPermission("insights.regions.bypass." + name)) {
            event.setCancelled(true);
            if (!isPassiveForPlayer(player, "region")) {
                MessageUtils.sendMessage(player, "messages.region_disallowed_block");
            }
            return;
        }

        int d = getAmount(block.getType());

        Limit limit = InsightsAPI.getLimit(player, name);
        if (limit != null) {
            ItemStack is = new ItemStack(event.getItemInHand());
            is.setAmount(1);
            if (cacheManager.hasSelections(player.getLocation())) {
                handleBlockCache(event, player, block, is, d, limit);
            } else {
                handleChunkPreBlockPlace(event, player, block, is, limit);
            }
        } else if (TileUtils.isTile(event.getBlockPlaced())) {
            int current = event.getBlock().getLocation().getChunk().getTileEntities().length + d;
            int generalLimit = plugin.getConfiguration().GENERAL_LIMIT;
            if (generalLimit > -1 && current >= generalLimit) {
                if (!player.hasPermission("insights.bypass")) {
                    event.setCancelled(true);
                    if (!isPassiveForPlayer(player, "tile")) {
                        MessageUtils.sendMessage(player, "messages.limit_reached",
                                "%limit%", NumberFormat.getIntegerInstance().format(generalLimit),
                                "%area%", "chunk");
                    }
                }
            }

            if (plugin.getConfiguration().GENERAL_ALWAYS_SHOW_NOTIFICATION || generalLimit > -1) {
                if (!isPassiveForPlayer(player, "tile")) {
                    tryNotifyRealtime(player, current, generalLimit);
                }
            }
        }
    }

    private boolean canNotifyRealtime(Player player) {
        return player.hasPermission("insights.check.realtime")
                && plugin.getSqLite().hasRealtimeCheckEnabled(player);
    }

    private void tryNotifyRealtime(Player player, int current, int limit) {
        if (!canNotifyRealtime(player)) return;

        double progress = ((double) current)/((double) limit);
        if (progress > 1 || progress < 0) progress = 1;

        if (limit > -1) {
            MessageUtils.sendSpecialMessage(player, "messages.realtime_check", progress,
                    "%tile_count%", NumberFormat.getIntegerInstance().format(current),
                    "%limit%", NumberFormat.getIntegerInstance().format(limit));
        } else {
            MessageUtils.sendSpecialMessage(player, "messages.realtime_check_no_limit", progress,
                    "%tile_count%", NumberFormat.getIntegerInstance().format(current));
        }
    }

    private boolean isNextToForbiddenLocation(Location location) {
        for (Location loc : blockLocations) {
            if (isEqual(loc, location, -1, 0, 0)
                    || isEqual(loc, location, 1, 0, 0)
                    || isEqual(loc, location, 0, -1, 0)
                    || isEqual(loc, location, 0, 1, 0)
                    || isEqual(loc, location, 0, 0, -1)
                    || isEqual(loc, location, 0, 0, 1)) return true;
        }
        return false;
    }

    private boolean isEqual(Location loc1, Location loc2, int x, int y, int z) {
        if (loc1 == null) return false;
        return loc1.clone().add(x, y, z).equals(loc2);
    }

    private boolean canPlaceInRegion(Location location, String str) {
        WorldGuardManager worldGuardManager = WorldGuardManager.getInstance();
        if (worldGuardManager != null) {
            ProtectedRegion region = worldGuardManager.getRegionWithLimitedBlocks(location);
            if (region != null) {
                return canPlaceInRegion(region.getId(), str);
            }
        }
        return true;
    }

    private boolean canPlaceInRegion(String region, String str) {
        List<RegionBlocks> regionBlocks = plugin.getConfiguration().GENERAL_REGION_BLOCKS;

        RegionBlocks matchedRegion = null;
        for (RegionBlocks rg : regionBlocks) {
            if (region.matches(rg.getRegex())) {
                matchedRegion = rg;
                break;
            }
        }

        if (matchedRegion != null) {
            List<String> strs = matchedRegion.getBlocks();
            if (matchedRegion.isWhitelist()) return strs.contains(str);
            else return !strs.contains(str);
        }
        return false;
    }

    private void handleBlockCache(Cancellable event, Player player, Block block, ItemStack is, int d, Limit limit) {
        String name = block.getType().name();

        Set<SelectionEntity> selections = cacheManager.updateCache(player.getLocation(), name, d);
        if (selections.size() == 0 && limit != null) {
            cacheManager.getMaxCountCache(player.getLocation(), name)
                    .ifPresent(scanCache -> handleCacheLimit(scanCache, event, player, block, name, is, d, limit));
            return;
        }

        Map<SelectionEntity, ScanOptions> list = from(selections, player);
        if (list.size() == 0) return;

        MessageUtils.sendMessage(player, "messages.area_scan.start");
        freezeManager.freezePlayer(player.getUniqueId());
        blockLocations.add(block.getLocation());

        AtomicInteger integer = new AtomicInteger(list.size());
        for (Map.Entry<SelectionEntity, ScanOptions> entry : list.entrySet()) {
            Scanner.create(entry.getValue()).scan().whenComplete((ev, err) -> {
                ScanCache cache = new ScanCache(entry.getKey(), ev.getScanResult());
                cacheManager.updateCache(cache);

                if (integer.decrementAndGet() == 0) {
                    MessageUtils.sendMessage(player, "messages.area_scan.end");
                    freezeManager.defrostPlayer(player.getUniqueId());

                    Optional<ScanCache> scanCache = cacheManager.getMaxCountCache(player.getLocation(), name);
                    if (scanCache.isPresent()) {
                        handleCacheLimit(scanCache.get(), null, player, block, name, is, d, limit);
                    } else {
                        blockLocations.remove(block.getLocation());
                    }
                }
            });
        }
    }

    private void handleCacheLimit(ScanCache cache, Cancellable event, Player player, Block block, String name, ItemStack is, int d, Limit limit) {
        Integer count = cache.getCount(name);
        if (count == null) count = 0;

        int l = limit.getLimit();
        if (d > 0 && count > l && !player.hasPermission(limit.getPermission())) {
            if (!isPassiveForPlayer(player, "block")) {
                MessageUtils.sendMessage(player, "messages.limit_reached_custom",
                        "%limit%", NumberFormat.getIntegerInstance().format(l),
                        "%material%", StringUtils.capitalizeName(limit.getName()),
                        "%area%", cache.getSelectionEntity().getAssistant().getAreaName());
            }

            cache.updateCache(name, -d);
            if (event != null) {
                event.setCancelled(true);
            } else {
                simulateBreak(player, block, is);
                return;
            }
        } else if (!isPassiveForPlayer(player, "block")) {
            sendMessage(player, limit.getName(), count, l);
        }
        blockLocations.remove(block.getLocation());
    }

    private void simulateBreak(Player player, Block block, ItemStack is) {
        if (player.getGameMode() != GameMode.CREATIVE) {
            player.getInventory().addItem(is);
        }
        new BukkitRunnable() {
            @Override
            public void run() {
                Block other = null;
                if (PaperLib.getMinecraftVersion() >= 13) {
                    other = Post1_13Listeners.getOther(block);
                }
                block.setType(Material.AIR);
                if (other != null) other.setType(Material.AIR);
                blockLocations.remove(block.getLocation());
            }
        }.runTask(plugin);
    }

    private Map<SelectionEntity, ScanOptions> from(Set<SelectionEntity> selections, Player player) {
        Map<SelectionEntity, ScanOptions> list = new HashMap<>();
        for (SelectionEntity selection : selections) {
            ScanOptions options = new ScanOptions();
            options.setScanType(ScanType.ALL);
            options.setWorld(player.getWorld());
            options.setUuid(player.getUniqueId());

            List<PartialChunk> partials = ChunkUtils.getPartialChunks(selection.getPos1(), selection.getPos2());
            options.setPartialChunks(partials);
            list.put(selection, options);
        }
        return list;
    }

    private void handleChunkPreBlockPlace(Cancellable event, Player player, Block block, ItemStack is, Limit limit) {
        ChunkSnapshot chunkSnapshot = block.getChunk().getChunkSnapshot();

        boolean async = shouldPerformAsync(block.getType().name());
        if (async) {
            if (!player.hasPermission(limit.getPermission())) {
                blockLocations.add(block.getLocation());
            }

            new BukkitRunnable() {
                @Override
                public void run() {
                    handleChunkBlockPlace(event, player, block, chunkSnapshot, is, async, limit);
                }
            }.runTaskAsynchronously(plugin);
        } else {
            handleChunkBlockPlace(event, player, block, chunkSnapshot, null, async, limit);
        }
    }

    private boolean shouldPerformAsync(String name) {
        Config config = plugin.getConfiguration();
        if (config.GENERAL_ASYNC_ENABLED) {
            if (config.GENERAL_ASYNC_WHITELIST) {
                return config.GENERAL_ASYNC_LIST.contains(name);
            } else {
                return !config.GENERAL_ASYNC_LIST.contains(name);
            }
        }
        return false;
    }

    private void handleChunkBlockPlace(Cancellable event, Player player, Block block, ChunkSnapshot chunkSnapshot, ItemStack is, boolean async, Limit limit) {
        int current = ChunkUtils.getAmountInChunk(block.getChunk(), chunkSnapshot, limit);
        int l = limit.getLimit();
        if (current > l) {
            if (!player.hasPermission(limit.getPermission())) {
                if (!isPassiveForPlayer(player, "block")) {
                    MessageUtils.sendMessage(player, "messages.limit_reached_custom",
                            "%limit%", NumberFormat.getIntegerInstance().format(l),
                            "%material%", StringUtils.capitalizeName(limit.getName()),
                            "%area%", "chunk");
                }
                if (async) {
                    simulateBreak(player, block, is);
                } else {
                    blockLocations.remove(block.getLocation());
                    event.setCancelled(true);
                }
                return;
            }
        }
        if (!isPassiveForPlayer(player, "block")) {
            sendMessage(player, limit.getName(), current, l);
        }
        blockLocations.remove(block.getLocation());
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();

        NotificationManager notificationManager = NotificationManager.getInstance();
        if (notificationManager != null) {
            notificationManager.refreshPersistent(player);
        }

        if (plugin.getConfiguration().GENERAL_UPDATES_CHECK) {
            if (player.hasPermission("insights.notification.update")) {
                UpdateCheckerTask.start(player);
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        if (!isChunkEqual(event.getFrom(), event.getTo())) {
            PlayerChunkMoveEvent chunkEnterEvent = new PlayerChunkMoveEvent(event.getPlayer(),
                    event.getFrom(),
                    event.getTo());
            Bukkit.getPluginManager().callEvent(chunkEnterEvent);
            if (chunkEnterEvent.isCancelled()) {
                event.setCancelled(true);
            }
        }
    }

    private boolean isChunkEqual(Location loc1, Location loc2) {
        return (loc1.getBlockX() >> 4) == (loc2.getBlockX() >> 4)
                && (loc1.getBlockZ() >> 4) == (loc2.getBlockZ() >> 4);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlayerChunkMove(PlayerChunkMoveEvent event) {
        Player player = event.getPlayer();
        String string = plugin.getSqLite().getAutoscan(player);
        Integer type = plugin.getSqLite().getAutoscanType(player);
        if (string != null && type != null) {
            List<String> strs = Arrays.asList(string.split(","));

            Chunk chunk = event.getToChunk();
            Limit limit = plugin.getConfiguration().getLimits().getLimit(string, player);
            if (type == 0 || limit == null) {
                ScanOptions scanOptions = new ScanOptions();
                scanOptions.setScanType(ScanType.CUSTOM);
                scanOptions.setEntityTypes(strs);
                scanOptions.setMaterials(strs);
                scanOptions.setWorld(chunk.getWorld());
                scanOptions.setPartialChunks(Collections.singletonList(PartialChunk.from(chunk)));

                Scanner.create(scanOptions)
                        .scan()
                        .whenComplete((ev, err) -> handleAutoScan(player, ev));
            } else {
                ChunkSnapshot chunkSnapshot = chunk.getChunkSnapshot();

                new BukkitRunnable() {
                    @Override
                    public void run() {
                        int count = ChunkUtils.getAmountInChunk(chunk, chunkSnapshot, limit);

                        double progress = ((double) count)/((double) limit.getLimit());
                        if (progress > 1 || progress < 0) progress = 1;
                        MessageUtils.sendSpecialMessage(player, "messages.autoscan.limit_entry", progress,
                                "%key%", StringUtils.capitalizeName(limit.getName()),
                                "%count%", NumberFormat.getInstance().format(count),
                                "%limit%", NumberFormat.getInstance().format(limit.getLimit()));
                    }
                }.runTask(plugin);
            }
        }
    }

    private void handleAutoScan(Player player, ScanCompleteEvent event) {
        TreeMap<String, Integer> counts = event.getScanResult().getCounts();

        if (counts.size() == 1) {
            Map.Entry<String, Integer> entry = counts.firstEntry();
            MessageUtils.sendSpecialMessage(player, "messages.autoscan.single_entry", 1.0,
                    "%key%", StringUtils.capitalizeName(entry.getKey()),
                    "%count%", NumberFormat.getInstance().format(entry.getValue()));
        } else {
            MessageUtils.sendMessage(player, "messages.autoscan.multiple_entries.header");
            for (String str : counts.keySet()) {
                MessageUtils.sendMessage(player, "messages.autoscan.multiple_entries.format",
                        "%entry%", StringUtils.capitalizeName(str),
                        "%count%", NumberFormat.getInstance().format(counts.get(str)));
            }
            MessageUtils.sendMessage(player, "messages.autoscan.multiple_entries.footer");
        }
    }

    private boolean isPassiveForPlayer(Player player, String what) {
        if (plugin.getConfiguration().GENERAL_NOTIFICATION_PASSIVE.contains(what)) {
            return !player.hasPermission("insights.check.passive." + what);
        }
        return false;
    }
}
