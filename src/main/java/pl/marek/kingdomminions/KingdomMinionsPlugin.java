package pl.marek.kingdomminions;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.command.*;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.*;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;

public final class KingdomMinionsPlugin extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {
    private NamespacedKey staffKey;
    private NamespacedKey staffOwnerKey;
    private DataStore store;
    private SelectionManager selections;
    private WorkerManager workers;
    private MenuManager menus;
    private final Map<UUID, OreClicks> oreClicks = new HashMap<>();
    private static final class OreClicks {
        final Location location;
        final org.bukkit.block.BlockFace face;
        int count = 1;
        OreClicks(Block block, org.bukkit.block.BlockFace face) { this.location = block.getLocation(); this.face = face; }
    }

    @Override public void onEnable() {
        saveDefaultConfig();
        Lang.load(this);
        getConfig().options().copyDefaults(true);
        if (getConfig().getInt("config-version", 0) < 2) {
            getConfig().set("config-version", 2);
            getConfig().set("selection.max-axis", 0);
        }
        if (getConfig().getInt("config-version", 0) < 3) {
            if (getConfig().getDouble("work.seconds-per-stone-block", 1) == 1.0)
                getConfig().set("work.seconds-per-stone-block", .7);
            if (getConfig().getInt("work.placement-ticks", 10) == 10)
                getConfig().set("work.placement-ticks", 7);
            getConfig().set("config-version", 3);
        }
        saveConfig();
        staffKey = new NamespacedKey(this, "staff_token");
        staffOwnerKey = new NamespacedKey(this, "staff_owner");
        store = new DataStore(this);
        store.load();
        selections = new SelectionManager(this);
        workers = new WorkerManager(this, store);
        menus = new MenuManager(this, store, workers);
        Bukkit.getPluginManager().registerEvents(this, this);
        PluginCommand command = Objects.requireNonNull(getCommand("minions"));
        command.setExecutor(this);
        command.setTabCompleter(this);
        Bukkit.getScheduler().runTaskTimer(this, selections::tick, 2L, 4L);
        Bukkit.getScheduler().runTaskTimer(this, workers::tick, 1L, 1L);
        long saveTicks = Math.max(20, getConfig().getLong("workers.save-interval-seconds", 30) * 20L);
        Bukkit.getScheduler().runTaskTimer(this, store::save, saveTicks, saveTicks);
        getLogger().info("KingdomMinions działa całkowicie po stronie serwera; gracze nie potrzebują moda.");
    }

    @EventHandler public void onJoin(PlayerJoinEvent event) {
        Bukkit.getScheduler().runTaskLater(this,() -> refreshLanguage(event.getPlayer()),20L);
    }
    @EventHandler public void onLocaleChange(PlayerLocaleChangeEvent event) {
        Bukkit.getScheduler().runTask(this,() -> refreshLanguage(event.getPlayer()));
    }
    private void refreshLanguage(Player player) {
        if(!player.isOnline())return;
        Domain.PlayerData data=store.get(player.getUniqueId());
        for(ItemStack item:player.getInventory().getContents()) {
            if(!isStaffLike(item))continue;
            ItemMeta meta=item.getItemMeta();
            String token=meta.getPersistentDataContainer().get(staffKey,PersistentDataType.STRING);
            String owner=meta.getPersistentDataContainer().get(staffOwnerKey,PersistentDataType.STRING);
            if(!Objects.equals(token,data.staffToken) || !player.getUniqueId().toString().equals(owner))continue;
            ItemMeta translated=createStaff(player,token).getItemMeta();
            meta.displayName(translated.displayName());meta.lore(translated.lore());item.setItemMeta(meta);
        }
        menus.refreshLanguage(player);
    }

    @Override public void onDisable() {
        if (workers != null) workers.releaseAllTickets();
        if (store != null) store.save();
    }

    SelectionManager selections() { return selections; }

    boolean isHoldingAuthorizedStaff(Player player) {
        return authorized(player, player.getInventory().getItemInMainHand()) || authorized(player, player.getInventory().getItemInOffHand());
    }

    private boolean authorized(Player player, ItemStack stack) {
        if (stack == null || stack.getType() != Material.BLAZE_ROD || !stack.hasItemMeta()) return false;
        ItemMeta meta = stack.getItemMeta();
        String token = meta.getPersistentDataContainer().get(staffKey, PersistentDataType.STRING);
        String owner = meta.getPersistentDataContainer().get(staffOwnerKey, PersistentDataType.STRING);
        Domain.PlayerData data = store.get(player.getUniqueId());
        return player.hasPermission("kingdomminions.use") && player.getUniqueId().toString().equals(owner) && data.granted() && data.staffToken.equals(token);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || !isStaffLike(event.getItem())) return;
        Player player = event.getPlayer();
        event.setCancelled(true);
        if (!authorized(player, event.getItem())) { error(player, Lang.text(player, "text.084")); return; }
        switch (event.getAction()) {
            case RIGHT_CLICK_BLOCK -> {
                Block clicked = event.getClickedBlock();
                if (clicked == null || event.getBlockFace() == null) return;
                if (!player.isSneaking() && (MenuManager.isOre(clicked.getType()) || Tag.LOGS.isTagged(clicked.getType()))) {
                    queueOreClick(player, clicked, event.getBlockFace());
                    return;
                }
                flushOreClicks(player);
                if (clicked.getState() instanceof Container) {
                    menus.assignChest(player, clicked);
                    return;
                }
                Domain.Selection active = selections.get(player);
                if (player.isSneaking()) {
                    if (active != null && !active.complete()) { selections.clear(player); player.sendActionBar(Component.text(Lang.text(player, "text.085"), NamedTextColor.YELLOW)); return; }
                    selections.clear(player);
                    menus.open(player, new MenuManager.Context(clicked.getLocation(), event.getBlockFace(), null, null));
                    return;
                }
                selectBlock(player, clicked, event.getBlockFace());
            }
            case RIGHT_CLICK_AIR -> { flushOreClicks(player); menus.open(player, new MenuManager.Context(player.getLocation().getBlock().getLocation(), org.bukkit.block.BlockFace.UP, null, null)); }
            default -> {}
        }
    }

    private void queueOreClick(Player player, Block block, org.bukkit.block.BlockFace face) {
        UUID id = player.getUniqueId();
        OreClicks clicks = oreClicks.get(id);
        if (clicks != null && clicks.location.equals(block.getLocation())) {
            if (++clicks.count == 3) {
                oreClicks.remove(id);
                if (MenuManager.isOre(block.getType())) workers.setJob(player, JobBuilder.vein(block, getConfig().getInt("work.vein-limit", 128)));
                else if (Tag.LOGS.isTagged(block.getType())) workers.setJob(player, JobBuilder.chop(new Domain.Selection(block.getLocation(), block.getLocation(), org.bukkit.block.BlockFace.UP), getConfig().getInt("work.max-queued-blocks", 32768)));
            }
            return;
        }
        flushOreClicks(player);
        OreClicks fresh = new OreClicks(block, face);
        oreClicks.put(id, fresh);
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (oreClicks.get(id) != fresh) return;
            if (!player.isOnline() || !authorized(player, player.getInventory().getItemInMainHand()) || !player.getWorld().equals(fresh.location.getWorld())) { oreClicks.remove(id); return; }
            flushOreClicks(player);
        }, 16L);
    }

    private void flushOreClicks(Player player) {
        OreClicks clicks = oreClicks.remove(player.getUniqueId());
        if (clicks == null) return;
        for (int i = 0; i < clicks.count; i++) selectBlock(player, clicks.location.getBlock(), clicks.face);
    }

    private void selectBlock(Player player, Block block, org.bukkit.block.BlockFace face) {
        if (selections.click(player, block, face)) {
            Domain.Selection complete = selections.get(player);
            menus.open(player, new MenuManager.Context(complete.second(), complete.face(), complete, null));
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onEntityInteract(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        Player player = event.getPlayer();
        Entity target = event.getRightClicked();
        ItemStack held = player.getInventory().getItemInMainHand();
        DyeColor dye = dyeColor(held);
        if (dye != null && workers.recolor(player, target, dye)) {
            event.setCancelled(true);
            if (player.getGameMode() != GameMode.CREATIVE) held.setAmount(held.getAmount() - 1);
            player.sendActionBar(Component.text(Lang.text(player, "text.086"), NamedTextColor.GREEN));
            return;
        }
        if (!isHoldingAuthorizedStaff(player)) return;
        event.setCancelled(true);
        menus.open(player, new MenuManager.Context(target.getLocation().getBlock().getLocation(), org.bukkit.block.BlockFace.UP, null, target.getUniqueId()));
    }

    @EventHandler public void onMenu(InventoryClickEvent event) { menus.click(event); }
    @EventHandler public void onNamingChat(AsyncPlayerChatEvent event) {
        if (!menus.isNaming(event.getPlayer())) return;
        event.setCancelled(true);
        String name = event.getMessage();
        Bukkit.getScheduler().runTask(this, () -> menus.nameFromChat(event.getPlayer(), name));
    }
    @EventHandler public void onQuit(PlayerQuitEvent event) { selections.clear(event.getPlayer()); oreClicks.remove(event.getPlayer().getUniqueId()); }
    @EventHandler public void onWorldChange(PlayerChangedWorldEvent event) { selections.clear(event.getPlayer()); oreClicks.remove(event.getPlayer().getUniqueId()); }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDamage(EntityDamageEvent event) { if (workers.isWorker(event.getEntity())) event.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onCombust(EntityCombustEvent event) { if (workers.isWorker(event.getEntity())) event.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onTarget(EntityTargetLivingEntityEvent event) { if (workers.isWorker(event.getEntity()) || event.getTarget() != null && workers.isWorker(event.getTarget())) event.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGHEST) public void onTransform(EntityTransformEvent event) { if (workers.isWorker(event.getEntity())) event.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGHEST) public void onPortal(EntityPortalEvent event) { if (workers.isWorker(event.getEntity())) event.setCancelled(true); }
    @EventHandler public void onDeath(EntityDeathEvent event) {
        if (workers.isWorker(event.getEntity())) {
            workers.handleDeath(event.getEntity());
            event.getDrops().clear();
            event.setDroppedExp(0);
        }
    }
    @EventHandler public void onEntitiesLoad(EntitiesLoadEvent event) {
        for (Entity entity : event.getEntities()) {
            if (!workers.isWorker(entity)) continue;
            UUID owner = workers.ownerOf(entity);
            if (owner == null || !store.get(owner).workers.contains(entity.getUniqueId())) workers.purgeOrphan(entity);
        }
    }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("menu")) {
            if (!(sender instanceof Player player)) { sender.sendMessage(Lang.text(sender, "text.087")); return true; }
            if (!isHoldingAuthorizedStaff(player)) { error(player, Lang.text(player, "text.088")); return true; }
            menus.open(player, new MenuManager.Context(player.getLocation().getBlock().getLocation(), org.bukkit.block.BlockFace.UP, null, null));
            return true;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (Set.of("stay", "zostan", "zostancie", "follow").contains(sub)) {
            if (!(sender instanceof Player player)) { sender.sendMessage(Lang.text(sender, "text.087")); return true; }
            if (!isHoldingAuthorizedStaff(player)) { error(player, Lang.text(player, "text.088")); return true; }
            if (sub.equals("follow")) workers.follow(player); else workers.stay(player);
            return true;
        }
        if (sub.equals("cancel")) {
            if (sender instanceof Player player) workers.cancel(player, true); else sender.sendMessage(Lang.text(sender, "text.087"));
            return true;
        }
        if (sub.equals("deliver")) {
            if (sender instanceof Player player) workers.deliverToOwner(player); else sender.sendMessage(Lang.text(sender, "text.087"));
            return true;
        }
        if (sub.equals("status")) {
            if (sender instanceof Player player) workers.status(player); else sender.sendMessage(Lang.text(sender, "text.087"));
            return true;
        }
        if (sub.equals("repair")) {
            if (!(sender instanceof Player player)) { sender.sendMessage(Lang.text(sender, "text.087")); return true; }
            if (!isHoldingAuthorizedStaff(player)) { error(player, Lang.text(player, "text.088")); return true; }
            int repaired = workers.repair(player);
            player.sendMessage(Component.text(repaired == 0 ? Lang.text(player, "text.089") : Lang.text(player, "text.122", repaired), repaired == 0 ? NamedTextColor.GREEN : NamedTextColor.YELLOW));
            return true;
        }
        if (sub.equals("depth") || sub.equals("size")) {
            if (!(sender instanceof Player player) || args.length < 2) return false;
            try {
                int value = Integer.parseInt(args[1]);
                Domain.PlayerData data = store.get(player.getUniqueId());
                if (sub.equals("depth")) data.depth = Math.max(1, value);
                else data.size = Math.max(1, Math.min(9, value));
                store.save();
                player.sendActionBar(Component.text(Lang.text(player, "text.123", sub, value), NamedTextColor.GREEN));
            } catch (NumberFormatException exception) { error(player, Lang.text(player, "text.090")); }
            return true;
        }
        if (!Set.of("grant", "replace", "revoke").contains(sub)) return false;
        if (!sender.hasPermission("kingdomminions.admin")) { sender.sendMessage(Component.text(Lang.text(sender, "text.091"), NamedTextColor.RED)); return true; }
        if (args.length < 2) return false;
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) { sender.sendMessage(Component.text(Lang.text(sender, "text.092"), NamedTextColor.RED)); return true; }
        Domain.PlayerData data = store.get(target.getUniqueId());
        if (sub.equals("revoke")) {
            workers.dismissAll(target);
            data.staffToken = null;
            removeStaffs(target);
            store.save();
            store.audit("staff_revoked", senderId(sender), target.getUniqueId().toString());
            sender.sendMessage(Component.text(Lang.text(sender, "text.124", target.getName()), NamedTextColor.YELLOW));
            return true;
        }
        if (sub.equals("grant") && data.granted()) { sender.sendMessage(Component.text(Lang.text(sender, "text.093"), NamedTextColor.RED)); return true; }
        if (target.getInventory().firstEmpty() < 0) { sender.sendMessage(Component.text(Lang.text(sender, "text.094"), NamedTextColor.RED)); return true; }
        if (sub.equals("replace")) removeStaffs(target);
        data.staffToken = UUID.randomUUID().toString();
        target.getInventory().addItem(createStaff(target, data.staffToken));
        store.save();
        store.audit(sub.equals("replace") ? "staff_replaced" : "staff_granted", senderId(sender), target.getUniqueId().toString());
        target.sendMessage(Component.text(Lang.text(target, "text.095"), NamedTextColor.GOLD));
        sender.sendMessage(Component.text(Lang.text(sender, "text.125", target.getName()), NamedTextColor.GREEN));
        return true;
    }

    @Override public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) return List.of("menu", "status", "stay", "zostan", "zostancie", "follow", "repair", "cancel", "deliver", "depth", "size", "grant", "replace", "revoke").stream().filter(s -> s.startsWith(args[0].toLowerCase(Locale.ROOT))).toList();
        if (args.length == 2 && Set.of("grant", "replace", "revoke").contains(args[0].toLowerCase(Locale.ROOT))) return Bukkit.getOnlinePlayers().stream().map(Player::getName).filter(s -> s.toLowerCase(Locale.ROOT).startsWith(args[1].toLowerCase(Locale.ROOT))).toList();
        return List.of();
    }

    private ItemStack createStaff(Player owner, String token) {
        ItemStack item = new ItemStack(Material.BLAZE_ROD);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(Lang.text(owner, "text.001"), NamedTextColor.GOLD));
        meta.lore(List.of(Component.text(Lang.text(owner, "text.126", owner.getName()), NamedTextColor.GRAY), Component.text(Lang.text(owner, "text.096"), NamedTextColor.AQUA), Component.text(Lang.text(owner, "text.097"), NamedTextColor.GOLD), Component.text(Lang.text(owner, "text.098"), NamedTextColor.YELLOW)));
        meta.setEnchantmentGlintOverride(true);
        meta.getPersistentDataContainer().set(staffKey, PersistentDataType.STRING, token);
        meta.getPersistentDataContainer().set(staffOwnerKey, PersistentDataType.STRING, owner.getUniqueId().toString());
        item.setItemMeta(meta);
        return item;
    }

    private void removeStaffs(Player player) {
        for (ItemStack item : player.getInventory().getContents()) if (isStaffLike(item)) player.getInventory().remove(item);
    }

    private boolean isStaffLike(ItemStack stack) { return stack != null && stack.getType() == Material.BLAZE_ROD && stack.hasItemMeta() && stack.getItemMeta().getPersistentDataContainer().has(staffKey, PersistentDataType.STRING); }
    private static DyeColor dyeColor(ItemStack stack) {
        if (stack == null || !stack.getType().name().endsWith("_DYE")) return null;
        try { return DyeColor.valueOf(stack.getType().name().substring(0, stack.getType().name().length() - 4)); }
        catch (IllegalArgumentException ignored) { return null; }
    }
    private static UUID senderId(CommandSender sender) { return sender instanceof Player player ? player.getUniqueId() : new UUID(0, 0); }
    private static void error(Player player, String message) { player.sendMessage(Component.text(message, NamedTextColor.RED)); }
}
