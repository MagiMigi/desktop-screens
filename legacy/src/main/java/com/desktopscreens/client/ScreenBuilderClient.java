package com.desktopscreens.client;

import com.desktopscreens.BuildScreen;
import com.desktopscreens.BuilderRules;
import com.desktopscreens.ChangeScreen;
import com.desktopscreens.DesktopScreens;
import com.desktopscreens.Net;
import com.desktopscreens.ScreenBlock;
import com.desktopscreens.ScreenBuilder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.tileentity.TileEntityRendererDispatcher;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.init.SoundEvents;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.SoundCategory;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.world.World;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import org.lwjgl.opengl.GL11;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * The Screen Builder in the player's hand. Right-click a block: the first corner goes where a block placed against
 * that face would, and the screen will face away from a wall, or toward the player from a floor or ceiling. Then a
 * hologram stretches from there to wherever the view meets the screen's plane (in mid-air too), green where screens
 * fit and red where something's in the way. Right-click again to build it (the server does, {@link ScreenBuilder}).
 *
 * <p>Shift-clicking a screen changes it. Shift + right-click grabs it where you look, like a window on the desktop:
 * near a corner both its width and height follow the view, near the middle of an edge only that edge does (a band of
 * {@link #GRIP} along each side), and in the middle the whole screen moves (the block grabbed stays under the
 * crosshair, on whatever wall you look at; on a floor it stands, under a ceiling it hangs). Shift + left-click marks it
 * to be taken down. Right-click again does it; what goes shows amber. Nothing is ever replaced, and it never joins
 * another screen.
 *
 * <p>Left-click, or putting the builder away, cancels. Clicks with the builder never reach Minecraft: on 1.12.2 they're
 * taken from Forge's mouse event, and from its interaction events for keys bound to use or attack (boot/ClientSide).
 * Client thread only.
 */
final class ScreenBuilderClient {
    private static final int GREEN = 0x55FF88, RED = 0xFF5555, AMBER = 0xFFB040;
    /** The glass starts this far in front of a screen block's back. */
    private static final double PANEL = 2 / 16.0;
    /** How far in from a screen's edge grabbing it takes that edge, in blocks; and how far along an edge from a corner that takes the corner. */
    private static final double GRIP = 0.25, CORNER = 0.75;

    /** What the builder is doing: building a new screen, or changing one. Null while it isn't. */
    private enum Mode { BUILD, RESIZE, MOVE, TAKE_DOWN }

    private static Mode mode;
    /** BUILD and RESIZE: the corner that stays, and the other one, where the view meets the screen's plane. */
    private static BlockPos anchor, corner;
    /** RESIZE along an edge: the columns, or the rows, stay as they were. */
    private static boolean colsFixed, rowsFixed;
    /** The way the picture will face. */
    private static EnumFacing facing;
    private static World level;
    /** RESIZE, MOVE, TAKE_DOWN: the block clicked, the screen it belongs to, its blocks and the way they face now. */
    private static BlockPos grabbed;
    private static ScreenGroups.Group group;
    private static List<BlockPos> members = Collections.emptyList();
    private static Set<BlockPos> memberSet = Collections.emptySet();
    private static EnumFacing oldFacing;
    private static ScreenBuilder.Bounds oldBounds;
    /** MOVE: where the grabbed block is in the screen's outline (it stays under the crosshair); where the outline's bottom-left goes. */
    private static int grabCol, grabRow;
    private static BlockPos base;
    /** What the screen will show, measured when it started, for the hint about its shape. Null if unknown. */
    private static WorldScreens.Source source;

    /** What the checks below were done for; null to check again. */
    private static List<Object> checked;
    /** The screen's blocks once done, what changes, what's in the way, how many screens the player has. */
    private static List<BlockPos> after = Collections.emptyList();
    private static Set<BlockPos> afterSet = Collections.emptySet();
    private static ScreenBuilder.Change change = ScreenBuilder.Change.NONE;
    private static List<BlockPos> blocked = Collections.emptyList(), joining = Collections.emptyList();
    /** What's in the way, named ({@link ScreenBuilder#inTheWay}); worked out with the checks, not every frame. */
    private static ITextComponent blockedText;
    private static int screensHeld;

    /** The use or attack button is still down from a click the builder took (or from before holding it). */
    private static boolean useHeld, attackHeld;
    private static final HintFade startFade = new HintFade(), screenFade = new HintFade();
    /** Counts the times the builder came into the hand: taking it again shows the hints' keys again. */
    private static int taken;
    private static boolean holding;

    /** Where a screen was grabbed, across and up: -1 near its left or bottom side, 1 near its right or top, 0 between. */
    private static final class Grip {
        final int across, up;

        Grip(int across, int up) {
            this.across = across;
            this.up = up;
        }

        boolean middle() {
            return across == 0 && up == 0;
        }
    }

    private ScreenBuilderClient() {}

    /** Right-click. True if the builder took it, so Minecraft does nothing with it. */
    static boolean onUse() {
        Minecraft mc = Minecraft.getMinecraft();
        EntityPlayerSP player = mc.player;
        if (player == null || mc.world == null || mc.currentScreen != null) return false;
        EnumHand hand = ScreenBuilder.builderHand(player);
        if (hand == null) return false;
        // A key held down repeats the use; only a new press counts.
        if (useHeld) return true;
        useHeld = true;
        if (mode != null) finish(mc, player, hand);
        else if (!(player.isSneaking() && grab(mc, player, hand, false))) start(mc, player, hand);
        return true;
    }

    /**
     * Left-click: cancels, or with Shift on a screen, marks it to be taken down. True if it did, so Minecraft doesn't
     * hit or break anything; also while the button is still down from such a click, so holding it doesn't mine.
     */
    static boolean onAttack() {
        Minecraft mc = Minecraft.getMinecraft();
        EntityPlayerSP player = mc.player;
        if (player == null || mc.world == null || mc.currentScreen != null) return false;
        EnumHand hand = ScreenBuilder.builderHand(player);
        if (hand == null) return false;
        if (attackHeld) return true;
        if (mode != null) {
            attackHeld = true;
            player.sendStatusMessage(new TextComponentTranslation(mode == Mode.BUILD ? "desktopscreens.builder.cancelled"
                    : "desktopscreens.builder.change_cancelled"), true);
            chime(mc, 0.7F);
            stop();
            return true;
        }
        if (player.isSneaking() && grab(mc, player, hand, true)) {
            attackHeld = true;
            return true;
        }
        return false;
    }

    private static void start(Minecraft mc, EntityPlayerSP player, EnumHand hand) {
        RayTraceResult hit = mc.objectMouseOver;
        if (hit == null || hit.typeOfHit != RayTraceResult.Type.BLOCK) {
            player.sendStatusMessage(new TextComponentTranslation("desktopscreens.builder.look_at_block"), true);
            return;
        }
        if (!player.capabilities.allowEdit) {
            player.sendStatusMessage(new TextComponentTranslation("desktopscreens.builder.cant_build"), true);
            return;
        }
        BlockPos pos = hit.getBlockPos();
        EnumFacing face = hit.sideHit;
        boolean replacing = mc.world.getBlockState(pos).getBlock().isReplaceable(mc.world, pos); // grass, snow: the screen goes in its place
        mode = Mode.BUILD;
        anchor = replacing ? pos : pos.offset(face);
        corner = anchor;
        // Like a screen placed by hand: on a wall it faces away from the wall; on a floor or a ceiling, toward you.
        facing = face.getAxis().isHorizontal() && !replacing ? face : player.getHorizontalFacing().getOpposite();
        oldFacing = facing;
        level = mc.world;
        checked = null;
        source = currentSource();
        player.swingArm(hand);
        chime(mc, 1.5F);
    }

    /**
     * Shift-click on a screen: marks it to be taken down (left button), or grabs it to resize or move it (right
     * button) by where it's grabbed. False if no screen is looked at.
     */
    private static boolean grab(Minecraft mc, EntityPlayerSP player, EnumHand hand, boolean takeDown) {
        RayTraceResult hit = screenLookedAt(mc);
        ScreenGroups.Group screen = hit == null ? null : ScreenGroups.of(mc.world, hit.getBlockPos());
        if (screen == null) return false;
        if (!player.capabilities.allowEdit) {
            player.sendStatusMessage(new TextComponentTranslation("desktopscreens.builder.cant_build"), true);
            return true;
        }
        if (inUseByOther(mc, screen)) {
            player.sendStatusMessage(new TextComponentTranslation("desktopscreens.screen.in_use", screen.ownerName(mc.world)), true);
            return true;
        }
        level = mc.world;
        grabbed = hit.getBlockPos();
        group = screen;
        members = screen.members;
        memberSet = new HashSet<>(members);
        oldFacing = facing = screen.facing;
        oldBounds = ScreenBuilder.bounds(members, oldFacing);
        Grip grip = grip(hit, oldBounds, oldFacing);
        ScreenBuilder.Bounds b = oldBounds;
        if (takeDown) {
            mode = Mode.TAKE_DOWN;
        } else if (grip.middle()) {
            mode = Mode.MOVE;
            grabCol = ScreenBuilder.column(grabbed, oldFacing) - b.minCol;
            grabRow = grabbed.getY() - b.minRow;
            base = ScreenBuilder.cellAt(grabbed, oldFacing, b.minCol, b.minRow);
        } else {
            mode = Mode.RESIZE;
            // The side grabbed follows the view and the opposite one stays; along an edge, the other direction stays too.
            anchor = ScreenBuilder.cellAt(grabbed, oldFacing, grip.across < 0 ? b.maxCol() : b.minCol, grip.up < 0 ? b.maxRow() : b.minRow);
            corner = ScreenBuilder.cellAt(grabbed, oldFacing, grip.across < 0 ? b.minCol : b.maxCol(), grip.up < 0 ? b.minRow : b.maxRow());
            colsFixed = grip.across == 0;
            rowsFixed = grip.up == 0;
        }
        checked = null;
        source = currentSource();
        player.swingArm(hand);
        chime(mc, 1.5F);
        return true;
    }

    /** How far the builder reaches: the server's {@code builder.range}, 32 blocks by default. */
    private static double reach() {
        return BuilderRules.current().range();
    }

    /**
     * The block looked at within the builder's reach, or null: for grabbing and moving screens, which are big, so
     * from further than a hand reaches. Starting a new one takes a block within the hand's reach, as placing one does.
     */
    private static RayTraceResult blockLookedAt(Minecraft mc, float partialTick) {
        RayTraceResult hit = mc.player.rayTrace(reach(), partialTick);
        return hit != null && hit.typeOfHit == RayTraceResult.Type.BLOCK ? hit : null;
    }

    /** The screen block looked at within the builder's reach, or null. */
    private static RayTraceResult screenLookedAt(Minecraft mc) {
        RayTraceResult hit = blockLookedAt(mc, 1f);
        return hit != null && mc.world.getBlockState(hit.getBlockPos()).getBlock() instanceof ScreenBlock ? hit : null;
    }

    /** Someone else's screen that's on, while they're here: only they may change it (as the server checks too). */
    private static boolean inUseByOther(Minecraft mc, ScreenGroups.Group screen) {
        UUID owner = screen.owner(mc.world);
        return owner != null && !owner.equals(mc.player.getUniqueID()) && mc.getConnection() != null
                && mc.getConnection().getPlayerInfo(owner) != null;
    }

    /** Which part of the screen {@code hit} is on: near a corner, near the middle of an edge, or in the middle. */
    private static Grip grip(RayTraceResult hit, ScreenBuilder.Bounds b, EnumFacing facing) {
        BlockPos pos = hit.getBlockPos();
        Vec3d at = hit.hitVec;
        EnumFacing right = ScreenBuilder.right(facing);
        // How far into the clicked block toward the viewer's right, 0 to 1.
        double into = right.getAxis() == EnumFacing.Axis.X ? at.x - pos.getX() : at.z - pos.getZ();
        if (right.getAxisDirection() == EnumFacing.AxisDirection.NEGATIVE) into = 1 - into;
        double across = ScreenBuilder.column(pos, facing) - b.minCol + MathHelper.clamp(into, 0, 1);
        double up = pos.getY() - b.minRow + MathHelper.clamp(at.y - pos.getY(), 0, 1);
        int a = end(across, b.cols, GRIP), u = end(up, b.rows, GRIP);
        // On an edge's band, its last CORNER blocks before a corner are that corner: where two thin bands cross
        // alone was too small to hit.
        if (a != 0 && u == 0) u = end(up, b.rows, CORNER);
        else if (u != 0 && a == 0) a = end(across, b.cols, CORNER);
        return new Grip(a, u);
    }

    /** Within {@code margin} of an end: that end (-1 or 1); else the middle (0). Within it of both (a small screen), the nearer one. */
    private static int end(double at, int size, double margin) {
        boolean low = at < margin, high = at > size - margin;
        if (low && high) return at < size / 2.0 ? -1 : 1;
        return low ? -1 : high ? 1 : 0;
    }

    private static void finish(Minecraft mc, EntityPlayerSP player, EnumHand hand) {
        check(mc);
        ITextComponent problem = problem(player);
        if (problem != null) {
            player.sendStatusMessage(problem, true);
            return;
        }
        if (mode != Mode.BUILD && change.added.isEmpty() && change.leaving.isEmpty()) {
            player.sendStatusMessage(new TextComponentTranslation("desktopscreens.builder.change_cancelled"), true);
            stop();
            return;
        }
        IMessage request;
        switch (mode) {
            case BUILD: request = new BuildScreen(anchor, corner, facing); break;
            case RESIZE: request = new ChangeScreen(grabbed, ChangeScreen.Action.RESIZE, anchor, corner, facing); break;
            case MOVE: request = new ChangeScreen(grabbed, ChangeScreen.Action.MOVE, base, base, facing); break;
            default: request = new ChangeScreen(grabbed, ChangeScreen.Action.TAKE_DOWN, grabbed, grabbed, oldFacing); break;
        }
        Net.CHANNEL.sendToServer(request);
        player.swingArm(hand);
        stop();
    }

    private static void stop() {
        mode = null;
        anchor = corner = grabbed = base = null;
        group = null;
        level = null;
        members = Collections.emptyList();
        memberSet = Collections.emptySet();
        after = Collections.emptyList();
        afterSet = Collections.emptySet();
        change = ScreenBuilder.Change.NONE;
        blocked = joining = Collections.emptyList();
        blockedText = null;
        checked = null;
    }

    private static void chime(Minecraft mc, float pitch) {
        if (mc.world != null && mc.player != null) {
            mc.world.playSound(mc.player.posX, mc.player.posY, mc.player.posZ, SoundEvents.BLOCK_NOTE_CHIME, SoundCategory.PLAYERS, 0.8F, pitch, false);
        }
    }

    /** Every client tick. */
    static void tick(Minecraft mc) {
        EntityPlayerSP player = mc.player;
        // A button already down when the builder comes into the hand isn't a click on it.
        if (!mc.gameSettings.keyBindUseItem.isKeyDown()) useHeld = false;
        else if (player == null || ScreenBuilder.builderHand(player) == null) useHeld = true;
        if (!mc.gameSettings.keyBindAttack.isKeyDown()) attackHeld = false;
        boolean nowHolding = player != null && ScreenBuilder.builderHand(player) != null;
        if (nowHolding && !holding) taken++;
        holding = nowHolding;
        if (mode == null) return;
        if (player == null || mc.world != level || !player.isEntityAlive() || ScreenBuilder.builderHand(player) == null) {
            stop();
            return;
        }
        if (mode != Mode.BUILD && changedMeanwhile()) {
            player.sendStatusMessage(new TextComponentTranslation("desktopscreens.builder.screen_changed"), true);
            stop();
            return;
        }
        checked = null; // blocks and players move: check again
        check(mc);
    }

    /** The screen being changed was broken, turned, or joined to more screens by someone meanwhile. */
    private static boolean changedMeanwhile() {
        if (!(level.getBlockState(grabbed).getBlock() instanceof ScreenBlock)) return true;
        ScreenGroups.Group now = ScreenGroups.of(level, grabbed);
        if (now == group) return false; // nothing changed anywhere since
        group = now; // the groups were worked out again: something changed, maybe elsewhere
        return now == null || now.facing != oldFacing || !memberSet.equals(new HashSet<>(now.members));
    }

    /** Where the screen's blocks will be, what's in the way, how many screens the player has. Only when something changed. */
    private static void check(Minecraft mc) {
        List<Object> shape = Arrays.asList(mode, anchor, corner, base, facing);
        if (shape.equals(checked)) return;
        checked = shape;
        switch (mode) {
            case BUILD: case RESIZE: after = ScreenBuilder.cells(anchor, corner); break;
            case MOVE: after = ScreenBuilder.moved(members, oldFacing, base, facing); break;
            default: after = Collections.emptyList(); break;
        }
        afterSet = new HashSet<>(after);
        change = ScreenBuilder.change(members, oldFacing, after, facing);
        ScreenBuilder.Obstacles obstacles = ScreenBuilder.obstacles(level, change, after, facing, mode != Mode.BUILD);
        blocked = obstacles.inTheWay;
        joining = obstacles.joining;
        blockedText = blocked.isEmpty() ? null : ScreenBuilder.inTheWay(level, blocked, facing, change.leaving);
        screensHeld = ScreenBuilder.screensIn(mc.player);
    }

    /** Why it can't be done right now, or null if it can. */
    private static ITextComponent problem(EntityPlayerSP player) {
        if (!blocked.isEmpty()) return blockedText;
        if (!joining.isEmpty()) return new TextComponentTranslation("desktopscreens.builder.joins_other");
        // Where it goes (the hologram); taken down, where it is. Not where a moved screen was.
        boolean near = mode == Mode.BUILD ? ScreenBuilder.inRange(player, anchor, corner, reach())
                : ScreenBuilder.inRange(player, mode == Mode.TAKE_DOWN ? members : after, reach());
        if (!near) return new TextComponentTranslation("desktopscreens.builder.too_far");
        if (mode == Mode.RESIZE) { // an edge keeps its length, which a lower limit on the server may not allow any more
            int[] size = ScreenBuilder.size(anchor, corner, facing);
            BuilderRules rules = BuilderRules.current();
            if (size[0] > rules.maxWidth() || size[1] > rules.maxHeight()) {
                return new TextComponentTranslation("desktopscreens.builder.too_big", rules.maxWidth(), rules.maxHeight());
            }
        }
        int needed = change.added.size() - change.leaving.size();
        if (!player.capabilities.isCreativeMode && needed > screensHeld) {
            return new TextComponentTranslation("desktopscreens.builder.need_screens", needed, screensHeld);
        }
        return null;
    }

    /** What the screen will show, for the hint about its shape: a grabbed screen's own choice, else the tablet's (a new one's). */
    private static WorldScreens.Source currentSource() {
        try {
            DesktopConfig.load();
            return WorldScreens.currentSource(group != null ? SourceChoice.of(Minecraft.getMinecraft().world, group) : DesktopConfig.tablet);
        } catch (RuntimeException | LinkageError e) {
            DesktopScreens.LOG.warn("Screen Builder: couldn't tell the desktop's size", e);
            return null;
        }
    }

    // ---- The hologram ----

    /** Every frame, after the world: follows the view and draws the hologram. */
    static void render(Minecraft mc, float partialTick) {
        if (mode == null || mc.player == null || mc.world != level) return;
        Vec3d eye = mc.player.getPositionEyes(partialTick), look = mc.player.getLook(partialTick);
        switch (mode) {
            case BUILD: stretch(eye, look, false, false); break;
            case RESIZE: stretch(eye, look, colsFixed, rowsFixed); break;
            case MOVE: follow(mc, partialTick); break;
            default: break;
        }
        check(mc);
        draw(problem(mc.player) == null);
    }

    /**
     * Moves the corner to where the view ray meets the screen's plane, within the server's size limit. Resizing along
     * an edge keeps the corner's column ({@code keepCol}) or row ({@code keepRow}).
     */
    private static void stretch(Vec3d eye, Vec3d look, boolean keepCol, boolean keepRow) {
        Vec3d normal = new Vec3d(facing.getXOffset(), 0, facing.getZOffset());
        // The plane of the glass: the front of the panels, which sit at the back of their blocks.
        Vec3d onPlane = new Vec3d(anchor.getX() + 0.5, anchor.getY() + 0.5, anchor.getZ() + 0.5).subtract(normal.scale(0.5 - PANEL));
        double toward = look.dotProduct(normal);
        if (Math.abs(toward) < 1e-6) return; // looking along the plane
        double distance = onPlane.subtract(eye).dotProduct(normal) / toward;
        if (distance <= 0 || distance > 512) return; // looking away from it
        Vec3d hit = eye.add(look.scale(distance));
        EnumFacing right = ScreenBuilder.right(facing); // the viewer's right, looking at the front
        BuilderRules rules = BuilderRules.current();
        int cols = keepCol ? (corner.getX() - anchor.getX()) * right.getXOffset() + (corner.getZ() - anchor.getZ()) * right.getZOffset()
                : MathHelper.clamp((MathHelper.floor(hit.x) - anchor.getX()) * right.getXOffset() + (MathHelper.floor(hit.z) - anchor.getZ()) * right.getZOffset(),
                        1 - rules.maxWidth(), rules.maxWidth() - 1);
        int rows = keepRow ? corner.getY() - anchor.getY()
                : MathHelper.clamp(MathHelper.floor(hit.y) - anchor.getY(), 1 - rules.maxHeight(), rules.maxHeight() - 1);
        int y = MathHelper.clamp(anchor.getY() + rows, 0, level.getHeight() - 1);
        corner = new BlockPos(anchor.getX() + cols * right.getXOffset(), y, anchor.getZ() + cols * right.getZOffset());
    }

    /**
     * Moving: the grabbed block goes where a block placed against the one looked at would (on the screen itself, it
     * stays in its own plane), the rest of the screen around it, facing away from a wall or toward the player from a
     * floor or ceiling. On a floor it stands on it and under a ceiling it hangs from it. Looking at the sky, it stays
     * where it was.
     */
    private static void follow(Minecraft mc, float partialTick) {
        RayTraceResult hit = blockLookedAt(mc, partialTick);
        if (hit == null) return;
        BlockPos pos = hit.getBlockPos();
        EnumFacing face = hit.sideHit;
        BlockPos target;
        int row = grabRow;
        if (memberSet.contains(pos)) {
            target = pos;
            facing = oldFacing;
        } else {
            // Grass or snow: in its place; else against the face looked at.
            target = level.getBlockState(pos).getBlock().isReplaceable(level, pos) ? pos : pos.offset(face);
            facing = face.getAxis().isHorizontal() && !target.equals(pos) ? face : mc.player.getHorizontalFacing().getOpposite();
            if (face == EnumFacing.UP) row = 0;
            else if (face == EnumFacing.DOWN) row = oldBounds.rows - 1;
        }
        base = target.offset(ScreenBuilder.right(facing), -grabCol).down(row);
    }

    /**
     * In the world's coordinates less the camera's: Minecraft has already turned the view the way the camera looks.
     * See-through, both sides, never hiding what's behind it, and a line between every two blocks on the front so the
     * size can be counted.
     */
    private static void draw(boolean ok) {
        double cx = TileEntityRendererDispatcher.staticPlayerX, cy = TileEntityRendererDispatcher.staticPlayerY, cz = TileEntityRendererDispatcher.staticPlayerZ;
        int color = ok ? GREEN : RED;
        float pulse = pulse();
        GlStateManager.pushMatrix();
        GlStateManager.translate(-cx, -cy, -cz);
        GlStateManager.disableTexture2D();
        GlStateManager.disableLighting();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA,
                GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ZERO);
        GlStateManager.enableDepth();
        GlStateManager.disableCull();
        GlStateManager.depthMask(false);
        Tessellator tessellator = Tessellator.getInstance();
        BufferBuilder out = tessellator.getBuffer();

        out.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_COLOR);
        for (BlockPos cell : after) panel(out, cell, facing, afterSet, color, 0.22F * pulse);
        for (BlockPos cell : change.leaving) box(out, cellPanel(cell, oldFacing).grow(0.01), AMBER, 0.3F * pulse);
        for (BlockPos pos : blocked) box(out, new AxisAlignedBB(pos).grow(0.004), RED, 0.4F);
        for (BlockPos pos : joining) box(out, new AxisAlignedBB(pos).grow(0.004), RED, 0.4F);
        tessellator.draw();

        GlStateManager.glLineWidth(2.0F);
        out.begin(GL11.GL_LINES, DefaultVertexFormats.POSITION_COLOR);
        grid(out, after, afterSet, facing, color, 0.9F * pulse);
        tessellator.draw();
        GlStateManager.glLineWidth(1.0F);

        GlStateManager.depthMask(true);
        GlStateManager.enableCull();
        GlStateManager.disableBlend();
        GlStateManager.enableTexture2D();
        GlStateManager.popMatrix();
    }

    /** The hologram's slow glow, between 0.7 and 1. */
    private static float pulse() {
        return 0.85F + 0.15F * MathHelper.sin((System.nanoTime() % 2_000_000_000L) / 2e9F * (float) (2 * Math.PI));
    }

    /** A screen block's panel in the world: the back 2/16 of its block, the way it faces. */
    private static AxisAlignedBB cellPanel(BlockPos pos, EnumFacing facing) {
        AxisAlignedBB b = new AxisAlignedBB(pos);
        // Facing +x or +z, the back of the block is its low side; facing -x or -z, its high side.
        boolean positive = facing.getAxisDirection() == EnumFacing.AxisDirection.POSITIVE;
        if (facing.getAxis() == EnumFacing.Axis.X) {
            return positive ? new AxisAlignedBB(b.minX, b.minY, b.minZ, b.minX + PANEL, b.maxY, b.maxZ)
                    : new AxisAlignedBB(b.maxX - PANEL, b.minY, b.minZ, b.maxX, b.maxY, b.maxZ);
        }
        return positive ? new AxisAlignedBB(b.minX, b.minY, b.minZ, b.maxX, b.maxY, b.minZ + PANEL)
                : new AxisAlignedBB(b.minX, b.minY, b.maxZ - PANEL, b.maxX, b.maxY, b.maxZ);
    }

    /**
     * One block of the see-through hologram: its front and back, and its sides only where the screen ends, so no
     * seams show between blocks. A hair bigger than a screen block, so it shows over one that's there.
     */
    private static void panel(BufferBuilder out, BlockPos cell, EnumFacing facing, Set<BlockPos> screen, int rgb, float alpha) {
        AxisAlignedBB b = cellPanel(cell, facing).grow(0.003);
        double x0 = b.minX, y0 = b.minY, z0 = b.minZ, x1 = b.maxX, y1 = b.maxY, z1 = b.maxZ;
        boolean alongX = facing.getAxis() == EnumFacing.Axis.Z; // the columns run along x
        if (alongX) {
            quad(out, rgb, alpha, x0, y0, z0, x1, y0, z0, x1, y1, z0, x0, y1, z0);
            quad(out, rgb, alpha, x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1);
            if (!screen.contains(cell.west())) quad(out, rgb, alpha, x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0);
            if (!screen.contains(cell.east())) quad(out, rgb, alpha, x1, y0, z0, x1, y0, z1, x1, y1, z1, x1, y1, z0);
        } else {
            quad(out, rgb, alpha, x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0);
            quad(out, rgb, alpha, x1, y0, z0, x1, y0, z1, x1, y1, z1, x1, y1, z0);
            if (!screen.contains(cell.north())) quad(out, rgb, alpha, x0, y0, z0, x1, y0, z0, x1, y1, z0, x0, y1, z0);
            if (!screen.contains(cell.south())) quad(out, rgb, alpha, x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1);
        }
        if (!screen.contains(cell.down())) quad(out, rgb, alpha, x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1);
        if (!screen.contains(cell.up())) quad(out, rgb, alpha, x0, y1, z0, x1, y1, z0, x1, y1, z1, x0, y1, z1);
    }

    private static void box(BufferBuilder out, AxisAlignedBB b, int rgb, float alpha) {
        double x0 = b.minX, y0 = b.minY, z0 = b.minZ, x1 = b.maxX, y1 = b.maxY, z1 = b.maxZ;
        quad(out, rgb, alpha, x0, y0, z0, x1, y0, z0, x1, y1, z0, x0, y1, z0);
        quad(out, rgb, alpha, x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1);
        quad(out, rgb, alpha, x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0);
        quad(out, rgb, alpha, x1, y0, z0, x1, y0, z1, x1, y1, z1, x1, y1, z0);
        quad(out, rgb, alpha, x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1);
        quad(out, rgb, alpha, x0, y1, z0, x1, y1, z0, x1, y1, z1, x0, y1, z1);
    }

    private static void quad(BufferBuilder out, int rgb, float alpha, double... xyz) {
        int a = (int) (alpha * 255);
        for (int i = 0; i < 12; i += 3) out.pos(xyz[i], xyz[i + 1], xyz[i + 2]).color(rgb >> 16 & 0xFF, rgb >> 8 & 0xFF, rgb & 0xFF, a).endVertex();
    }

    /**
     * Lines on the front of the hologram: the outline, and one between every two blocks. Each line once: every block
     * draws its low-side and bottom lines, and its high-side and top lines only where the screen ends.
     */
    private static void grid(BufferBuilder out, List<BlockPos> cells, Set<BlockPos> screen, EnumFacing facing, int rgb, float alpha) {
        boolean alongX = facing.getAxis() == EnumFacing.Axis.Z; // the columns run along x
        boolean positive = facing.getAxisDirection() == EnumFacing.AxisDirection.POSITIVE;
        int a = (int) (alpha * 255);
        for (BlockPos cell : cells) {
            AxisAlignedBB p = cellPanel(cell, facing);
            // Just in front of the hologram's glass, so the lines aren't hidden in it.
            double depth = alongX ? (positive ? p.maxZ + 0.006 : p.minZ - 0.006) : (positive ? p.maxX + 0.006 : p.minX - 0.006);
            double a0 = alongX ? p.minX : p.minZ, a1 = a0 + 1, y0 = p.minY, y1 = y0 + 1;
            line(out, rgb, a, alongX, depth, a0, y0, a0, y1);
            line(out, rgb, a, alongX, depth, a0, y0, a1, y0);
            if (!screen.contains(alongX ? cell.east() : cell.south())) line(out, rgb, a, alongX, depth, a1, y0, a1, y1);
            if (!screen.contains(cell.up())) line(out, rgb, a, alongX, depth, a0, y1, a1, y1);
        }
    }

    /** A line on the front plane, from (across, y) to (across, y). */
    private static void line(BufferBuilder out, int rgb, int a, boolean alongX, double depth, double a0, double y0, double a1, double y1) {
        int r = rgb >> 16 & 0xFF, g = rgb >> 8 & 0xFF, b = rgb & 0xFF;
        if (alongX) {
            out.pos(a0, y0, depth).color(r, g, b, a).endVertex();
            out.pos(a1, y1, depth).color(r, g, b, a).endVertex();
        } else {
            out.pos(depth, y0, a0).color(r, g, b, a).endVertex();
            out.pos(depth, y1, a1).color(r, g, b, a).endVertex();
        }
    }

    // ---- Text under the crosshair ----

    /**
     * While building or changing a screen: its size (and the size it had), a hint about the shape that fits what it
     * shows, screens used or given back, what's wrong if anything, and the keys ("[LMB] Cancel    [RMB] Build", left
     * button on the left). False otherwise.
     */
    static boolean renderHud(Minecraft mc, int width, int height) {
        if (mode == null || mc.player == null || mc.gameSettings.hideGUI || mc.currentScreen != null) return false;
        FontRenderer font = mc.fontRenderer;
        ITextComponent problem = problem(mc.player);
        int x = width / 2, y = height / 2 + 16;
        int[] size = mode == Mode.BUILD || mode == Mode.RESIZE ? ScreenBuilder.size(anchor, corner, facing) : null;
        ITextComponent title;
        switch (mode) {
            case BUILD: title = new TextComponentTranslation("desktopscreens.builder.size", size[0], size[1]); break;
            case RESIZE: title = new TextComponentTranslation("desktopscreens.builder.resize_size", oldBounds.cols, oldBounds.rows, size[0], size[1]); break;
            case MOVE: title = new TextComponentTranslation("desktopscreens.builder.moving", oldBounds.cols, oldBounds.rows); break;
            default: title = new TextComponentTranslation("desktopscreens.builder.take_down_title", oldBounds.cols, oldBounds.rows); break;
        }
        centered(font, title, x, y, problem != null ? 0xFF7070 : mode == Mode.TAKE_DOWN ? 0xFFC870 : 0x80FF9F);
        y += 10;
        ITextComponent shape = size == null ? null : shapeHint(size[0], size[1]);
        if (shape != null) {
            centered(font, shape, x, y, 0xB0B0B0);
            y += 10;
        }
        int needed = change.added.size() - change.leaving.size();
        if (mode != Mode.BUILD && needed != 0 && !mc.player.capabilities.isCreativeMode) { // in creative they're free
            centered(font, new TextComponentTranslation(needed > 0 ? "desktopscreens.builder.screens_used" : "desktopscreens.builder.screens_back",
                    Math.abs(needed)), x, y, 0xB0B0B0);
            y += 10;
        }
        if (problem != null) {
            centered(font, problem, x, y, 0xFF7070);
            y += 10;
        }
        ITextComponent cancel = DesktopClient.key(mc.gameSettings.keyBindAttack), confirm = DesktopClient.key(mc.gameSettings.keyBindUseItem);
        String keys;
        if (problem != null) keys = "desktopscreens.builder.keys_cancel";
        else if (mode == Mode.BUILD) keys = "desktopscreens.builder.keys";
        else if (mode == Mode.RESIZE) keys = "desktopscreens.builder.keys_resize";
        else if (mode == Mode.MOVE) keys = "desktopscreens.builder.keys_move";
        else keys = "desktopscreens.builder.keys_take_down";
        centered(font, new TextComponentTranslation(keys, cancel, confirm), x, y, 0xE0E0E0);
        return true;
    }

    private static void centered(FontRenderer font, ITextComponent text, int x, int y, int rgb) {
        String line = text.getFormattedText();
        font.drawStringWithShadow(line, x - font.getStringWidth(line) / 2f, y, 0xFF000000 | rgb);
    }

    /**
     * Holding the builder and looking at a screen, not building: the keys to take it down and to move or resize it,
     * and which part grabbing it here would take. For a few seconds ({@link HintFade}), and all the while Shift is
     * held. True whenever the builder is on a screen, so no other hint shows meanwhile.
     */
    static boolean renderScreenHint(Minecraft mc, int width, int height) {
        if (mode != null || mc.player == null || mc.world == null || mc.gameSettings.hideGUI || mc.currentScreen != null) return false;
        if (ScreenBuilder.builderHand(mc.player) == null) return false;
        RayTraceResult hit = screenLookedAt(mc);
        ScreenGroups.Group screen = hit == null ? null : ScreenGroups.of(mc.world, hit.getBlockPos());
        if (screen == null) return false;
        List<ITextComponent> lines;
        if (inUseByOther(mc, screen)) {
            lines = Collections.singletonList(new TextComponentTranslation("desktopscreens.screen.in_use", screen.ownerName(mc.world)));
        } else {
            Grip grip = grip(hit, ScreenBuilder.bounds(screen.members, screen.facing), screen.facing);
            ITextComponent takeDown = DesktopClient.key(mc.gameSettings.keyBindSneak, mc.gameSettings.keyBindAttack);
            ITextComponent grab = DesktopClient.key(mc.gameSettings.keyBindSneak, mc.gameSettings.keyBindUseItem);
            ITextComponent keys = new TextComponentTranslation(grip.middle() ? "desktopscreens.builder.screen_keys_move"
                    : "desktopscreens.builder.screen_keys_resize", takeDown, grab);
            if (grip.middle()) {
                lines = Collections.singletonList(keys);
            } else {
                ITextComponent where = new TextComponentTranslation("desktopscreens.builder.grip", gripName(grip));
                where.getStyle().setColor(TextFormatting.GRAY);
                lines = Arrays.asList(keys, where);
            }
        }
        int x = width / 2, y = height / 2 + 16;
        if (mc.player.isSneaking()) {
            for (int i = 0; i < lines.size(); i++) centered(mc.fontRenderer, lines.get(i), x, y + i * 12, 0xE0E0E0);
        } else {
            screenFade.draw(mc.fontRenderer, "screen " + screen.anchor() + " " + taken, lines, x, y, 0xE0E0E0);
        }
        return true;
    }

    /** "the top-left corner", "the right edge"... */
    private static ITextComponent gripName(Grip grip) {
        String upDown = grip.up > 0 ? "top" : grip.up < 0 ? "bottom" : "";
        String side = grip.across > 0 ? "right" : grip.across < 0 ? "left" : "";
        String key = upDown.isEmpty() ? side + "_edge" : side.isEmpty() ? upDown + "_edge" : upDown + "_" + side;
        return new TextComponentTranslation("desktopscreens.builder.grip." + key);
    }

    /** Holding the builder, looking at a block, not building yet: how to start, for a few seconds ({@link HintFade}). */
    static void renderStartHint(Minecraft mc, int width, int height) {
        if (mode != null || mc.player == null || mc.gameSettings.hideGUI || mc.currentScreen != null) return;
        RayTraceResult hit = mc.objectMouseOver;
        if (ScreenBuilder.builderHand(mc.player) == null || hit == null || hit.typeOfHit != RayTraceResult.Type.BLOCK) return;
        startFade.draw(mc.fontRenderer, "start " + taken,
                Collections.singletonList(new TextComponentTranslation("desktopscreens.builder.start", DesktopClient.key(mc.gameSettings.keyBindUseItem))),
                width / 2, height / 2 + 16, 0xE0E0E0);
    }

    /**
     * How tall the screen should be at this width for what it will show to fill it, like "For DISPLAY2 (4:3): 4 × 3".
     * The picture fills the glass inside the outer bezel.
     */
    private static ITextComponent shapeHint(int cols, int rows) {
        if (source == null || source.width <= 0 || source.height <= 0) return null;
        float bezel = ScreenGroups.BEZEL;
        int best = Math.max(1, Math.round((cols - 2 * bezel) * source.height / source.width + 2 * bezel));
        ITextComponent shown = source.window ? new TextComponentTranslation("desktopscreens.builder.the_window") : new TextComponentString(source.monitor);
        String ratio = ratio(source.width, source.height);
        return best == rows ? new TextComponentTranslation("desktopscreens.builder.shape_fits", shown, ratio)
                : new TextComponentTranslation("desktopscreens.builder.shape", shown, ratio, cols, best);
    }

    /** Like "16:9", or "2.39:1" when the whole numbers would be big. */
    private static String ratio(int w, int h) {
        int a = w, b = h;
        while (b != 0) {
            int t = a % b;
            a = b;
            b = t;
        }
        if (w / a <= 32 && h / a <= 32) return w / a + ":" + h / a;
        return String.format(Locale.ROOT, "%.2f:1", w / (double) h);
    }
}
