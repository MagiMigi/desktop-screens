package com.desktopscreens.client;

import com.desktopscreens.ScreenBlock;
import com.desktopscreens.ScreenBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The joined screens in the client's world, worked out the first time one of their blocks is drawn and kept until
 * any screen changes ({@link ScreenBlockEntity#clientChanges}). Render thread only.
 */
final class ScreenGroups {
    /** The bezel's width, in blocks. */
    static final float BEZEL = 1 / 16f;
    private static final Map<BlockPos, Group> BY_POS = new HashMap<>();
    private static int changes;
    private static Level level;

    private ScreenGroups() {}

    /** The joined screen the block at {@code pos} belongs to, or null if there's no screen there. */
    static Group of(Level lvl, BlockPos pos) {
        int now = ScreenBlockEntity.clientChanges();
        if (now != changes || lvl != level) {
            BY_POS.clear();
            changes = now;
            level = lvl;
        }
        Group group = BY_POS.get(pos);
        if (group == null) {
            List<BlockPos> members = ScreenBlock.group(lvl, pos);
            if (members.isEmpty()) return null;
            group = new Group(lvl, members);
            for (BlockPos member : members) BY_POS.put(member, group);
        }
        return group;
    }

    /** One joined screen, as seen from the front: columns count to the right, rows up, in blocks from one of its blocks. */
    static final class Group {
        final Direction facing;
        /** The viewer's right, looking at the front. */
        private final Direction right;
        private final BlockPos origin;
        final int minCol, minRow, cols, rows;
        /** The bottom-left block, seen from the front: whoever turned it on owns the whole screen. */
        private final BlockPos anchor;
        /** All its blocks. */
        final List<BlockPos> members;
        /** The picture and buffers {@link ScreenRenderer} last drew the game's cover for (once per frame and pass). */
        Object coverPicture, coverBuffers;

        Group(Level level, List<BlockPos> members) {
            this.members = List.copyOf(members);
            origin = members.get(0);
            facing = level.getBlockState(origin).getValue(ScreenBlock.FACING);
            right = facing.getCounterClockWise();
            int minC = Integer.MAX_VALUE, maxC = Integer.MIN_VALUE, minR = Integer.MAX_VALUE, maxR = Integer.MIN_VALUE;
            BlockPos first = origin;
            for (BlockPos p : members) {
                int c = col(p), r = row(p);
                minC = Math.min(minC, c);
                maxC = Math.max(maxC, c);
                minR = Math.min(minR, r);
                maxR = Math.max(maxR, r);
                if (r < row(first) || r == row(first) && c < col(first)) first = p;
            }
            minCol = minC;
            minRow = minR;
            cols = maxC - minC + 1;
            rows = maxR - minR + 1;
            anchor = first;
        }

        int col(BlockPos p) {
            return (p.getX() - origin.getX()) * right.getStepX() + (p.getZ() - origin.getZ()) * right.getStepZ();
        }

        int row(BlockPos p) {
            return p.getY() - origin.getY();
        }

        /**
         * Where a picture of this size goes, {left, bottom, width, height} in columns and rows: inside the outer
         * bezel, as big as fits, centered (black bars where the shape doesn't match).
         */
        float[] pictureRect(int pictureWidth, int pictureHeight) {
            float areaW = cols - 2 * BEZEL, areaH = rows - 2 * BEZEL;
            float scale = Math.min(areaW / pictureWidth, areaH / pictureHeight);
            float w = pictureWidth * scale, h = pictureHeight * scale;
            return new float[] {minCol + BEZEL + (areaW - w) / 2, minRow + BEZEL + (areaH - h) / 2, w, h};
        }

        /** A point on the front of the glass, in the world, from columns (from a block's left edge) and rows (from its bottom). */
        Vec3 frontPoint(double col, double row) {
            // Column 0 starts at the origin block's left edge, seen from the front; the glass is 2/16 in front of the
            // block's back, 6/16 behind its middle.
            double x = origin.getX() + 0.5 + (col - 0.5) * right.getStepX() - 0.375 * facing.getStepX();
            double z = origin.getZ() + 0.5 + (col - 0.5) * right.getStepZ() - 0.375 * facing.getStepZ();
            return new Vec3(x, origin.getY() + row, z);
        }

        /** The point of the glass nearest to {@code p}: how far away the screen is, for its sound. */
        Vec3 nearestPoint(Vec3 p) {
            // Undoes frontPoint: columns along the viewer's right, from the origin block's left edge.
            double col = (p.x - origin.getX() - 0.5) * right.getStepX() + (p.z - origin.getZ() - 0.5) * right.getStepZ() + 0.5;
            double row = p.y - origin.getY();
            return frontPoint(Math.clamp(col, minCol, minCol + cols), Math.clamp(row, minRow, minRow + rows));
        }

        /** The block that speaks for the whole screen: its owner, and whom it's shared with. */
        BlockPos anchor() {
            return anchor;
        }

        private ScreenBlockEntity anchorEntity(Level level) {
            return level.getBlockEntity(anchor) instanceof ScreenBlockEntity screen ? screen : null;
        }

        /** Who turned it on, or null while it's off. */
        UUID owner(Level level) {
            ScreenBlockEntity screen = anchorEntity(level);
            return screen != null ? screen.owner() : null;
        }

        String ownerName(Level level) {
            ScreenBlockEntity screen = anchorEntity(level);
            return screen != null ? screen.ownerName() : "";
        }

        boolean ownedBy(Level level, UUID player) {
            return player.equals(owner(level));
        }

        /** Which screen it is ({@link ScreenBlockEntity#screenId}), or null. */
        UUID screenId(Level level) {
            ScreenBlockEntity screen = anchorEntity(level);
            return screen != null ? screen.screenId() : null;
        }

        /** The players its owner shares it with. */
        List<ScreenBlockEntity.Watcher> shared(Level level) {
            ScreenBlockEntity screen = anchorEntity(level);
            return screen != null ? screen.shared() : List.of();
        }

        /** Whether its owner shares it with {@code player} (this client's player): on its own list, or all their screens. */
        boolean sharedWith(Level level, UUID player) {
            ScreenBlockEntity screen = anchorEntity(level);
            return screen != null && screen.owner() != null && (screen.sharedWith(player) || SharingInfo.sharesAll(screen.owner()));
        }
    }
}
