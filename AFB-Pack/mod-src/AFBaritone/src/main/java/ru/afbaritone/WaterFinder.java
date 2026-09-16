package ru.afbaritone;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.Comparator;
import java.util.Optional;

public final class WaterFinder {

    private WaterFinder() {
    }

    /**
     * Ищет ближайшую воду (вода с воздухом сверху = поверхность воды)
     * вокруг центра в радиусе radius по X/Z и +/-8 блоков по Y.
     */
    public static BlockPos find(World world, BlockPos center, int radius) {
        if (world == null || center == null) return null;

        BlockPos.Mutable mutable = new BlockPos.Mutable();
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;

        int r = Math.max(4, Math.min(radius, 64));
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                for (int dy = -8; dy <= 8; dy++) {
                    mutable.set(center.getX() + dx, center.getY() + dy, center.getZ() + dz);
                    BlockState state = world.getBlockState(mutable);
                    if (!state.isOf(Blocks.WATER)) continue;
                    BlockState above = world.getBlockState(mutable.up());
                    if (!above.isAir()) continue;

                    double dist = Math.sqrt(dx * dx + dy * dy * 4.0 + dz * dz); // Y чуть важнее
                    if (dist < bestDist) {
                        bestDist = dist;
                        best = mutable.toImmutable();
                    }
                }
            }
        }
        return best;
    }

    /**
     * Блок, на котором можно стоять рядом с водой (чтобы Baritone шёл туда,
     * а не в воду): соседний воздух над твёрдым блоком.
     */
    public static BlockPos findStandSpot(World world, BlockPos water) {
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;

        for (var dir : new net.minecraft.util.math.Direction[]{
                net.minecraft.util.math.Direction.NORTH,
                net.minecraft.util.math.Direction.SOUTH,
                net.minecraft.util.math.Direction.EAST,
                net.minecraft.util.math.Direction.WEST}) {
            BlockPos adj = water.offset(dir);
            BlockPos ground = adj.down();
            BlockState groundState = world.getBlockState(ground);
            if (!world.getBlockState(adj).isAir()) continue;
            if (!groundState.isSolidBlock(world, ground)) continue;

            double d = 0; // любой подходящий соседний блок ок, берём первый пригодный
            if (d < bestD) {
                bestD = d;
                best = adj;
            }
        }
        return best;
    }

    public static double horizontalDist(Vec3d from, BlockPos to) {
        double dx = from.x - (to.getX() + 0.5);
        double dz = from.z - (to.getZ() + 0.5);
        return Math.sqrt(dx * dx + dz * dz);
    }

    public static Optional<BlockPos> nothing() {
        return Optional.empty();
    }

    @SuppressWarnings("unused")
    private static Comparator<BlockPos> unused() {
        return Comparator.comparingDouble(BlockPos::asLong);
    }
}
