package pl.marek.kingdomminions;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Container;
import org.bukkit.block.data.Directional;
import org.bukkit.entity.*;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.*;

final class WorkerManager {
    private final KingdomMinionsPlugin plugin;
    private final DataStore store;
    private final NamespacedKey ownerKey, workerKey, waitingKey;
    private final Map<UUID, Set<Chunk>> chunkTickets = new HashMap<>();
    private final Map<UUID, MovementProgress> movementProgress = new HashMap<>();
    private final Set<UUID> normalizedWorkers = new HashSet<>();
    private final Set<UUID> deliveryBlocked = new HashSet<>();
    private long tick;
    private final Map<Location, Long> settlingBlocks = new HashMap<>();
    private final Map<UUID, Set<Location>> flattenTreeLogs = new HashMap<>();
    private final Map<UUID, Long> lightingChecks = new HashMap<>();
    private final Map<UUID, Location> safeFooting = new HashMap<>();
    private final Map<UUID, Location> accessCuts = new HashMap<>();
    private final Map<UUID, Integer> accessCutTicks = new HashMap<>();

    WorkerManager(KingdomMinionsPlugin plugin, DataStore store) {
        this.plugin = plugin;
        this.store = store;
        ownerKey = new NamespacedKey(plugin, "owner");
        workerKey = new NamespacedKey(plugin, "worker");
        waitingKey = new NamespacedKey(plugin, "waiting");
    }

    boolean isWorker(Entity entity) { return entity.getPersistentDataContainer().has(workerKey, PersistentDataType.BYTE); }
    UUID ownerOf(Entity entity) {
        String raw = entity.getPersistentDataContainer().get(ownerKey, PersistentDataType.STRING);
        try { return raw == null ? null : UUID.fromString(raw); } catch (IllegalArgumentException ignored) { return null; }
    }

    int summon(Player owner, Location requested) {
        Domain.PlayerData data = store.get(owner.getUniqueId());
        reconcile(data);
        int max = plugin.getConfig().getInt("max-workers", 4);
        if (data.workers.size() >= max) return -1;
        Location spawn = safeNear(requested.clone().add(0, 1, 0));
        if (spawn == null) return 0;
        int number = data.workers.size() + 1;
        Villager helper = spawn.getWorld().spawn(spawn, Villager.class, worker -> {
            worker.setBaby();
            worker.setAgeLock(true);
            worker.setProfession(Villager.Profession.TOOLSMITH);
            worker.setCanPickupItems(false);
            worker.setRemoveWhenFarAway(false);
            worker.setPersistent(true);
            worker.setInvulnerable(true);
            worker.setSilent(true);
            worker.customName(Component.text("Minion " + number, NamedTextColor.GOLD));
            worker.setCustomNameVisible(true);
            worker.getPersistentDataContainer().set(workerKey, PersistentDataType.BYTE, (byte) 1);
            worker.getPersistentDataContainer().set(ownerKey, PersistentDataType.STRING, owner.getUniqueId().toString());
            dress(worker, number);
        });
        data.workers.add(helper.getUniqueId());
        data.workerLocations.put(helper.getUniqueId(), helper.getLocation());
        data.follow = true;
        spawn.getWorld().spawnParticle(Particle.PORTAL, spawn.clone().add(0, .8, 0), 42, .45, .7, .45, .12);
        spawn.getWorld().playSound(spawn, Sound.ENTITY_ILLUSIONER_MIRROR_MOVE, .8f, 1.15f);
        store.audit("worker_summoned", owner.getUniqueId(), helper.getUniqueId() + " at " + format(spawn));
        store.save();
        return number;
    }

    void setJob(Player owner, Domain.Job job) {
        Domain.PlayerData data = store.get(owner.getUniqueId());
        reconcile(data);
        for (UUID id : data.workers) {
            Location saved = data.workerLocations.get(id);
            if (saved != null && saved.getWorld() != null) saved.getChunk().load();
        }
        if (loadedWorkers(data).isEmpty()) {
            owner.sendMessage(Component.text(Lang.text(owner, "text.049"), NamedTextColor.RED));
            return;
        }
        cancel(owner, false);
        World jobWorld = jobWorld(job);
        if (data.chest != null && jobWorld != null && !jobWorld.equals(data.chest.getWorld()))
            owner.sendMessage(Component.text(Lang.text(owner, "text.050"), NamedTextColor.YELLOW));
        if(data.job!=null) flattenTreeLogs.remove(data.job.id);
        data.job = job;
        job.lighting = data.lighting;
        job.lining = (job.kind == Domain.JobKind.TUNNEL || job.kind == Domain.JobKind.DIG) && data.lining;
        data.follow = false;
        data.rally = null;
        bringWorkersToJob(owner, data, job);
        setOwnerWorkersAi(data, true);
        store.audit("job_started", owner.getUniqueId(), job.kind + " remaining=" + job.remainingEstimate() + " id=" + job.id);
        owner.sendMessage(Component.text(Lang.text(owner, "text.116", polishJob(owner,job.kind), job.remainingEstimate()), NamedTextColor.GREEN));
        store.save();
    }

    Domain.Job job(Player owner) { return store.get(owner.getUniqueId()).job; }

    private void bringWorkersToJob(Player owner, Domain.PlayerData data, Domain.Job job) {
        Location anchor = null;
        for (Entity entity : loadedWorkers(data)) {
            if (!(entity instanceof Mob worker)) continue;
            worker.getPersistentDataContainer().set(waitingKey, PersistentDataType.BYTE, (byte) 0);
            worker.getPathfinder().stopPathfinding();
            movementProgress.remove(worker.getUniqueId());
            Domain.WorkStep step = job.assign(worker.getUniqueId());
            if (step != null) anchor = step.location();
            if (anchor == null) continue;
            anchor.getChunk().load();
            Location destination = job.kind == Domain.JobKind.FLATTEN ? flattenLanding(job, step) : safeNear(anchor);
            if (destination == null && anchor.getWorld().equals(owner.getWorld())) destination = safeNear(owner.getLocation());
            if (destination == null) {
                notify(owner, Lang.text(owner, "text.051"), NamedTextColor.YELLOW);
                continue;
            }
            holdChunks(worker.getUniqueId(), worker.getLocation(), destination);
            if (worker.teleport(destination)) data.workerLocations.put(worker.getUniqueId(), worker.getLocation());
        }
    }

    void togglePause(Player owner) {
        Domain.PlayerData data = store.get(owner.getUniqueId());
        if (data.job == null) {
            data.follow = false;
            stopPaths(owner.getUniqueId());
            owner.sendActionBar(Component.text(Lang.text(owner, "text.052"), NamedTextColor.YELLOW));
            store.save();
            return;
        }
        data.job.paused = !data.job.paused;
        if(!data.job.paused) wakeWorkers(data);
        setOwnerWorkersAi(data, !data.job.paused);
        if (data.job.paused) stopPaths(owner.getUniqueId());
        owner.sendActionBar(Component.text(data.job.paused ? Lang.text(owner, "text.053") : Lang.text(owner, "text.054"), data.job.paused ? NamedTextColor.YELLOW : NamedTextColor.GREEN));
        store.audit(data.job.paused ? "job_paused" : "job_resumed", owner.getUniqueId(), data.job.id.toString());
        store.save();
    }

    void cancel(Player owner, boolean notify) {
        Domain.PlayerData data = store.get(owner.getUniqueId());
        Domain.Job removed = data.job;
        if(removed!=null) flattenTreeLogs.remove(removed.id);
        data.job = null;
        data.deliveryOrigins.clear();
        if (notify) { data.deliverToOwner.clear();data.deliveryOnce.clear(); }
        for (UUID worker : data.workers) releaseTickets(worker);
        setOwnerWorkersAi(data, true);
        stopPaths(owner.getUniqueId());
        if (notify) for (Entity entity : loadedWorkers(data))
            entity.getPersistentDataContainer().set(waitingKey, PersistentDataType.BYTE, (byte) 0);
        if (removed != null) store.audit("job_cancelled", owner.getUniqueId(), removed.id.toString());
        if (notify) owner.sendActionBar(Component.text(data.chest == null ? Lang.text(owner, "text.055") : Lang.text(owner, "text.056"), NamedTextColor.YELLOW));
        store.save();
    }

    void follow(Player owner) {
        cancel(owner, false);
        Domain.PlayerData data = store.get(owner.getUniqueId());
        data.follow = true;
        data.rally = null;
        for (Entity worker : loadedWorkers(data)) worker.getPersistentDataContainer().set(waitingKey, PersistentDataType.BYTE, (byte) 0);
        setOwnerWorkersAi(data, true);
        for (Entity worker : loadedWorkers(data)) worker.eject();
        owner.sendActionBar(Component.text(Lang.text(owner, "text.057"), NamedTextColor.GREEN));
        store.audit("workers_follow", owner.getUniqueId(), "count=" + data.workers.size());
        store.save();
    }

    void stay(Player owner) {
        Domain.PlayerData data = store.get(owner.getUniqueId());
        data.follow = false;
        data.rally = null;
        for (UUID id : data.workers) {
            Location saved = data.workerLocations.get(id);
            if (saved != null) saved.getChunk().load();
        }
        for (Entity worker : loadedWorkers(data)) {
            worker.getPersistentDataContainer().set(waitingKey, PersistentDataType.BYTE, (byte) 1);
            data.workerLocations.put(worker.getUniqueId(), worker.getLocation());
            if (worker instanceof Mob mob) { mob.getPathfinder().stopPathfinding(); mob.setAI(false); }
        }
        stopPaths(owner.getUniqueId());
        store.audit("workers_stay", owner.getUniqueId(), "count=" + data.workers.size());
        store.save();
        owner.sendMessage(Component.text(Lang.text(owner, "text.058"), NamedTextColor.GREEN));
    }

    private void wakeWorkers(Domain.PlayerData data) {
        for(Location location:data.workerLocations.values()) if(location!=null && location.getWorld()!=null)location.getChunk().load();
        for(Entity entity:loadedWorkers(data)) {
            entity.getPersistentDataContainer().set(waitingKey,PersistentDataType.BYTE,(byte)0);
            if(entity instanceof Mob mob) { mob.setAI(true);mob.getPathfinder().stopPathfinding(); }
        }
    }

    void move(Player owner, Location destination) {
        cancel(owner, false);
        Domain.PlayerData data = store.get(owner.getUniqueId());
        data.follow = false;
        data.rally = destination.clone().add(.5, 1, .5);
        wakeWorkers(data);
        setOwnerWorkersAi(data, true);
        store.audit("workers_move", owner.getUniqueId(), format(destination));
        store.save();
    }

    void dismissAll(Player owner) {
        cancel(owner, false);
        Domain.PlayerData data = store.get(owner.getUniqueId());
        for (UUID id : new ArrayList<>(data.workers)) {
            Entity entity = Bukkit.getEntity(id);
            if (entity != null) {
                dropBackpack(entity);
                entity.remove();
            } else {
                for (ItemStack item : store.takeItems(id)) owner.getWorld().dropItemNaturally(owner.getLocation(), item);
            }
            releaseTickets(id);
        }
        int count = data.workers.size();
        data.workers.clear();
        data.workerLocations.clear();
        data.workerNames.clear();
        data.deliverToOwner.clear();data.deliveryOnce.clear();
        store.audit("workers_dismissed_all", owner.getUniqueId(), "count=" + count);
        store.save();
    }

    boolean dismiss(Player owner, Entity entity) {
        if (!isWorker(entity) || !owner.getUniqueId().equals(ownerOf(entity))) return false;
        Domain.PlayerData data = store.get(owner.getUniqueId());
        data.workers.remove(entity.getUniqueId());
        data.workerLocations.remove(entity.getUniqueId());
        data.workerNames.remove(entity.getUniqueId());
        data.deliverToOwner.remove(entity.getUniqueId());data.deliveryOnce.remove(entity.getUniqueId());
        if (data.job != null) data.job.release(entity.getUniqueId());
        releaseTickets(entity.getUniqueId());
        entity.eject();
        dropBackpack(entity);
        entity.remove();
        store.audit("worker_dismissed", owner.getUniqueId(), entity.getUniqueId().toString());
        store.save();
        return true;
    }

    void dropAndRelease(Player owner, Entity worker) {
        if (!owner.getUniqueId().equals(ownerOf(worker))) return;
        worker.eject();
        dropBackpack(worker);
        owner.sendActionBar(Component.text(Lang.text(owner, "text.059"), NamedTextColor.YELLOW));
        store.save();
    }

    boolean toggleWait(Player owner, Entity entity) {
        if (!isWorker(entity) || !owner.getUniqueId().equals(ownerOf(entity))) return false;
        boolean waiting = isWaiting(entity);
        entity.getPersistentDataContainer().set(waitingKey, PersistentDataType.BYTE, waiting ? (byte) 0 : (byte) 1);
        if (entity instanceof Mob mob) {
            mob.getPathfinder().stopPathfinding();
            Domain.Job job = store.get(owner.getUniqueId()).job;
            mob.setAI(waiting && (job == null || !job.paused));
        }
        owner.sendActionBar(Component.text(waiting ? Lang.text(owner, "text.060") : Lang.text(owner, "text.061"), NamedTextColor.YELLOW));
        return true;
    }

    void purgeOrphan(Entity entity) {
        UUID owner = ownerOf(entity);
        if (owner != null) {
            Domain.PlayerData data = store.get(owner);
            data.workerLocations.remove(entity.getUniqueId());
            if (data.job != null) data.job.release(entity.getUniqueId());
        }
        releaseTickets(entity.getUniqueId());
        dropBackpack(entity);
        entity.remove();
        store.save();
    }

    void handleDeath(Entity entity) {
        UUID owner = ownerOf(entity);
        if (owner == null) return;
        Domain.PlayerData data = store.get(owner);
        data.workers.remove(entity.getUniqueId());
        data.workerLocations.remove(entity.getUniqueId());
        data.workerNames.remove(entity.getUniqueId());
        data.deliverToOwner.remove(entity.getUniqueId());data.deliveryOnce.remove(entity.getUniqueId());
        if (data.job != null) data.job.release(entity.getUniqueId());
        releaseTickets(entity.getUniqueId());
        dropBackpack(entity);
        store.audit("worker_lost", owner, entity.getUniqueId().toString());
        store.save();
    }

    int repair(Player owner) {
        Domain.PlayerData data = store.get(owner.getUniqueId());
        int repaired = 0;
        for (UUID id : new ArrayList<>(data.workers)) {
            Entity entity = Bukkit.getEntity(id);
            Location saved = data.workerLocations.get(id);
            if (entity == null && saved != null && saved.getWorld() != null) {
                int centerX = saved.getBlockX() >> 4, centerZ = saved.getBlockZ() >> 4;
                for (int dx = -1; dx <= 1 && entity == null; dx++) for (int dz = -1; dz <= 1 && entity == null; dz++) {
                    saved.getWorld().getChunkAt(centerX + dx, centerZ + dz).load();
                    entity = Bukkit.getEntity(id);
                }
            }
            if (entity != null && entity.isValid() && data.owner.equals(ownerOf(entity))) {
                data.workerLocations.put(id, entity.getLocation());
                continue;
            }
            if (data.job != null) data.job.release(id);
            for (ItemStack item : store.takeItems(id)) owner.getWorld().dropItemNaturally(owner.getLocation(), item);
            data.workers.remove(id);
            data.workerLocations.remove(id);
            releaseTickets(id);
            repaired++;
        }
        if (repaired > 0) {
            store.audit("workers_repaired", owner.getUniqueId(), "removed_missing=" + repaired);
            store.save();
        }
        return repaired;
    }

    boolean carry(Player owner, Entity target) {
        Domain.PlayerData data = store.get(owner.getUniqueId());
        List<Entity> workers = loadedWorkers(data);
        boolean allowed = target.getUniqueId().equals(owner.getUniqueId()) || target instanceof Tameable tameable && owner.getUniqueId().equals(tameable.getOwnerUniqueId());
        if (workers.isEmpty() || !allowed) return false;
        Entity worker = workers.stream().min(Comparator.comparingDouble(e -> e.getLocation().distanceSquared(target.getLocation()))).orElse(null);
        return worker != null && worker.addPassenger(target);
    }

    boolean recolor(Player owner, Entity entity, DyeColor color) {
        if (!isWorker(entity) || !owner.getUniqueId().equals(ownerOf(entity)) || !(entity instanceof LivingEntity living) || living.getEquipment() == null) return false;
        ItemStack chest = living.getEquipment().getChestplate();
        if (chest == null || !(chest.getItemMeta() instanceof LeatherArmorMeta meta)) return false;
        meta.setColor(color.getColor());
        chest.setItemMeta(meta);
        living.getEquipment().setChestplate(chest);
        store.audit("worker_recolored", owner.getUniqueId(), entity.getUniqueId() + " color=" + color.name());
        return true;
    }

    void status(Player owner) {
        Domain.PlayerData data = store.get(owner.getUniqueId());
        Domain.Job job = data.job;
        long items = data.workers.stream().mapToLong(id -> store.items(id).stream().mapToInt(ItemStack::getAmount).sum()).sum();
        String chest = data.deliveryPoint!=null ? Lang.text(owner,"delivery.point",format(data.deliveryPoint)) : data.chest == null ? Lang.text(owner, "text.062") : Lang.text(owner, "text.117", format(data.chest));
        String work = job == null ? Lang.text(owner, "text.063") : Lang.text(owner, "text.118", polishJob(owner,job.kind), (job.paused ? Lang.text(owner, "text.064") : ""), job.remainingEstimate());
        owner.sendMessage(Component.text(Lang.text(owner, "text.119", loadedWorkers(data).size(), data.workers.size(), work, items, chest), NamedTextColor.AQUA));
    }

    void setDeliveryPoint(Player owner, Location point) {
        Domain.PlayerData data=store.get(owner.getUniqueId());
        data.deliveryPoint=point.getBlock().getLocation();data.chest=null;
        store.audit("delivery_point_set",data.owner,format(data.deliveryPoint));store.save();
        owner.sendActionBar(Component.text(Lang.text(owner, "text.065"),NamedTextColor.GREEN));
    }

    int deliverHere(Player owner, Location point) {
        Domain.PlayerData data=store.get(owner.getUniqueId());
        int count=0;
        for(Location location:data.workerLocations.values())if(location!=null && location.getWorld()!=null)location.getChunk().load();
        for(UUID id:data.workers)if(!store.items(id).isEmpty()) {
            data.deliveryOnce.put(id,point.getBlock().getLocation());data.deliverToOwner.remove(id);count++;
            Entity entity=Bukkit.getEntity(id);
            if(entity instanceof Mob mob) {
                entity.getPersistentDataContainer().set(waitingKey,PersistentDataType.BYTE,(byte)0);mob.setAI(true);mob.getPathfinder().stopPathfinding();
                if(data.job!=null && !data.job.workFinished)data.deliveryOrigins.putIfAbsent(id,mob.getLocation().clone());
            }
        }
        store.audit("delivery_here_requested",data.owner,"workers="+count+" point="+format(point));store.save();
        owner.sendActionBar(Component.text(count==0?Lang.text(owner, "text.066"):Lang.text(owner, "text.067"),NamedTextColor.GREEN));
        return count;
    }

    int deliverToOwner(Player owner) {
        Domain.PlayerData data = store.get(owner.getUniqueId());
        int count = 0;
        for(Location location:data.workerLocations.values()) if(location!=null && location.getWorld()!=null)location.getChunk().load();
        for (UUID id : data.workers) if (!store.items(id).isEmpty()) {
            data.deliverToOwner.add(id);data.deliveryOnce.remove(id);
            count++;
            Entity entity = Bukkit.getEntity(id);
            if (entity instanceof Mob mob) {
                entity.getPersistentDataContainer().set(waitingKey, PersistentDataType.BYTE, (byte) 0);
                mob.setAI(true);
                mob.getPathfinder().stopPathfinding();
                if(data.job!=null && !data.job.workFinished)data.deliveryOrigins.putIfAbsent(id,mob.getLocation().clone());
            }
        }
        if (count == 0) owner.sendActionBar(Component.text(Lang.text(owner, "text.068"), NamedTextColor.YELLOW));
        else owner.sendActionBar(Component.text(Lang.text(owner, "text.069"), NamedTextColor.GREEN));
        store.audit("delivery_owner_requested", data.owner, "workers=" + count);
        store.save();
        return count;
    }

    List<UUID> workerIds(Player owner) { return List.copyOf(store.get(owner.getUniqueId()).workers); }

    String workerName(Player owner, UUID worker) {
        Domain.PlayerData data = store.get(owner.getUniqueId());
        if (!data.workers.contains(worker)) return null;
        return data.workerNames.getOrDefault(worker, "Minion " + (data.workers.indexOf(worker) + 1));
    }

    boolean rename(Player owner, UUID worker, String name) {
        Domain.PlayerData data = store.get(owner.getUniqueId());
        if (!data.workers.contains(worker) || name == null || name.isBlank() || name.length() > 24) return false;
        String clean = name.strip();
        data.workerNames.put(worker, clean);
        Entity entity = Bukkit.getEntity(worker);
        if (entity != null) entity.customName(Component.text(clean, NamedTextColor.GOLD));
        store.audit("worker_renamed", data.owner, worker + " name=" + clean);
        store.save();
        return true;
    }

    void tick() {
        tick++;
        for (Domain.PlayerData data : store.all().values()) {
            Player owner = Bukkit.getPlayer(data.owner);
            try {
                reconcile(data);
                ensureWorkersLoaded(data);
                updateLocations(data);
                Domain.Job job = data.job;
                if ((job == null || job.workFinished || job.kind == Domain.JobKind.FLATTEN || job.kind == Domain.JobKind.DIG) && tick % 10 == 0) cleanupSupports(owner, data);
                if (job != null && (owner == null || !owner.isOnline()) && !plugin.getConfig().getBoolean("work.continue-offline", true)) {
                    for (UUID worker : data.workers) releaseTickets(worker);
                    continue;
                }
                if (job != null && !job.paused) tickJob(owner, data, job);
                else {
                    for (Entity entity : loadedWorkers(data)) if (entity instanceof Mob mob && !isWaiting(mob)
                            && (!store.items(mob.getUniqueId()).isEmpty() && job == null
                            || data.deliverToOwner.contains(mob.getUniqueId()) || data.deliveryOnce.containsKey(mob.getUniqueId())))
                        tickDelivery(owner, data, mob);
                    if (job == null && owner != null && owner.isOnline() && tick % 10 == 0 && allBackpacksEmpty(data)) tickMovement(owner, data);
                }
            } catch (RuntimeException exception) {
                if (data.job != null) data.job.paused = true;
                for (UUID worker : data.workers) releaseTickets(worker);
                plugin.getLogger().severe("Wstrzymano pracę gracza " + data.owner + " po błędzie: " + exception.getMessage());
                notify(owner, Lang.text(owner, "text.070"), NamedTextColor.RED);
                store.audit("job_error", data.owner, exception.getClass().getSimpleName() + ": " + exception.getMessage());
                store.save();
            }
        }
    }

    private boolean buildAccess(Player owner, Domain.PlayerData data, Mob worker, Location target) {
        if (owner == null || !owner.isOnline() || !worker.getWorld().equals(target.getWorld())) return false;
        Block feet = worker.getLocation().getBlock();
        if (!feet.getRelative(BlockFace.DOWN).getType().isSolid()) return false;
        int dx = target.getBlockX() - feet.getX(), dz = target.getBlockZ() - feet.getZ();
        boolean climb = target.getBlockY() > feet.getY() + 1 && Math.abs(dx) + Math.abs(dz) <= 2;
        Location walkTarget = safeNear(target);
        if (walkTarget != null && walkTarget.clone().add(0,worker.getEyeHeight(),0).distanceSquared(target)<=6.25) {
            var path = worker.getPathfinder().findPath(walkTarget);
            if (path != null && path.canReachFinalPoint()) {
                double previousY = worker.getLocation().getY();
                boolean safe = true;
                for (Location point : path.getPoints()) {
                    if (previousY - point.getY() > 1.1 || point.getBlock().isLiquid() || !point.getBlock().getRelative(BlockFace.DOWN).getType().isSolid()) { safe = false; break; }
                    previousY = point.getY();
                }
                if (safe) return false;
            }
        }
        // Raise one block at a time; bridge only the next missing foothold.
        int sx = Math.abs(dx) >= Math.abs(dz) ? Integer.signum(dx) : 0;
        int sz = sx == 0 ? Integer.signum(dz) : 0;
        if (!climb && sx == 0 && sz == 0) return false;
        Block landing = feet.getRelative(sx, climb ? 1 : 0, sz);
        Block floor = landing.getRelative(BlockFace.DOWN);
        if (climb && floor.getType().isSolid()) {
            Block obstruction = !landing.isEmpty() ? landing : !landing.getRelative(BlockFace.UP).isEmpty() ? landing.getRelative(BlockFace.UP) : null;
            if (obstruction != null && data.job != null && obstruction.getType().getHardness()>=0 && !(obstruction.getState() instanceof Container)) {
                UUID id = worker.getUniqueId();
                Domain.WorkStep cut = new Domain.WorkStep(obstruction.getLocation(),Domain.StepKind.BREAK,null,null);
                if (!obstruction.getLocation().equals(accessCuts.get(id))) { accessCuts.put(id,obstruction.getLocation());accessCutTicks.put(id,durationTicks(cut)); }
                int remaining=accessCutTicks.get(id)-10;accessCutTicks.put(id,remaining);
                animateWork(worker,cut);
                if(remaining<=0){execute(owner,worker,cut,data.job);accessCuts.remove(id);accessCutTicks.remove(id);}
                return true;
            }
            if(landing.isEmpty() && landing.getRelative(BlockFace.UP).isEmpty()) { moveToward(worker,landing.getLocation().add(.5,0,.5),1.05,false);return true; }
        }
        if (!climb && floor.getType().isSolid() && landing.isEmpty() && landing.getRelative(BlockFace.UP).isEmpty()) {
            moveToward(worker, landing.getLocation().add(.5, 0, .5), 1.05, false);
            return true;
        }
        if (data.job != null && (data.job.kind == Domain.JobKind.TUNNEL || data.job.kind == Domain.JobKind.WALL)) return false;
        if (!floor.isEmpty() || !landing.isEmpty() || !landing.getRelative(BlockFace.UP).isEmpty()) return false;
        List<ItemStack> backpack = store.items(worker.getUniqueId());
        ItemStack material = backpack.stream().filter(i -> i.getAmount() > 0 && (isSealingMaterial(i.getType()) || Tag.LOGS.isTagged(i.getType()))).findFirst().orElse(null);
        if (material == null) return false;
        var previous = floor.getState();
        Material placed = material.getType();
        floor.setType(placed, false);
        BlockPlaceEvent event = new BlockPlaceEvent(floor, previous, feet.getRelative(BlockFace.DOWN), material.clone(), owner, true, org.bukkit.inventory.EquipmentSlot.HAND);
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled() || !event.canBuild() || !worker.teleport(landing.getLocation().add(.5, 0, .5))) {
            previous.update(true, false);
            return false;
        }
        material.setAmount(material.getAmount() - 1);
        backpack.removeIf(i -> i.getAmount() <= 0);
        data.temporaryBlocks.put(floor.getLocation(), placed);
        store.audit("temporary_support_placed", data.owner, format(floor.getLocation()));
        store.save();
        return true;
    }

    private void cleanupSupports(Player owner, Domain.PlayerData data) {
        if (owner == null || !owner.isOnline()) return;
        List<Location> positions = new ArrayList<>(data.temporaryBlocks.keySet());
        positions.sort(Comparator.comparingInt(Location::getBlockY).reversed());
        int removed = 0;
        for (Location position : positions) {
            if (removed >= 4) break;
            position.getChunk().load();
            Block block = position.getBlock();
            Material material = data.temporaryBlocks.get(position);
            if (block.getType() != material) { data.temporaryBlocks.remove(position); continue; }
            BlockBreakEvent event = new BlockBreakEvent(block, owner);
            Bukkit.getPluginManager().callEvent(event);
            if (event.isCancelled()) continue;
            if(data.job!=null && !data.job.workFinished && data.job.assigned.values().stream().anyMatch(step -> step.location().getWorld().equals(position.getWorld()) && step.location().distanceSquared(position)<=9))continue;
            boolean occupied = false;
            for (Entity entity : block.getWorld().getNearbyEntities(position.clone().add(.5,1,.5), .6, 1, .6)) {
                if (!entity.getLocation().getBlock().getRelative(BlockFace.DOWN).equals(block)) continue;
                if (!isWorker(entity)) { occupied = true; break; }
                Location safe = safeNear(entity.getLocation(), block);
                if (safe == null && block.getRelative(BlockFace.DOWN).getType().isSolid()) safe = position.clone().add(.5, 0, .5);
                if (safe == null || !entity.teleport(safe)) { occupied = true; break; }
            }
            if (occupied) continue;
            block.setType(Material.AIR, false);
            data.temporaryBlocks.remove(position);
            Mob receiver = loadedWorkers(data).stream().filter(Mob.class::isInstance).map(Mob.class::cast).findFirst().orElse(null);
            if (receiver != null) collect(receiver, List.of(new ItemStack(material)));
            else block.getWorld().dropItemNaturally(position, new ItemStack(material));
            store.audit("temporary_support_removed", data.owner, format(position));
            removed++;
        }
        if (removed > 0) store.save();
    }

    private void tickJob(Player owner, Domain.PlayerData data, Domain.Job job) {
        List<Entity> workers = loadedWorkers(data);
        if (workers.isEmpty()) return;
        for (Entity entity : workers) {
            if (!(entity instanceof Mob worker) || isWaiting(worker)) continue;
            UUID workerId = worker.getUniqueId();
            data.workerLocations.put(workerId, worker.getLocation());
            if (data.deliveryOrigins.containsKey(workerId) || data.deliverToOwner.contains(workerId) || data.deliveryOnce.containsKey(workerId) || backpackFull(workerId) || job.workFinished) {
                worker.setAI(true);
                tickDelivery(owner, data, worker);
                continue;
            }

            Domain.WorkStep step = readyWork(job,worker);
            if (step == null) { worker.setAI(true); continue; }
            lightWorkArea(owner, data, worker, job);
            if (step.kind() == Domain.StepKind.BREAK && (step.location().getBlock().getType()==Material.TORCH || step.location().getBlock().getType()==Material.WALL_TORCH || Tag.STAIRS.isTagged(step.location().getBlock().getType()))) { job.complete(workerId); continue; }
            if (step.kind() == Domain.StepKind.BREAK && Domain.ignoredVegetation(step.location().getBlock().getType())) { job.complete(workerId); continue; }
            if (step.kind() == Domain.StepKind.BREAK && step.location().getBlock().isEmpty() && job.kind != Domain.JobKind.TUNNEL) { job.complete(workerId); continue; }
            showTool(worker, step, job.kind);
            Location target = step.location().clone().add(.5, .5, .5);
            holdChunks(workerId, worker.getLocation(), target);
            if (!worker.getWorld().equals(target.getWorld())) continue;
            Location workPosition = workPosition(worker, step, job);
            boolean tooFar = worker.getEyeLocation().distanceSquared(target) > 6.25;
            boolean needsApproach = workPosition != null && worker.getLocation().distanceSquared(workPosition) > .64;
            if (tooFar || needsApproach) {
                worker.setAI(true);
                if (tick % 5 == 0) {
                    Location destination = workPosition == null ? target : workPosition;
                    if (job.kind == Domain.JobKind.TUNNEL && workPosition == null && target.getY()>worker.getLocation().getY()+1) {
                        double horizontal=Math.pow(target.getX()-worker.getLocation().getX(),2)+Math.pow(target.getZ()-worker.getLocation().getZ(),2);
                        if (horizontal>4) moveToward(worker,new Location(target.getWorld(),target.getX(),worker.getLocation().getY(),target.getZ()),1.15,true,20);
                        else if (!buildAccess(owner,data,worker,target)) moveToward(worker,target,1.15,true,20);
                    } else if(job.kind==Domain.JobKind.FLATTEN && workPosition==null && Tag.LOGS.isTagged(step.location().getBlock().getType())) {
                        if(!buildAccess(owner,data,worker,target))moveToward(worker,target,1.15,true,20);
                    } else if (job.kind == Domain.JobKind.FLATTEN || job.kind == Domain.JobKind.TUNNEL || job.kind == Domain.JobKind.DIG)
                        moveToward(worker, destination, 1.15, true, 20);
                    else if (!buildAccess(owner, data, worker, target)) moveToward(worker, destination, 1.15, true, 20);
                }
                continue;
            }
            worker.getPathfinder().stopPathfinding();
            worker.setAI(!safeStanding(worker.getLocation()));
            int remaining = job.remainingTicks.computeIfAbsent(workerId, ignored -> durationTicks(step));
            if (tick % 8 == 0) animateWork(worker, step);
            if (remaining > 1) { job.remainingTicks.put(workerId, remaining - 1); continue; }
            if ((job.kind == Domain.JobKind.TUNNEL || job.kind == Domain.JobKind.DIG && job.lining) && !prepareTunnel(owner, data, worker, step, job)) continue;
            if (execute(owner, worker, step, job)) {
                worker.setAI(true);
                lightWork(owner, data, worker, step, job);
                if (!settlingBlocks.containsKey(step.location())) job.complete(workerId);
            }
            if (job.paused) break;
        }
        if (!job.workFinished && job.sourceEmpty() && job.assigned.isEmpty()) job.workFinished = true;
        if (job.workFinished && data.temporaryBlocks.isEmpty() && allBackpacksEmpty(data)) completeJob(owner, data, job);
    }

    private void expandFlattenTree(Domain.Job job, Domain.WorkStep origin, UUID owner, UUID worker) {
        Set<Location> known=flattenTreeLogs.computeIfAbsent(job.id,ignored -> new HashSet<>());
        if(known.contains(origin.location())) return;
        Domain.Selection treeSelection=new Domain.Selection(origin.location(),origin.location(),BlockFace.UP);
        Domain.Job tree=JobBuilder.chop(treeSelection,Integer.MAX_VALUE);
        List<Domain.WorkStep> trunk=new ArrayList<>();
        for(Domain.WorkStep step:tree.pending) {
            if(step.location().getBlockY()<=job.flatten.targetY || !known.add(step.location())) continue;
            if(job.assigned.entrySet().stream().anyMatch(assigned -> !assigned.getKey().equals(worker) && assigned.getValue().location().equals(step.location()))) continue;
            job.pending.removeIf(pending -> pending.location().equals(step.location()));
            for(ArrayDeque<Domain.WorkStep> queue:job.workerQueues.values()) queue.removeIf(pending -> pending.location().equals(step.location()));
            trunk.add(step);
        }
        job.complete(worker);
        job.workerQueues.computeIfAbsent(worker,ignored -> new ArrayDeque<>()).addAll(trunk);
        store.audit("flatten_tree_queued",owner,"logs="+trunk.size()+" origin="+format(origin.location()));
    }

    private Domain.WorkStep readyWork(Domain.Job job, Mob worker) {
        UUID id=worker.getUniqueId();
        List<Domain.WorkStep> waiting=new ArrayList<>();
        Domain.WorkStep ready=null;
        for(int attempt=0;attempt<32;attempt++) {
            Domain.WorkStep candidate=job.assignNear(id,worker.getLocation());
            if(candidate==null) break;
            if(job.kind==Domain.JobKind.FLATTEN && candidate.kind()==Domain.StepKind.BREAK) {
                Material material=candidate.location().getBlock().getType();
                if(Tag.LEAVES.isTagged(material)) { job.complete(id);continue; }
                if(Tag.LOGS.isTagged(material)) {
                    Set<Location> known=flattenTreeLogs.getOrDefault(job.id,Set.of());
                    if(!known.contains(candidate.location())) {
                        expandFlattenTree(job,candidate,ownerOf(worker),id);continue;
                    }
                    boolean reserved=job.workerQueues.entrySet().stream().anyMatch(e -> !e.getKey().equals(id) && e.getValue().stream().anyMatch(s -> s.location().equals(candidate.location())));
                    if(reserved) { job.complete(id);continue; }
                }
            }
            Long deadline=settlingBlocks.get(candidate.location());
            if(deadline==null) { ready=candidate;break; }
            Block cell=candidate.location().getBlock();
            boolean falling=cell.getWorld().getNearbyEntities(cell.getLocation().add(.5,.5,.5),1,cell.getWorld().getMaxHeight()-cell.getY(),1)
                    .stream().anyMatch(entity -> entity instanceof FallingBlock && entity.getLocation().getBlockX()==cell.getX() && entity.getLocation().getBlockZ()==cell.getZ() && entity.getLocation().getY()>=cell.getY());
            if(falling) { deadline=tick+10;settlingBlocks.put(candidate.location(),deadline); }
            if(tick>=deadline) { settlingBlocks.remove(candidate.location());job.remainingTicks.remove(id);ready=candidate;break; }
            job.assigned.remove(id);job.remainingTicks.remove(id);waiting.add(candidate);
        }
        job.pending.addAll(waiting);
        return ready;
    }

    private boolean placeSupply(Player owner, Mob worker, Block block, boolean torch) {
        if (!block.isEmpty()) return true;
        if (owner == null || !owner.isOnline()) return false;
        List<ItemStack> backpack = store.items(worker.getUniqueId());
        ItemStack supply = torch ? new ItemStack(Material.TORCH) : backpack.stream().filter(i -> i.getAmount() > 0 && structuralMaterial(i.getType())).findFirst().orElse(null);
        if (supply == null) {
            refillSupply(worker, torch, !torch);
            supply = backpack.stream().filter(i -> i.getAmount() > 0 && (torch ? i.getType() == Material.TORCH : structuralMaterial(i.getType()))).findFirst().orElse(null);
        }
        if (supply == null) return false;
        var previous = block.getState();
        Domain.PlayerData builder = store.get(ownerOf(worker));
        BlockFace support = torch ? wallTorchSupport(block,builder.job,builder) : null;
        if(torch && builder.job != null && (builder.job.kind==Domain.JobKind.TUNNEL || builder.job.kind==Domain.JobKind.DIG) && support==null) return false;
        block.setType(torch && support != null ? Material.WALL_TORCH : supply.getType(), false);
        if (torch && support != null && block.getBlockData() instanceof Directional direction) {
            direction.setFacing(support.getOppositeFace());block.setBlockData(direction,false);
        }
        BlockPlaceEvent event = new BlockPlaceEvent(block, previous, block.getRelative(support == null ? BlockFace.DOWN : support), supply.clone(), owner, true, EquipmentSlot.HAND);
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled() || !event.canBuild()) { previous.update(true,false); return false; }
        if (!torch) supply.setAmount(supply.getAmount()-1);
        backpack.removeIf(i -> i.getAmount() <= 0);
        store.audit(torch ? "work_torch_placed" : "lining_placed", dataOwner(worker), format(block.getLocation()));
        store.save();
        return true;
    }

    private UUID dataOwner(Mob worker) { return ownerOf(worker); }
    private static boolean structuralMaterial(Material material) {
        return Set.of(Material.COBBLESTONE, Material.STONE, Material.COBBLED_DEEPSLATE, Material.DEEPSLATE,
                Material.NETHERRACK, Material.DIRT, Material.COARSE_DIRT, Material.ROOTED_DIRT, Material.GRASS_BLOCK,
                Material.ANDESITE, Material.GRANITE, Material.DIORITE, Material.TUFF, Material.CALCITE,
                Material.BASALT, Material.BLACKSTONE, Material.END_STONE, Material.CLAY, Material.TERRACOTTA, Material.SANDSTONE, Material.RED_SANDSTONE).contains(material);
    }

    private boolean prepareTunnel(Player owner, Domain.PlayerData data, Mob worker, Domain.WorkStep step, Domain.Job job) {
        Domain.ExcavationPlan plan = job.excavation;
        if (plan == null) return true;
        Block block = step.location().getBlock();
        List<Block> gaps = new ArrayList<>();
        int floorY = job.kind==Domain.JobKind.DIG ? plan.maxY-plan.depth : plan.minY-1;
        if(job.kind==Domain.JobKind.TUNNEL || block.getY()==floorY+1) gaps.add(block.getWorld().getBlockAt(block.getX(),floorY,block.getZ()));
        if (job.lining) {
            int offset = job.kind==Domain.JobKind.DIG ? 0 : plan.inward.getModX()!=0 ? (block.getX()-plan.minX)*plan.inward.getModX() : (block.getZ()-plan.minZ)*plan.inward.getModZ();
            int minX=plan.minX+plan.inward.getModX()*offset, maxX=plan.maxX+plan.inward.getModX()*offset;
            int minZ=plan.minZ+plan.inward.getModZ()*offset, maxZ=plan.maxZ+plan.inward.getModZ()*offset;
            if(job.kind==Domain.JobKind.DIG || plan.inward.getModX()==0){if(block.getX()==minX) gaps.add(block.getRelative(BlockFace.WEST));if(block.getX()==maxX) gaps.add(block.getRelative(BlockFace.EAST));}
            if(job.kind==Domain.JobKind.DIG || plan.inward.getModX()!=0){if(block.getZ()==minZ) gaps.add(block.getRelative(BlockFace.NORTH));if(block.getZ()==maxZ) gaps.add(block.getRelative(BlockFace.SOUTH));}
            if(job.kind==Domain.JobKind.TUNNEL && block.getY()==plan.maxY) gaps.add(block.getRelative(BlockFace.UP));
        }
        if (gaps.stream().anyMatch(Block::isEmpty) && structuralMaterial(block.getType())
                && store.items(worker.getUniqueId()).stream().noneMatch(i -> i.getAmount()>0 && structuralMaterial(i.getType()))) {
            // Obtain the material from this timed excavation step before consuming it for lining.
            if (!execute(owner,worker,step,job)) return false;
        }
        for (Block gap : gaps) if (gap.isEmpty() && !placeSupply(owner,worker,gap,false)) {
            job.paused = true;
            notify(owner,Lang.text(owner, "text.071"),NamedTextColor.YELLOW);
            return false;
        }
        return true;
    }

    private void lightWorkArea(Player owner, Domain.PlayerData data, Mob worker, Domain.Job job) {
        if (!job.lighting || job.kind == Domain.JobKind.CHOP || job.kind == Domain.JobKind.WART || owner == null || !owner.isOnline()) return;
        UUID id = worker.getUniqueId();
        if (tick < lightingChecks.getOrDefault(id, 0L)) return;
        lightingChecks.put(id, tick + 20);
        Block feet = worker.getLocation().getBlock();
        Block darkest = null;
        boolean raised = job.kind==Domain.JobKind.TUNNEL || job.kind==Domain.JobKind.DIG;
        for (int dy=raised?1:-1;dy<=(raised?2:1);dy++) for(int dx=-2;dx<=2;dx++) for(int dz=-2;dz<=2;dz++) {
            Block candidate=feet.getRelative(dx,dy,dz);
            if(!candidate.isEmpty() || !torchSupport(candidate,job,data) || candidate.getLightLevel()>0) continue;
            if(darkest==null || candidate.getLightFromBlocks()<darkest.getLightFromBlocks()
                    || candidate.getLightFromBlocks()==darkest.getLightFromBlocks() && candidate.getLocation().distanceSquared(worker.getLocation())<darkest.getLocation().distanceSquared(worker.getLocation())) darkest=candidate;
        }
        if(darkest!=null) placeWorkTorch(owner,worker,darkest,job);
    }

    private static boolean inExcavation(Block block, Domain.ExcavationPlan plan) {
        if(!block.getWorld().getUID().equals(plan.worldId)) return false;
        int offset=plan.depth-1;
        int minX=plan.minX+Math.min(0,plan.inward.getModX()*offset),maxX=plan.maxX+Math.max(0,plan.inward.getModX()*offset);
        int minZ=plan.minZ+Math.min(0,plan.inward.getModZ()*offset),maxZ=plan.maxZ+Math.max(0,plan.inward.getModZ()*offset);
        int minY=plan.kind==Domain.JobKind.DIG?plan.maxY-offset:plan.minY;
        return block.getX()>=minX && block.getX()<=maxX && block.getZ()>=minZ && block.getZ()<=maxZ && block.getY()>=minY && block.getY()<=plan.maxY;
    }
    private static BlockFace wallTorchSupport(Block block, Domain.Job job, Domain.PlayerData data) {
        boolean excavation=job!=null && job.excavation!=null && (job.kind==Domain.JobKind.TUNNEL || job.kind==Domain.JobKind.DIG);
        if(excavation && !inExcavation(block,job.excavation)) return null;
        for(BlockFace face : List.of(BlockFace.NORTH,BlockFace.SOUTH,BlockFace.EAST,BlockFace.WEST)) {
            Block wall=block.getRelative(face);
            if(!wall.getType().isOccluding() || Tag.STAIRS.isTagged(wall.getType()) || data.temporaryBlocks.containsKey(wall.getLocation())) continue;
            if(excavation && inExcavation(wall,job.excavation)) continue;
            return face;
        }
        return null;
    }
    private static boolean torchSupport(Block block, Domain.Job job, Domain.PlayerData data) {
        if(job.kind==Domain.JobKind.TUNNEL || job.kind==Domain.JobKind.DIG) return wallTorchSupport(block,job,data)!=null;
        return wallTorchSupport(block,job,data)!=null || block.getRelative(BlockFace.DOWN).getType().isSolid();
    }
    private void placeWorkTorch(Player owner, Mob worker, Block block, Domain.Job job) {
        Domain.PlayerData data=store.get(ownerOf(worker));
        if (!torchSupport(block,job,data) || tick < lightingChecks.getOrDefault(job.id,0L)) return;
        for (int x=-5;x<=5;x++) for(int y=-2;y<=2;y++) for(int z=-5;z<=5;z++) {
            Material nearby=block.getRelative(x,y,z).getType();
            if (nearby==Material.TORCH || nearby==Material.WALL_TORCH) return;
        }
        lightingChecks.put(job.id,tick+40);
        if(!placeSupply(owner,worker,block,true) && !job.warnedTorches) {
            job.warnedTorches=true;
            notify(owner,Lang.text(owner, "text.072"),NamedTextColor.YELLOW);
        }
    }

    private void lightWork(Player owner, Domain.PlayerData data, Mob worker, Domain.WorkStep step, Domain.Job job) {
        if (job.kind == Domain.JobKind.CHOP || job.kind == Domain.JobKind.WART) return;
        if (!job.lighting || owner == null || !owner.isOnline()) return;
        Block block = step.location().getBlock();
        Domain.ExcavationPlan plan = job.excavation;
        if (plan != null) {
            int floorY = job.kind == Domain.JobKind.DIG ? plan.maxY-plan.depth+1 : plan.minY;
            if(block.getY()!=floorY) return;
            int along=plan.inward.getModX()!=0?(block.getX()-plan.minX)*plan.inward.getModX():(block.getZ()-plan.minZ)*plan.inward.getModZ();
            if(job.kind==Domain.JobKind.TUNNEL) {if(along%8!=0 || block.getX()!=plan.minX+plan.inward.getModX()*along || block.getZ()!=plan.minZ+plan.inward.getModZ()*along)return;}
            else if((block.getX()-plan.minX)%8!=0 || (block.getZ()-plan.minZ)%8!=0)return;
        } else if(job.flatten!=null) {
            if(block.getY()!=job.flatten.targetY+1 && block.getY()!=job.flatten.targetY)return;
            if((block.getX()-job.flatten.minX)%8!=0 || (block.getZ()-job.flatten.minZ)%8!=0)return;
        }
        if (job.kind==Domain.JobKind.TUNNEL || job.kind==Domain.JobKind.DIG || !block.isEmpty()) block=block.getRelative(BlockFace.UP);
        if(!block.isEmpty() || !torchSupport(block,job,data) || block.getLightFromBlocks()>=8) return;
        placeWorkTorch(owner,worker,block,job);
    }

    private Location surfaceLanding(Location target) {
        int y = target.getWorld().getHighestBlockYAt(target.getBlockX(), target.getBlockZ(), org.bukkit.HeightMap.WORLD_SURFACE);
        return safeNear(new Location(target.getWorld(), target.getBlockX(), y + 1, target.getBlockZ()));
    }

    private Location flattenLanding(Domain.Job job, Domain.WorkStep step) {
        if (step == null) return null;
        if (step.kind() != Domain.StepKind.PLACE) return surfaceLanding(step.location());
        Location target = step.location();
        Location nearest = null;
        int y = job.flatten.targetY + 1;
        for(int dx=-4;dx<=4;dx++)for(int dz=-4;dz<=4;dz++) {
            Location candidate = new Location(target.getWorld(),target.getBlockX()+dx+.5,y,target.getBlockZ()+dz+.5);
            if(!safeStanding(candidate)) continue;
            if(nearest==null || candidate.distanceSquared(target)<nearest.distanceSquared(target))nearest=candidate;
        }
        return nearest;
    }

    private Location workPosition(Mob worker, Domain.WorkStep step, Domain.Job job) {
        if (job.kind == Domain.JobKind.FLATTEN && step.kind() == Domain.StepKind.PLACE) return flattenLanding(job,step);
        Location block = step.location(), centre = block.clone().add(.5,.5,.5);
        Location best = null;
        double bestScore = Double.MAX_VALUE;
        for (int dx=-1;dx<=1;dx++) for(int dz=-1;dz<=1;dz++) for(int dy=-3;dy<=2;dy++) {
            Location candidate=block.clone().add(dx+.5,dy,dz+.5);
            if (!safeStanding(candidate) || candidate.getBlock().getRelative(BlockFace.DOWN).equals(block.getBlock())) continue;
            Location eyes=candidate.clone().add(0,worker.getEyeLocation().getY()-worker.getLocation().getY(),0);
            if(eyes.distanceSquared(centre)>6.25)continue;
            if (candidate.clone().add(0,worker.getEyeHeight(),0).distanceSquared(centre)>6.25) continue;
            double score=candidate.distanceSquared(worker.getLocation())+.2*candidate.distanceSquared(centre);
            if (score<bestScore) { best=candidate;bestScore=score; }
        }
        return best;
    }

    private static boolean safeStanding(Location location) {
        Block feet=location.getBlock(),head=feet.getRelative(BlockFace.UP);
        return feet.isPassable() && head.isPassable() && !feet.isLiquid() && !head.isLiquid() && feet.getRelative(BlockFace.DOWN).getType().isSolid();
    }

    private boolean execute(Player owner, Mob worker, Domain.WorkStep step, Domain.Job job) {
        Block block = step.location().getBlock();
        if (!block.getChunk().isLoaded()) return false;
        if (step.kind() == Domain.StepKind.PLACE) {
            if (block.getType() == step.material()) return true;
            if (!block.isEmpty() && !block.isPassable() && !block.isLiquid()) return job.kind != Domain.JobKind.DIG;
            if (block.isEmpty() || block.isPassable() || block.isLiquid()) {
                block.setType(step.material(), false);
                if (block.getBlockData() instanceof Directional directional && step.facing() != null) {
                    directional.setFacing(step.facing());
                    block.setBlockData(directional, false);
                }
                SoundGroup sounds = block.getBlockSoundGroup();
                block.getWorld().playSound(block.getLocation(), sounds.getPlaceSound(), Math.min(.7f, sounds.getVolume()), sounds.getPitch());
            }
            return true;
        }
        Material type = block.getType();
        if (type==Material.TORCH || type==Material.WALL_TORCH || Tag.STAIRS.isTagged(type)) return true;
        if (type.isAir() || type.getHardness() < 0 || plugin.getConfig().getBoolean("work.protect-containers", true) && block.getState() instanceof Container) return true;
        for (Entity nearby : block.getWorld().getNearbyEntities(block.getLocation().add(.5, 1, .5), 1.5, 2, 1.5)) {
            if (!isWorker(nearby) || !nearby.getLocation().getBlock().getRelative(BlockFace.DOWN).equals(block)) continue;
            // A one-block descent onto solid ground is safe and keeps mountain workers in their column.
            if (block.getRelative(BlockFace.DOWN).getType().isSolid()) continue;
            Location safe = safeNear(nearby.getLocation(), block);
            if (safe == null || !nearby.teleport(safe)) {
                job.paused = true;
                notify(owner, Lang.text(owner, "text.073"), NamedTextColor.YELLOW);
                return false;
            }
        }
        if (plugin.getConfig().getBoolean("work.stop-at-liquids", true)) {
            Block liquid = firstLiquid(block);
            if (liquid != null) {
                if (!sealLiquid(owner, worker, block, liquid) && !store.get(ownerOf(worker)).deliveryOrigins.containsKey(worker.getUniqueId())) job.paused = true;
                job.remainingTicks.put(worker.getUniqueId(), Math.max(1, plugin.getConfig().getInt("work.placement-ticks", 7)));
                return false;
            }
        }
        BlockBreakEvent event = null;
        if (owner != null && owner.isOnline()) {
            event = new BlockBreakEvent(block, owner);
            Bukkit.getPluginManager().callEvent(event);
            if (event.isCancelled()) {
                job.paused = true;
                notify(owner, Lang.text(owner, "text.074"), NamedTextColor.RED);
                return false;
            }
        }
        ItemStack tool = toolFor(type, job.kind);
        Collection<ItemStack> drops = event == null || event.isDropItems() ? block.getDrops(tool, worker) : List.of();
        var brokenData = block.getBlockData().clone();
        SoundGroup sounds = block.getBlockSoundGroup();
        boolean gravityColumn = type.hasGravity() || block.getRelative(BlockFace.UP).getType().hasGravity();
        block.setType(Material.AIR, true);
        // Keep this work cell assigned until falling material has settled, then mine it again.
        if (gravityColumn) settlingBlocks.put(step.location(), tick+20);
        UUID master = ownerOf(worker);
        if (master != null) store.get(master).temporaryBlocks.remove(block.getLocation());
        block.getWorld().spawnParticle(Particle.BLOCK, block.getLocation().add(.5, .5, .5), 12, .25, .25, .25, brokenData);
        block.getWorld().playSound(block.getLocation(), sounds.getBreakSound(), Math.min(.7f, sounds.getVolume()), sounds.getPitch());
        collect(worker, drops);
        return true;
    }

    private void collect(Mob worker, Collection<ItemStack> drops) {
        List<ItemStack> backpack = store.items(worker.getUniqueId());
        int maxSlots = Math.max(1, plugin.getConfig().getInt("work.inventory-slots", 27));
        for (ItemStack incoming : drops) {
            ItemStack leftover = addToBackpack(backpack, incoming.clone(), maxSlots);
            if (leftover != null && leftover.getAmount() > 0) worker.getWorld().dropItemNaturally(worker.getLocation(), leftover);
        }
    }

    private boolean tickDelivery(Player owner, Domain.PlayerData data, Mob worker) {
        worker.setAI(true);
        List<ItemStack> backpack = store.items(worker.getUniqueId());
        if (backpack.isEmpty()) { data.deliveryOnce.remove(worker.getUniqueId());data.deliverToOwner.remove(worker.getUniqueId()); return returnToWork(data, worker); }
        if (!data.deliverToOwner.contains(worker.getUniqueId()) && !data.deliveryOnce.containsKey(worker.getUniqueId()) && data.deliveryOrigins.containsKey(worker.getUniqueId()) && !backpackFull(worker.getUniqueId()) && !deliveryBlocked.contains(worker.getUniqueId())) return returnToWork(data, worker);
        Location oneShot=data.deliveryOnce.get(worker.getUniqueId());
        if(!data.deliverToOwner.contains(worker.getUniqueId()) && (oneShot!=null || data.deliveryPoint!=null))
            return deliverAtPoint(data,worker,oneShot!=null?oneShot:data.deliveryPoint,oneShot!=null);
        Location chest = data.deliverToOwner.contains(worker.getUniqueId()) ? null : data.chest;
        if (chest != null && chest.getWorld() != null) {
            Location target = chest.clone().add(.5, .5, .5);
            holdChunks(worker.getUniqueId(), worker.getLocation(), target);
            if (!(chest.getBlock().getState() instanceof Container)) {
                data.chest = null;
                store.audit("delivery_chest_missing", data.owner, format(chest));
                notify(owner, Lang.text(owner, "text.075"), NamedTextColor.YELLOW);
                store.save();
                return false;
            }
            if (!worker.getWorld().equals(target.getWorld()) || worker.getLocation().distanceSquared(target) > 16) {
                Location landing = safeNear(target);
                if (landing == null) return false;
                if (data.job != null && !data.job.workFinished) data.deliveryOrigins.putIfAbsent(worker.getUniqueId(), worker.getLocation().clone());
                if (!worker.teleport(landing)) return false;
                store.save();
                worker.getPathfinder().stopPathfinding();
            }
            worker.getPathfinder().stopPathfinding();
            Container container = (Container) chest.getBlock().getState();
            List<ItemStack> reserve = takeReserve(backpack, data.job != null && !data.job.workFinished ? 64 : 0);
            deposit(container.getInventory(), backpack);
            boolean cargoRemaining = !backpack.isEmpty();
            backpack.addAll(reserve);
            if (cargoRemaining) {
                if (deliveryBlocked.add(worker.getUniqueId())) {
                    notify(owner, Lang.text(owner, "text.076"), NamedTextColor.YELLOW);
                    store.audit("delivery_blocked_full_chest", data.owner, worker.getUniqueId().toString());
                }
                return false;
            }
            deliveryBlocked.remove(worker.getUniqueId());
            worker.getWorld().playSound(worker.getLocation(), Sound.BLOCK_CHEST_CLOSE, .65f, 1.15f);
            worker.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, worker.getLocation().add(0, 1, 0), 8, .3, .35, .3, 0);
            store.audit("delivery_completed", data.owner, worker.getUniqueId() + " chest=" + format(chest));
            if (!returnToWork(data, worker)) return false;
            store.save();
            return true;
        }
        if (owner == null || !owner.isOnline() || !worker.getWorld().equals(owner.getWorld())) return false;
        holdChunks(worker.getUniqueId(), worker.getLocation(), owner.getLocation());
        if (worker.getLocation().distanceSquared(owner.getLocation()) > 6.25) {
            if (tick % 10 == 0) moveToward(worker, owner.getLocation(), 1.08, false);
            return false;
        }
        worker.getPathfinder().stopPathfinding();
        List<ItemStack> reserve = takeReserve(backpack, data.deliverToOwner.contains(worker.getUniqueId()) ? 0 : data.job != null && !data.job.workFinished ? 64 : 0);
        Location launch=worker.getLocation().clone().add(0,.7,0);
        org.bukkit.util.Vector toward=owner.getLocation().clone().add(0,.7,0).toVector().subtract(launch.toVector());
        if(toward.lengthSquared()>0) toward.normalize().multiply(.3);
        toward.setY(Math.max(.1,toward.getY()));
        for(ItemStack item:new ArrayList<>(backpack)) {
            Item thrown=owner.getWorld().dropItem(launch,item);thrown.setVelocity(toward.clone());thrown.setPickupDelay(10);thrown.setOwner(owner.getUniqueId());
        }
        backpack.clear();
        backpack.addAll(reserve);
        data.deliverToOwner.remove(worker.getUniqueId());
        deliveryBlocked.remove(worker.getUniqueId());
        worker.getWorld().playSound(worker.getLocation(), Sound.ENTITY_ITEM_PICKUP, .65f, .9f);
        store.audit("delivery_completed", data.owner, worker.getUniqueId() + " owner");
        returnToWork(data,worker);
        return true;
    }

    private boolean deliverAtPoint(Domain.PlayerData data, Mob worker, Location point, boolean oneShot) {
        Location target=point.clone().add(.5,1,.5);
        if(!worker.getWorld().equals(target.getWorld()))return false;
        holdChunks(worker.getUniqueId(),worker.getLocation(),target);
        if(worker.getLocation().distanceSquared(target)>6.25) {
            if(tick%10==0)moveToward(worker,target,1.08,false);
            return false;
        }
        worker.getPathfinder().stopPathfinding();
        List<ItemStack> backpack=store.items(worker.getUniqueId());
        List<ItemStack> reserve=takeReserve(backpack,oneShot?0:data.job!=null && !data.job.workFinished?64:0);
        Location launch=worker.getLocation().clone().add(0,.7,0);
        org.bukkit.util.Vector velocity=target.toVector().subtract(launch.toVector());
        if(velocity.lengthSquared()>0)velocity.normalize().multiply(.3);velocity.setY(Math.max(.1,velocity.getY()));
        for(ItemStack item:new ArrayList<>(backpack)) { Item thrown=worker.getWorld().dropItem(launch,item);thrown.setVelocity(velocity.clone());thrown.setPickupDelay(10); }
        backpack.clear();backpack.addAll(reserve);data.deliveryOnce.remove(worker.getUniqueId());
        store.audit("delivery_completed",data.owner,worker.getUniqueId()+" point="+format(point)+" one-shot="+oneShot);
        returnToWork(data,worker);store.save();return true;
    }

    private boolean returnToWork(Domain.PlayerData data, Mob worker) {
        Location origin = data.deliveryOrigins.get(worker.getUniqueId());
        if (origin == null) return true;
        if (data.job == null || data.job.workFinished) {
            data.deliveryOrigins.remove(worker.getUniqueId());
            store.save();
            return true;
        }
        origin.getChunk().load();
        Domain.WorkStep work = data.job.assigned.get(worker.getUniqueId());
        Location landing = work == null ? null : safeNear(work.location());
        if (landing == null) landing = safeNear(origin);
        if (landing == null) {
            int surface = origin.getWorld().getHighestBlockYAt(origin.getBlockX(), origin.getBlockZ());
            landing = safeNear(new Location(origin.getWorld(), origin.getBlockX(), surface + 1, origin.getBlockZ()));
        }
        if (landing == null || !worker.teleport(landing)) return false;
        worker.getPathfinder().stopPathfinding();
        data.deliveryOrigins.remove(worker.getUniqueId());
        store.save();
        return true;
    }

    static List<ItemStack> takeReserve(List<ItemStack> backpack, int limit) {
        List<ItemStack> reserve = new ArrayList<>();

        for (Iterator<ItemStack> iterator = backpack.iterator(); iterator.hasNext() && limit > 0;) {
            ItemStack item = iterator.next();
            if (!structuralMaterial(item.getType())) continue;
            int count = Math.min(limit, item.getAmount());
            ItemStack saved = item.clone();
            saved.setAmount(count);
            addToBackpack(reserve, saved, Integer.MAX_VALUE);
            item.setAmount(item.getAmount() - count);
            if (item.getAmount() == 0) iterator.remove();
            limit -= count;
        }
        return reserve;
    }

    private static void deposit(Inventory target, List<ItemStack> backpack) {
        List<ItemStack> remaining = new ArrayList<>();
        for (ItemStack item : new ArrayList<>(backpack)) remaining.addAll(target.addItem(item).values());
        backpack.clear();
        backpack.addAll(remaining);
    }

    private void completeJob(Player owner, Domain.PlayerData data, Domain.Job job) {
        flattenTreeLogs.remove(job.id);
        data.job = null;
        data.follow = true;
        setOwnerWorkersAi(data, true);
        for (Entity entity : loadedWorkers(data)) if (entity instanceof LivingEntity living && living.getEquipment() != null) living.getEquipment().setItemInMainHand(new ItemStack(Material.IRON_PICKAXE));
        for (UUID worker : data.workers) releaseTickets(worker);
        store.audit("job_completed", data.owner, job.id.toString());
        notify(owner, Lang.text(owner, "text.077"), NamedTextColor.GREEN);
        store.save();
    }

    private boolean backpackFull(UUID worker) {
        List<ItemStack> backpack = store.items(worker);
        if (backpack.size() < Math.max(1, plugin.getConfig().getInt("work.inventory-slots", 27))) return false;
        List<ItemStack> compact = new ArrayList<>();
        for (ItemStack item : backpack) if (item.getAmount() > 0) addToBackpack(compact, item.clone(), Integer.MAX_VALUE);
        backpack.clear();
        backpack.addAll(compact);
        return backpack.size() >= Math.max(1, plugin.getConfig().getInt("work.inventory-slots", 27));
    }
    private boolean allBackpacksEmpty(Domain.PlayerData data) { return data.workers.stream().allMatch(id -> store.items(id).isEmpty()); }
    private void dropBackpack(Entity worker) { for (ItemStack item : store.takeItems(worker.getUniqueId())) worker.getWorld().dropItemNaturally(worker.getLocation(), item); }

    static ItemStack addToBackpack(List<ItemStack> backpack, ItemStack incoming, int maxSlots) {
        for (ItemStack stack : backpack) {
            if (!stack.isSimilar(incoming) || stack.getAmount() >= stack.getMaxStackSize()) continue;
            int moved = Math.min(incoming.getAmount(), stack.getMaxStackSize() - stack.getAmount());
            stack.setAmount(stack.getAmount() + moved);
            incoming.setAmount(incoming.getAmount() - moved);
            if (incoming.getAmount() == 0) return null;
        }
        while (incoming.getAmount() > 0 && backpack.size() < maxSlots) {
            int amount = Math.min(incoming.getAmount(), incoming.getMaxStackSize());
            ItemStack split = incoming.clone();
            split.setAmount(amount);
            backpack.add(split);
            incoming.setAmount(incoming.getAmount() - amount);
        }
        return incoming.getAmount() == 0 ? null : incoming;
    }

    private int durationTicks(Domain.WorkStep step) {
        if (step.kind() == Domain.StepKind.PLACE) return Math.max(1, plugin.getConfig().getInt("work.placement-ticks", 7));
        float hardness = Math.max(0, step.location().getBlock().getType().getHardness());
        int base = Math.max(1, (int) Math.round(plugin.getConfig().getDouble("work.seconds-per-stone-block", .7) * 20));
        int minimum = Math.max(1, plugin.getConfig().getInt("work.minimum-ticks", 8));
        int maximum = Math.max(minimum, plugin.getConfig().getInt("work.maximum-ticks", 120));
        return acceleratedMiningTicks(workTicksForHardness(hardness, base, minimum, maximum));
    }

    static int acceleratedMiningTicks(int ticks) { return Math.max(1, (int) Math.ceil(ticks / 1.4)); }

    static int workTicksForHardness(float hardness, int stoneTicks, int minimum, int maximum) {
        if (hardness < 0) return minimum;
        int result = (int) Math.round(stoneTicks * Math.max(.25, hardness / 1.5));
        return Math.max(minimum, Math.min(maximum, result));
    }

    private void animateWork(Mob worker, Domain.WorkStep step) {
        worker.swingMainHand();
        Block block = step.location().getBlock();
        if (!block.getType().isAir()) {
            block.getWorld().spawnParticle(Particle.BLOCK, block.getLocation().add(.5, .5, .5), 3, .15, .15, .15, block.getBlockData());
            SoundGroup sounds = block.getBlockSoundGroup();
            block.getWorld().playSound(block.getLocation(), sounds.getHitSound(), .22f, sounds.getPitch());
        }
    }

    private void tickMovement(Player owner, Domain.PlayerData data) {
        List<Entity> workers = loadedWorkers(data);
        for (int i = 0; i < workers.size(); i++) {
            if (!(workers.get(i) instanceof Mob worker) || !worker.getPassengers().isEmpty() || isWaiting(worker)) continue;
            Location target = data.follow ? formation(owner, i) : data.rally == null ? worker.getLocation() : data.rally.clone().add((i % 2) * 2 - 1, 0, (i / 2) * 2 - 1);
            if (!worker.getWorld().equals(target.getWorld())) continue;
            double distance = worker.getLocation().distanceSquared(target);
            int teleportDistance = plugin.getConfig().getInt("workers.teleport-distance", 14);
            if (distance > teleportDistance * teleportDistance) {
                Location safe = safeNear(target);
                if (safe != null) { worker.teleport(safe); worker.getWorld().spawnParticle(Particle.PORTAL, safe, 28, .4, .8, .4, .1); }
            } else if (distance > 4) moveToward(worker, target, 1.12, false);
        }
    }

    private void ensureWorkersLoaded(Domain.PlayerData data) {
        if (data.job == null || !plugin.getConfig().getBoolean("work.continue-offline", true)) return;
        for (UUID worker : data.workers) {
            if (Bukkit.getEntity(worker) != null) continue;
            Location saved = data.workerLocations.get(worker);
            if (saved != null && saved.getWorld() != null) holdChunks(worker, saved);
        }
    }

    private void updateLocations(Domain.PlayerData data) {
        for (Entity entity : loadedWorkers(data)) {
            Location current=entity.getLocation();UUID id=entity.getUniqueId();
            if(safeStanding(current)) safeFooting.put(id,current.getBlock().getLocation().add(.5,0,.5));
            else if(data.job!=null && entity.getPassengers().isEmpty() && entity.getVelocity().getY()<=0) {
                Location last=safeFooting.get(id);
                if(last!=null && last.getWorld().equals(current.getWorld()) && current.getY()<last.getY()-.5 && safeStanding(last)) {
                    if(entity instanceof Mob mob)mob.getPathfinder().stopPathfinding();
                    entity.teleport(last);current=entity.getLocation();
                }
            }
            data.workerLocations.put(id,current);
        }
    }

    private void holdChunks(UUID worker, Location... locations) {
        Set<Chunk> desired = new HashSet<>();
        for (Location location : locations) {
            if (location == null || location.getWorld() == null) continue;
            Chunk chunk = location.getChunk();
            chunk.addPluginChunkTicket(plugin);
            desired.add(chunk);
        }
        Set<Chunk> previous = chunkTickets.put(worker, desired);
        if (previous != null) for (Chunk chunk : previous) if (!desired.contains(chunk)) chunk.removePluginChunkTicket(plugin);
    }

    private void releaseTickets(UUID worker) {
        Set<Chunk> chunks = chunkTickets.remove(worker);
        if (chunks != null) for (Chunk chunk : chunks) chunk.removePluginChunkTicket(plugin);
        movementProgress.remove(worker);
        accessCuts.remove(worker);
        accessCutTicks.remove(worker);
        lightingChecks.remove(worker);
        safeFooting.remove(worker);
        normalizedWorkers.remove(worker);
        deliveryBlocked.remove(worker);
    }

    void releaseAllTickets() { for (UUID worker : new ArrayList<>(chunkTickets.keySet())) releaseTickets(worker); }

    private static Location formation(Player owner, int slot) {
        double[][] offsets = {{-1.8, 2.2}, {1.8, 2.2}, {-1.8, 4.2}, {1.8, 4.2}};
        double[] o = offsets[Math.min(slot, offsets.length - 1)];
        double radians = Math.toRadians(owner.getLocation().getYaw());
        return owner.getLocation().clone().add(o[0] * Math.cos(radians) - o[1] * Math.sin(radians), 0, o[0] * Math.sin(radians) + o[1] * Math.cos(radians));
    }

    private void moveToward(Mob mob, Location target, double speed, boolean allowBlink) {
        moveToward(mob,target,speed,allowBlink,Math.max(40, plugin.getConfig().getInt("workers.stuck-blink-seconds",6)*20));
    }

    private void moveToward(Mob mob, Location target, double speed, boolean allowBlink, int blinkTicks) {
        UUID id = mob.getUniqueId();
        String targetKey = target.getWorld().getUID() + ":" + target.getBlockX() + ":" + target.getBlockY() + ":" + target.getBlockZ();
        MovementProgress progress = movementProgress.computeIfAbsent(id, ignored -> new MovementProgress());
        if (!targetKey.equals(progress.targetKey)) {
            progress.targetKey = targetKey;
            progress.last = mob.getLocation();
            progress.stagnantTicks = 0;
        } else if (progress.last != null && progress.last.getWorld().equals(mob.getWorld()) && progress.last.distanceSquared(mob.getLocation()) < .0625) {
            progress.stagnantTicks += blinkTicks == 20 ? 5 : 10;
        } else {
            progress.last = mob.getLocation();
            progress.stagnantTicks = 0;
        }

        if (allowBlink && (progress.stagnantTicks >= blinkTicks || blinkTicks == 20 && (Math.abs(target.getY()-mob.getLocation().getY())>3 || target.getWorld().equals(mob.getWorld()) && target.distanceSquared(mob.getLocation())>64))) {
            Location safe = safeNear(target);
            if (safe != null && mob.getWorld().equals(safe.getWorld()) && safe.distanceSquared(target) <= 7.0
                    && safe.distanceSquared(mob.getLocation()) > 1) {
                Location from = mob.getLocation();
                from.getWorld().spawnParticle(Particle.PORTAL, from.clone().add(0, .7, 0), 20, .35, .6, .35, .08);
                mob.teleport(safe);
                safe.getWorld().spawnParticle(Particle.PORTAL, safe.clone().add(0, .7, 0), 20, .35, .6, .35, .08);
                progress.last = safe;
                progress.stagnantTicks = 0;
                return;
            }
        }
        Location safeTarget = safeNear(target);
        if (safeTarget == null) { mob.getPathfinder().stopPathfinding(); return; }
        var path = mob.getPathfinder().findPath(safeTarget);
        if (path == null) { mob.getPathfinder().stopPathfinding(); return; }
        double previousY = mob.getLocation().getY();
        for (Location point : path.getPoints()) {
            if (previousY - point.getY() > 1.1 || point.getBlock().isLiquid()
                    || !point.getBlock().getRelative(BlockFace.DOWN).getType().isSolid()) {
                mob.getPathfinder().stopPathfinding();
                return;
            }
            previousY = point.getY();
        }
        mob.getPathfinder().moveTo(path, speed);
    }
    private boolean isWaiting(Entity worker) { return worker.getPersistentDataContainer().getOrDefault(waitingKey, PersistentDataType.BYTE, (byte) 0) != 0; }

    private List<Entity> loadedWorkers(Domain.PlayerData data) {
        List<Entity> result = new ArrayList<>();
        for (UUID id : data.workers) {
            Entity entity = Bukkit.getEntity(id);
            if (entity != null && entity.isValid() && data.owner.equals(ownerOf(entity))) {
                normalizeWorker(entity, data.job != null && data.job.paused, data.workerNames.get(id));
                result.add(entity);
            }
        }
        return result;
    }

    private void normalizeWorker(Entity entity, boolean paused, String name) {
        if (!normalizedWorkers.add(entity.getUniqueId())) return;
        if (name != null) entity.customName(Component.text(name, NamedTextColor.GOLD));
        entity.setPersistent(true);
        entity.setInvulnerable(true);
        entity.setSilent(true);
        if (entity instanceof Villager villager) {
            villager.setBaby();
            villager.setAgeLock(true);
            villager.setCanPickupItems(false);
        }
        if (entity instanceof Mob mob) mob.setAI(!paused && !isWaiting(entity));
    }

    private void setOwnerWorkersAi(Domain.PlayerData data, boolean enabled) {
        for (Entity entity : loadedWorkers(data)) if (entity instanceof Mob mob) mob.setAI(enabled && !isWaiting(entity));
    }

    private void reconcile(Domain.PlayerData data) {
        data.workers.removeIf(id -> {
            Entity entity = Bukkit.getEntity(id);
            boolean invalid = entity != null && (!entity.isValid() || !data.owner.equals(ownerOf(entity)));
            if (invalid) {
                data.workerLocations.remove(id);
                data.workerNames.remove(id);
                data.deliverToOwner.remove(id);
                releaseTickets(id);
                if (data.job != null) data.job.release(id);
            }
            return invalid;
        });
    }

    private void stopPaths(UUID owner) { for (Entity entity : loadedWorkers(store.get(owner))) if (entity instanceof Mob mob) mob.getPathfinder().stopPathfinding(); }

    private static Location safeNear(Location desired) {
        return safeNear(desired, null);
    }

    private static Location safeNear(Location desired, Block excludedFloor) {
        if (desired.getWorld() == null) return null;
        for (int radius = 0; radius <= 3; radius++) for (int dx = -radius; dx <= radius; dx++) for (int dz = -radius; dz <= radius; dz++) {
            Location test = desired.clone().add(dx, 0, dz);
            for (int dy : new int[]{0, 1, -1, 2, -2}) {
                Location candidate = test.clone().add(0, dy, 0);
                Block feet = candidate.getBlock(), head = feet.getRelative(BlockFace.UP), floor = feet.getRelative(BlockFace.DOWN);
                if (!floor.equals(excludedFloor) && feet.isPassable() && head.isPassable() && !feet.isLiquid() && !head.isLiquid() && floor.getType().isSolid()) return feet.getLocation().add(.5, 0, .5);
            }
        }
        return null;
    }

    private static Block firstLiquid(Block block) {
        if (block.isLiquid()) return block;
        for (BlockFace face : List.of(BlockFace.UP, BlockFace.DOWN, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST)) {
            Block neighbor = block.getRelative(face);
            if (neighbor.isLiquid()) return neighbor;
        }
        return null;
    }

    private boolean sealLiquid(Player owner, Mob worker, Block mined, Block liquid) {
        List<ItemStack> backpack = store.items(worker.getUniqueId());
        if (backpack.stream().noneMatch(item -> isSealingMaterial(item.getType()) && item.getAmount() > 0)) {
            refillSealingBlocks(worker);
        }
        UUID master = ownerOf(worker);
        if (master != null && store.get(master).deliveryOrigins.containsKey(worker.getUniqueId())) return false;
        ItemStack material = backpack.stream().filter(item -> item.getAmount() > 0 && isSealingMaterial(item.getType()))
                .findFirst().orElse(null);
        String position = liquid.getX() + ", " + liquid.getY() + ", " + liquid.getZ();
        if (material == null) {
            notify(owner, Lang.text(owner, "text.120", position), NamedTextColor.RED);
            return false;
        }
        // Protection checks need the actual player; never bypass them while the owner is offline.
        if (owner == null || !owner.isOnline()) return false;
        var previous = liquid.getState();
        liquid.setType(material.getType(), false);
        BlockPlaceEvent event = new BlockPlaceEvent(liquid, previous, mined, material.clone(), owner, true, org.bukkit.inventory.EquipmentSlot.HAND);
        try {
            Bukkit.getPluginManager().callEvent(event);
            if (event.isCancelled() || !event.canBuild()) {
                previous.update(true, false);
                notify(owner, Lang.text(owner, "text.121", position), NamedTextColor.RED);
                return false;
            }
        } catch (RuntimeException exception) {
            previous.update(true, false);
            throw exception;
        }
        material.setAmount(material.getAmount() - 1);
        if (material.getAmount() == 0) backpack.remove(material);
        store.audit("liquid_sealed", owner.getUniqueId(), worker.getUniqueId() + " at=" + format(liquid.getLocation()) + " material=" + liquid.getType());
        store.save();
        SoundGroup sounds = liquid.getBlockSoundGroup();
        liquid.getWorld().playSound(liquid.getLocation(), sounds.getPlaceSound(), .7f, sounds.getPitch());
        return true;
    }

    private static boolean isSealingMaterial(Material type) {
        return structuralMaterial(type);
    }

    private void refillSealingBlocks(Mob worker) {
        refillSupply(worker,false);
    }

    private void refillSupply(Mob worker, boolean torches) {
        refillSupply(worker,torches,false);
    }

    private void refillSupply(Mob worker, boolean torches, boolean structural) {
        UUID ownerId = ownerOf(worker);
        if (ownerId == null) return;
        Domain.PlayerData data = store.get(ownerId);
        if (data.chest == null || data.chest.getWorld() == null) return;
        data.chest.getChunk().load();
        if (!(data.chest.getBlock().getState() instanceof Container container)) return;
        Inventory inventory = container.getInventory();
        boolean available = Arrays.stream(inventory.getContents()).filter(Objects::nonNull).anyMatch(item -> torches ? item.getType()==Material.TORCH : structural ? structuralMaterial(item.getType()) : isSealingMaterial(item.getType()));
        if (!available) return;
        Location landing = safeNear(data.chest.clone().add(.5, .5, .5));
        if (landing == null) return;
        data.deliveryOrigins.putIfAbsent(worker.getUniqueId(), worker.getLocation().clone());
        store.save();
        if (!worker.teleport(landing)) { data.deliveryOrigins.remove(worker.getUniqueId()); return; }
        int supplyLimit=torches?16:64;
        int remaining = supplyLimit;
        for (int slot = 0; slot < inventory.getSize() && remaining > 0; slot++) {
            ItemStack item = inventory.getItem(slot);
            if (item == null || !(torches ? item.getType()==Material.TORCH : structural ? structuralMaterial(item.getType()) : isSealingMaterial(item.getType()))) continue;
            int amount = Math.min(remaining, item.getAmount());
            ItemStack supply = item.clone(); supply.setAmount(amount);
            collect(worker, List.of(supply));
            item.setAmount(item.getAmount() - amount);
            inventory.setItem(slot, item.getAmount() == 0 ? null : item);
            remaining -= amount;
        }
        store.audit("work_material_refilled", ownerId, worker.getUniqueId() + " count=" + (supplyLimit - remaining));
        store.save();
        returnToWork(data, worker);
    }

    private static ItemStack toolFor(Material material, Domain.JobKind kind) {
        if (kind == Domain.JobKind.CHOP || Tag.MINEABLE_AXE.isTagged(material)) return new ItemStack(Material.NETHERITE_AXE);
        if (kind == Domain.JobKind.WART || Tag.MINEABLE_HOE.isTagged(material)) return new ItemStack(Material.NETHERITE_HOE);
        if (Tag.MINEABLE_SHOVEL.isTagged(material)) return new ItemStack(Material.NETHERITE_SHOVEL);
        return new ItemStack(Material.NETHERITE_PICKAXE);
    }

    private static void showTool(Mob worker, Domain.WorkStep step, Domain.JobKind kind) {
        if (worker.getEquipment() == null) return;
        if (step.kind() == Domain.StepKind.PLACE) {
            worker.getEquipment().setItemInMainHand(new ItemStack(step.material()));
            return;
        }
        Material material = step.location().getBlock().getType();
        Material visual = kind == Domain.JobKind.CHOP || Tag.MINEABLE_AXE.isTagged(material) ? Material.IRON_AXE
                : kind == Domain.JobKind.WART || Tag.MINEABLE_HOE.isTagged(material) ? Material.IRON_HOE
                : Tag.MINEABLE_SHOVEL.isTagged(material) ? Material.IRON_SHOVEL : Material.IRON_PICKAXE;
        worker.getEquipment().setItemInMainHand(new ItemStack(visual));
    }

    private static void dress(LivingEntity worker, int number) {
        ItemStack chest = new ItemStack(Material.LEATHER_CHESTPLATE);
        if (chest.getItemMeta() instanceof LeatherArmorMeta meta) {
            Color[] colors = {Color.fromRGB(125, 40, 35), Color.fromRGB(35, 80, 145), Color.fromRGB(35, 120, 65), Color.fromRGB(120, 75, 150)};
            meta.setColor(colors[(number - 1) % colors.length]);
            chest.setItemMeta(meta);
        }
        if (worker.getEquipment() != null) {
            worker.getEquipment().setChestplate(chest);
            worker.getEquipment().setItemInMainHand(new ItemStack(Material.IRON_PICKAXE));
            worker.getEquipment().setChestplateDropChance(0);
            worker.getEquipment().setItemInMainHandDropChance(0);
        }
    }

    private static void notify(Player player, String message, NamedTextColor color) { if (player != null && player.isOnline()) player.sendMessage(Component.text(message, color)); }
    private static String polishJob(Player owner,Domain.JobKind kind) {
        return switch (kind) {
            case DIG -> Lang.text(owner, "text.078"); case TUNNEL -> Lang.text(owner,"job.tunnel"); case VEIN -> Lang.text(owner, "text.079");
            case CHOP -> Lang.text(owner, "text.080"); case WART -> Lang.text(owner, "text.081"); case FLATTEN -> Lang.text(owner, "text.082"); case WALL -> Lang.text(owner, "text.083");
        };
    }
    private static String format(Location location) { return location.getWorld() == null ? "brak świata" : location.getWorld().getName() + ":" + location.getBlockX() + "," + location.getBlockY() + "," + location.getBlockZ(); }

    private static World jobWorld(Domain.Job job) {
        Domain.WorkStep first = job.pending.peekFirst();
        if (first != null) return first.location().getWorld();
        if (job.excavation != null) return Bukkit.getWorld(job.excavation.worldId);
        return job.flatten == null ? null : Bukkit.getWorld(job.flatten.worldId);
    }

    private static final class MovementProgress {
        String targetKey;
        Location last;
        int stagnantTicks;
    }
}


























