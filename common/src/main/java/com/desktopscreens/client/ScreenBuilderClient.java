package com.desktopscreens.client;

import com.desktopscreens.BuildScreen;
import com.desktopscreens.BuilderRules;
import com.desktopscreens.ChangeScreen;
import com.desktopscreens.DesktopScreens;
import com.desktopscreens.ScreenBlock;
import com.desktopscreens.ScreenBuilder;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4fStack;
import org.joml.Quaternionf;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * The Screen Builder in the player's hand. Right-click a block: the first corner goes where a block placed against
 * that face would, and the screen will face away from a wall, or toward the player from a floor or ceiling. Then a
 * hologram stretches from there to wherever the view meets the screen's plane (in mid-air too), green where screens
 * fit and red where something's in the way. Right-click again to build it (the server does, {@link ScreenBuilder#build}).
 *
 * <p>Shift-clicking a screen changes it (2026-09-25). Shift + right-click grabs it where you look,
 * like a window on the desktop: near a corner both its width and height follow the view, near the middle of an edge
 * only that edge does (a band of {@link #GRIP} along each side), and in the middle the whole screen moves (the block
 * grabbed stays under the crosshair, on whatever wall you look at; on a floor it stands, under a ceiling it hangs).
 * Shift + left-click marks it to be taken down. Right-click again does it
 * ({@link ScreenBuilder#change}); what goes shows amber. Nothing is ever replaced, and it never joins another screen.
 *
 * <p>Left-click, or putting the builder away, cancels. Clicks with the builder never reach Minecraft
 * ({@code MinecraftMixin}), so they can't open a chest or break a block. Client thread only.
 */
public final class ScreenBuilderClient {
    private static final int GREEN = 0x55FF88, RED = 0xFF5555, AMBER = 0xFFB040;
    /** The glass starts this far in front of a screen block's back. */
    private static final double PANEL = 2 / 16.0;
    /**
     * How far in from a screen's edge grabbing it takes that edge, in blocks; and how far along an edge from a corner
     * that takes the corner (2026-09-25).
     */
    private static final double GRIP = 0.25, CORNER = 0.75;
    private static final ScreenBuilder.Change NO_CHANGE = new ScreenBuilder.Change(List.of(), List.of());

    /** What the builder is doing: building a new screen, or changing one. Null while it isn't. */
    private enum Mode { BUILD, RESIZE, MOVE, TAKE_DOWN }

    private static Mode mode;
    /** BUILD and RESIZE: the corner that stays, and the other one, where the view meets the screen's plane. */
    private static BlockPos anchor, corner;
    /** RESIZE along an edge: the columns, or the rows, stay as they were. */
    private static boolean colsFixed, rowsFixed;
    /** The way the picture will face. */
    private static Direction facing;
    private static Level level;
    /** RESIZE, MOVE, TAKE_DOWN: the block clicked, the screen it belongs to, its blocks and the way they face now. */
    private static BlockPos grabbed;
    private static ScreenGroups.Group group;
    private static List<BlockPos> members = List.of();
    private static Set<BlockPos> memberSet = Set.of();
    private static Direction oldFacing;
    private static ScreenBuilder.Bounds oldBounds;
    /** MOVE: where the grabbed block is in the screen's outline (it stays under the crosshair); where the outline's bottom-left goes. */
    private static int grabCol, grabRow;
    private static BlockPos base;
    /** What the screen will show, measured when it started, for the hint about its shape. Null if unknown. */
    private static WorldScreens.Source source;

    /** What the checks below were done for; null to check again. */
    private static Shape checked;
    /** The screen's blocks once done, what changes, what's in the way, how many screens the player has. */
    private static List<BlockPos> after = List.of();
    private static Set<BlockPos> afterSet = Set.of();
    private static ScreenBuilder.Change change = NO_CHANGE;
    private static List<BlockPos> blocked = List.of(), joining = List.of();
    /** What's in the way, named ({@link ScreenBuilder#inTheWay}); worked out with the checks, not every frame. */
    private static Component blockedText;
    private static int screensHeld;

    /** The use or attack button is still down from a click the builder took (or from before holding it). */
    private static boolean useHeld, attackHeld;
    private static final HintFade startFade = new HintFade(), screenFade = new HintFade();
    /**
     * Counts the times the builder came into the hand, for the hints' keys: taking it again shows them again, even
     * within the 2 s after which a hint would come back by itself (a natural way to look at it again, 2026-09-25).
     */
    private static int taken;
    private static boolean holding;

    private record Shape(Mode mode, BlockPos anchor, BlockPos corner, BlockPos base, Direction facing) {}

    /** Where a screen was grabbed, across and up: -1 near its left or bottom side, 1 near its right or top, 0 between. */
    private record Grip(int across, int up) {
        boolean middle() {
            return across == 0 && up == 0;
        }
    }

    private ScreenBuilderClient() {}

    /** Right-click, from the mixin. True if the builder took it, so Minecraft does nothing with it. */
    public static boolean onUse() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null) return false;
        InteractionHand hand = ScreenBuilder.builderHand(player);
        if (hand == null) return false;
        // Minecraft repeats the use while the button is held; only a new click counts.
        if (useHeld) return true;
        useHeld = true;
        if (mode != null) finish(mc, player, hand);
        else if (!(player.isShiftKeyDown() && grab(mc, player, hand, false))) start(mc, player, hand);
        return true;
    }

    /**
     * Left-click, from the mixin: cancels, or with Shift on a screen, marks it to be taken down. True if it did, so
     * Minecraft doesn't hit or break anything.
     */
    public static boolean onAttack() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null) return false;
        InteractionHand hand = ScreenBuilder.builderHand(player);
        if (hand == null) return false;
        if (mode != null) {
            attackHeld = true;
            player.displayClientMessage(Component.translatable(mode == Mode.BUILD ? "desktopscreens.builder.cancelled"
                    : "desktopscreens.builder.change_cancelled"), true);
            chime(mc, 0.7F);
            stop();
            return true;
        }
        if (player.isShiftKeyDown() && grab(mc, player, hand, true)) {
            attackHeld = true;
            return true;
        }
        return false;
    }

    /** From the mixin: true while the left button is still down from a cancel, so holding it doesn't mine. */
    public static boolean holdAttack() {
        return attackHeld;
    }

    private static void start(Minecraft mc, LocalPlayer player, InteractionHand hand) {
        if (!(mc.hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) {
            player.displayClientMessage(Component.translatable("desktopscreens.builder.look_at_block"), true);
            return;
        }
        if (!player.mayBuild()) {
            player.displayClientMessage(Component.translatable("desktopscreens.builder.cant_build"), true);
            return;
        }
        BlockPos pos = hit.getBlockPos();
        Direction face = hit.getDirection();
        boolean replacing = mc.level.getBlockState(pos).canBeReplaced(); // grass, snow: the screen goes in its place
        mode = Mode.BUILD;
        anchor = replacing ? pos : pos.relative(face);
        corner = anchor;
        // Like a screen placed by hand: on a wall it faces away from the wall; on a floor or a ceiling, toward you.
        facing = face.getAxis().isHorizontal() && !replacing ? face : player.getDirection().getOpposite();
        oldFacing = facing;
        level = mc.level;
        checked = null;
        source = currentSource(mc);
        player.swing(hand);
        chime(mc, 1.5F);
    }

    /**
     * Shift-click on a screen: marks it to be taken down (left button), or grabs it to resize or move it (right
     * button) by where it's grabbed. False if no screen is looked at.
     */
    private static boolean grab(Minecraft mc, LocalPlayer player, InteractionHand hand, boolean takeDown) {
        BlockHitResult hit = screenLookedAt(mc);
        ScreenGroups.Group screen = hit == null ? null : ScreenGroups.of(mc.level, hit.getBlockPos());
        if (screen == null) return false;
        if (!player.mayBuild()) {
            player.displayClientMessage(Component.translatable("desktopscreens.builder.cant_build"), true);
            return true;
        }
        if (inUseByOther(mc, screen)) {
            player.displayClientMessage(Component.translatable("desktopscreens.screen.in_use", screen.ownerName(mc.level)), true);
            return true;
        }
        level = mc.level;
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
            grabCol = ScreenBuilder.column(grabbed, oldFacing) - b.minCol();
            grabRow = grabbed.getY() - b.minRow();
            base = ScreenBuilder.cellAt(grabbed, oldFacing, b.minCol(), b.minRow());
        } else {
            mode = Mode.RESIZE;
            // The side grabbed follows the view and the opposite one stays; along an edge, the other direction stays too.
            anchor = ScreenBuilder.cellAt(grabbed, oldFacing, grip.across() < 0 ? b.maxCol() : b.minCol(), grip.up() < 0 ? b.maxRow() : b.minRow());
            corner = ScreenBuilder.cellAt(grabbed, oldFacing, grip.across() < 0 ? b.minCol() : b.maxCol(), grip.up() < 0 ? b.minRow() : b.maxRow());
            colsFixed = grip.across() == 0;
            rowsFixed = grip.up() == 0;
        }
        checked = null;
        source = currentSource(mc);
        player.swing(hand);
        chime(mc, 1.5F);
        return true;
    }

    /** How far the builder reaches: the server's {@code builder.range}, 32 blocks by default ({@link ScreenBuilder#reach}). */
    private static double reach() {
        return BuilderRules.current().range();
    }

    /**
     * The block looked at within the builder's reach, or null: for grabbing and moving screens, which are big, so
     * from further than a hand reaches. Starting a new one takes a block within the hand's reach, as placing one does.
     */
    private static BlockHitResult blockLookedAt(Minecraft mc, float partialTick) {
        return mc.player.pick(reach(), partialTick, false) instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK ? hit : null;
    }

    /** The screen block looked at within the builder's reach, or null. */
    private static BlockHitResult screenLookedAt(Minecraft mc) {
        BlockHitResult hit = blockLookedAt(mc, 1f);
        return hit != null && mc.level.getBlockState(hit.getBlockPos()).getBlock() instanceof ScreenBlock ? hit : null;
    }

    /** Someone else's screen that's on, while they're here: only they may change it (as the server checks too). */
    private static boolean inUseByOther(Minecraft mc, ScreenGroups.Group screen) {
        UUID owner = screen.owner(mc.level);
        return owner != null && !owner.equals(mc.player.getUUID()) && mc.player.connection.getPlayerInfo(owner) != null;
    }

    /** Which part of the screen {@code hit} is on: near a corner, near the middle of an edge, or in the middle. */
    private static Grip grip(BlockHitResult hit, ScreenBuilder.Bounds b, Direction facing) {
        BlockPos pos = hit.getBlockPos();
        Vec3 at = hit.getLocation();
        Direction right = ScreenBuilder.right(facing);
        // How far into the clicked block toward the viewer's right, 0 to 1.
        double into = right.getAxis() == Direction.Axis.X ? at.x - pos.getX() : at.z - pos.getZ();
        if (right.getAxisDirection() == Direction.AxisDirection.NEGATIVE) into = 1 - into;
        double across = ScreenBuilder.column(pos, facing) - b.minCol() + Mth.clamp(into, 0, 1);
        double up = pos.getY() - b.minRow() + Mth.clamp(at.y - pos.getY(), 0, 1);
        int a = end(across, b.cols(), GRIP), u = end(up, b.rows(), GRIP);
        // On an edge's band, its last CORNER blocks before a corner are that corner: where two thin bands cross
        // alone was too small to hit (2026-09-25).
        if (a != 0 && u == 0) u = end(up, b.rows(), CORNER);
        else if (u != 0 && a == 0) a = end(across, b.cols(), CORNER);
        return new Grip(a, u);
    }

    /**
     * Within {@code margin} of an end: that end (-1 or 1); else the middle (0). Within it of both (a small screen),
     * the nearer one. The same on every side of every screen: the first version took a third of the size (at most a
     * block), so on a screen 2 blocks tall the corner began a third into the top block but a whole block in from the
     * side (2026-09-25).
     */
    private static int end(double at, int size, double margin) {
        boolean low = at < margin, high = at > size - margin;
        if (low && high) return at < size / 2.0 ? -1 : 1;
        return low ? -1 : high ? 1 : 0;
    }

    private static void finish(Minecraft mc, LocalPlayer player, InteractionHand hand) {
        check(mc);
        Component problem = problem(player);
        if (problem != null) {
            player.displayClientMessage(problem, true);
            return;
        }
        if (mode != Mode.BUILD && change.added().isEmpty() && change.leaving().isEmpty()) {
            player.displayClientMessage(Component.translatable("desktopscreens.builder.change_cancelled"), true);
            stop();
            return;
        }
        CustomPacketPayload request = switch (mode) {
            case BUILD -> new BuildScreen(anchor, corner, facing);
            case RESIZE -> new ChangeScreen(grabbed, ChangeScreen.Action.RESIZE, anchor, corner, facing);
            case MOVE -> new ChangeScreen(grabbed, ChangeScreen.Action.MOVE, base, base, facing);
            case TAKE_DOWN -> new ChangeScreen(grabbed, ChangeScreen.Action.TAKE_DOWN, grabbed, grabbed, oldFacing);
        };
        mc.getConnection().send(new ServerboundCustomPayloadPacket(request));
        player.swing(hand);
        stop();
    }

    private static void stop() {
        mode = null;
        anchor = corner = grabbed = base = null;
        group = null;
        level = null;
        members = List.of();
        memberSet = Set.of();
        after = List.of();
        afterSet = Set.of();
        change = NO_CHANGE;
        blocked = joining = List.of();
        blockedText = null;
        checked = null;
    }

    private static void chime(Minecraft mc, float pitch) {
        if (mc.level != null && mc.player != null) {
            mc.level.playLocalSound(mc.player, SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS, 0.8F, pitch);
        }
    }

    /** Every client tick. */
    static void tick(Minecraft mc) {
        LocalPlayer player = mc.player;
        // A button already down when the builder comes into the hand isn't a click on it.
        if (!mc.options.keyUse.isDown()) useHeld = false;
        else if (player == null || ScreenBuilder.builderHand(player) == null) useHeld = true;
        if (!mc.options.keyAttack.isDown()) attackHeld = false;
        boolean nowHolding = player != null && ScreenBuilder.builderHand(player) != null;
        if (nowHolding && !holding) taken++;
        holding = nowHolding;
        if (mode == null) return;
        if (player == null || mc.level != level || !player.isAlive() || ScreenBuilder.builderHand(player) == null) {
            stop();
            return;
        }
        if (mode != Mode.BUILD && changedMeanwhile()) {
            player.displayClientMessage(Component.translatable("desktopscreens.builder.screen_changed"), true);
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
        Shape shape = new Shape(mode, anchor, corner, base, facing);
        if (shape.equals(checked)) return;
        checked = shape;
        after = switch (mode) {
            case BUILD, RESIZE -> ScreenBuilder.cells(anchor, corner);
            case MOVE -> ScreenBuilder.moved(members, oldFacing, base, facing);
            case TAKE_DOWN -> List.of();
        };
        afterSet = new HashSet<>(after);
        change = ScreenBuilder.change(members, oldFacing, after, facing);
        ScreenBuilder.Obstacles obstacles = ScreenBuilder.obstacles(level, change, after, facing, mode != Mode.BUILD);
        blocked = obstacles.inTheWay();
        joining = obstacles.joining();
        blockedText = blocked.isEmpty() ? null : ScreenBuilder.inTheWay(level, blocked, facing, change.leaving());
        screensHeld = ScreenBuilder.screensIn(mc.player);
    }

    /** Why it can't be done right now, or null if it can. */
    private static Component problem(LocalPlayer player) {
        if (!blocked.isEmpty()) return blockedText;
        if (!joining.isEmpty()) return Component.translatable("desktopscreens.builder.joins_other");
        // Where it goes (the hologram); taken down, where it is. Not where a moved screen was (see ScreenBuilder.reach).
        boolean near = mode == Mode.BUILD ? ScreenBuilder.inRange(player, anchor, corner, reach())
                : ScreenBuilder.inRange(player, mode == Mode.TAKE_DOWN ? members : after, reach());
        if (!near) return Component.translatable("desktopscreens.builder.too_far");
        if (mode == Mode.RESIZE) { // an edge keeps its length, which a lower limit on the server may not allow any more
            int[] size = ScreenBuilder.size(anchor, corner, facing);
            BuilderRules rules = BuilderRules.current();
            if (size[0] > rules.maxWidth() || size[1] > rules.maxHeight()) {
                return Component.translatable("desktopscreens.builder.too_big", rules.maxWidth(), rules.maxHeight());
            }
        }
        int needed = change.added().size() - change.leaving().size();
        if (!player.hasInfiniteMaterials() && needed > screensHeld) {
            return Component.translatable("desktopscreens.builder.need_screens", needed, screensHeld);
        }
        return null;
    }

    /** What the screen will show, for the hint about its shape: a grabbed screen's own choice, else the tablet's (a new one's). */
    private static WorldScreens.Source currentSource(Minecraft mc) {
        try {
            DesktopConfig.load();
            return WorldScreens.currentSource(mc, group != null ? SourceChoice.of(mc.level, group) : DesktopConfig.tablet);
        } catch (RuntimeException | LinkageError e) {
            DesktopScreens.LOG.warn("Screen Builder: couldn't tell the desktop's size", e);
            return null;
        }
    }

    // ---- The hologram ----

    /** Every frame, after the world's see-through parts: follows the view and draws the hologram. */
    static void render(Minecraft mc, Camera camera) {
        if (mode == null || mc.player == null || mc.level != level) return;
        float partialTick = mc.getTimer().getGameTimeDeltaPartialTick(true);
        Vec3 eye = mc.player.getEyePosition(partialTick), look = mc.player.getViewVector(partialTick);
        switch (mode) {
            case BUILD -> stretch(eye, look, false, false);
            case RESIZE -> stretch(eye, look, colsFixed, rowsFixed);
            case MOVE -> follow(mc, partialTick);
            case TAKE_DOWN -> {}
        }
        check(mc);
        draw(camera, problem(mc.player) == null);
    }

    /**
     * Moves the corner to where the view ray meets the screen's plane, within the server's size limit. Resizing along
     * an edge keeps the corner's column ({@code keepCol}) or row ({@code keepRow}).
     */
    private static void stretch(Vec3 eye, Vec3 look, boolean keepCol, boolean keepRow) {
        Vec3 normal = new Vec3(facing.getStepX(), 0, facing.getStepZ());
        // The plane of the glass: the front of the panels, which sit at the back of their blocks.
        Vec3 onPlane = Vec3.atCenterOf(anchor).subtract(normal.scale(0.5 - PANEL));
        double toward = look.dot(normal);
        if (Math.abs(toward) < 1e-6) return; // looking along the plane
        double distance = onPlane.subtract(eye).dot(normal) / toward;
        if (distance <= 0 || distance > 512) return; // looking away from it
        Vec3 hit = eye.add(look.scale(distance));
        Direction right = ScreenBuilder.right(facing); // the viewer's right, looking at the front
        BuilderRules rules = BuilderRules.current();
        int cols = keepCol ? (corner.getX() - anchor.getX()) * right.getStepX() + (corner.getZ() - anchor.getZ()) * right.getStepZ()
                : Mth.clamp((Mth.floor(hit.x) - anchor.getX()) * right.getStepX() + (Mth.floor(hit.z) - anchor.getZ()) * right.getStepZ(),
                        1 - rules.maxWidth(), rules.maxWidth() - 1);
        int rows = keepRow ? corner.getY() - anchor.getY()
                : Mth.clamp(Mth.floor(hit.y) - anchor.getY(), 1 - rules.maxHeight(), rules.maxHeight() - 1);
        int y = Mth.clamp(anchor.getY() + rows, level.getMinBuildHeight(), level.getMaxBuildHeight() - 1);
        corner = new BlockPos(anchor.getX() + cols * right.getStepX(), y, anchor.getZ() + cols * right.getStepZ());
    }

    /**
     * Moving: the grabbed block goes where a block placed against the one looked at would (on the screen itself, it
     * stays in its own plane), the rest of the screen around it, facing away from a wall or toward the player from a
     * floor or ceiling. On a floor it stands on it and under a ceiling it hangs from it, rather than keeping the
     * grabbed row under the crosshair: that put a screen grabbed in its second row one block into the ground (the
     * user, 2026-09-25). Looking at the sky, it stays where it was.
     */
    private static void follow(Minecraft mc, float partialTick) {
        BlockHitResult hit = blockLookedAt(mc, partialTick);
        if (hit == null) return;
        BlockPos pos = hit.getBlockPos();
        Direction face = hit.getDirection();
        BlockPos target;
        int row = grabRow;
        if (memberSet.contains(pos)) {
            target = pos;
            facing = oldFacing;
        } else {
            // Grass or snow: in its place; else against the face looked at.
            target = level.getBlockState(pos).canBeReplaced() ? pos : pos.relative(face);
            facing = face.getAxis().isHorizontal() && !target.equals(pos) ? face : mc.player.getDirection().getOpposite();
            if (face == Direction.UP) row = 0;
            else if (face == Direction.DOWN) row = oldBounds.rows() - 1;
        }
        base = target.relative(ScreenBuilder.right(facing), -grabCol).below(row);
    }

    private static void draw(Camera camera, boolean ok) {
        Vec3 cam = camera.getPosition();
        int color = ok ? GREEN : RED;
        float pulse = 0.85F + 0.15F * Mth.sin((System.nanoTime() % 2_000_000_000L) / 2e9F * Mth.TWO_PI);

        // Drawn relative to the camera, turned the way it looks: set here rather than taken from the loader's hook.
        Matrix4fStack view = RenderSystem.getModelViewStack();
        view.pushMatrix();
        view.identity().rotate(camera.rotation().conjugate(new Quaternionf()));
        RenderSystem.applyModelViewMatrix();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.enableDepthTest();
        RenderSystem.disableCull();
        RenderSystem.depthMask(false);

        // See-through panels where the screen will be, amber over its blocks that go, red boxes around what's in the way.
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        BufferBuilder quads = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        for (BlockPos cell : after) panel(quads, cell, facing, afterSet, color, 0.22F * pulse, cam);
        for (BlockPos cell : change.leaving()) box(quads, cellPanel(cell, oldFacing).inflate(0.01).move(-cam.x, -cam.y, -cam.z), AMBER, 0.3F * pulse);
        for (BlockPos pos : blocked) box(quads, new AABB(pos).inflate(0.004).move(-cam.x, -cam.y, -cam.z), RED, 0.4F);
        for (BlockPos pos : joining) box(quads, new AABB(pos).inflate(0.004).move(-cam.x, -cam.y, -cam.z), RED, 0.4F);
        MeshData mesh = quads.build();
        if (mesh != null) BufferUploader.drawWithShader(mesh);

        // A line between every two blocks on the front, so the size can be counted.
        RenderSystem.setShader(GameRenderer::getRendertypeLinesShader);
        RenderSystem.lineWidth(2.0F);
        BufferBuilder lines = Tesselator.getInstance().begin(VertexFormat.Mode.LINES, DefaultVertexFormat.POSITION_COLOR_NORMAL);
        grid(lines, after, afterSet, facing, color, 0.9F * pulse, cam);
        mesh = lines.build();
        if (mesh != null) BufferUploader.drawWithShader(mesh);
        RenderSystem.lineWidth(1.0F);

        RenderSystem.depthMask(true);
        RenderSystem.enableCull();
        RenderSystem.disableBlend();
        view.popMatrix();
        RenderSystem.applyModelViewMatrix();
    }

    /** A screen block's panel in the world: the back 2/16 of its block, the way it faces. */
    private static AABB cellPanel(BlockPos pos, Direction facing) {
        AABB block = new AABB(pos);
        // Facing +x or +z, the back of the block is its low side; facing -x or -z, its high side.
        boolean positive = facing.getAxisDirection() == Direction.AxisDirection.POSITIVE;
        if (facing.getAxis() == Direction.Axis.X) {
            return positive ? block.setMaxX(block.minX + PANEL) : block.setMinX(block.maxX - PANEL);
        }
        return positive ? block.setMaxZ(block.minZ + PANEL) : block.setMinZ(block.maxZ - PANEL);
    }

    /**
     * One block of the see-through hologram: its front and back, and its sides only where the screen ends, so no
     * seams show between blocks. A hair bigger than a screen block, so it shows over one that's there.
     */
    private static void panel(BufferBuilder out, BlockPos cell, Direction facing, Set<BlockPos> screen, int rgb, float alpha, Vec3 cam) {
        AABB b = cellPanel(cell, facing).inflate(0.003).move(-cam.x, -cam.y, -cam.z);
        float x0 = (float) b.minX, y0 = (float) b.minY, z0 = (float) b.minZ;
        float x1 = (float) b.maxX, y1 = (float) b.maxY, z1 = (float) b.maxZ;
        boolean alongX = facing.getAxis() == Direction.Axis.Z; // the columns run along x
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
        if (!screen.contains(cell.below())) quad(out, rgb, alpha, x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1);
        if (!screen.contains(cell.above())) quad(out, rgb, alpha, x0, y1, z0, x1, y1, z0, x1, y1, z1, x0, y1, z1);
    }

    private static void box(BufferBuilder out, AABB b, int rgb, float alpha) {
        float x0 = (float) b.minX, y0 = (float) b.minY, z0 = (float) b.minZ;
        float x1 = (float) b.maxX, y1 = (float) b.maxY, z1 = (float) b.maxZ;
        quad(out, rgb, alpha, x0, y0, z0, x1, y0, z0, x1, y1, z0, x0, y1, z0);
        quad(out, rgb, alpha, x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1);
        quad(out, rgb, alpha, x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0);
        quad(out, rgb, alpha, x1, y0, z0, x1, y0, z1, x1, y1, z1, x1, y1, z0);
        quad(out, rgb, alpha, x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1);
        quad(out, rgb, alpha, x0, y1, z0, x1, y1, z0, x1, y1, z1, x0, y1, z1);
    }

    private static void quad(BufferBuilder out, int rgb, float alpha, float... xyz) {
        for (int i = 0; i < 12; i += 3) out.addVertex(xyz[i], xyz[i + 1], xyz[i + 2]).setColor(rgb | (int) (alpha * 255) << 24);
    }

    /**
     * Lines on the front of the hologram: the outline, and one between every two blocks. Each line once: every block
     * draws its low-side and bottom lines, and its high-side and top lines only where the screen ends.
     */
    private static void grid(BufferBuilder out, List<BlockPos> cells, Set<BlockPos> screen, Direction facing, int rgb, float alpha, Vec3 cam) {
        boolean alongX = facing.getAxis() == Direction.Axis.Z; // the columns run along x
        boolean positive = facing.getAxisDirection() == Direction.AxisDirection.POSITIVE;
        int argb = rgb | (int) (alpha * 255) << 24;
        for (BlockPos cell : cells) {
            AABB p = cellPanel(cell, facing).move(-cam.x, -cam.y, -cam.z);
            // Just in front of the hologram's glass, so the lines aren't hidden in it.
            float depth = (float) (alongX ? (positive ? p.maxZ + 0.006 : p.minZ - 0.006) : (positive ? p.maxX + 0.006 : p.minX - 0.006));
            float a0 = (float) (alongX ? p.minX : p.minZ), a1 = a0 + 1, y0 = (float) p.minY, y1 = y0 + 1;
            line(out, argb, alongX, depth, a0, y0, a0, y1);
            line(out, argb, alongX, depth, a0, y0, a1, y0);
            if (!screen.contains(alongX ? cell.east() : cell.south())) line(out, argb, alongX, depth, a1, y0, a1, y1);
            if (!screen.contains(cell.above())) line(out, argb, alongX, depth, a0, y1, a1, y1);
        }
    }

    /** A line on the front plane, from (across, y) to (across, y); the lines shader wants its direction as the normal. */
    private static void line(BufferBuilder out, int argb, boolean alongX, float depth, float a0, float y0, float a1, float y1) {
        float da = a1 - a0, dy = y1 - y0;
        float length = Mth.sqrt(da * da + dy * dy);
        float na = da / length, ny = dy / length;
        if (alongX) {
            out.addVertex(a0, y0, depth).setColor(argb).setNormal(na, ny, 0);
            out.addVertex(a1, y1, depth).setColor(argb).setNormal(na, ny, 0);
        } else {
            out.addVertex(depth, y0, a0).setColor(argb).setNormal(0, ny, na);
            out.addVertex(depth, y1, a1).setColor(argb).setNormal(0, ny, na);
        }
    }

    // ---- Text under the crosshair ----

    /**
     * While building or changing a screen: its size (and the size it had), a hint about the shape that fits what it
     * shows, screens used or given back, what's wrong if anything, and the keys ("[LMB] Cancel    [RMB] Build", left
     * button on the left). False otherwise.
     */
    static boolean renderHud(Minecraft mc, GuiGraphics g) {
        if (mode == null || mc.player == null || mc.options.hideGui || mc.screen != null) return false;
        Component problem = problem(mc.player);
        int x = g.guiWidth() / 2, y = g.guiHeight() / 2 + 16;
        int[] size = mode == Mode.BUILD || mode == Mode.RESIZE ? ScreenBuilder.size(anchor, corner, facing) : null;
        Component title = switch (mode) {
            case BUILD -> Component.translatable("desktopscreens.builder.size", size[0], size[1]);
            case RESIZE -> Component.translatable("desktopscreens.builder.resize_size", oldBounds.cols(), oldBounds.rows(), size[0], size[1]);
            case MOVE -> Component.translatable("desktopscreens.builder.moving", oldBounds.cols(), oldBounds.rows());
            case TAKE_DOWN -> Component.translatable("desktopscreens.builder.take_down_title", oldBounds.cols(), oldBounds.rows());
        };
        g.drawCenteredString(mc.font, title, x, y, problem != null ? 0xFF7070 : mode == Mode.TAKE_DOWN ? 0xFFC870 : 0x80FF9F);
        y += 10;
        Component shape = size == null ? null : shapeHint(size[0], size[1]);
        if (shape != null) {
            g.drawCenteredString(mc.font, shape, x, y, 0xB0B0B0);
            y += 10;
        }
        int needed = change.added().size() - change.leaving().size();
        if (mode != Mode.BUILD && needed != 0 && !mc.player.hasInfiniteMaterials()) { // in creative they're free
            g.drawCenteredString(mc.font, Component.translatable(needed > 0 ? "desktopscreens.builder.screens_used" : "desktopscreens.builder.screens_back",
                    Math.abs(needed)), x, y, 0xB0B0B0);
            y += 10;
        }
        if (problem != null) {
            g.drawCenteredString(mc.font, problem, x, y, 0xFF7070);
            y += 10;
        }
        Component cancel = DesktopClient.key(mc.options.keyAttack), confirm = DesktopClient.key(mc.options.keyUse);
        String keys = problem != null ? "desktopscreens.builder.keys_cancel" : switch (mode) {
            case BUILD -> "desktopscreens.builder.keys";
            case RESIZE -> "desktopscreens.builder.keys_resize";
            case MOVE -> "desktopscreens.builder.keys_move";
            case TAKE_DOWN -> "desktopscreens.builder.keys_take_down";
        };
        g.drawCenteredString(mc.font, Component.translatable(keys, cancel, confirm), x, y, 0xE0E0E0);
        return true;
    }

    /**
     * Holding the builder and looking at a screen, not building: the keys to take it down and to move or resize it,
     * and which part grabbing it here would take. For a few seconds ({@link HintFade}), and all the while Shift is
     * held. True whenever the builder is on a screen, so no other hint shows meanwhile.
     */
    static boolean renderScreenHint(Minecraft mc, GuiGraphics g) {
        if (mode != null || mc.player == null || mc.level == null || mc.options.hideGui || mc.screen != null) return false;
        if (ScreenBuilder.builderHand(mc.player) == null) return false;
        BlockHitResult hit = screenLookedAt(mc);
        ScreenGroups.Group screen = hit == null ? null : ScreenGroups.of(mc.level, hit.getBlockPos());
        if (screen == null) return false;
        List<Component> lines;
        if (inUseByOther(mc, screen)) {
            lines = List.of(Component.translatable("desktopscreens.screen.in_use", screen.ownerName(mc.level)));
        } else {
            Grip grip = grip(hit, ScreenBuilder.bounds(screen.members, screen.facing), screen.facing);
            Component takeDown = DesktopClient.key(mc.options.keyShift, mc.options.keyAttack), grab = DesktopClient.key(mc.options.keyShift, mc.options.keyUse);
            Component keys = Component.translatable(grip.middle() ? "desktopscreens.builder.screen_keys_move" : "desktopscreens.builder.screen_keys_resize", takeDown, grab);
            lines = grip.middle() ? List.of(keys)
                    : List.of(keys, Component.translatable("desktopscreens.builder.grip", gripName(grip)).withColor(0xB0B0B0));
        }
        int x = g.guiWidth() / 2, y = g.guiHeight() / 2 + 16;
        if (mc.player.isShiftKeyDown()) {
            for (int i = 0; i < lines.size(); i++) g.drawCenteredString(mc.font, lines.get(i), x, y + i * 12, 0xE0E0E0);
        } else {
            screenFade.draw(g, mc.font, "screen " + screen.anchor() + " " + taken, lines, x, y, 0xE0E0E0);
        }
        return true;
    }

    /** "the top-left corner", "the right edge"... */
    private static Component gripName(Grip grip) {
        String upDown = grip.up() > 0 ? "top" : grip.up() < 0 ? "bottom" : "";
        String side = grip.across() > 0 ? "right" : grip.across() < 0 ? "left" : "";
        String key = upDown.isEmpty() ? side + "_edge" : side.isEmpty() ? upDown + "_edge" : upDown + "_" + side;
        return Component.translatable("desktopscreens.builder.grip." + key);
    }

    /** Holding the builder, looking at a block, not building yet: how to start, for a few seconds ({@link HintFade}). */
    static void renderStartHint(Minecraft mc, GuiGraphics g) {
        if (mode != null || mc.player == null || mc.options.hideGui || mc.screen != null) return;
        if (ScreenBuilder.builderHand(mc.player) == null || !(mc.hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) return;
        startFade.draw(g, mc.font, "start " + taken, Component.translatable("desktopscreens.builder.start", DesktopClient.key(mc.options.keyUse)),
                g.guiWidth() / 2, g.guiHeight() / 2 + 16, 0xE0E0E0);
    }

    /**
     * How tall the screen should be at this width for what it will show to fill it, like "For DISPLAY2 (4:3): 4 × 3".
     * The picture fills the glass inside the outer bezel.
     */
    private static Component shapeHint(int cols, int rows) {
        if (source == null || source.width() <= 0 || source.height() <= 0) return null;
        float bezel = ScreenGroups.BEZEL;
        int best = Math.max(1, Math.round((cols - 2 * bezel) * source.height() / source.width() + 2 * bezel));
        Component shown = source.window() ? Component.translatable("desktopscreens.builder.the_window") : Component.literal(source.monitor());
        String ratio = ratio(source.width(), source.height());
        return best == rows ? Component.translatable("desktopscreens.builder.shape_fits", shown, ratio)
                : Component.translatable("desktopscreens.builder.shape", shown, ratio, cols, best);
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
