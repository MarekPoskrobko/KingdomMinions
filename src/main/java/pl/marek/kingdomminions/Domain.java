package pl.marek.kingdomminions;

import org.bukkit.Bukkit;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;

import java.util.*;

final class Domain {
    static boolean ignoredVegetation(Material material) {
        return material == Material.SHORT_GRASS || material == Material.TALL_GRASS
                || material == Material.FERN || material == Material.LARGE_FERN
                || material == Material.DEAD_BUSH || org.bukkit.Tag.FLOWERS.isTagged(material);
    }
    enum StairMode { NONE, CLOCKWISE, COUNTERCLOCKWISE }
    enum JobKind { DIG, TUNNEL, VEIN, CHOP, WART, FLATTEN, WALL }
    enum StepKind { BREAK, PLACE }

    record Selection(Location first, Location second, BlockFace face) {
        boolean complete() { return second != null; }
        int dx() { return second == null ? 1 : Math.abs(first.getBlockX() - second.getBlockX()) + 1; }
        int dy() { return second == null ? 1 : Math.abs(first.getBlockY() - second.getBlockY()) + 1; }
        int dz() { return second == null ? 1 : Math.abs(first.getBlockZ() - second.getBlockZ()) + 1; }
    }

    record WorkStep(Location location, StepKind kind, Material material, BlockFace facing) {}

    static final class Job {
        final UUID id;
        final JobKind kind;
        final ArrayDeque<WorkStep> pending;
        final Map<UUID, WorkStep> assigned = new LinkedHashMap<>();
        final Map<UUID, Integer> remainingTicks = new HashMap<>();
        final Map<UUID, ArrayDeque<WorkStep>> workerQueues = new LinkedHashMap<>();
        ExcavationPlan excavation;
        FlattenPlan flatten;
        boolean paused;
        boolean workFinished;
        boolean lining, lighting, warnedTorches;

        Job(JobKind kind, Collection<WorkStep> steps) { this(UUID.randomUUID(), kind, steps); }
        Job(UUID id, JobKind kind, Collection<WorkStep> steps) {
            this.id = id;
            this.kind = kind;
            this.pending = new ArrayDeque<>(steps);
        }

        WorkStep assign(UUID worker) {
            WorkStep existing = assigned.get(worker);
            if (existing != null) return existing;
            ArrayDeque<WorkStep> personal = workerQueues.get(worker);
            if(personal!=null && !personal.isEmpty()) { WorkStep next=personal.pollFirst();assigned.put(worker,next);return next; }
            if (kind == JobKind.CHOP && !pending.isEmpty() && assigned.values().stream().anyMatch(step -> step.location().getBlockY() > pending.peekFirst().location().getBlockY())) return null;
            if (flatten != null && flatten.layers && !pending.isEmpty()) {
                WorkStep released = pollFlattenAvailable(worker);
                if(released!=null) { assigned.put(worker, released);return released; }
            }
            WorkStep next;
            if (flatten != null) {
                ArrayDeque<WorkStep> queue = workerQueues.computeIfAbsent(worker, ignored -> new ArrayDeque<>());
                if (flatten.layers) {
                    if (!flatten.prepareLayer()) return null;
                    if (flatten.layerExhausted) {
                        if (pending.size()>=64 || pending.stream().anyMatch(step -> !blockedColumn(worker,step))) return null;
                        flatten.advanceLayer();
                    }
                    for (int scanned = 0; queue.isEmpty() && !flatten.finished && !flatten.layerExhausted && scanned < 32; scanned++) queue.addAll(flatten.claimLayerCell());
                } else for (int scanned = 0; queue.isEmpty() && !flatten.finished && scanned < 32; scanned++) queue.addAll(flatten.claimColumn());
                if(flatten.layers) { pending.addAll(queue);queue.clear();next=pollFlattenAvailable(worker); }
                else { next=queue.pollFirst();if(next==null)next=pending.pollFirst(); }
            } else { next = pending.pollFirst(); if (next == null && excavation != null) next = excavation.next(); }
            if (next != null) assigned.put(worker, next);
            return next;
        }

        WorkStep assignNear(UUID worker, Location current) {
            if (assigned.containsKey(worker) || kind == JobKind.CHOP || workerQueues.containsKey(worker) && !workerQueues.get(worker).isEmpty()) return assign(worker);
            if (flatten != null) {
                if (!flatten.layers || !flatten.prepareLayer()) return assign(worker);
                if(flatten.layerExhausted && !flatten.finished && pending.size()<64 && pending.stream().allMatch(step -> blockedColumn(worker,step)))flatten.advanceLayer();
                // Look ahead within this layer only; keep the batch bounded for huge selections.
                for (int scanned=0; pending.size()<32 && !flatten.finished && !flatten.layerExhausted && scanned<128; scanned++) pending.addAll(flatten.claimLayerCell());
            }
            if (pending.isEmpty() && flatten != null && flatten.layers && flatten.layerExhausted) {
                WorkStep local = exposedLowerWork(current);
                if (local != null) { assigned.put(worker,local); return local; }
            }
            if (pending.isEmpty()) return assign(worker);
            if(flatten!=null && flatten.layers) {
                WorkStep closest=null;double nearest=Double.MAX_VALUE;
                for(WorkStep candidate:pending) {
                    if(blockedColumn(worker,candidate))continue;
                    double distance=candidate.location().distanceSquared(current);
                    if(distance<nearest){closest=candidate;nearest=distance;}
                }
                if(closest==null)return null;
                pending.remove(closest);assigned.put(worker,closest);return closest;
            }
            WorkStep first = pending.peekFirst();
            if (first.kind() != StepKind.BREAK || !first.location().getWorld().equals(current.getWorld())) return assign(worker);
            WorkStep closest = first;
            double distance = first.location().distanceSquared(current);
            int scanned = 0;
            for (WorkStep candidate : pending) {
                if (++scanned > 24 || candidate.kind() != StepKind.BREAK || candidate.location().getBlockY() != first.location().getBlockY()) break;
                double candidateDistance = candidate.location().distanceSquared(current);
                if (candidateDistance < distance) { closest = candidate; distance = candidateDistance; }
            }
            pending.remove(closest);
            assigned.put(worker, closest);
            return closest;
        }

        private boolean blockedColumn(UUID worker,WorkStep candidate) {
            Location cell=candidate.location();
            java.util.function.Predicate<WorkStep> above=s -> s.location().getWorld().equals(cell.getWorld()) && s.location().getBlockX()==cell.getBlockX() && s.location().getBlockZ()==cell.getBlockZ() && s.location().getBlockY()>cell.getBlockY();
            return assigned.entrySet().stream().anyMatch(e -> !e.getKey().equals(worker) && above.test(e.getValue()))
                || workerQueues.entrySet().stream().anyMatch(e -> !e.getKey().equals(worker) && e.getValue().stream().anyMatch(above))
                || pending.stream().anyMatch(above);
        }
        private WorkStep pollFlattenAvailable(UUID worker) {
            Iterator<WorkStep> steps=pending.iterator();
            while(steps.hasNext()) { WorkStep step=steps.next();if(!blockedColumn(worker,step)){steps.remove();return step;} }
            return null;
        }

        private WorkStep exposedLowerWork(Location current) {
            World world = Bukkit.getWorld(flatten.worldId);
            if (world == null || !world.equals(current.getWorld())) return null;
            WorkStep best = null;
            double bestDistance = Double.MAX_VALUE;
            for (int x=Math.max(flatten.minX,current.getBlockX()-4);x<=Math.min(flatten.maxX,current.getBlockX()+4);x++) {
                for (int z=Math.max(flatten.minZ,current.getBlockZ()-4);z<=Math.min(flatten.maxZ,current.getBlockZ()+4);z++) {
                    int y=world.getHighestBlockYAt(x,z,HeightMap.WORLD_SURFACE);
                    for (int skipped=0;skipped<world.getMaxHeight()-flatten.targetY && y>flatten.targetY && (ignoredVegetation(world.getBlockAt(x,y,z).getType()) || org.bukkit.Tag.LEAVES.isTagged(world.getBlockAt(x,y,z).getType()));skipped++) y--;
                    if (y<=flatten.targetY || y>=flatten.layerY) continue;
                    Block block=world.getBlockAt(x,y,z);
                    if (block.isEmpty() || block.isLiquid() || ignoredVegetation(block.getType())) continue;
                    Location location=block.getLocation();
                    if (assigned.values().stream().anyMatch(step -> step.location().equals(location))
                            || pending.stream().anyMatch(step -> step.location().equals(location))
                            || workerQueues.values().stream().flatMap(Collection::stream).anyMatch(step -> step.location().equals(location))) continue;
                    double distance=location.distanceSquared(current);
                    if (best==null || y>best.location().getBlockY() || y==best.location().getBlockY() && distance<bestDistance) {
                        best=new WorkStep(location,StepKind.BREAK,null,null);bestDistance=distance;
                    }
                }
            }
            return best;
        }

        void complete(UUID worker) {
            assigned.remove(worker);
            remainingTicks.remove(worker);
        }

        void release(UUID worker) {
            WorkStep interrupted = assigned.remove(worker);
            remainingTicks.remove(worker);
            ArrayDeque<WorkStep> queue = workerQueues.remove(worker);
            if (queue != null) while (!queue.isEmpty()) pending.addFirst(queue.removeLast());
            if (interrupted != null) pending.addFirst(interrupted);
        }

        boolean sourceEmpty() { return pending.isEmpty() && workerQueues.values().stream().allMatch(Collection::isEmpty) && (flatten == null || flatten.finished) && (excavation == null || excavation.cursor >= excavation.total()); }
        long remainingEstimate() {
            long queued = pending.size() + assigned.size() + workerQueues.values().stream().mapToLong(Collection::size).sum();
            return queued + (flatten == null ? 0 : flatten.remainingColumns()) + (excavation == null ? 0 : excavation.total() - excavation.cursor);
        }
    }

    static final class ExcavationPlan {
        final UUID worldId;
        final JobKind kind;
        final int minX,minY,minZ,maxX,maxY,maxZ,depth;
        final BlockFace inward;
        final StairMode stairs;
        long cursor;
        boolean layeredTunnel = true;
        boolean naturalTunnel = true;
        int accessOrder = 2, naturalStartDepth;
        ExcavationPlan(UUID worldId, JobKind kind, int minX,int minY,int minZ,int maxX,int maxY,int maxZ,int depth,BlockFace inward,StairMode stairs) {
            this.worldId=worldId;this.kind=kind;this.minX=minX;this.minY=minY;this.minZ=minZ;this.maxX=maxX;this.maxY=maxY;this.maxZ=maxZ;this.depth=depth;this.inward=inward;this.stairs=stairs;
        }
        long width(){return (long)maxX-minX+1;}
        long length(){return (long)maxZ-minZ+1;}
        long frame(){return Math.multiplyExact(width(),length())*(kind!=JobKind.DIG?(long)maxY-minY+1:1)+(stairs==StairMode.NONE?0:1);}
        long total(){return Math.multiplyExact(frame(),depth);}
        WorkStep next(){
            World world=Bukkit.getWorld(worldId);
            if(world==null || cursor>=total()) return null;
            long index=cursor++; int layer=(int)(index/frame()); long cell=index%frame();
            if(kind==JobKind.DIG){
                if(cell==width()*length()){
                    int[] offset=JobBuilder.stairOffset((int)width(),(int)length(),layer,stairs==StairMode.CLOCKWISE);
                    return JobBuilder.stairPlacement(new Location(world,minX+offset[0],maxY-layer,minZ+offset[1]),BlockFace.values()[offset[2]]);
                }
                int row=(int)(cell/width()), column=(int)(cell%width());
                int z=layer%2==0?minZ+row:maxZ-row;
                int x=((row+layer*length())&1)==0?minX+column:maxX-column;
                return new WorkStep(new Location(world,x,maxY-layer,z),StepKind.BREAK,null,null);
            }
            long height=(long)maxY-minY+1;
            if ((kind==JobKind.TUNNEL || kind==JobKind.WALL) && naturalTunnel && height>3) {
                long rowSize=width()*length(), remainingDepth=depth-naturalStartDepth;
                long local=index-(long)naturalStartDepth*frame();
                int run=(int)Math.min(height,remainingDepth);
                long approachRows=2L*run-(run==height?1:0),approachCells=approachRows*rowSize;
                long mapped;
                if(local<approachCells) {
                    long pair=local/rowSize;
                    int forward=(int)(pair/2),up=forward+(int)(pair%2);
                    mapped=(height-1-up)*remainingDepth*rowSize+(long)forward*rowSize+local%rowSize;
                } else {
                    mapped=local-approachCells;
                    List<Long> intervals=new ArrayList<>();
                    for(int forward=0;forward<run;forward++) for(int head=0;head<2 && forward+head<height;head++)
                        intervals.add((height-1-forward-head)*remainingDepth*rowSize+(long)forward*rowSize);
                    intervals.sort(Long::compare);
                    for(long start:intervals) if(start<=mapped) mapped+=rowSize;
                }
                int y=maxY-(int)(mapped/(remainingDepth*rowSize));
                long row=mapped%(remainingDepth*rowSize);
                int forward=naturalStartDepth+(int)(row/rowSize);
                long faceCell=row%rowSize;
                int x=minX+(int)(faceCell%width()),z=minZ+(int)(faceCell/width());
                return new WorkStep(new Location(world,x+inward.getModX()*forward,y,z+inward.getModZ()*forward),StepKind.BREAK,null,null);
            }            if (kind==JobKind.TUNNEL && layeredTunnel) {
                long rowSize=width()*length();
                int y=minY+(int)(cell/rowSize);
                long row=cell%rowSize;
                int z=minZ+(int)(row/width());
                int column=(int)(row%width());
                int x=((z-minZ+y-minY)&1)==0?minX+column:maxX-column;
                return new WorkStep(new Location(world,x+inward.getModX()*layer,y+inward.getModY()*layer,z+inward.getModZ()*layer),StepKind.BREAK,null,null);
            }
            int x=minX+(int)(cell/(height*length()));
            int y=(kind==JobKind.WALL?maxY-(int)((cell/length())%height):minY+(int)((cell/length())%height)), z=minZ+(int)(cell%length());
            return new WorkStep(new Location(world,x+inward.getModX()*layer,y+inward.getModY()*layer,z+inward.getModZ()*layer),StepKind.BREAK,null,null);
        }
    }

    static final class FlattenPlan {
        final UUID worldId;
        final int minX, maxX, minZ, maxZ, targetY;
        final Material floorMaterial;
        boolean fillHoles = true;
        boolean serpentine = true;
        int cursorX, cursorZ;
        int currentY = Integer.MIN_VALUE;
        boolean floorPending;
        boolean finished;
        boolean layers, scannedSurface, layerExhausted;
        int layerY = Integer.MIN_VALUE;

        boolean prepareLayer() {
            if (scannedSurface) return true;
            World world = Bukkit.getWorld(worldId);
            if (world == null) return false;
            if (layerY == Integer.MIN_VALUE) layerY = targetY;
            for (int i = 0; i < 32 && !finished; i++) {
                layerY = Math.max(layerY, world.getHighestBlockYAt(cursorX, cursorZ, HeightMap.WORLD_SURFACE));
                advanceColumn();
            }
            if (!finished) return false;
            scannedSurface = true;
            resetLayerCursor();
            return true;
        }

        private void resetLayerCursor() { cursorX = minX; cursorZ = minZ; finished = false; layerExhausted = false; }
        void advanceLayer() {
            layerY--;
            resetLayerCursor();
            if (layerY < targetY) finished = true;
        }

        private final Map<Location,Boolean> enclosedWater = new HashMap<>();
        boolean fillableFloor(Block block) {
            if(fillableHole(block))return true;
            if(!block.isLiquid() && !block.isEmpty() && !block.isPassable())return false;
            boolean water=false;
            for(int y=block.getY();y>=block.getWorld().getMinHeight();y--) {
                Block below=block.getWorld().getBlockAt(block.getX(),y,block.getZ());
                if(below.isLiquid()){water=true;break;}
                if(below.getType().isSolid())break;
            }
            if(!water)return false;
            Boolean cached=enclosedWater.get(block.getLocation());if(cached!=null)return cached;
            Set<Location> visited=new HashSet<>();ArrayDeque<Block> queue=new ArrayDeque<>();
            queue.add(block);visited.add(block.getLocation());boolean closed=true;
            while(!queue.isEmpty()) {
                Block current=queue.removeFirst();
                for(BlockFace face:List.of(BlockFace.NORTH,BlockFace.SOUTH,BlockFace.EAST,BlockFace.WEST)) {
                    Block next=current.getRelative(face);
                    if(next.getType().isSolid())continue;
                    if(next.getX()<minX || next.getX()>maxX || next.getZ()<minZ || next.getZ()>maxZ){closed=false;continue;}
                    if(visited.add(next.getLocation()))queue.addLast(next);
                }
            }
            for(Location location:visited)enclosedWater.put(location,closed);
            return closed;
        }

        static boolean fillableHole(Block block) {
            if(block.isLiquid() || !(block.isEmpty() || block.isPassable())) return false;
            for(int y=block.getY()-1;y>=block.getWorld().getMinHeight();y--) {
                Block below=block.getWorld().getBlockAt(block.getX(),y,block.getZ());
                if(below.isLiquid()) return false;
                if(below.getType().isSolid()) return true;
            }
            return false;
        }

        List<WorkStep> claimLayerCell() {
            if (finished || layerExhausted) return List.of();
            World world = Bukkit.getWorld(worldId);
            if (world == null) return List.of();
            Block block = world.getBlockAt(cursorX, layerY, cursorZ);
            WorkStep step = null;
            if (layerY > targetY && !block.getType().isAir() && !ignoredVegetation(block.getType())) step = new WorkStep(block.getLocation(), StepKind.BREAK, null, null);
            else if (layerY == targetY && fillHoles && fillableFloor(block)) step = new WorkStep(block.getLocation(), StepKind.PLACE, floorMaterial, null);
            advanceColumn();
            if (finished) { finished = false; layerExhausted = true; }
            return step == null ? List.of() : List.of(step);
        }

        FlattenPlan(UUID worldId, int minX, int maxX, int minZ, int maxZ, int targetY, Material floorMaterial) {
            this.worldId = worldId;
            this.minX = minX;
            this.maxX = maxX;
            this.minZ = minZ;
            this.maxZ = maxZ;
            this.targetY = targetY;
            this.floorMaterial = floorMaterial;
            this.cursorX = minX;
            this.cursorZ = minZ;
        }

        List<WorkStep> claimColumn() {
            if (finished) return List.of();
            FlattenPlan column = new FlattenPlan(worldId, cursorX, cursorX, cursorZ, cursorZ, targetY, floorMaterial);
            column.fillHoles = fillHoles;
            column.currentY = currentY;
            column.floorPending = floorPending;
            advanceColumn();
            List<WorkStep> result = new ArrayList<>();
            WorkStep step;
            while ((step = column.next()) != null) result.add(step);
            return result;
        }

        WorkStep next() {
            World world = Bukkit.getWorld(worldId);
            if (world == null || finished) return null;
            while (!finished) {
                if (currentY == Integer.MIN_VALUE) {
                    currentY = Math.max(targetY, world.getHighestBlockYAt(cursorX, cursorZ, HeightMap.WORLD_SURFACE));
                    floorPending = true;
                }
                while (currentY > targetY) {
                    int y = currentY--;
                    Block block = world.getBlockAt(cursorX, y, cursorZ);
                    if (!block.getType().isAir() && !ignoredVegetation(block.getType())) return new WorkStep(block.getLocation(), StepKind.BREAK, null, null);
                }
                if (floorPending) {
                    floorPending = false;
                    Block floor = world.getBlockAt(cursorX, targetY, cursorZ);
                    if (fillHoles && fillableFloor(floor)) {
                        WorkStep result = new WorkStep(floor.getLocation(), StepKind.PLACE, floorMaterial, null);
                        advanceColumn();
                        return result;
                    }
                }
                advanceColumn();
            }
            return null;
        }

        private void advanceColumn() {
            currentY = Integer.MIN_VALUE;
            int direction = serpentine && (cursorZ - minZ) % 2 != 0 ? -1 : 1;
            if (direction > 0 && cursorX < maxX || direction < 0 && cursorX > minX) cursorX += direction;
            else if (cursorZ < maxZ) {
                cursorZ++;
                cursorX = serpentine && (cursorZ - minZ) % 2 != 0 ? maxX : minX;
            } else finished = true;
        }

        long totalColumns() { return (long) (maxX - minX + 1) * (maxZ - minZ + 1); }
        long remainingColumns() {
            if (finished) return 0;
            long completedRows = (long) (cursorZ - minZ) * (maxX - minX + 1);
            long completedInRow = serpentine && (cursorZ - minZ) % 2 != 0 ? maxX - cursorX : cursorX - minX;
            return Math.max(1, totalColumns() - completedRows - completedInRow);
        }
    }

    static final class PlayerData {
        final UUID owner;
        String staffToken;
        boolean flattenFillHoles = true;
        boolean lining, lighting = true;
        final List<UUID> workers = new ArrayList<>();
        final Map<UUID, Location> deliveryOrigins = new HashMap<>();
        final Map<UUID, Location> workerLocations = new HashMap<>();
        final Set<UUID> deliverToOwner = new HashSet<>();
        final Map<UUID, String> workerNames = new HashMap<>();
        final Map<Location, Material> temporaryBlocks = new LinkedHashMap<>();
        Location chest;
        Location deliveryPoint;
        final Map<UUID, Location> deliveryOnce = new HashMap<>();
        Location rally;
        Job job;
        int depth;
        int size = 3;
        StairMode stairs = StairMode.CLOCKWISE;
        boolean follow = true;

        PlayerData(UUID owner, int defaultDepth) { this.owner = owner; this.depth = defaultDepth; }
        boolean granted() { return staffToken != null && !staffToken.isBlank(); }
    }

    private Domain() {}
}







