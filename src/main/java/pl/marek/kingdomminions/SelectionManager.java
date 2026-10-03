package pl.marek.kingdomminions;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.util.RayTraceResult;

import java.util.*;

final class SelectionManager {
    private static final Particle.DustOptions BORDER = new Particle.DustOptions(Color.fromRGB(255, 190, 45), 1.15f);
    private static final Particle.DustOptions GRID = new Particle.DustOptions(Color.fromRGB(75, 220, 255), 0.75f);
    private static final Particle.DustOptions CORNER = new Particle.DustOptions(Color.fromRGB(255, 70, 70), 1.4f);

    private final KingdomMinionsPlugin plugin;
    private final Map<UUID, Domain.Selection> selections = new HashMap<>();

    SelectionManager(KingdomMinionsPlugin plugin) { this.plugin = plugin; }

    Domain.Selection get(Player player) { return selections.get(player.getUniqueId()); }
    void clear(Player player) { selections.remove(player.getUniqueId()); }

    boolean click(Player player, Block clicked, BlockFace face) {
        Domain.Selection current = selections.get(player.getUniqueId());
        if (current == null || current.complete()) {
            Domain.Selection fresh = new Domain.Selection(clicked.getLocation(), null, face);
            selections.put(player.getUniqueId(), fresh);
            player.sendActionBar(Component.text(Lang.text(player, "text.099"), NamedTextColor.GOLD));
            return false;
        }
        Location projected = project(current.first(), clicked.getLocation(), current.face());
        if (!valid(current.first(), projected)) {
            int max = plugin.getConfig().getInt("selection.max-axis", 0);
            player.sendActionBar(Component.text(max > 0 ? Lang.text(player, "text.127", max) : Lang.text(player, "text.100"), NamedTextColor.RED));
            return false;
        }
        Domain.Selection complete = new Domain.Selection(current.first(), projected, current.face());
        selections.put(player.getUniqueId(), complete);
        render(player, complete, true);
        player.sendActionBar(Component.text(Lang.text(player, "text.128", complete.dx(), complete.dy(), complete.dz()), NamedTextColor.AQUA));
        return true;
    }

    void tick() {
        int reach = plugin.getConfig().getInt("selection.reach", 12);
        for (Iterator<Map.Entry<UUID, Domain.Selection>> iterator = selections.entrySet().iterator(); iterator.hasNext();) {
            Map.Entry<UUID, Domain.Selection> entry = iterator.next();
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player == null || !player.isOnline() || !plugin.isHoldingAuthorizedStaff(player)) { iterator.remove(); continue; }
            Domain.Selection selection = entry.getValue();
            if (selection.complete()) { render(player, selection, true); continue; }
            RayTraceResult ray = player.rayTraceBlocks(reach, FluidCollisionMode.NEVER);
            Location second = ray == null || ray.getHitBlock() == null ? selection.first() : project(selection.first(), ray.getHitBlock().getLocation(), selection.face());
            if (!valid(selection.first(), second)) second = selection.first();
            Domain.Selection preview = new Domain.Selection(selection.first(), second, selection.face());
            render(player, preview, false);
            player.sendActionBar(Component.text(Lang.text(player, "text.129", preview.dx(), preview.dy(), preview.dz()), NamedTextColor.YELLOW));
        }
    }

    private boolean valid(Location a, Location b) {
        if (a.getWorld() == null || b.getWorld() == null || !a.getWorld().equals(b.getWorld())) return false;
        int max = plugin.getConfig().getInt("selection.max-axis", 32);
        if (max <= 0) return true;
        return Math.abs(a.getBlockX() - b.getBlockX()) < max && Math.abs(a.getBlockY() - b.getBlockY()) < max && Math.abs(a.getBlockZ() - b.getBlockZ()) < max;
    }

    private static Location project(Location first, Location target, BlockFace face) {
        Location result = target.clone();
        switch (face) {
            case EAST, WEST -> result.setX(first.getBlockX());
            case UP, DOWN -> result.setY(first.getBlockY());
            case NORTH, SOUTH -> result.setZ(first.getBlockZ());
            default -> { }
        }
        return result;
    }

    private void render(Player player, Domain.Selection selection, boolean complete) {
        Location a = selection.first(), b = selection.second() == null ? a : selection.second();
        World world = a.getWorld();
        if (world == null) return;
        double spacing = Math.max(.2, plugin.getConfig().getDouble("selection.particle-spacing", .35));
        double minX = Math.min(a.getBlockX(), b.getBlockX()), maxX = Math.max(a.getBlockX(), b.getBlockX()) + 1;
        double minY = Math.min(a.getBlockY(), b.getBlockY()), maxY = Math.max(a.getBlockY(), b.getBlockY()) + 1;
        double minZ = Math.min(a.getBlockZ(), b.getBlockZ()), maxZ = Math.max(a.getBlockZ(), b.getBlockZ()) + 1;
        BlockFace face = selection.face();
        if (face == BlockFace.UP || face == BlockFace.DOWN) {
            double y = face == BlockFace.UP ? a.getBlockY() + 1.03 : a.getBlockY() - .03;
            rectangle(player, minX, y, minZ, maxX, y, maxZ, spacing, complete);
            gridHorizontal(player, minX, maxX, minZ, maxZ, y, spacing);
        } else if (face == BlockFace.EAST || face == BlockFace.WEST) {
            double x = face == BlockFace.EAST ? a.getBlockX() + 1.03 : a.getBlockX() - .03;
            rectangleX(player, x, minY, minZ, x, maxY, maxZ, spacing, complete);
            gridX(player, minY, maxY, minZ, maxZ, x, spacing);
        } else {
            double z = face == BlockFace.SOUTH ? a.getBlockZ() + 1.03 : a.getBlockZ() - .03;
            rectangleZ(player, minX, minY, z, maxX, maxY, z, spacing, complete);
            gridZ(player, minX, maxX, minY, maxY, z, spacing);
        }
        marker(player, surfaceCorner(a, face));
    }

    private void rectangle(Player p,double x1,double y,double z1,double x2,double ignored,double z2,double s,boolean complete){
        line(p,x1,y,z1,x2,y,z1,s,BORDER);line(p,x1,y,z2,x2,y,z2,s,BORDER);line(p,x1,y,z1,x1,y,z2,s,BORDER);line(p,x2,y,z1,x2,y,z2,s,BORDER);
        if(complete){corner(p,x1,y,z1);corner(p,x2,y,z1);corner(p,x1,y,z2);corner(p,x2,y,z2);}
    }
    private void rectangleX(Player p,double x,double y1,double z1,double ignored,double y2,double z2,double s,boolean complete){
        line(p,x,y1,z1,x,y2,z1,s,BORDER);line(p,x,y1,z2,x,y2,z2,s,BORDER);line(p,x,y1,z1,x,y1,z2,s,BORDER);line(p,x,y2,z1,x,y2,z2,s,BORDER);
        if(complete){corner(p,x,y1,z1);corner(p,x,y2,z1);corner(p,x,y1,z2);corner(p,x,y2,z2);}
    }
    private void rectangleZ(Player p,double x1,double y1,double z,double x2,double y2,double ignored,double s,boolean complete){
        line(p,x1,y1,z,x2,y1,z,s,BORDER);line(p,x1,y2,z,x2,y2,z,s,BORDER);line(p,x1,y1,z,x1,y2,z,s,BORDER);line(p,x2,y1,z,x2,y2,z,s,BORDER);
        if(complete){corner(p,x1,y1,z);corner(p,x2,y1,z);corner(p,x1,y2,z);corner(p,x2,y2,z);}
    }

    private void gridHorizontal(Player p,double minX,double maxX,double minZ,double maxZ,double y,double spacing){
        int step=gridStep(Math.max(maxX-minX,maxZ-minZ));
        for(double x=Math.ceil(minX)+step-1;x<maxX;x+=step)line(p,x,y,minZ,x,y,maxZ,Math.max(1.25,spacing*3),GRID);
        for(double z=Math.ceil(minZ)+step-1;z<maxZ;z+=step)line(p,minX,y,z,maxX,y,z,Math.max(1.25,spacing*3),GRID);
    }
    private void gridX(Player p,double minY,double maxY,double minZ,double maxZ,double x,double spacing){
        int step=gridStep(Math.max(maxY-minY,maxZ-minZ));
        for(double y=Math.ceil(minY)+step-1;y<maxY;y+=step)line(p,x,y,minZ,x,y,maxZ,Math.max(1.25,spacing*3),GRID);
        for(double z=Math.ceil(minZ)+step-1;z<maxZ;z+=step)line(p,x,minY,z,x,maxY,z,Math.max(1.25,spacing*3),GRID);
    }
    private void gridZ(Player p,double minX,double maxX,double minY,double maxY,double z,double spacing){
        int step=gridStep(Math.max(maxX-minX,maxY-minY));
        for(double x=Math.ceil(minX)+step-1;x<maxX;x+=step)line(p,x,minY,z,x,maxY,z,Math.max(1.25,spacing*3),GRID);
        for(double y=Math.ceil(minY)+step-1;y<maxY;y+=step)line(p,minX,y,z,maxX,y,z,Math.max(1.25,spacing*3),GRID);
    }

    private static int gridStep(double span){return Math.max(1, (int)Math.ceil(span / 8.0));}

    private static Location surfaceCorner(Location block, BlockFace face){
        Location result=block.clone().add(.5,.5,.5);
        return result.add(face.getModX()*.52,face.getModY()*.52,face.getModZ()*.52);
    }
    private static void marker(Player p,Location l){
        for(double d=-.25;d<=.25;d+=.125){particle(p,l.getX()+d,l.getY(),l.getZ(),CORNER);particle(p,l.getX(),l.getY()+d,l.getZ(),CORNER);particle(p,l.getX(),l.getY(),l.getZ()+d,CORNER);}
    }
    private static void corner(Player p,double x,double y,double z){particle(p,x,y,z,CORNER);}
    private static void line(Player p,double ax,double ay,double az,double bx,double by,double bz,double spacing,Particle.DustOptions dust){
        double dx=bx-ax,dy=by-ay,dz=bz-az,length=Math.sqrt(dx*dx+dy*dy+dz*dz);int steps=Math.max(1,Math.min(120,(int)Math.ceil(length/spacing)));
        for(int i=0;i<=steps;i++){double t=(double)i/steps;particle(p,ax+dx*t,ay+dy*t,az+dz*t,dust);}
    }
    private static void particle(Player p,double x,double y,double z,Particle.DustOptions dust){p.spawnParticle(Particle.DUST,x,y,z,1,0,0,0,0,dust);}
}
