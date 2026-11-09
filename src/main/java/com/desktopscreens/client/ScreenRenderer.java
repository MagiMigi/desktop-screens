package com.desktopscreens.client;

import com.desktopscreens.ScreenBlock;
import com.desktopscreens.ScreenBlockEntity;
import com.desktopscreens.core.PictureFit;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
//? if >=26.1 {
/*import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
*///?} else {
import net.minecraft.client.renderer.MultiBufferSource;
//?}
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

import java.util.UUID;

/**
 * Draws the desktop on the screens in the world that are on for this player. The picture spans the whole joined
 * screen (its bounding box, as big as fits inside the outer bezel, black bars where the shape doesn't match), and
 * each block draws only its own piece of it, so Minecraft's per-block visibility checks just work.
 */
//? if >=26.1 {
/*public final class ScreenRenderer implements BlockEntityRenderer<ScreenBlockEntity, ScreenRenderer.State> {
*///?} else {
public final class ScreenRenderer implements BlockEntityRenderer<ScreenBlockEntity> {
//?}
    /** How far the picture floats in front of the glass, in blocks. */
    private static final float LIFT = 0.002f;
    /** Just in front of the glass, in the model's coordinates (the panel is the back 2/16 of the block). */
    private static final float FRONT_Z = 14 / 16f - LIFT;
    /** The note on the cover over the game in a picture of its own monitor ({@link GameCover}): a little toward the viewer. */
    private static final float NOTE_Z = FRONT_Z - 0.004f;

    public ScreenRenderer(BlockEntityRendererProvider.Context context) {}

    /** What one block draws this frame, worked out from the world first; {@code eye} is the camera as seen from the glass. */
    private record Piece(ScreenGroups.Group group, BlockPos pos, BlockState state, WorldScreens.Picture picture, float[] rect,
                         float[] cover, boolean drawsCover, WorldBar.Frame bar, float[] eye) {}

    /**
     * What {@code screen} shows this frame, or null; also notes whether it's the block that draws the game's cover. In VR
     * a screen's hint shows at it ({@link ScreenAim#hintFrame}), also when it shows no picture: then that's all it draws.
     */
    private static Piece piece(ScreenBlockEntity screen, Object pass, Vec3 camera) {
        Minecraft mc = Minecraft.getInstance();
        Level level = screen.getLevel();
        BlockState state = screen.getBlockState();
        if (level == null || mc.player == null || !(state.getBlock() instanceof ScreenBlock)) return null;
        ScreenGroups.Group group = ScreenGroups.of(level, screen.getBlockPos());
        if (group == null) return null;
        WorldBar.Frame hint = ScreenAim.hintFrame(mc, group, screen.getBlockPos());
        UUID me = mc.player.getUUID(), owner = group.owner(level);
        if (owner == null) return hintOnly(group, screen, state, hint);
        boolean mine = owner.equals(me);
        // Our own desktop, or the picture its owner shares with us while they're here (else it would stay frozen on
        // their last one until the server turns the screen off) and we haven't hidden it; anyone else's stays dark.
        boolean ownerHere = mc.getConnection() != null && mc.getConnection().getPlayerInfo(owner) != null;
        long start = System.nanoTime();
        WorldScreens.Picture picture = mine ? WorldScreens.picture(mc, group)
                : ownerHere && group.sharedWith(level, me) && !WatchChoices.hidden(level, group) ? SharedPictures.picture(mc, group, owner) : null;
        if (picture == null) return hintOnly(group, screen, state, hint);
        Awake.now(mc); // a live picture in sight
        // The picture's place on the whole screen, in blocks (columns right, rows up).
        float[] rect = group.pictureRect(picture.cropW(), picture.cropH());
        float[] cover = coverRect(picture, rect);
        // Once per screen and frame (and pass: shader mods draw the world twice), by whichever block comes first:
        // block by block, it would switch between the picture and the cover's drawing for every one of them.
        boolean drawsCover = cover != null && (group.coverPicture != picture || group.coverBuffers != pass);
        if (drawsCover) {
            group.coverPicture = picture;
            group.coverBuffers = pass;
        }
        if (mine) WorldScreens.drew(mc, group, System.nanoTime() - start);
        // The VR command bar under the screen pointed at, from one of its blocks.
        WorldBar.Frame bar = mine ? WorldPointing.barFrame(mc, group, screen.getBlockPos(), rect) : null;
        return new Piece(group, screen.getBlockPos(), state, picture, rect, cover, drawsCover, bar != null ? bar : hint, group.fromGlass(camera));
    }

    /** A block of a screen that shows no picture: only its hint, if it draws that, else nothing. */
    private static Piece hintOnly(ScreenGroups.Group group, ScreenBlockEntity screen, BlockState state, WorldBar.Frame hint) {
        return hint == null ? null : new Piece(group, screen.getBlockPos(), state, null, null, null, false, hint, null);
    }

    //? if >=26.1 {
    /*/^* What the world gave for one block this frame (26.1 reads the world first, and draws later). ^/
    public static final class State extends BlockEntityRenderState {
        Piece piece;
    }

    @Override
    public State createRenderState() {
        return new State();
    }

    @Override
    public void extractRenderState(ScreenBlockEntity screen, State state, float partialTick, Vec3 camera,
                                   ModelFeatureRenderer.CrumblingOverlay breakProgress) {
        BlockEntityRenderer.super.extractRenderState(screen, state, partialTick, camera, breakProgress);
        // The world is read once a frame, so the frame stands in for 1.21.1's pass.
        state.piece = piece(screen, null, camera);
    }

    @Override
    public void submit(State state, PoseStack pose, SubmitNodeCollector collector, CameraRenderState camera) {
        Piece p = state.piece;
        if (p == null) return;
        pose.pushPose();
        turn(pose, p.group().facing);
        if (p.picture() != null) collector.submitCustomGeometry(pose, p.picture().renderType(), (at, out) -> drawPiece(p, at.pose(), out));
        if (p.drawsCover()) {
            Minecraft mc = Minecraft.getInstance();
            Level level = mc.level;
            if (level != null) {
                collector.submitCustomGeometry(pose, GameCover.renderType(mc), (at, out) -> drawCoverGlass(level, p, at.pose(), out));
                float[] cover = p.cover();
                int col = p.group().col(p.pos()), row = p.group().row(p.pos());
                GameCover.drawWorld(pose, collector, 1 - (cover[0] - col), cover[3] - row, NOTE_Z, cover[1] - cover[0], cover[3] - cover[2]);
            }
        }
        if (p.bar() != null) WorldBar.draw(p.bar(), Minecraft.getInstance().font, pose, collector, p.group().col(p.pos()), p.group().row(p.pos()), NOTE_Z);
        pose.popPose();
    }
    *///?} else {
    @Override
    public void render(ScreenBlockEntity screen, float partialTick, PoseStack pose, MultiBufferSource buffers, int light, int overlay) {
        Piece p = piece(screen, buffers, Minecraft.getInstance().gameRenderer.getMainCamera().getPosition());
        if (p == null) return;
        pose.pushPose();
        turn(pose, p.group().facing);
        if (p.picture() != null) drawPiece(p, pose.last().pose(), buffers.getBuffer(p.picture().renderType()));
        if (p.drawsCover()) {
            Minecraft mc = Minecraft.getInstance();
            drawCoverGlass(screen.getLevel(), p, pose.last().pose(), buffers.getBuffer(GameCover.renderType(mc)));
            float[] cover = p.cover();
            int col = p.group().col(p.pos()), row = p.group().row(p.pos());
            GameCover.drawWorld(pose, buffers, 1 - (cover[0] - col), cover[3] - row, NOTE_Z, cover[1] - cover[0], cover[3] - cover[2]);
        }
        if (p.bar() != null) WorldBar.draw(p.bar(), Minecraft.getInstance().font, pose, buffers, p.group().col(p.pos()), p.group().row(p.pos()), NOTE_Z);
        pose.popPose();
    }
    //?}

    /**
     * This block's piece of the picture, in the model's coordinates ({@code m} already turned the model's way); around
     * the cover only, which would otherwise fight it for its place at a distance.
     */
    private static void drawPiece(Piece p, Matrix4f m, VertexConsumer out) {
        float[] rect = p.rect(), cover = p.cover();
        float[] glass = glass(p.group(), p.pos(), p.state(), p.eye(), rect[0], rect[1], rect[2], rect[3]);
        if (glass == null) return;
        int col = p.group().col(p.pos()), row = p.group().row(p.pos());
        WorldScreens.Picture picture = p.picture();
        if (cover == null || glass[1] <= cover[0] || cover[1] <= glass[0] || glass[3] <= cover[2] || cover[3] <= glass[2]) {
            drawPicture(m, out, col, row, picture, rect, glass);
        } else {
            float x0 = Math.max(glass[0], cover[0]), x1 = Math.min(glass[1], cover[1]);
            drawPicture(m, out, col, row, picture, rect, new float[] {glass[0], x0, glass[2], glass[3]});
            drawPicture(m, out, col, row, picture, rect, new float[] {x1, glass[1], glass[2], glass[3]});
            drawPicture(m, out, col, row, picture, rect, new float[] {x0, x1, glass[2], Math.max(glass[2], cover[2])});
            drawPicture(m, out, col, row, picture, rect, new float[] {x0, x1, Math.min(glass[3], cover[3]), glass[3]});
        }
    }

    /** The {@code piece} ({x0, x1, y0, y1} on the whole screen) of the picture at {@code rect}, if there's any of it. */
    private static void drawPicture(Matrix4f m, VertexConsumer out, int col, int row, WorldScreens.Picture p, float[] rect, float[] piece) {
        if (piece[0] >= piece[1] || piece[2] >= piece[3]) return;
        float left = rect[0], bottom = rect[1], w = rect[2], h = rect[3];
        // The picture's rows run top to bottom.
        float u0 = (p.cropX() + (piece[0] - left) / w * p.cropW()) / p.frameWidth();
        float u1 = (p.cropX() + (piece[1] - left) / w * p.cropW()) / p.frameWidth();
        float vTop = (p.cropY() + (bottom + h - piece[3]) / h * p.cropH()) / p.frameHeight();
        float vBottom = (p.cropY() + (bottom + h - piece[2]) / h * p.cropH()) / p.frameHeight();
        quad(m, out, col, row, piece, FRONT_Z, u0, u1, vTop, vBottom);
    }

    /** Where the picture at {@code rect} shows the game itself ({@link GameCover}), {x0, x1, y0, y1} on the whole screen; or null. */
    private static float[] coverRect(WorldScreens.Picture p, float[] rect) {
        int[] game = GameCover.place(p.game(), p.cropX(), p.cropY(), p.cropW(), p.cropH(), 0, 0, p.cropW(), p.cropH());
        if (game == null) return null;
        float left = rect[0], bottom = rect[1], w = rect[2], h = rect[3];
        return new float[] {left + game[0] * w / p.cropW(), left + game[2] * w / p.cropW(),
                bottom + h - game[3] * h / p.cropH(), bottom + h - game[1] * h / p.cropH()};
    }

    /** The cover over every block's glass, drawn from the block of {@code p} ({@code m} turned the model's way). */
    private static void drawCoverGlass(Level level, Piece p, Matrix4f m, VertexConsumer out) {
        float[] cover = p.cover();
        float w = cover[1] - cover[0], h = cover[3] - cover[2];
        ScreenGroups.Group group = p.group();
        int col = group.col(p.pos()), row = group.row(p.pos());
        for (BlockPos member : group.members) {
            BlockState state = level.getBlockState(member);
            if (!(state.getBlock() instanceof ScreenBlock)) continue;
            float[] glass = glass(group, member, state, p.eye(), cover[0], cover[2], w, h);
            if (glass != null) quad(m, out, col, row, glass, FRONT_Z, 0.5F, 0.5F, 0.5F, 0.5F);
        }
    }

    /**
     * The part of {@code left, bottom, w, h} (in blocks, on the whole screen) on this block's glass: its square, less
     * the bezel on the sides with no screen joined. Without a bezel, those sides end where the floating picture still
     * looks inside the screen from {@code eye} ({@link ScreenGroups.Group#fromGlass}). {x0, x1, y0, y1}, or null.
     */
    private static float[] glass(ScreenGroups.Group group, BlockPos pos, BlockState state, float[] eye, float left, float bottom, float w, float h) {
        int col = group.col(pos), row = group.row(pos);
        float bezel = ScreenFrames.bezel();
        float outerLeft = col + bezel, outerRight = col + 1 - bezel, outerBottom = row + bezel, outerTop = row + 1 - bezel;
        if (bezel == 0) {
            outerLeft = PictureFit.lowEdge(col, eye[0], eye[2], LIFT);
            outerRight = PictureFit.highEdge(col + 1, eye[0], eye[2], LIFT);
            outerBottom = PictureFit.lowEdge(row, eye[1], eye[2], LIFT);
            outerTop = PictureFit.highEdge(row + 1, eye[1], eye[2], LIFT);
        }
        float x0 = Math.max(left, state.getValue(ScreenBlock.LEFT) ? col : outerLeft);
        float x1 = Math.min(left + w, state.getValue(ScreenBlock.RIGHT) ? col + 1 : outerRight);
        float y0 = Math.max(bottom, state.getValue(ScreenBlock.DOWN) ? row : outerBottom);
        float y1 = Math.min(bottom + h, state.getValue(ScreenBlock.UP) ? row + 1 : outerTop);
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
    private static void quad(Matrix4f m, VertexConsumer out, int col, int row, float[] glass, float z,
                             float u0, float u1, float vTop, float vBottom) {
        float xLeft = 1 - (glass[0] - col), xRight = 1 - (glass[1] - col), yBottom = glass[2] - row, yTop = glass[3] - row;
        int tint = PictureRenderType.tint();
        Vertices.picture(out, m, xLeft, yBottom, z, u0, vBottom, tint);
        Vertices.picture(out, m, xRight, yBottom, z, u1, vBottom, tint);
        Vertices.picture(out, m, xRight, yTop, z, u1, vTop, tint);
        Vertices.picture(out, m, xLeft, yTop, z, u0, vTop, tint);
    }

    /** The blockstate's y rotation of the model for a facing: north 0, east 90, south 180, west 270. */
    private static float modelYRotation(Direction facing) {
        return (facing.toYRot() + 180) % 360;
    }
}
