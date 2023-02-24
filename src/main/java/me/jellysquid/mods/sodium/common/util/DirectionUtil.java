package me.jellysquid.mods.sodium.common.util;

import java.util.Arrays;
import java.util.stream.IntStream;

import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Direction.Axis;

/**
 * Contains a number of cached arrays to avoid allocations since calling Enum#values() requires the backing array to
 * be cloned every time.
 */
public class DirectionUtil {
    public static final Direction[] ALL_DIRECTIONS = Direction.values();

    // Provides the same order as enumerating Direction and checking the axis of each value
    public static final Direction[] HORIZONTAL_DIRECTIONS = new Direction[] { Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST };

    private static final Direction[] OPPOSITE_DIRECTIONS = Arrays.stream(ALL_DIRECTIONS)
            .map(Direction::getOpposite)
            .toArray(Direction[]::new);

    public static final int[] AXIS_NORMAL_FORM = Arrays.stream(DirectionUtil.ALL_DIRECTIONS).flatMapToInt(dir -> {
        Axis axis = dir.getAxis();
        int axisIndex = axis == Axis.X ? 0 : axis == Axis.Y ? 1 : 2; // x = 0, y = 1, z = 2
        // only one of the offsets is non-zero
        return IntStream.of(axisIndex, dir.getOffsetX() + dir.getOffsetY() + dir.getOffsetZ());
    }).toArray();

    public static int getAxisIndex(Direction dir) {
        return AXIS_NORMAL_FORM[dir.ordinal() << 1];
    }

    public static int getAxisSign(Direction dir) {
        return AXIS_NORMAL_FORM[(dir.ordinal() << 1) + 1];
    }

    // Direction#byId is slow in the absence of Lithium
    public static Direction getOpposite(Direction dir) {
        return OPPOSITE_DIRECTIONS[dir.ordinal()];
    }
}
