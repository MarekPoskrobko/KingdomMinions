package pl.marek.kingdomminions;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Container;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.*;

final class MenuManager {
    record Context(Location location, BlockFace face, Domain.Selection selection, UUID target) {}
    private final KingdomMinionsPlugin plugin;
    private final DataStore store;
    private final WorkerManager workers;
    private final NamespacedKey actionKey;
    private final Map<UUID, Context> contexts = new HashMap<>();
    private final Map<UUID, UUID> pendingNames = new java.util.concurrent.ConcurrentHashMap<>();

    MenuManager(KingdomMinionsPlugin plugin, DataStore store, WorkerManager workers) {
        this.plugin = plugin;
        this.store = store;
        this.workers = workers;
        this.actionKey = new NamespacedKey(plugin, "menu_action");
    }

    void open(Player player, Context context) {
        contexts.put(player.getUniqueId(), context);
        MenuHolder holder = new MenuHolder(player.getUniqueId());
        Inventory menu = Bukkit.createInventory(holder, 54, Component.text(Lang.text(player, "text.001"), NamedTextColor.DARK_RED));
        holder.inventory = menu;
        Domain.PlayerData data = store.get(player.getUniqueId());
        fill(menu);
        set(menu, 10, icon(Material.ARMOR_STAND, Lang.text(player, "text.002"), NamedTextColor.GREEN, "summon"));
        set(menu, 11, icon(Material.LEAD, Lang.text(player, "text.003"), NamedTextColor.GREEN, "follow"));
        set(menu, 12, icon(Material.COMPASS, Lang.text(player, "text.004"), NamedTextColor.AQUA, "move"));
        set(menu, 13, icon(Material.CLOCK, Lang.text(player, "text.005"), NamedTextColor.YELLOW, "pause"));
        set(menu, 14, icon(Material.BARRIER, Lang.text(player, "text.006"), NamedTextColor.RED, "cancel"));
        set(menu, 15, icon(Material.ENDER_CHEST, Lang.text(player, "text.007"), NamedTextColor.GOLD, "deliver_here"));
        set(menu, 16, icon(Material.ENDER_CHEST, Lang.text(player, "text.008"), NamedTextColor.GOLD, "deliver_owner"));

        set(menu, 19, icon(Material.IRON_PICKAXE, Lang.text(player, "text.009"), NamedTextColor.GRAY, "tunnel"));
        set(menu, 20, icon(Material.DEEPSLATE, Lang.text(player, "text.010"), NamedTextColor.GRAY, "dig"));
        set(menu, 21, icon(Material.DIAMOND_ORE, Lang.text(player, "text.011"), NamedTextColor.AQUA, "vein"));
        set(menu, 22, icon(Material.OAK_LOG, Lang.text(player, "text.012"), NamedTextColor.GREEN, "chop"));
        set(menu, 23, icon(Material.NETHER_WART_BLOCK, Lang.text(player, "text.013"), NamedTextColor.RED, "wart"));
        set(menu, 24, icon(Material.COBBLESTONE_STAIRS, stairName(player,data.stairs), NamedTextColor.YELLOW, "stairs"));
        set(menu, 25, icon(Material.SPYGLASS, Lang.text(player, "text.106", data.depth), NamedTextColor.YELLOW, "depth"));
        set(menu, 26, icon(Material.GRASS_BLOCK, Lang.text(player, "text.014"), NamedTextColor.GREEN, "flatten"));

        set(menu, 35, icon(Material.DIRT, Lang.text(player, "text.107", (data.flattenFillHoles ? Lang.text(player, "text.015") : Lang.text(player, "text.016"))), NamedTextColor.YELLOW, "flatten_fill"));
        set(menu, 18, icon(Material.STONE_BRICKS, Lang.text(player, "text.017"), NamedTextColor.GREEN, "wall"));
        set(menu, 29, icon(Material.COBBLESTONE, Lang.text(player, "text.108", (data.lining ? Lang.text(player, "text.018") : Lang.text(player, "text.019"))), NamedTextColor.YELLOW, "lining"));
        set(menu, 27, icon(Material.CHEST, Lang.text(player, "text.020"), NamedTextColor.GOLD, "delivery_point"));
        set(menu, 28, icon(Material.TORCH, Lang.text(player, "text.109", (data.lighting ? Lang.text(player, "text.021") : Lang.text(player, "text.022"))), NamedTextColor.YELLOW, "lighting"));
        set(menu, 37, icon(Material.REDSTONE_TORCH, Lang.text(player, "text.023"), NamedTextColor.YELLOW, "release"));
        set(menu, 38, icon(Material.SADDLE, Lang.text(player, "text.024"), NamedTextColor.LIGHT_PURPLE, "carry"));
        set(menu, 39, icon(Material.TARGET, Lang.text(player, "text.110", data.size), NamedTextColor.YELLOW, "size"));
        set(menu, 40, icon(Material.SKELETON_SKULL, Lang.text(player, "text.025"), NamedTextColor.RED, "dismiss"));
        set(menu, 41, icon(Material.WITHER_SKELETON_SKULL, Lang.text(player, "text.026"), NamedTextColor.DARK_RED, "dismiss_all"));
        set(menu, 42, icon(Material.OAK_SIGN, Lang.text(player, "text.027"), NamedTextColor.YELLOW, "wait"));
        set(menu, 43, icon(Material.OAK_HANGING_SIGN, Lang.text(player, "text.028"), NamedTextColor.YELLOW, "wait_all"));
        List<UUID> roster = workers.workerIds(player);
        for (int i = 0; i < Math.min(roster.size(), 4); i++) {
            UUID id = roster.get(i);
            ItemStack entry = icon(Material.NAME_TAG, (i + 1) + ". " + workers.workerName(player, id), NamedTextColor.AQUA, "rename:" + id);
            ItemMeta meta = entry.getItemMeta();
            meta.lore(List.of(Component.text(Lang.text(player, "text.029"), NamedTextColor.GRAY)));
            entry.setItemMeta(meta);
            set(menu, 46 + i, entry);
        }

        if (context.selection != null && context.selection.complete()) {
            set(menu, 4, info(Material.END_ROD, Lang.text(player, "text.111", context.selection.dx(), context.selection.dy(), context.selection.dz()), NamedTextColor.AQUA));
        } else {
            set(menu, 4, info(Material.PAPER, Lang.text(player, "text.112", data.size), NamedTextColor.GRAY));
        }
        player.openInventory(menu);
    }

    void refreshLanguage(Player player) {
        Context context=contexts.get(player.getUniqueId());
        if(context!=null && player.getOpenInventory().getTopInventory().getHolder() instanceof MenuHolder holder && holder.owner.equals(player.getUniqueId()))open(player,context);
    }

    void click(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof MenuHolder holder)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || !player.getUniqueId().equals(holder.owner)) return;
        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || clicked.getType().isAir()) return;
        String action = clicked.getItemMeta().getPersistentDataContainer().get(actionKey, PersistentDataType.STRING);
        if (action == null) return;
        if (action.startsWith("rename:")) {
            UUID worker = UUID.fromString(action.substring(7));
            if (workers.workerName(player, worker) == null) return;
            pendingNames.put(player.getUniqueId(), worker);
            player.closeInventory();
            player.sendMessage(Component.text(Lang.text(player, "text.030"), NamedTextColor.YELLOW));
            return;
        }
        Context context = contexts.get(player.getUniqueId());
        if (context == null) { player.closeInventory(); return; }
        Domain.PlayerData data = store.get(player.getUniqueId());
        try {
            switch (action) {
                case "summon" -> messageSummon(player, workers.summon(player, player.getLocation()), plugin.getConfig().getInt("max-workers", 4));
                case "follow" -> workers.follow(player);
                case "move" -> workers.move(player, context.location);
                case "pause" -> workers.togglePause(player);
                case "cancel" -> workers.cancel(player, true);
                case "dig" -> startDig(player, context, data);
                case "tunnel" -> startTunnel(player, context, data);
                case "lining" -> { data.lining = !data.lining; store.save(); open(player, context); return; }
                case "lighting" -> { data.lighting = !data.lighting; store.save(); open(player, context); return; }
                case "vein" -> startVein(player, context);
                case "chop" -> workers.setJob(player, JobBuilder.harvest(selection(context, data), data.depth, maxSteps(), false));
                case "wart" -> workers.setJob(player, JobBuilder.harvest(selection(context, data), data.depth, maxSteps(), true));
                case "flatten_fill" -> { data.flattenFillHoles = !data.flattenFillHoles; store.save(); open(player, context); return; }
                case "flatten" -> startFlatten(player, context);
                case "wall" -> {
                    if (context.selection == null || !context.selection.complete()) throw Lang.failure("text.031");
                    workers.setJob(player, JobBuilder.wall(context.selection, data.depth));
                }
                case "chest" -> setChest(player, context, data);
                case "delivery_point" -> workers.setDeliveryPoint(player,context.location);
                case "deliver_here" -> workers.deliverHere(player,context.location);
                case "deliver_owner" -> workers.deliverToOwner(player);
                case "stairs" -> { data.stairs = Domain.StairMode.values()[(data.stairs.ordinal() + 1) % Domain.StairMode.values().length]; store.save(); open(player, context); return; }
                case "depth" -> { data.depth = nextDepth(data.depth); store.save(); open(player, context); return; }
                case "size" -> { data.size = data.size >= 9 ? 1 : data.size + 2; store.save(); open(player, context); return; }
                case "dismiss_all" -> workers.dismissAll(player);
                case "dismiss" -> target(context).ifPresentOrElse(entity -> { if (!workers.dismiss(player, entity)) error(player, Lang.text(player, "text.032")); }, () -> error(player, Lang.text(player, "text.033")));
                case "release" -> target(context).ifPresentOrElse(entity -> workers.dropAndRelease(player, entity), () -> error(player, Lang.text(player, "text.033")));
                case "wait" -> target(context).ifPresentOrElse(entity -> { if (!workers.toggleWait(player, entity)) error(player, Lang.text(player, "text.032")); }, () -> error(player, Lang.text(player, "text.033")));
                case "wait_all" -> workers.stay(player);
                case "carry" -> { Entity target = target(context).orElse(player); if (!workers.carry(player, target)) error(player, Lang.text(player, "text.034")); }
                default -> { return; }
            }
            player.closeInventory();
            plugin.selections().clear(player);
        } catch (IllegalArgumentException exception) {
            error(player, Lang.message(player,exception));
        }
    }

    boolean isNaming(Player player) { return pendingNames.containsKey(player.getUniqueId()); }

    void nameFromChat(Player player, String message) {
        UUID worker = pendingNames.remove(player.getUniqueId());
        if (worker == null) return;
        if ((message.equalsIgnoreCase("anuluj") || message.equalsIgnoreCase("cancel"))) {
            player.sendActionBar(Component.text(Lang.text(player, "text.035"), NamedTextColor.YELLOW));
            return;
        }
        if (workers.rename(player, worker, message)) player.sendMessage(Component.text(Lang.text(player, "text.113", message.strip()), NamedTextColor.GREEN));
        else error(player, Lang.text(player, "text.036"));
    }

    private void startDig(Player player, Context context, Domain.PlayerData data) {
        Domain.Selection selected = selection(context, data);
        if (selected.face() != BlockFace.UP && selected.face() != BlockFace.DOWN) throw Lang.failure("text.037");
        if (data.stairs != Domain.StairMode.NONE && (selected.dx() < 3 || selected.dz() < 3)) throw Lang.failure("text.038");
        workers.setJob(player, JobBuilder.dig(selected, data.depth, data.stairs, maxSteps()));
    }

    private void startTunnel(Player player, Context context, Domain.PlayerData data) {
        Domain.Selection selected = selection(context, data);
        if (selected.face() == BlockFace.UP || selected.face() == BlockFace.DOWN) throw Lang.failure("text.039");
        Domain.Job job = JobBuilder.tunnel(selected, data.depth, maxSteps());
        job.lining = data.lining;
        job.lighting = data.lighting;
        workers.setJob(player, job);
    }

    private void startVein(Player player, Context context) {
        Block block = context.location.getBlock();
        if (!isOre(block.getType())) throw Lang.failure("text.040");
        workers.setJob(player, JobBuilder.vein(block, plugin.getConfig().getInt("work.vein-limit", 128)));
    }

    private void startFlatten(Player player, Context context) {
        if (context.selection == null || !context.selection.complete()) throw Lang.failure("text.041");
        Domain.Job job = JobBuilder.flatten(context.selection);
        job.flatten.fillHoles = store.get(player.getUniqueId()).flattenFillHoles;
        workers.setJob(player, job);
    }

    private void setChest(Player player, Context context, Domain.PlayerData data) {
        if (!(context.location.getBlock().getState() instanceof Container)) throw Lang.failure("text.042");
        assignChest(player, context.location.getBlock());
    }

    void assignChest(Player player, Block block) {
        if (!(block.getState() instanceof Container)) throw Lang.failure("text.042");
        Domain.PlayerData data = store.get(player.getUniqueId());
        data.chest = block.getLocation();
        data.deliveryPoint = null;
        store.save();
        store.audit("delivery_chest_set", player.getUniqueId(), block.getLocation().toVector().toString());
        player.sendActionBar(Component.text(Lang.text(player, "text.043"), NamedTextColor.GREEN));
    }

    private Domain.Selection selection(Context context, Domain.PlayerData data) {
        return context.selection != null && context.selection.complete() ? context.selection : JobBuilder.around(context.location, context.face, data.size);
    }

    private Optional<Entity> target(Context context) { return context.target == null ? Optional.empty() : Optional.ofNullable(Bukkit.getEntity(context.target)); }
    private int maxSteps() { return plugin.getConfig().getInt("work.max-queued-blocks", 32768); }
    static boolean isOre(Material material) { String name = material.name(); return name.endsWith("_ORE") || material == Material.ANCIENT_DEBRIS || material == Material.RAW_COPPER_BLOCK || material == Material.RAW_GOLD_BLOCK || material == Material.RAW_IRON_BLOCK; }
    private static int nextDepth(int current) { int[] values = {4,8,16,32,64,128,256}; for (int value : values) if (value > current) return value; return values[0]; }
    private static String stairName(Player player,Domain.StairMode mode) { return switch (mode) { case NONE -> Lang.text(player, "text.044"); case CLOCKWISE -> Lang.text(player, "text.045"); case COUNTERCLOCKWISE -> Lang.text(player, "text.046"); }; }
    private static void messageSummon(Player player, int result, int max) {
        if (result > 0) {
            player.sendActionBar(Component.text(Lang.text(player, "text.047"), NamedTextColor.GREEN));
            player.sendMessage(Component.text(Lang.text(player, "text.114", result, max), NamedTextColor.GREEN));
        } else error(player, result < 0 ? Lang.text(player, "text.115", max) : Lang.text(player, "text.048"));
    }
    private static void error(Player player, String message) { player.sendMessage(Component.text(message, NamedTextColor.RED)); }

    private ItemStack icon(Material material, String name, NamedTextColor color, String action) {
        ItemStack item = info(material, name, color);
        ItemMeta meta = item.getItemMeta();
        meta.getPersistentDataContainer().set(actionKey, PersistentDataType.STRING, action);
        item.setItemMeta(meta);
        return item;
    }
    private static ItemStack info(Material material, String name, NamedTextColor color) { ItemStack item = new ItemStack(material); ItemMeta meta = item.getItemMeta(); meta.displayName(Component.text(name, color)); item.setItemMeta(meta); return item; }
    private static void fill(Inventory inventory) { ItemStack pane = info(Material.BLACK_STAINED_GLASS_PANE, " ", NamedTextColor.BLACK); for (int i = 0; i < inventory.getSize(); i++) inventory.setItem(i, pane); }
    private static void set(Inventory inventory, int slot, ItemStack item) { inventory.setItem(slot, item); }

    private static final class MenuHolder implements InventoryHolder {
        private final UUID owner;
        private Inventory inventory;
        private MenuHolder(UUID owner) { this.owner = owner; }
        public Inventory getInventory() { return inventory; }
    }
}



