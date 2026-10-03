package pl.marek.kingdomminions;

import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Proxy;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class LiquidSafetyTest {
    private Block block(boolean liquid, Map<BlockFace, Block> neighbors) {
        return (Block) Proxy.newProxyInstance(Block.class.getClassLoader(), new Class[]{Block.class}, (proxy, method, args) -> {
            if (method.getName().equals("isLiquid")) return liquid;
            if (method.getName().equals("getRelative")) return neighbors.getOrDefault((BlockFace) args[0], block(false, Map.of()));
            if (method.getName().equals("toString")) return "test block";
            return null;
        });
    }
    private Block detected(Block target) throws Exception {
        var method = WorkerManager.class.getDeclaredMethod("firstLiquid", Block.class);
        method.setAccessible(true);
        return (Block) method.invoke(null, target);
    }
    @Test void identifiesExactLiquidOnEveryFace() throws Exception {
        for (BlockFace face : new BlockFace[]{BlockFace.UP, BlockFace.DOWN, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST}) {
            Block liquid = block(true, Map.of());
            assertSame(liquid, detected(block(false, Map.of(face, liquid))));
        }
    }
    @Test void detectsLiquidInExcavationAndLeavesDryBlocksAlone() throws Exception {
        Block liquid = block(true, Map.of());
        assertSame(liquid, detected(liquid));
        assertNull(detected(block(false, Map.of())));
    }
}
