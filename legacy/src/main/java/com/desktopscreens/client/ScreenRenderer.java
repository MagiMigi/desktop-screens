package com.desktopscreens.client;

import com.desktopscreens.ScreenBlock;
import com.desktopscreens.ScreenBlockEntity;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.tileentity.TileEntitySpecialRenderer;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraft.world.World;
import org.lwjgl.opengl.GL11;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * Draws the desktop on the screens in the world that are on for this player. The picture spans the whole joined
 * screen (its bounding box, as big as fits inside the outer bezel, black bars where the shape doesn't match), and
 * each block draws only its own piece of it, so Minecraft's per-block visibility checks just work.
 */
final class ScreenRenderer extends TileEntitySpecialRenderer<ScreenBlockEntity> {
    private static final float BEZEL = ScreenGroups.BEZEL;
    /** Just in front of the glass, in the model's coordinates (the panel is the back 2/16 of the block). */
    private static final float FRONT_Z = 14 / 16f - 0.002f;
    /** The note on the cover over the game in a picture of its own monitor ({@link GameCover}): a little toward the viewer. */
    private static final float NOTE_Z = FRONT_Z - 0.004f;
    private static final int GREY = 0x16, TITLE = 0xFFFFFFFF, TEXT = 0xFFB0B0B0, LINE_GAP = 2, TITLE_GAP = 5;

    @Override
    public void render(ScreenBlockEntity screen, double x, double y, double z, float partialTicks, int destroyStage, float alpha) {
        Minecraft mc = Minecraft.getMinecraft();
        World world = screen.getWorld();
        BlockPos pos = screen.getPos();
        IBlockState state = world == null ? null : world.getBlockState(pos);
        if (state == null || mc.player == null || !(state.getBlock() instanceof ScreenBlock)) return;
        ScreenGroups.Group group = ScreenGroups.of(world, pos);
        if (group == null) return;
        UUID me = mc.player.getUniqueID(), owner = group.owner(world);
        if (owner == null) return;
        boolean mine = owner.equals(me);
        // Our own desktop, or the picture its owner shares with us while they're here (else it would stay frozen on
        // their last one until the server turns the screen off) and we haven't hidden it; anyone else's stays dark.
        boolean ownerHere = mc.getConnection() != null && mc.getConnection().getPlayerInfo(owner) != null;
        long start = System.nanoTime();
        WorldScreens.Picture picture = mine ? WorldScreens.picture(mc, group)
                : ownerHere && group.sharedWith(world, me) && !WatchChoices.hidden(world, group) ? SharedPictures.picture(mc, group, owner) : null;
        if (picture == null) return;
        // The picture's place on the whole screen, in blocks (columns right, rows up).
        float[] rect = group.pictureRect(picture.cropW, picture.cropH);
        float[] cover = coverRect(picture, rect);
        IBlockState actual = state.getActualState(world, pos);

        GlStateManager.pushMatrix();
        GlStateManager.translate(x, y, z);
        turn(group.facing);
        GlStateManager.disableLighting();
        // Front side only (the quads and the note go round counter-clockwise seen from the front): pulled toward the
        // viewer, it showed through the block from behind at a slant (Test 30, 2026-10-04).
        GlStateManager.enableCull();
        GlStateManager.cullFace(GlStateManager.CullFace.BACK);
        GlStateManager.disableBlend();
        GlStateManager.enableTexture2D();
        GlStateManager.color(1, 1, 1, 1);
        // It glows, like a real screen, and is pulled a little toward the viewer, so it never flickers with the black
        // glass right behind it.
        float lightX = OpenGlHelper.lastBrightnessX, lightY = OpenGlHelper.lastBrightnessY;
        OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, 240, 240);
        GlStateManager.enablePolygonOffset();
        GlStateManager.doPolygonOffset(-1, -10);
        GlStateManager.bindTexture(picture.texture.id());
        drawPiece(group, pos, actual, picture, rect, cover);
        // Once per screen and frame, by whichever block comes first: the cover spans blocks.
        if (cover != null && group.coverPicture != picture) {
            group.coverPicture = picture;
            drawCoverGlass(world, group, pos, cover);
            int col = group.col(pos), row = group.row(pos);
            drawNote(1 - (cover[0] - col), cover[3] - row, cover[1] - cover[0], cover[3] - cover[2]);
        }
        GlStateManager.doPolygonOffset(0, 0);
        GlStateManager.disablePolygonOffset();
        OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, lightX, lightY);
        GlStateManager.enableLighting();
        GlStateManager.popMatrix();
        if (mine) WorldScreens.drew(mc, group, System.nanoTime() - start);
    }

    /**
     * This block's piece of the picture, in the model's coordinates; around the cover only, which would otherwise
     * fight it for its place at a distance.
     */
    private static void drawPiece(ScreenGroups.Group group, BlockPos pos, IBlockState state, WorldScreens.Picture picture, float[] rect, float[] cover) {
        float[] glass = glass(group, pos, state, rect[0], rect[1], rect[2], rect[3]);
        if (glass == null) return;
        int col = group.col(pos), row = group.row(pos);
        Tessellator tessellator = Tessellator.getInstance();
        BufferBuilder out = tessellator.getBuffer();
        out.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX);
        if (cover == null || glass[1] <= cover[0] || cover[1] <= glass[0] || glass[3] <= cover[2] || cover[3] <= glass[2]) {
            drawPicture(out, col, row, picture, rect, glass);
        } else {
            float x0 = Math.max(glass[0], cover[0]), x1 = Math.min(glass[1], cover[1]);
            drawPicture(out, col, row, picture, rect, new float[] {glass[0], x0, glass[2], glass[3]});
            drawPicture(out, col, row, picture, rect, new float[] {x1, glass[1], glass[2], glass[3]});
            drawPicture(out, col, row, picture, rect, new float[] {x0, x1, glass[2], Math.max(glass[2], cover[2])});
            drawPicture(out, col, row, picture, rect, new float[] {x0, x1, Math.min(glass[3], cover[3]), glass[3]});
        }
        tessellator.draw();
    }

    /** The {@code piece} ({x0, x1, y0, y1} on the whole screen) of the picture at {@code rect}, if there's any of it. */
    private static void drawPicture(BufferBuilder out, int col, int row, WorldScreens.Picture p, float[] rect, float[] piece) {
        if (piece[0] >= piece[1] || piece[2] >= piece[3]) return;
        float left = rect[0], bottom = rect[1], w = rect[2], h = rect[3];
        // The picture's rows run top to bottom.
        float u0 = (p.cropX + (piece[0] - left) / w * p.cropW) / p.frameWidth;
        float u1 = (p.cropX + (piece[1] - left) / w * p.cropW) / p.frameWidth;
        float vTop = (p.cropY + (bottom + h - piece[3]) / h * p.cropH) / p.frameHeight;
        float vBottom = (p.cropY + (bottom + h - piece[2]) / h * p.cropH) / p.frameHeight;
        quad(out, col, row, piece, FRONT_Z, u0, u1, vTop, vBottom, true);
    }

    /** Where the picture at {@code rect} shows the game itself ({@link GameCover}), {x0, x1, y0, y1} on the whole screen; or null. */
    private static float[] coverRect(WorldScreens.Picture p, float[] rect) {
        int[] game = GameCover.place(p.game, p.cropX, p.cropY, p.cropW, p.cropH, 0, 0, p.cropW, p.cropH);
        if (game == null) return null;
        float left = rect[0], bottom = rect[1], w = rect[2], h = rect[3];
        return new float[] {left + game[0] * w / p.cropW, left + game[2] * w / p.cropW,
                bottom + h - game[3] * h / p.cropH, bottom + h - game[1] * h / p.cropH};
    }

    /** The cover over every block's glass, drawn from the block at {@code pos} (turned the model's way). */
    private static void drawCoverGlass(World world, ScreenGroups.Group group, BlockPos pos, float[] cover) {
        float w = cover[1] - cover[0], h = cover[3] - cover[2];
        int col = group.col(pos), row = group.row(pos);
        GlStateManager.disableTexture2D();
        GlStateManager.color(GREY / 255f, GREY / 255f, GREY / 255f, 1);
        Tessellator tessellator = Tessellator.getInstance();
        BufferBuilder out = tessellator.getBuffer();
        out.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION);
        for (BlockPos member : group.members) {
            IBlockState state = world.getBlockState(member);
            if (!(state.getBlock() instanceof ScreenBlock)) continue;
            float[] glass = glass(group, member, state.getActualState(world, member), cover[0], cover[2], w, h);
            if (glass != null) quad(out, col, row, glass, FRONT_Z, 0, 0, 0, 0, false);
        }
        tessellator.draw();
        GlStateManager.color(1, 1, 1, 1);
        GlStateManager.enableTexture2D();
    }

    /**
     * The note in the cover, as signs draw their text: its top-left corner, seen from the front, at {@code x, y}
     * (model coordinates), {@code w} wide and {@code h} tall, in blocks.
     */
    private static void drawNote(float x, float y, float w, float h) {
        FontRenderer font = Minecraft.getMinecraft().fontRenderer;
        List<ITextComponent> lines = Arrays.asList(new TextComponentTranslation("desktopscreens.cover.title"),
                new TextComponentTranslation("desktopscreens.cover.how", DesktopClient.hostKey("O")),
                new TextComponentTranslation("desktopscreens.cover.why"));
        int widest = 1;
        for (ITextComponent line : lines) widest = Math.max(widest, font.getStringWidth(line.getFormattedText()));
        int height = lines.size() * font.FONT_HEIGHT + TITLE_GAP + (lines.size() - 2) * LINE_GAP;
        float s = Math.min(0.85f * w / widest, 0.8f * h / height);
        GlStateManager.pushMatrix();
        GlStateManager.translate(x, y, NOTE_Z);
        // As signs do it: their text faces the one looking at them from higher z, with x to the right.
        GlStateManager.rotate(180, 0, 1, 0);
        GlStateManager.scale(s, -s, s);
        GlStateManager.depthMask(false);
        float width = w / s, top = (h / s - height) / 2;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).getFormattedText();
            font.drawString(line, (int) ((width - font.getStringWidth(line)) / 2), (int) top, i == 0 ? TITLE : TEXT);
            top += font.FONT_HEIGHT + (i == 0 ? TITLE_GAP : LINE_GAP);
        }
        GlStateManager.depthMask(true);
        GlStateManager.popMatrix();
    }

    /**
     * The part of {@code left, bottom, w, h} (in blocks, on the whole screen) on this block's glass: its square, less
     * the bezel on the sides with no screen joined. {x0, x1, y0, y1}, or null.
     */
    private static float[] glass(ScreenGroups.Group group, BlockPos pos, IBlockState state, float left, float bottom, float w, float h) {
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
    private static void turn(EnumFacing facing) {
        GlStateManager.translate(0.5F, 0, 0.5F);
        GlStateManager.rotate(-modelYRotation(facing), 0, 1, 0);
        GlStateManager.translate(-0.5F, 0, -0.5F);
    }

    /** {@code glass} ({x0, x1, y0, y1} on the whole screen), relative to the block at {@code col, row}. */
    private static void quad(BufferBuilder out, int col, int row, float[] glass, float z,
                             float u0, float u1, float vTop, float vBottom, boolean textured) {
        float xLeft = 1 - (glass[0] - col), xRight = 1 - (glass[1] - col), yBottom = glass[2] - row, yTop = glass[3] - row;
        vertex(out, xLeft, yBottom, z, u0, vBottom, textured);
        vertex(out, xRight, yBottom, z, u1, vBottom, textured);
        vertex(out, xRight, yTop, z, u1, vTop, textured);
        vertex(out, xLeft, yTop, z, u0, vTop, textured);
    }

    private static void vertex(BufferBuilder out, float x, float y, float z, float u, float v, boolean textured) {
        if (textured) out.pos(x, y, z).tex(u, v).endVertex();
        else out.pos(x, y, z).endVertex();
    }

    /** The blockstate's y rotation of the model for a facing: north 0, east 90, south 180, west 270. */
    private static float modelYRotation(EnumFacing facing) {
        return (facing.getHorizontalAngle() + 180) % 360;
    }
}
