package pl.marek.kingdomminions;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.*;

final class DataStore {
    private final KingdomMinionsPlugin plugin;
    private final File file;
    private final File events;
    private final File backup;
    private final Map<UUID, Domain.PlayerData> players = new HashMap<>();
    private final Map<UUID, List<ItemStack>> workerItems = new HashMap<>();

    DataStore(KingdomMinionsPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "data.yml");
        this.events = new File(plugin.getDataFolder(), "events.jsonl");
        this.backup = new File(plugin.getDataFolder(), "data.yml.bak");
    }

    Map<UUID, Domain.PlayerData> all() { return players; }
    List<ItemStack> items(UUID worker) { return workerItems.computeIfAbsent(worker, ignored -> new ArrayList<>()); }
    List<ItemStack> takeItems(UUID worker) { List<ItemStack> result = workerItems.remove(worker); return result == null ? List.of() : result; }

    Domain.PlayerData get(UUID owner) {
        return players.computeIfAbsent(owner, id -> new Domain.PlayerData(id, plugin.getConfig().getInt("work.default-depth", 8)));
    }

    void load() {
        players.clear();
        workerItems.clear();
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection root = yaml.getConfigurationSection("players");
        if (root != null) for (String key : root.getKeys(false)) {
            try {
                UUID id = UUID.fromString(key);
                ConfigurationSection section = root.getConfigurationSection(key);
                if (section == null) continue;
                Domain.PlayerData data = new Domain.PlayerData(id, section.getInt("depth", 8));
                data.flattenFillHoles = section.getBoolean("flatten-fill-holes", true);
                data.lining = section.getBoolean("lining");
                data.lighting = section.getBoolean("lighting", true);
                data.staffToken = section.getString("staff-token");
                data.size = Math.max(1, Math.min(9, section.getInt("size", 3)));
                data.stairs = parseStairs(section.getString("stairs", "CLOCKWISE"));
                data.follow = section.getBoolean("follow", true);
                for (String worker : section.getStringList("workers")) {
                    try { data.workers.add(UUID.fromString(worker)); } catch (IllegalArgumentException ignored) {}
                }
                for (String worker : section.getStringList("deliver-to-owner")) {
                    try { data.deliverToOwner.add(UUID.fromString(worker)); } catch (IllegalArgumentException ignored) {}
                }
                ConfigurationSection names = section.getConfigurationSection("worker-names");
                if (names != null) for (String worker : names.getKeys(false)) {
                    try { data.workerNames.put(UUID.fromString(worker), names.getString(worker)); } catch (IllegalArgumentException ignored) {}
                }
                ConfigurationSection locations = section.getConfigurationSection("worker-locations");
                if (locations != null) for (String worker : locations.getKeys(false)) {
                    try {
                        Location location = readLocation(locations.getConfigurationSection(worker));
                        if (location != null) data.workerLocations.put(UUID.fromString(worker), location);
                    } catch (IllegalArgumentException ignored) {}
                }
                ConfigurationSection origins = section.getConfigurationSection("delivery-origins");
                if (origins != null) for (String worker : origins.getKeys(false)) {
                    try {
                        Location origin = readLocation(origins.getConfigurationSection(worker));
                        if (origin != null) data.deliveryOrigins.put(UUID.fromString(worker), origin);
                    } catch (IllegalArgumentException ignored) {}
                }
                data.chest = readLocation(section.getConfigurationSection("chest"));
                data.deliveryPoint = data.chest==null ? readLocation(section.getConfigurationSection("delivery-point")) : null;
                ConfigurationSection oneShot=section.getConfigurationSection("delivery-once");
                if(oneShot!=null) for(String worker:oneShot.getKeys(false)) {
                    try { Location point=readLocation(oneShot.getConfigurationSection(worker));if(point!=null)data.deliveryOnce.put(UUID.fromString(worker),point); }
                    catch(IllegalArgumentException ignored) {}
                }
                ConfigurationSection supports = section.getConfigurationSection("temporary-blocks");
                if (supports != null) for (String index : supports.getKeys(false)) {
                    ConfigurationSection support = supports.getConfigurationSection(index);
                    Location location = readLocation(support);
                    if (location != null) data.temporaryBlocks.put(location, Material.valueOf(support.getString("material", "COBBLESTONE")));
                }
                data.rally = readLocation(section.getConfigurationSection("rally"));
                data.job = readJob(section.getConfigurationSection("job"), id);
                players.put(id, data);
            } catch (IllegalArgumentException ignored) {
                plugin.getLogger().warning("Pominięto nieprawidłowy UUID w data.yml: " + key);
            }
        }
        ConfigurationSection inventoryRoot = yaml.getConfigurationSection("worker-items");
        if (inventoryRoot != null) for (String key : inventoryRoot.getKeys(false)) {
            try {
                UUID worker = UUID.fromString(key);
                List<ItemStack> items = new ArrayList<>();
                for (Object value : inventoryRoot.getList(key, List.of())) if (value instanceof ItemStack stack && !stack.getType().isAir()) items.add(stack);
                if (!items.isEmpty()) workerItems.put(worker, items);
            } catch (IllegalArgumentException ignored) { plugin.getLogger().warning("Pominięto nieprawidłowy plecak: " + key); }
        }
    }

    void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        for (Domain.PlayerData data : players.values()) {
            String path = "players." + data.owner;
            yaml.set(path + ".flatten-fill-holes", data.flattenFillHoles);
            yaml.set(path + ".lining", data.lining);
            yaml.set(path + ".lighting", data.lighting);
            yaml.set(path + ".staff-token", data.staffToken);
            yaml.set(path + ".workers", data.workers.stream().map(UUID::toString).toList());
            yaml.set(path + ".deliver-to-owner", data.deliverToOwner.stream().map(UUID::toString).toList());
            for (Map.Entry<UUID, String> name : data.workerNames.entrySet()) yaml.set(path + ".worker-names." + name.getKey(), name.getValue());
            yaml.set(path + ".depth", data.depth);
            yaml.set(path + ".size", data.size);
            yaml.set(path + ".stairs", data.stairs.name());
            yaml.set(path + ".follow", data.follow);
            for (Map.Entry<UUID, Location> worker : data.workerLocations.entrySet()) writeLocation(yaml, path + ".worker-locations." + worker.getKey(), worker.getValue());
            for (var origin : data.deliveryOrigins.entrySet()) writeLocation(yaml, path + ".delivery-origins." + origin.getKey(), origin.getValue());
            writeLocation(yaml, path + ".chest", data.chest);
            writeLocation(yaml,path+".delivery-point",data.deliveryPoint);
            for(var point:data.deliveryOnce.entrySet())writeLocation(yaml,path+".delivery-once."+point.getKey(),point.getValue());
            int supportIndex = 0;
            for (var support : data.temporaryBlocks.entrySet()) {
                String supportPath = path + ".temporary-blocks." + supportIndex++;
                writeLocation(yaml, supportPath, support.getKey());
                yaml.set(supportPath + ".material", support.getValue().name());
            }
            writeLocation(yaml, path + ".rally", data.rally);
            writeJob(yaml, path + ".job", data.job);
        }
        for (Map.Entry<UUID, List<ItemStack>> entry : workerItems.entrySet()) if (!entry.getValue().isEmpty()) yaml.set("worker-items." + entry.getKey(), entry.getValue());
        try {
            if (!plugin.getDataFolder().exists() && !plugin.getDataFolder().mkdirs()) throw new IOException("Nie można utworzyć folderu pluginu");
            File temporary = new File(plugin.getDataFolder(), "data.yml.tmp");
            yaml.save(temporary);
            if (file.isFile()) Files.copy(file.toPath(), backup.toPath(), StandardCopyOption.REPLACE_EXISTING);
            try {
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            plugin.getLogger().severe("Nie udało się zapisać data.yml: " + exception.getMessage());
        }
    }

    void audit(String event, UUID actor, String details) {
        if (!plugin.getConfig().getBoolean("logging.audit-events", true)) return;
        String safe = details == null ? "" : details.replace("\\", "\\\\").replace("\"", "\\\"").replace("\r", " ").replace("\n", " ");
        String line = "{\"time\":\"" + Instant.now() + "\",\"event\":\"" + event + "\",\"actor\":\"" + actor + "\",\"details\":\"" + safe + "\"}" + System.lineSeparator();
        try {
            if (!plugin.getDataFolder().exists()) plugin.getDataFolder().mkdirs();
            Files.writeString(events.toPath(), line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException exception) {
            plugin.getLogger().warning("Nie udało się zapisać zdarzenia audytowego: " + exception.getMessage());
        }
    }

    private static Domain.StairMode parseStairs(String raw) {
        try { return Domain.StairMode.valueOf(raw); } catch (Exception ignored) { return Domain.StairMode.CLOCKWISE; }
    }

    private static void writeLocation(YamlConfiguration yaml, String path, Location location) {
        if (location == null || location.getWorld() == null) { yaml.set(path, null); return; }
        yaml.set(path + ".world", location.getWorld().getUID().toString());
        yaml.set(path + ".x", location.getBlockX());
        yaml.set(path + ".y", location.getBlockY());
        yaml.set(path + ".z", location.getBlockZ());
    }

    private static Location readLocation(ConfigurationSection section) {
        if (section == null) return null;
        try {
            World world = Bukkit.getWorld(UUID.fromString(Objects.requireNonNull(section.getString("world"))));
            return world == null ? null : new Location(world, section.getInt("x"), section.getInt("y"), section.getInt("z"));
        } catch (Exception ignored) { return null; }
    }

    private static void writeJob(YamlConfiguration yaml, String path, Domain.Job job) {
        if (job == null) { yaml.set(path, null); return; }
        yaml.set(path + ".id", job.id.toString());
        yaml.set(path + ".kind", job.kind.name());
        yaml.set(path + ".paused", job.paused);
        yaml.set(path + ".work-finished", job.workFinished);
        yaml.set(path + ".lining", job.lining);
        yaml.set(path + ".lighting", job.lighting);
        yaml.set(path + ".pending", job.pending.stream().map(DataStore::writeStep).toList());
        for (var queue : job.workerQueues.entrySet()) yaml.set(path + ".worker-queues." + queue.getKey(), queue.getValue().stream().map(DataStore::writeStep).toList());
        for (Map.Entry<UUID, Domain.WorkStep> entry : job.assigned.entrySet()) yaml.set(path + ".assigned." + entry.getKey(), writeStep(entry.getValue()));
        for (Map.Entry<UUID, Integer> entry : job.remainingTicks.entrySet()) yaml.set(path + ".remaining-ticks." + entry.getKey(), entry.getValue());
        if (job.excavation != null) {
            Domain.ExcavationPlan plan=job.excavation;
            String source=path+".excavation";
            yaml.set(source+".world",plan.worldId.toString());
            yaml.set(source+".layered-tunnel",plan.layeredTunnel);
            yaml.set(source+".natural-tunnel",plan.naturalTunnel);
            yaml.set(source+".access-order",plan.accessOrder);yaml.set(source+".natural-start-depth",plan.naturalStartDepth);
            yaml.set(source+".bounds",List.of(plan.minX,plan.minY,plan.minZ,plan.maxX,plan.maxY,plan.maxZ));
            yaml.set(source+".depth",plan.depth);yaml.set(source+".inward",plan.inward.name());yaml.set(source+".stairs",plan.stairs.name());yaml.set(source+".cursor",plan.cursor);
        }
        if (job.flatten != null) {
            Domain.FlattenPlan plan = job.flatten;
            yaml.set(path + ".flatten.world", plan.worldId.toString());
            yaml.set(path + ".flatten.min-x", plan.minX);
            yaml.set(path + ".flatten.max-x", plan.maxX);
            yaml.set(path + ".flatten.min-z", plan.minZ);
            yaml.set(path + ".flatten.max-z", plan.maxZ);
            yaml.set(path + ".flatten.target-y", plan.targetY);
            yaml.set(path + ".flatten.fill-holes", plan.fillHoles);
            yaml.set(path + ".flatten.serpentine", plan.serpentine);
            yaml.set(path + ".flatten.floor", plan.floorMaterial.name());
            yaml.set(path + ".flatten.cursor-x", plan.cursorX);
            yaml.set(path + ".flatten.cursor-z", plan.cursorZ);
            yaml.set(path + ".flatten.current-y", plan.currentY);
            yaml.set(path + ".flatten.floor-pending", plan.floorPending);
            yaml.set(path + ".flatten.finished", plan.finished);
            yaml.set(path + ".flatten.layers", plan.layers);
            yaml.set(path + ".flatten.scanned-surface", plan.scannedSurface);
            yaml.set(path + ".flatten.layer-y", plan.layerY);
            yaml.set(path + ".flatten.layer-exhausted", plan.layerExhausted);
        }
    }

    private Domain.Job readJob(ConfigurationSection section, UUID owner) {
        if (section == null) return null;
        try {
            UUID id = UUID.fromString(Objects.requireNonNull(section.getString("id")));
            Domain.JobKind kind = Domain.JobKind.valueOf(Objects.requireNonNull(section.getString("kind")));
            List<Domain.WorkStep> pending = new ArrayList<>();
            for (Map<?, ?> map : section.getMapList("pending")) {
                Domain.WorkStep step = readStep(map);
                if (step != null) pending.add(step);
            }
            Domain.Job job = new Domain.Job(id, kind, pending);
            ConfigurationSection queues = section.getConfigurationSection("worker-queues");
            if (queues != null) for (String key : queues.getKeys(false)) {
                ArrayDeque<Domain.WorkStep> queue = new ArrayDeque<>();
                for (Map<?, ?> map : queues.getMapList(key)) { Domain.WorkStep step = readStep(map); if (step != null) queue.add(step); }
                job.workerQueues.put(UUID.fromString(key), queue);
            }
            job.paused = section.getBoolean("paused");
            job.workFinished = section.getBoolean("work-finished");
            job.lining = section.getBoolean("lining");
            job.lighting = section.getBoolean("lighting");
            ConfigurationSection assigned = section.getConfigurationSection("assigned");
            if (assigned != null) for (String worker : assigned.getKeys(false)) {
                Domain.WorkStep step = readStep(assigned.getConfigurationSection(worker));
                if (step != null) job.assigned.put(UUID.fromString(worker), step);
            }
            ConfigurationSection remaining = section.getConfigurationSection("remaining-ticks");
            if (remaining != null) for (String worker : remaining.getKeys(false)) job.remainingTicks.put(UUID.fromString(worker), remaining.getInt(worker));
            ConfigurationSection excavation=section.getConfigurationSection("excavation");
            if(excavation!=null){
                List<Integer> bounds=excavation.getIntegerList("bounds");
                job.excavation=new Domain.ExcavationPlan(UUID.fromString(excavation.getString("world")),kind,bounds.get(0),bounds.get(1),bounds.get(2),bounds.get(3),bounds.get(4),bounds.get(5),excavation.getInt("depth"),BlockFace.valueOf(excavation.getString("inward")),Domain.StairMode.valueOf(excavation.getString("stairs")));
                job.excavation.cursor=excavation.getLong("cursor");
                job.excavation.layeredTunnel=excavation.getBoolean("layered-tunnel",false);
                job.excavation.naturalTunnel=excavation.getBoolean("natural-tunnel",false);
                job.excavation.accessOrder=excavation.getInt("access-order",0);job.excavation.naturalStartDepth=excavation.getInt("natural-start-depth",0);
                if((kind==Domain.JobKind.TUNNEL || kind==Domain.JobKind.WALL) && job.excavation.maxY-job.excavation.minY+1>3 && job.excavation.accessOrder<2 && !job.workFinished) {
                    long frame=job.excavation.frame();
                    job.excavation.naturalStartDepth=(int)Math.max(0,job.excavation.cursor/frame-1);
                    job.excavation.cursor=(long)job.excavation.naturalStartDepth*frame;job.excavation.accessOrder=2;
                    job.excavation.naturalTunnel=true;
                    job.pending.clear();job.assigned.clear();job.workerQueues.clear();job.remainingTicks.clear();
                    audit("tunnel_migrated_to_natural_steps",owner,job.id.toString());
                }
            }
            ConfigurationSection flatten = section.getConfigurationSection("flatten");
            if (flatten != null) {
                Domain.FlattenPlan plan = new Domain.FlattenPlan(UUID.fromString(Objects.requireNonNull(flatten.getString("world"))), flatten.getInt("min-x"), flatten.getInt("max-x"), flatten.getInt("min-z"), flatten.getInt("max-z"), flatten.getInt("target-y"), Material.valueOf(flatten.getString("floor", "DIRT")));
                plan.fillHoles = flatten.getBoolean("fill-holes", true);
                plan.serpentine = flatten.getBoolean("serpentine", false);
                plan.cursorX = flatten.getInt("cursor-x", plan.minX);
                plan.cursorZ = flatten.getInt("cursor-z", plan.minZ);
                plan.currentY = flatten.getInt("current-y", Integer.MIN_VALUE);
                plan.floorPending = flatten.getBoolean("floor-pending");
                plan.finished = flatten.getBoolean("finished");
                plan.layers = flatten.getBoolean("layers");
                plan.scannedSurface = flatten.getBoolean("scanned-surface");
                plan.layerY = flatten.getInt("layer-y", Integer.MIN_VALUE);
                plan.layerExhausted = flatten.getBoolean("layer-exhausted");
                if (!plan.layers && !job.workFinished) {
                    Domain.FlattenPlan migrated = new Domain.FlattenPlan(plan.worldId,plan.minX,plan.maxX,plan.minZ,plan.maxZ,plan.targetY,plan.floorMaterial);
                    migrated.fillHoles=plan.fillHoles;migrated.layers=true;
                    plan=migrated;job.pending.clear();job.assigned.clear();job.workerQueues.clear();job.remainingTicks.clear();
                    audit("flatten_migrated_to_layers",owner,job.id.toString());
                }
                job.flatten = plan;
            }
            return job;
        } catch (Exception exception) {
            plugin.getLogger().warning("Nie udało się odczytać zadania gracza " + owner + ": " + exception.getMessage());
            return null;
        }
    }

    private static Map<String, Object> writeStep(Domain.WorkStep step) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("world", step.location().getWorld().getUID().toString());
        map.put("x", step.location().getBlockX());
        map.put("y", step.location().getBlockY());
        map.put("z", step.location().getBlockZ());
        map.put("kind", step.kind().name());
        if (step.material() != null) map.put("material", step.material().name());
        if (step.facing() != null) map.put("facing", step.facing().name());
        return map;
    }

    private static Domain.WorkStep readStep(Map<?, ?> map) {
        try {
            World world = Bukkit.getWorld(UUID.fromString(String.valueOf(map.get("world"))));
            if (world == null) return null;
            int x = ((Number) map.get("x")).intValue(), y = ((Number) map.get("y")).intValue(), z = ((Number) map.get("z")).intValue();
            Domain.StepKind kind = Domain.StepKind.valueOf(String.valueOf(map.get("kind")));
            Material material = map.get("material") == null ? null : Material.valueOf(String.valueOf(map.get("material")));
            BlockFace facing = map.get("facing") == null ? null : BlockFace.valueOf(String.valueOf(map.get("facing")));
            return new Domain.WorkStep(new Location(world, x, y, z), kind, material, facing);
        } catch (Exception ignored) { return null; }
    }

    private static Domain.WorkStep readStep(ConfigurationSection section) {
        if (section == null) return null;
        Map<String, Object> map = new LinkedHashMap<>();
        for (String key : section.getKeys(false)) map.put(key, section.get(key));
        return readStep(map);
    }
}





