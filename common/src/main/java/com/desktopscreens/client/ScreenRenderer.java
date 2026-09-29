package com.desktopscreens.client;

import com.desktopscreens.ScreenBlock;
import com.desktopscreens.ScreenBlockEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import java.util.UUID;

/**
 * Draws the desktop on the screens in the world that are on for this player. The picture spans the whole joined
 * screen (its bounding box, as big as fits inside the outer bezel, black bars where the shape doesn't match), and
 * each block draws only its own piece of it, so Minecraft's per-block visibility checks just work.
 */
public final class ScreenRenderer implements BlockEntityRenderer<ScreenBlockEntity> {
    private static final float BEZEL = ScreenGroups.BEZEL;
    /** Just in front of the glass, in the model's coordinates (the panel is the back 2/16 of the block). */
    private static final float FRONT_Z = 14 / 16f - 0.002f;
    /** The note on the cover over the game in a picture of its own monitor ({@link GameCover}): a little toward the viewer. */
    private static final float NOTE_Z = FRONT_Z - 0.004f;

    public ScreenRenderer(BlockEntityRendererProvider.Context context) {}

    @Override
    public void render(ScreenBlockEntity screen, float partialTick, PoseStack pose, MultiBufferSource buffers, int light, int overlay) {
        Minecraft mc = Minecraft.getInstance();
        Level level = screen.getLevel();
        BlockState state = screen.getBlockState();
        if (level == null || mc.player == null || !(state.getBlock() instanceof ScreenBlock)) return;
        ScreenGroups.Group group = ScreenGroups.of(level, screen.getBlockPos());
        if (group == null) return;
        UUID me = mc.player.getUUID(), owner = group.owner(level);
        if (owner == null) return;
        boolean mine = owner.equals(me);
        // Our own desktop, or the picture its owner shares with us while they're here (else it would stay frozen on
        // their last one until the server turns the screen off) and we haven't hidden it; anyone else's stays dark.
        boolean ownerHere = mc.getConnection() != null && mc.getConnection().getPlayerInfo(owner) != null;
        WorldScreens.Picture picture = mine ? WorldScreens.picture(mc, group)
                : ownerHere && group.sharedWith(level, me) && !WatchChoices.hidden(level, group) ? SharedPictures.picture(mc, group, owner) : null;
        if (picture == null) return;
        long start = System.nanoTime();
        BlockPos pos = screen.getBlockPos();
        // The picture's place on the whole screen, in blocks (columns right, rows up).
        float[] rect = group.pictureRect(picture.cropW(), picture.cropH());
        float[] cover = coverRect(picture, rect);
        drawPiece(group, pos, state, picture, rect, cover, pose, buffers.getBuffer(picture.renderType()));
        if (cover != null && (group.coverPicture != picture || group.coverBuffers != buffers)) {
            // Once per screen and frame (and pass: shader mods draw the world twice), by whichever block comes first:
            // block by block, it would switch between the picture and the cover's drawing for every one of them.
            group.coverPicture = picture;
            group.coverBuffers = buffers;
            drawCover(mc, level, group, pos, cover, pose, buffers);
        }
        if (mine) WorldScreens.drew(mc, group, System.nanoTime() - start);
    }

    /** This block's piece of the picture; around the cover only, which would otherwise fight it for its place at a distance. */
    private static void drawPiece(ScreenGroups.Group group, BlockPos pos, BlockState state, WorldScreens.Picture p, float[] rect,
                                  float[] cover, PoseStack pose, VertexConsumer out) {
        float[] glass = glass(group, pos, state, rect[0], rect[1], rect[2], rect[3]);
        if (glass == null) return;
        int col = group.col(pos), row = group.row(pos);
        pose.pushPose();
        turn(pose, group.facing);
        if (cover == null || glass[1] <= cover[0] || cover[1] <= glass[0] || glass[3] <= cover[2] || cover[3] <= glass[2]) {
            drawPicture(pose, out, col, row, p, rect, glass);
        } else {
            float x0 = Math.max(glass[0], cover[0]), x1 = Math.min(glass[1], cover[1]);
            drawPicture(pose, out, col, row, p, rect, new float[] {glass[0], x0, glass[2], glass[3]});
            drawPicture(pose, out, col, row, p, rect, new float[] {x1, glass[1], glass[2], glass[3]});
            drawPicture(pose, out, col, row, p, rect, new float[] {x0, x1, glass[2], Math.max(glass[2], cover[2])});
            drawPicture(pose, out, col, row, p, rect, new float[] {x0, x1, Math.min(glass[3], cover[3]), glass[3]});
        }
        pose.popPose();
    }

    /** The {@code piece} ({x0, x1, y0, y1} on the whole screen) of the picture at {@code rect}, if there's any of it. */
    private static void drawPicture(PoseStack pose, VertexConsumer out, int col, int row, WorldScreens.Picture p, float[] rect, float[] piece) {
        if (piece[0] >= piece[1] || piece[2] >= piece[3]) return;
        float left = rect[0], bottom = rect[1], w = rect[2], h = rect[3];
        // The picture's rows run top to bottom.
        float u0 = (p.cropX() + (piece[0] - left) / w * p.cropW()) / p.frameWidth();
        float u1 = (p.cropX() + (piece[1] - left) / w * p.cropW()) / p.frameWidth();
        float vTop = (p.cropY() + (bottom + h - piece[3]) / h * p.cropH()) / p.frameHeight();
        float vBottom = (p.cropY() + (bottom + h - piece[2]) / h * p.cropH()) / p.frameHeight();
        quad(pose, out, col, row, piece, FRONT_Z, u0, u1, vTop, vBottom);
    }

    /** Where the picture at {@code rect} shows the game itself ({@link GameCover}), {x0, x1, y0, y1} on the whole screen; or null. */
    private static float[] coverRect(WorldScreens.Picture p, float[] rect) {
        int[] game = GameCover.place(p.game(), p.cropX(), p.cropY(), p.cropW(), p.cropH(), 0, 0, p.cropW(), p.cropH());
        if (game == null) return null;
        float left = rect[0], bottom = rect[1], w = rect[2], h = rect[3];
        return new float[] {left + game[0] * w / p.cropW(), left + game[2] * w / p.cropW(),
                bottom + h - game[3] * h / p.cropH(), bottom + h - game[1] * h / p.cropH()};
    }

    /** The cover and its note, over every block's glass, drawn from the block at {@code at}. */
    private static void drawCover(Minecraft mc, Level level, ScreenGroups.Group group, BlockPos at, float[] cover,
                                  PoseStack pose, MultiBufferSource buffers) {
        float w = cover[1] - cover[0], h = cover[3] - cover[2];
        int col = group.col(at), row = group.row(at);
        pose.pushPose();
        turn(pose, group.facing);
        VertexConsumer out = buffers.getBuffer(GameCover.renderType(mc));
        for (BlockPos member : group.members) {
            BlockState state = level.getBlockState(member);
            if (!(state.getBlock() instanceof ScreenBlock)) continue;
            float[] glass = glass(group, member, state, cover[0], cover[2], w, h);
            if (glass != null) quad(pose, out, col, row, glass, FRONT_Z, 0.5F, 0.5F, 0.5F, 0.5F);
        }
        GameCover.drawWorld(pose, buffers, 1 - (cover[0] - col), cover[3] - row, NOTE_Z, w, h);
        pose.popPose();
    }

    /**
     * The part of {@code left, bottom, w, h} (in blocks, on the whole screen) on this block's glass: its square, less
     * the bezel on the sides with no screen joined. {x0, x1, y0, y1}, or null.
     */
    private static float[] glass(ScreenGroups.Group group, BlockPos pos, BlockState state, float left, float bottom, float w, float h) {
        int col = group.col(pos), row = group.row(pos);
        float x0 = Math.max(left, col + (state.getValue(ScreenBlock.LEFT) ? 0 : BEZEL));
        float x1 = Math.min(left + w, col + 1 - (state.getValue(ScreenBlock.RIGHT) ? 0 : BEZEL));
        float y0 = Math.max(bottom, row + (state.getValue(ScreenBlock.DOWN) ? 0 : BEZEL));
        float y1 = Math.min(bottom + h, row + 1 - (state.getValue(ScreenBlock.UP) ? 0 : BEZEL));
        return x0 < x1 && y0 < y1 ? new float[] {x0, x1, y0, y1} : null;
    }

    /**
     * Turned the way the blockstate turns the model, to draw in the model's coordinates: the model faces north, so the
     * viewer's left is +x there.
     */
    private static void turn(PoseStack pose, Direction facing) {
        pose.translate(0.5F, 0, 0.5F);
        pose.mulPose(Axis.YP.rotationDegrees(-modelYRotation(facing)));
        pose.translate(-0.5F, 0, -0.5F);
    }

    /** {@code glass} ({x0, x1, y0, y1} on the whole screen), relative to the block at {@code col, row}. */
    private static void quad(PoseStack pose, VertexConsumer out, int col, int row, float[] glass, float z,
                             float u0, float u1, float vTop, float vBottom) {
        float xLeft = 1 - (glass[0] - col), xRight = 1 - (glass[1] - col), yBottom = glass[2] - row, yTop = glass[3] - row;
        PoseStack.Pose last = pose.last();
        out.addVertex(last, xLeft, yBottom, z).setUv(u0, vBottom);
        out.addVertex(last, xRight, yBottom, z).setUv(u1, vBottom);
        out.addVertex(last, xRight, yTop, z).setUv(u1, vTop);
        out.addVertex(last, xLeft, yTop, z).setUv(u0, vTop);
    }

    /** The blockstate's y rotation of the model for a facing: north 0, east 90, south 180, west 270. */
    private static float modelYRotation(Direction facing) {
        return (facing.toYRot() + 180) % 360;
    }
}
