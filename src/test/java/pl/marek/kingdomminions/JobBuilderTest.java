package pl.marek.kingdomminions;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.BlockFace;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class JobBuilderTest {
    @Test void hugeExcavationIsRepresentedByCursorWithoutMaterializingBlocks() {
        Domain.ExcavationPlan plan = new Domain.ExcavationPlan(UUID.randomUUID(), Domain.JobKind.DIG,0,64,0,9999,64,9999,100,BlockFace.DOWN,Domain.StairMode.NONE);
        Domain.Job job = new Domain.Job(Domain.JobKind.DIG,List.of());
        job.excavation = plan;
        assertEquals(10_000_000_000L,job.remainingEstimate());
        assertTrue(job.pending.isEmpty());
        plan.cursor=500;
        assertEquals(9_999_999_500L,job.remainingEstimate());
        assertFalse(job.sourceEmpty());
    }
    @Test void rectangularStairsCoverThePerimeterInBothDirections() {
        for (boolean clockwise : new boolean[]{true, false}) {
            Set<String> visited = new HashSet<>();
            for (int i = 0; i < 16; i++) {
                int[] step = JobBuilder.stairOffset(3, 7, i, clockwise);
                assertTrue(step[0] >= 0 && step[0] < 3 && step[1] >= 0 && step[1] < 7);
                assertTrue(step[0] == 0 || step[0] == 2 || step[1] == 0 || step[1] == 6);
                assertTrue(visited.add(step[0] + ":" + step[1]));
                int[] next = JobBuilder.stairOffset(3, 7, i + 1, clockwise);
                assertEquals(1, Math.abs(step[0] - next[0]) + Math.abs(step[1] - next[1]));
            }
            assertArrayEquals(JobBuilder.stairOffset(3,7,0,clockwise), JobBuilder.stairOffset(3,7,16,clockwise));
        }
    }
    @Test void selectionDimensionsIncludeBothCorners() {
        Domain.Selection selection = new Domain.Selection(new Location(null, 10, 20, 30), new Location(null, 14, 20, 37), BlockFace.UP);
        assertAll(() -> assertEquals(5, selection.dx()), () -> assertEquals(1, selection.dy()), () -> assertEquals(8, selection.dz()));
    }

    @Test void threeByThreeStairMakesOneFullUniqueSpiralBeforeRepeating() {
        Set<String> coordinates = new HashSet<>();
        for (int depth = 0; depth < 8; depth++) {
            int[] step = JobBuilder.stairOffset(3, depth, true);
            assertTrue(coordinates.add(step[0] + ":" + step[1]));
        }
        assertArrayEquals(JobBuilder.stairOffset(3, 0, true), JobBuilder.stairOffset(3, 8, true));
    }

    @Test void oppositeDirectionStartsOnOtherEndOfPerimeter() {
        int[] clockwise = JobBuilder.stairOffset(5, 0, true);
        int[] counter = JobBuilder.stairOffset(5, 0, false);
        assertNotEquals(clockwise[0] + ":" + clockwise[1], counter[0] + ":" + counter[1]);
    }

    @Test void stairsRejectDegenerateArea() {
        assertThrows(IllegalArgumentException.class, () -> JobBuilder.stairOffset(1, 0, true));
    }

    @Test void stairPlacementUsesCobblestoneStairsFacingUphill() {
        Domain.WorkStep step = JobBuilder.stairPlacement(new Location(null, 1, 64, 2), BlockFace.EAST);
        assertAll(
                () -> assertEquals(Domain.StepKind.PLACE, step.kind()),
                () -> assertEquals(Material.COBBLESTONE_STAIRS, step.material()),
                () -> assertEquals(BlockFace.WEST, step.facing())
        );
    }

    @Test void stoneTakesAboutThirtyPercentLessTimeAndHardBlocksAreCapped() {
        assertAll(
                () -> assertEquals(14, WorkerManager.workTicksForHardness(1.5f, 14, 8, 120)),
                () -> assertEquals(8, WorkerManager.workTicksForHardness(.1f, 14, 8, 120)),
                () -> assertEquals(120, WorkerManager.workTicksForHardness(50f, 14, 8, 120))
        );
    }

    @Test void miningThroughputIncreasesByFortyPercent() {
        assertEquals(10, WorkerManager.acceleratedMiningTicks(14));
        assertEquals(100, WorkerManager.acceleratedMiningTicks(140));
        assertEquals(1, WorkerManager.acceleratedMiningTicks(1));
    }

    @Test void workersReserveDifferentStepsUntilCompletion() {
        Domain.WorkStep a = new Domain.WorkStep(new Location(null, 1, 2, 3), Domain.StepKind.BREAK, null, null);
        Domain.WorkStep b = new Domain.WorkStep(new Location(null, 4, 5, 6), Domain.StepKind.BREAK, null, null);
        Domain.Job job = new Domain.Job(Domain.JobKind.DIG, List.of(a, b));
        UUID one = UUID.randomUUID(), two = UUID.randomUUID();
        assertAll(() -> assertSame(a, job.assign(one)), () -> assertSame(b, job.assign(two)), () -> assertSame(a, job.assign(one)));
        job.complete(one);
        assertNull(job.assign(one));
    }

    @Test void interruptedWorkerReturnsItsStepToTheFront() {
        Domain.WorkStep first = new Domain.WorkStep(new Location(null, 1, 2, 3), Domain.StepKind.BREAK, null, null);
        Domain.WorkStep second = new Domain.WorkStep(new Location(null, 4, 5, 6), Domain.StepKind.BREAK, null, null);
        Domain.Job job = new Domain.Job(Domain.JobKind.DIG, List.of(first, second));
        UUID interrupted = UUID.randomUUID(), replacement = UUID.randomUUID();
        assertSame(first, job.assign(interrupted));
        job.remainingTicks.put(interrupted, 7);
        job.release(interrupted);
        assertAll(() -> assertSame(first, job.assign(replacement)), () -> assertFalse(job.remainingTicks.containsKey(interrupted)));
    }

    @Test void hugeFlattenAreaIsRepresentedWithoutHugeQueue() {
        Domain.FlattenPlan plan = new Domain.FlattenPlan(UUID.randomUUID(), 0, 9999, 0, 9999, 64, null);
        Domain.Job job = new Domain.Job(Domain.JobKind.FLATTEN, List.of());
        job.flatten = plan;
        assertAll(() -> assertEquals(100_000_000L, plan.totalColumns()), () -> assertEquals(100_000_000L, job.remainingEstimate()), () -> assertTrue(job.pending.isEmpty()));
    }
}
