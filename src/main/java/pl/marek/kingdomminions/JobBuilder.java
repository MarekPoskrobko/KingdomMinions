package pl.marek.kingdomminions;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;

import java.util.*;

final class JobBuilder {
    static Domain.Job dig(Domain.Selection selection, int depth, Domain.StairMode stairs, int limit) {
        Bounds bounds = Bounds.of(selection);
        int actualDepth = Math.min(depth, bounds.maxY - bounds.world.getMinHeight() + 1);
        Domain.Job job = new Domain.Job(Domain.JobKind.DIG, List.of());
        Domain.StairMode mode = bounds.width() >= 3 && bounds.length() >= 3 ? stairs : Domain.StairMode.NONE;
        job.excavation = new Domain.ExcavationPlan(bounds.world.getUID(), job.kind,bounds.minX,bounds.minY,bounds.minZ,bounds.maxX,bounds.maxY,bounds.maxZ,actualDepth,BlockFace.DOWN,mode);
        return job;
    }

    static Domain.Job tunnel(Domain.Selection selection, int depth, int limit) {
        Bounds bounds = Bounds.of(selection);
        Domain.Job job = new Domain.Job(Domain.JobKind.TUNNEL, List.of());
        job.excavation = new Domain.ExcavationPlan(bounds.world.getUID(),job.kind,bounds.minX,bounds.minY,bounds.minZ,bounds.maxX,bounds.maxY,bounds.maxZ,depth,selection.face().getOppositeFace(),Domain.StairMode.NONE);
        return job;
    }

    static Domain.Job wall(Domain.Selection selection, int depth) {
        if (selection.face() == BlockFace.UP || selection.face() == BlockFace.DOWN) throw Lang.failure("text.101");
        Bounds b = Bounds.of(selection);
        BlockFace face = selection.face();
        int x = selection.first().getBlockX() + face.getModX();
        int z = selection.first().getBlockZ() + face.getModZ();
        Domain.Job job = new Domain.Job(Domain.JobKind.WALL, List.of());
        job.excavation = new Domain.ExcavationPlan(b.world.getUID(), job.kind,
                face.getModX() != 0 ? x : b.minX, b.minY, face.getModZ() != 0 ? z : b.minZ,
                face.getModX() != 0 ? x : b.maxX, b.maxY, face.getModZ() != 0 ? z : b.maxZ,
                depth, face, Domain.StairMode.NONE);
        return job;
    }

    static Domain.Job vein(Block origin, int veinLimit) {
        Material material = origin.getType();
        World world = origin.getWorld();
        ArrayDeque<Block> open = new ArrayDeque<>();
        Set<String> seen = new HashSet<>();
        List<Domain.WorkStep> steps = new ArrayList<>();
        open.add(origin);
        while (!open.isEmpty() && steps.size() < veinLimit) {
            Block block = open.removeFirst();
            if (block.getY() < world.getMinHeight() || block.getY() >= world.getMaxHeight()) continue;
            String key = block.getX() + ":" + block.getY() + ":" + block.getZ();
            if (!seen.add(key) || block.getType() != material) continue;
            steps.add(breakStep(world, block.getX(), block.getY(), block.getZ()));
            for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) {
                int nextY = block.getY() + dy;
                if ((dx != 0 || dy != 0 || dz != 0) && nextY >= world.getMinHeight() && nextY < world.getMaxHeight()) open.add(block.getRelative(dx, dy, dz));
            }
        }
        return new Domain.Job(Domain.JobKind.VEIN, steps);
    }

    static Domain.Job harvest(Domain.Selection selection, int depth, int limit, boolean wart) {
        if (!wart) return chop(selection, limit);
        Bounds bounds = Bounds.of(selection);
        if (selection.face() == BlockFace.UP || selection.face() == BlockFace.DOWN) bounds = bounds.withY(bounds.minY, Math.min(bounds.world.getMaxHeight() - 1, bounds.minY + depth - 1));
        List<Domain.WorkStep> steps = new ArrayList<>();
        for (int y = bounds.minY; y <= bounds.maxY; y++) for (int x = bounds.minX; x <= bounds.maxX; x++) for (int z = bounds.minZ; z <= bounds.maxZ; z++) {
            Material material = bounds.world.getBlockAt(x, y, z).getType();
            boolean match = wart ? material == Material.NETHER_WART_BLOCK || material == Material.WARPED_WART_BLOCK || material == Material.NETHER_WART : Tag.LOGS.isTagged(material);
            if (match) add(steps, breakStep(bounds.world, x, y, z), limit);
        }
        return new Domain.Job(wart ? Domain.JobKind.WART : Domain.JobKind.CHOP, steps);
    }

    static Domain.Job chop(Domain.Selection selection, int limit) {
        Bounds bounds = Bounds.of(selection);
        ArrayDeque<Block> open = new ArrayDeque<>();
        Set<String> seen = new HashSet<>();
        List<Domain.WorkStep> steps = new ArrayList<>();
        int seedTop = selection.face() == BlockFace.UP || selection.face() == BlockFace.DOWN
                ? Math.min(bounds.world.getMaxHeight() - 1, bounds.minY + 2) : bounds.maxY;
        for (int y = bounds.minY; y <= seedTop; y++)
            for (int x = bounds.minX; x <= bounds.maxX; x++)
                for (int z = bounds.minZ; z <= bounds.maxZ; z++) {
                    Block block = bounds.world.getBlockAt(x, y, z);
                    if (Tag.LOGS.isTagged(block.getType())) open.add(block);
                }
        if (open.isEmpty()) throw Lang.failure("text.102");
        while (!open.isEmpty()) {
            Block block = open.removeFirst();
            if (block.getY() < bounds.world.getMinHeight() || block.getY() >= bounds.world.getMaxHeight()) continue;
            String key = block.getX() + ":" + block.getY() + ":" + block.getZ();
            if (!seen.add(key) || !Tag.LOGS.isTagged(block.getType())) continue;
            steps.add(breakStep(bounds.world, block.getX(), block.getY(), block.getZ()));
            for(int dx=-1;dx<=1;dx++)for(int dy=-1;dy<=1;dy++)for(int dz=-1;dz<=1;dz++)
                if(dx!=0 || dy!=0 || dz!=0) open.add(block.getRelative(dx,dy,dz));
        }
        steps.sort(Comparator.comparingInt((Domain.WorkStep step)->step.location().getBlockY()).reversed());
        return new Domain.Job(Domain.JobKind.CHOP, steps);
    }

    static Domain.Job flatten(Domain.Selection selection) {
        if (selection.face() != BlockFace.UP && selection.face() != BlockFace.DOWN) throw Lang.failure("text.103");
        Bounds bounds = Bounds.of(selection);
        Material floor = selection.first().getBlock().getType();
        if (!floor.isBlock() || floor.isAir() || floor == Material.BEDROCK || floor == Material.WATER || floor == Material.LAVA) floor = Material.DIRT;
        Domain.Job job = new Domain.Job(Domain.JobKind.FLATTEN, List.of());
        job.flatten = new Domain.FlattenPlan(bounds.world.getUID(), bounds.minX, bounds.maxX, bounds.minZ, bounds.maxZ, selection.first().getBlockY(), floor);
        job.flatten.layers = true;
        return job;
    }

    static Domain.Selection around(Location center, BlockFace face, int size) {
        int half = size / 2;
        Location a = center.clone().add(-half, 0, -half);
        Location b = center.clone().add(size - 1 - half, 0, size - 1 - half);
        if (face == BlockFace.EAST || face == BlockFace.WEST) { a = center.clone().add(0, 0, -half); b = center.clone().add(0, size - 1, size - 1 - half); }
        if (face == BlockFace.NORTH || face == BlockFace.SOUTH) { a = center.clone().add(-half, 0, 0); b = center.clone().add(size - 1 - half, size - 1, 0); }
        return new Domain.Selection(a, b, face);
    }

    private static void add(List<Domain.WorkStep> list, Domain.WorkStep step, int limit) {
        if (list.size() >= limit) throw Lang.failure("text.130",limit);
        list.add(step);
    }

    private static Domain.WorkStep breakStep(World world, int x, int y, int z) {
        return new Domain.WorkStep(new Location(world, x, y, z), Domain.StepKind.BREAK, null, null);
    }

    private static Stair stairAt(Bounds bounds, int depth, boolean clockwise) {
        int[] step = stairOffset(bounds.width(), bounds.length(), depth, clockwise);
        return new Stair(bounds.minX + step[0], bounds.minZ + step[1], BlockFace.values()[step[2]]);
    }

    static Domain.WorkStep stairPlacement(Location location, BlockFace downhill) {
        // The full-height side of a stair faces uphill, opposite the next lower step.
        return new Domain.WorkStep(location, Domain.StepKind.PLACE, Material.COBBLESTONE_STAIRS, downhill.getOppositeFace());
    }

    static int[] stairOffset(int size, int depth, boolean clockwise) {
        return stairOffset(size, size, depth, clockwise);
    }

    static int[] stairOffset(int width, int length, int depth, boolean clockwise) {
        if (width < 2 || length < 2) throw Lang.failure("text.104");
        List<int[]> perimeter = new ArrayList<>();
        for (int x = 0; x < width; x++) perimeter.add(new int[]{x, 0});
        for (int z = 1; z < length; z++) perimeter.add(new int[]{width - 1, z});
        for (int x = width - 2; x >= 0; x--) perimeter.add(new int[]{x, length - 1});
        for (int z = length - 2; z > 0; z--) perimeter.add(new int[]{0, z});
        if (!clockwise) Collections.reverse(perimeter);
        int index = depth % perimeter.size();
        int[] here = perimeter.get(index), next = perimeter.get((index + 1) % perimeter.size());
        return new int[]{here[0], here[1], face(next[0] - here[0], next[1] - here[1]).ordinal()};
    }

    private static BlockFace face(int dx, int dz) {
        if (Math.abs(dx) > Math.abs(dz)) return dx > 0 ? BlockFace.EAST : BlockFace.WEST;
        return dz > 0 ? BlockFace.SOUTH : BlockFace.NORTH;
    }

    private record Stair(int x, int z, BlockFace facing) {}

    private record Bounds(World world, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        static Bounds of(Domain.Selection selection) {
            Location a = selection.first(), b = selection.second() == null ? a : selection.second();
            if (a.getWorld() == null || !a.getWorld().equals(b.getWorld())) throw Lang.failure("text.105");
            return new Bounds(a.getWorld(), Math.min(a.getBlockX(), b.getBlockX()), Math.min(a.getBlockY(), b.getBlockY()), Math.min(a.getBlockZ(), b.getBlockZ()), Math.max(a.getBlockX(), b.getBlockX()), Math.max(a.getBlockY(), b.getBlockY()), Math.max(a.getBlockZ(), b.getBlockZ()));
        }
        int width() { return maxX - minX + 1; }
        int length() { return maxZ - minZ + 1; }
        Bounds withY(int min, int max) { return new Bounds(world, minX, min, minZ, maxX, max, maxZ); }
    }

    private JobBuilder() {}
}

