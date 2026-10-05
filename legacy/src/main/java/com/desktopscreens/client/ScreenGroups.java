package com.desktopscreens.client;

import com.desktopscreens.ScreenBlock;
import com.desktopscreens.ScreenBlockEntity;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.Collections;
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
    private static World level;

    private ScreenGroups() {}

    /** The joined screen the block at {@code pos} belongs to, or null if there's no screen there. */
    static Group of(World world, BlockPos pos) {
        int now = ScreenBlockEntity.clientChanges();
        if (now != changes || world != level) {
            BY_POS.clear();
            changes = now;
            level = world;
        }
        Group group = BY_POS.get(pos);
        if (group == null) {
            List<BlockPos> members = ScreenBlock.group(world, pos);
            if (members.isEmpty()) return null;
            group = new Group(world, members);
            for (BlockPos member : members) BY_POS.put(member, group);
        }
        return group;
    }

    /** One joined screen, as seen from the front: columns count to the right, rows up, in blocks from one of its blocks. */
    static final class Group {
        final EnumFacing facing;
        /** The viewer's right, looking at the front. */
        private final EnumFacing right;
        private final BlockPos origin;
        final int minCol, minRow, cols, rows;
        /** The bottom-left block, seen from the front: whoever turned it on owns the whole screen. */
        private final BlockPos anchor;
        /** All its blocks. */
        final List<BlockPos> members;
        /** The picture {@link ScreenRenderer} last drew the game's cover for (once per frame). */
        Object coverPicture;

        Group(World world, List<BlockPos> members) {
            this.members = Collections.unmodifiableList(new ArrayList<>(members));
            origin = members.get(0);
            facing = world.getBlockState(origin).getValue(ScreenBlock.FACING);
            right = facing.rotateYCCW();
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
            return (p.getX() - origin.getX()) * right.getXOffset() + (p.getZ() - origin.getZ()) * right.getZOffset();
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
        Vec3d frontPoint(double col, double row) {
            // Column 0 starts at the origin block's left edge, seen from the front; the glass is 2/16 in front of the
            // block's back, 6/16 behind its middle.
            double x = origin.getX() + 0.5 + (col - 0.5) * right.getXOffset() - 0.375 * facing.getXOffset();
            double z = origin.getZ() + 0.5 + (col - 0.5) * right.getZOffset() - 0.375 * facing.getZOffset();
            return new Vec3d(x, origin.getY() + row, z);
        }

        /** The point of the glass nearest to {@code p}: how far away the screen is, for its sound. */
        Vec3d nearestPoint(Vec3d p) {
            // Undoes frontPoint: columns along the viewer's right, from the origin block's left edge.
            double col = (p.x - origin.getX() - 0.5) * right.getXOffset() + (p.z - origin.getZ() - 0.5) * right.getZOffset() + 0.5;
            double row = p.y - origin.getY();
            return frontPoint(MathHelper.clamp(col, minCol, minCol + cols), MathHelper.clamp(row, minRow, minRow + rows));
        }

        /** The block that speaks for the whole screen: its owner, and whom it's shared with. */
        BlockPos anchor() {
            return anchor;
        }

        private ScreenBlockEntity anchorEntity(World world) {
            TileEntity te = world.getTileEntity(anchor);
            return te instanceof ScreenBlockEntity ? (ScreenBlockEntity) te : null;
        }

        /** Who turned it on, or null while it's off. */
        UUID owner(World world) {
            ScreenBlockEntity screen = anchorEntity(world);
            return screen != null ? screen.owner() : null;
        }

        String ownerName(World world) {
            ScreenBlockEntity screen = anchorEntity(world);
            return screen != null ? screen.ownerName() : "";
        }

        boolean ownedBy(World world, UUID player) {
            return player.equals(owner(world));
        }

        /** Which screen it is ({@link ScreenBlockEntity#screenId}), or null. */
        UUID screenId(World world) {
            ScreenBlockEntity screen = anchorEntity(world);
            return screen != null ? screen.screenId() : null;
        }

        /** The players its owner shares it with. */
        List<ScreenBlockEntity.Watcher> shared(World world) {
            ScreenBlockEntity screen = anchorEntity(world);
            return screen != null ? screen.shared() : Collections.emptyList();
        }

        /** Whether its owner shares it with {@code player} (this client's player): on its own list, or all their screens. */
        boolean sharedWith(World world, UUID player) {
            ScreenBlockEntity screen = anchorEntity(world);
            return screen != null && screen.owner() != null && (screen.sharedWith(player) || SharingInfo.sharesAll(screen.owner()));
        }
    }
}
