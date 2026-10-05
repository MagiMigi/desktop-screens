package com.desktopscreens;

import net.minecraft.block.SoundType;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.SoundCategory;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraft.world.World;
import net.minecraftforge.items.ItemHandlerHelper;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Building a whole rectangle of screens at once, and resizing, moving or taking down a screen that's there, for the
 * Screen Builder. The checks are shared: the client colors the hologram with them, and the server checks again before
 * it changes anything.
 */
public final class ScreenBuilder {
    /** How many different things in the way the message names; the rest are counted ("and 2 more"). */
    private static final int NAMED = 3;

    private ScreenBuilder() {}

    /** The hand holding the builder: the main hand, or the off hand while the main hand is empty. Null if neither. */
    public static EnumHand builderHand(EntityPlayer player) {
        if (player.getHeldItemMainhand().getItem() instanceof ScreenBuilderItem) return EnumHand.MAIN_HAND;
        if (player.getHeldItemMainhand().isEmpty() && player.getHeldItemOffhand().getItem() instanceof ScreenBuilderItem) return EnumHand.OFF_HAND;
        return null;
    }

    /** One coordinate of a block along an axis. */
    private static int along(EnumFacing.Axis axis, BlockPos p) {
        return axis == EnumFacing.Axis.X ? p.getX() : axis == EnumFacing.Axis.Y ? p.getY() : p.getZ();
    }

    /**
     * Columns and rows of the screen from {@code from} to {@code to}, as seen from the front, or null unless it's
     * upright, flat and facing one of the four horizontal directions.
     */
    public static int[] size(BlockPos from, BlockPos to, EnumFacing facing) {
        if (!facing.getAxis().isHorizontal()) return null;
        EnumFacing.Axis depth = facing.getAxis();
        if (along(depth, from) != along(depth, to)) return null;
        int across = depth == EnumFacing.Axis.Z ? to.getX() - from.getX() : to.getZ() - from.getZ();
        return new int[] {Math.abs(across) + 1, Math.abs(to.getY() - from.getY()) + 1};
    }

    /** Whether {@code pos} is in the same plane across {@code facing} as {@code screen}. */
    public static boolean samePlane(BlockPos pos, BlockPos screen, EnumFacing facing) {
        return along(facing.getAxis(), pos) == along(facing.getAxis(), screen);
    }

    /** A plain screen facing {@code facing}, for the checks. */
    public static IBlockState panel(EnumFacing facing) {
        return DesktopScreens.SCREEN.getDefaultState().withProperty(ScreenBlock.FACING, facing);
    }

    /**
     * Whether a screen can go at {@code pos}: inside the world and its border, with only replaceable blocks there
     * (air, water, grass), and nobody standing where the panel would be.
     */
    public static boolean fits(World world, BlockPos pos, IBlockState panel) {
        return !world.isOutsideBuildHeight(pos) && world.getWorldBorder().contains(pos) && world.isBlockLoaded(pos)
                && world.getBlockState(pos).getBlock().isReplaceable(world, pos) && unobstructed(world, pos, panel);
    }

    /** Nobody stands where the panel would be. */
    private static boolean unobstructed(World world, BlockPos pos, IBlockState panel) {
        AxisAlignedBB box = panel.getCollisionBoundingBox(world, pos);
        return box == null || world.checkNoEntityCollision(box.offset(pos));
    }

    /**
     * Server side: how far away (to its nearest block) a screen may go, be changed or taken down: the server's
     * {@code builder.range}, 32 blocks by default. A move or a resize counts where the screen goes, not where it was.
     */
    public static double reach(EntityPlayerMP player) {
        return BuilderRules.of(player.getServer()).range();
    }

    /** Whether the screen is close enough to {@code player} to be built. */
    public static boolean inRange(EntityPlayer player, BlockPos from, BlockPos to, double reach) {
        return inRange(player, Arrays.asList(from, to), reach);
    }

    /** Whether blocks of a screen are close enough to {@code player} to be changed (none: yes). */
    public static boolean inRange(EntityPlayer player, Collection<BlockPos> cells, double reach) {
        if (cells.isEmpty()) return true;
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (BlockPos p : cells) {
            minX = Math.min(minX, p.getX());
            minY = Math.min(minY, p.getY());
            minZ = Math.min(minZ, p.getZ());
            maxX = Math.max(maxX, p.getX());
            maxY = Math.max(maxY, p.getY());
            maxZ = Math.max(maxZ, p.getZ());
        }
        Vec3d eye = player.getPositionEyes(1f);
        double dx = Math.max(0, Math.max(minX - eye.x, eye.x - (maxX + 1)));
        double dy = Math.max(0, Math.max(minY - eye.y, eye.y - (maxY + 1)));
        double dz = Math.max(0, Math.max(minZ - eye.z, eye.z - (maxZ + 1)));
        return dx * dx + dy * dy + dz * dz <= reach * reach;
    }

    /**
     * What's in the way of new screens facing {@code facing} at {@code cells}, for the message: "In the way (red):
     * Cobblestone, Dirt, Sand and 2 more", the most common first, at most {@link #NAMED} named, so the others show as
     * those are cleared away. Blocks by name; players and mobs standing there; the build limit, the world border, land
     * not loaded. Where the screen's own blocks will go ({@code leaving}), only what stands there counts.
     */
    public static ITextComponent inTheWay(World world, List<BlockPos> cells, EnumFacing facing, Collection<BlockPos> leaving) {
        IBlockState panel = panel(facing);
        Set<BlockPos> vacated = new HashSet<>(leaving);
        Map<Object, ITextComponent> names = new HashMap<>();
        Map<Object, Integer> counts = new LinkedHashMap<>(); // in the order found, so equal counts don't swap places
        for (BlockPos cell : cells) {
            Object[] o = obstacle(world, cell, panel, vacated.contains(cell));
            names.putIfAbsent(o[0], (ITextComponent) o[1]);
            counts.merge(o[0], 1, Integer::sum);
        }
        List<Map.Entry<Object, Integer>> kinds = new ArrayList<>(counts.entrySet());
        kinds.sort((a, b) -> b.getValue() - a.getValue()); // stable
        ITextComponent list = new TextComponentString("");
        int shown = Math.min(NAMED, kinds.size());
        for (int i = 0; i < shown; i++) {
            if (i > 0) list.appendText(", ");
            list.appendSibling(names.get(kinds.get(i).getKey()));
        }
        int more = kinds.size() - shown;
        return more > 0 ? new TextComponentTranslation("desktopscreens.builder.blocked_more", list, more)
                : new TextComponentTranslation("desktopscreens.builder.blocked", list);
    }

    /** One thing in the way: {what tells it apart from others of its kind (a block, an entity type...), its name}. */
    private static Object[] obstacle(World world, BlockPos pos, IBlockState panel, boolean vacated) {
        if (world.isOutsideBuildHeight(pos)) return reason("desktopscreens.builder.build_limit");
        if (!world.getWorldBorder().contains(pos)) return reason("desktopscreens.builder.world_border");
        if (!world.isBlockLoaded(pos)) return reason("desktopscreens.builder.unloaded");
        IBlockState state = world.getBlockState(pos);
        if (!vacated && !state.getBlock().isReplaceable(world, pos)) {
            return new Object[] {state.getBlock(), new TextComponentTranslation(state.getBlock().getTranslationKey() + ".name")};
        }
        // Else something standing there.
        AxisAlignedBB box = panel.getCollisionBoundingBox(world, pos);
        if (box != null) {
            for (Entity e : world.getEntitiesWithinAABBExcludingEntity(null, box.offset(pos))) {
                if (!e.isDead && e.preventEntitySpawning) {
                    return e instanceof EntityPlayer ? new Object[] {e.getUniqueID(), e.getDisplayName()} : new Object[] {e.getClass(), e.getDisplayName()};
                }
            }
        }
        return reason("desktopscreens.builder.something");
    }

    private static Object[] reason(String key) {
        return new Object[] {key, new TextComponentTranslation(key)};
    }

    // ---- Where a screen's blocks are, seen from the front ----

    /** The viewer's right, looking at the front of a screen facing {@code facing}: where its columns count up. */
    public static EnumFacing right(EnumFacing facing) {
        return facing.rotateYCCW();
    }

    /** The column {@code pos} is in, for a screen facing {@code facing}: counted to the viewer's right. */
    public static int column(BlockPos pos, EnumFacing facing) {
        EnumFacing right = right(facing);
        return pos.getX() * right.getXOffset() + pos.getZ() * right.getZOffset();
    }

    /** The block in column {@code col} ({@link #column}) at height {@code y}, in the plane of {@code inPlane}. */
    public static BlockPos cellAt(BlockPos inPlane, EnumFacing facing, int col, int y) {
        EnumFacing right = right(facing);
        return right.getAxis() == EnumFacing.Axis.X
                ? new BlockPos(col * right.getXOffset(), y, inPlane.getZ())
                : new BlockPos(inPlane.getX(), y, col * right.getZOffset());
    }

    /** A screen's outline seen from the front: its lowest column ({@link #column}) and row (y), how many of each. */
    public static final class Bounds {
        public final int minCol, minRow, cols, rows;

        Bounds(int minCol, int minRow, int cols, int rows) {
            this.minCol = minCol;
            this.minRow = minRow;
            this.cols = cols;
            this.rows = rows;
        }

        public int maxCol() {
            return minCol + cols - 1;
        }

        public int maxRow() {
            return minRow + rows - 1;
        }
    }

    public static Bounds bounds(Collection<BlockPos> cells, EnumFacing facing) {
        int minC = Integer.MAX_VALUE, maxC = Integer.MIN_VALUE, minR = Integer.MAX_VALUE, maxR = Integer.MIN_VALUE;
        for (BlockPos p : cells) {
            int c = column(p, facing);
            minC = Math.min(minC, c);
            maxC = Math.max(maxC, c);
            minR = Math.min(minR, p.getY());
            maxR = Math.max(maxR, p.getY());
        }
        return new Bounds(minC, minR, maxC - minC + 1, maxR - minR + 1);
    }

    /** Every block of the rectangle from {@code from} to {@code to}. */
    public static List<BlockPos> cells(BlockPos from, BlockPos to) {
        List<BlockPos> out = new ArrayList<>();
        for (BlockPos p : BlockPos.getAllInBox(from, to)) out.add(p.toImmutable());
        return out;
    }

    /**
     * Where the blocks of a screen facing {@code from} go when it's moved so that the bottom-left of its outline is at
     * {@code base}, facing {@code to}: the same shape, seen from the front.
     */
    public static List<BlockPos> moved(List<BlockPos> members, EnumFacing from, BlockPos base, EnumFacing to) {
        Bounds b = bounds(members, from);
        EnumFacing right = right(to);
        List<BlockPos> out = new ArrayList<>(members.size());
        for (BlockPos p : members) out.add(base.offset(right, column(p, from) - b.minCol).up(p.getY() - b.minRow));
        return out;
    }

    // ---- Changing a screen that's there ----

    /** What changing a screen does: the blocks to place, and its own blocks that go. The others stay as they are. */
    public static final class Change {
        public static final Change NONE = new Change(Collections.emptyList(), Collections.emptyList());
        public final List<BlockPos> added, leaving;

        Change(List<BlockPos> added, List<BlockPos> leaving) {
            this.added = added;
            this.leaving = leaving;
        }
    }

    /** From a screen's blocks {@code members}, facing {@code oldFacing}, to blocks {@code after}, facing {@code newFacing}. */
    public static Change change(List<BlockPos> members, EnumFacing oldFacing, List<BlockPos> after, EnumFacing newFacing) {
        boolean turned = oldFacing != newFacing; // then every block is replaced
        Set<BlockPos> before = new HashSet<>(members), then = new HashSet<>(after);
        List<BlockPos> added = new ArrayList<>(), leaving = new ArrayList<>();
        for (BlockPos p : after) {
            if (turned || !before.contains(p)) added.add(p);
        }
        for (BlockPos p : members) {
            if (turned || !then.contains(p)) leaving.add(p);
        }
        return new Change(added, leaving);
    }

    /** What stops a change: blocks in the way of new screens, and new screens that would join another screen. */
    public static final class Obstacles {
        public final List<BlockPos> inTheWay, joining;

        Obstacles(List<BlockPos> inTheWay, List<BlockPos> joining) {
            this.inTheWay = inTheWay;
            this.joining = joining;
        }
    }

    /**
     * What's in the way of {@code change}, for a screen facing {@code facing} whose blocks will be {@code after}. Its
     * own leaving blocks count as free. With {@code apart}, a new block may not touch another screen either: moving or
     * resizing a screen never merges it into another (a new build may join one, as a screen placed by hand does).
     */
    public static Obstacles obstacles(World world, Change change, Collection<BlockPos> after, EnumFacing facing, boolean apart) {
        IBlockState panel = panel(facing);
        Set<BlockPos> leaving = new HashSet<>(change.leaving), then = new HashSet<>(after);
        EnumFacing left = facing.rotateY();
        List<BlockPos> inTheWay = new ArrayList<>(), joining = new ArrayList<>();
        for (BlockPos p : change.added) {
            boolean free = leaving.contains(p) ? unobstructed(world, p, panel) : fits(world, p, panel);
            if (!free) {
                inTheWay.add(p);
            } else if (apart && (joinsOther(world, p.up(), facing, then, leaving) || joinsOther(world, p.down(), facing, then, leaving)
                    || joinsOther(world, p.offset(left), facing, then, leaving) || joinsOther(world, p.offset(left.getOpposite()), facing, then, leaving))) {
                joining.add(p);
            }
        }
        return new Obstacles(inTheWay, joining);
    }

    private static boolean joinsOther(World world, BlockPos neighbor, EnumFacing facing, Set<BlockPos> then, Set<BlockPos> leaving) {
        if (then.contains(neighbor) || leaving.contains(neighbor) || !world.isBlockLoaded(neighbor)) return false;
        IBlockState state = world.getBlockState(neighbor);
        return state.getBlock() instanceof ScreenBlock && state.getValue(ScreenBlock.FACING) == facing;
    }

    /**
     * Someone else's screen that's on, while they're online: only they may change it (one left on by a player who's
     * gone is anyone's, as for turning it on).
     */
    public static boolean inUseByOther(EntityPlayerMP player, ScreenBlockEntity screen) {
        UUID owner = screen.owner();
        return owner != null && !owner.equals(player.getUniqueID()) && player.getServer().getPlayerList().getPlayerByUUID(owner) != null;
    }

    /** How many screens {@code player} carries. */
    public static int screensIn(EntityPlayer player) {
        InventoryPlayer inventory = player.inventory;
        int count = 0;
        for (int i = 0; i < inventory.getSizeInventory(); i++) {
            ItemStack stack = inventory.getStackInSlot(i);
            if (isScreen(stack)) count += stack.getCount();
        }
        return count;
    }

    private static boolean isScreen(ItemStack stack) {
        return stack.getItem() instanceof ItemBlock && ((ItemBlock) stack.getItem()).getBlock() instanceof ScreenBlock;
    }

    /** Whether the player may change blocks at all (not in adventure mode). */
    private static boolean mayBuild(EntityPlayer player) {
        return player.capabilities.allowEdit;
    }

    /** Server side, when a player asks for a screen. Says in the action bar what happened. */
    static void build(EntityPlayerMP player, BuildScreen request) {
        BlockPos from = request.from, to = request.to;
        EnumFacing facing = request.facing;
        int[] size = size(from, to, facing);
        if (size == null || player.isSpectator() || builderHand(player) == null) return;
        if (!mayBuild(player)) {
            say(player, "desktopscreens.builder.cant_build");
            return;
        }
        BuilderRules rules = BuilderRules.of(player.getServer());
        if (size[0] > rules.maxWidth() || size[1] > rules.maxHeight()) {
            say(player, "desktopscreens.builder.too_big", rules.maxWidth(), rules.maxHeight());
            return;
        }
        if (!inRange(player, from, to, reach(player))) {
            say(player, "desktopscreens.builder.too_far");
            return;
        }
        World world = player.world;
        List<BlockPos> cells = cells(from, to);
        for (BlockPos cell : cells) {
            if (!world.isBlockModifiable(player, cell)) { // spawn protection
                say(player, "desktopscreens.builder.cant_build");
                return;
            }
        }
        // Before looking at what's in the way, so that also sees anything a mod told of the breaks changed meanwhile.
        if (!LandClaims.mayBreak(world, player, replaced(world, cells, Collections.emptySet()))) {
            say(player, "desktopscreens.builder.cant_build");
            return;
        }
        IBlockState panel = panel(facing);
        List<BlockPos> blocked = new ArrayList<>();
        for (BlockPos cell : cells) {
            if (!fits(world, cell, panel)) blocked.add(cell);
        }
        if (!blocked.isEmpty()) {
            Players.overlay(player, inTheWay(world, blocked, facing, Collections.emptyList()));
            return;
        }
        boolean free = player.capabilities.isCreativeMode;
        if (!free) {
            int have = screensIn(player);
            if (have < cells.size()) {
                say(player, "desktopscreens.builder.need_screens", cells.size(), have);
                return;
            }
        }

        boolean placed = LandClaims.place(world, player, cells, facing, () -> {
            for (BlockPos cell : cells) world.setBlockState(cell, panel, 3);
        });
        if (!placed) {
            say(player, "desktopscreens.builder.cant_build");
            return;
        }
        joinOwner(world, cells);
        if (!free) take(player, cells.size());
        Vec3d middle = middle(cells);
        world.playSound(null, middle.x, middle.y, middle.z, SoundType.METAL.getPlaceSound(), SoundCategory.BLOCKS, 1.0F, 0.8F);
        say(player, "desktopscreens.builder.built", size[0], size[1]);
    }

    private static Vec3d middle(Collection<BlockPos> cells) {
        double x = 0, y = 0, z = 0;
        for (BlockPos p : cells) {
            x += p.getX() + 0.5;
            y += p.getY() + 0.5;
            z += p.getZ() + 0.5;
        }
        return new Vec3d(x / cells.size(), y / cells.size(), z / cells.size());
    }

    /**
     * Where new screens go at {@code cells}, what they'd replace (grass, snow, water; not the screen's own blocks at
     * {@code leaving}), which the claims are asked about as broken.
     */
    private static List<BlockPos> replaced(World world, List<BlockPos> cells, Collection<BlockPos> leaving) {
        Set<BlockPos> own = new HashSet<>(leaving);
        List<BlockPos> out = new ArrayList<>();
        for (BlockPos cell : cells) {
            if (!own.contains(cell) && !world.isAirBlock(cell) && world.getBlockState(cell).getBlock().isReplaceable(world, cell)) out.add(cell);
        }
        return out;
    }

    /** New screens joined to one that's on take over its owner, like a screen placed by hand does. */
    private static void joinOwner(World world, List<BlockPos> built) {
        Set<BlockPos> fresh = new HashSet<>(built);
        ScreenBlockEntity on = null;
        for (BlockPos member : ScreenBlock.group(world, built.get(0))) {
            TileEntity te = world.getTileEntity(member);
            if (!fresh.contains(member) && te instanceof ScreenBlockEntity && ((ScreenBlockEntity) te).owner() != null) {
                on = (ScreenBlockEntity) te;
                break;
            }
        }
        if (on == null) return;
        copyOwner(world, built, on);
    }

    /**
     * Server side, when a player resizes, moves or takes down a screen with the builder (Shift-click). Nothing changes
     * unless all of it can: nothing is ever replaced, and it never joins another screen. It stays on or off, for the
     * same owner, shared with the same players. Says in the action bar what happened.
     */
    static void change(EntityPlayerMP player, ChangeScreen request) {
        World world = player.world;
        BlockPos at = request.screen;
        if (player.isSpectator() || builderHand(player) == null) return;
        if (!world.isBlockLoaded(at)) { // carried away from where it was, beyond what's loaded
            say(player, "desktopscreens.builder.too_far");
            return;
        }
        IBlockState clicked = world.getBlockState(at);
        TileEntity te = world.getTileEntity(at);
        if (!(clicked.getBlock() instanceof ScreenBlock) || !(te instanceof ScreenBlockEntity)) return;
        ScreenBlockEntity was = (ScreenBlockEntity) te;
        if (!mayBuild(player)) {
            say(player, "desktopscreens.builder.cant_build");
            return;
        }
        if (inUseByOther(player, was)) {
            say(player, "desktopscreens.screen.in_use", was.ownerName());
            return;
        }
        EnumFacing facing = clicked.getValue(ScreenBlock.FACING), newFacing = facing;
        List<BlockPos> members = ScreenBlock.group(world, at);
        Bounds before = bounds(members, facing);
        List<BlockPos> after = Collections.emptyList();
        switch (request.action) {
            case RESIZE: {
                BlockPos from = request.from, to = request.to;
                int[] size = size(from, to, facing);
                if (size == null || !samePlane(from, at, facing)) return;
                BuilderRules rules = BuilderRules.of(player.getServer());
                if (size[0] > rules.maxWidth() || size[1] > rules.maxHeight()) {
                    say(player, "desktopscreens.builder.too_big", rules.maxWidth(), rules.maxHeight());
                    return;
                }
                after = cells(from, to);
                break;
            }
            case MOVE:
                if (!request.facing.getAxis().isHorizontal()) return;
                newFacing = request.facing;
                after = moved(members, facing, request.from, newFacing);
                break;
            case TAKE_DOWN:
                break;
        }
        Change change = change(members, facing, after, newFacing);
        if (change.added.isEmpty() && change.leaving.isEmpty()) return;
        // Where it goes; or, taken down, where it is.
        if (!inRange(player, after.isEmpty() ? members : after, reach(player))) {
            say(player, "desktopscreens.builder.too_far");
            return;
        }
        for (List<BlockPos> cells : Arrays.asList(change.added, change.leaving)) {
            for (BlockPos cell : cells) {
                if (!world.isBlockModifiable(player, cell)) { // spawn protection
                    say(player, "desktopscreens.builder.cant_build");
                    return;
                }
            }
        }
        List<BlockPos> broken = new ArrayList<>(change.leaving);
        broken.addAll(replaced(world, change.added, change.leaving));
        if (!LandClaims.mayBreak(world, player, broken)) {
            say(player, "desktopscreens.builder.cant_build");
            return;
        }
        // A mod told of the breaks may have changed the screen meanwhile, say a vein miner breaking the rest of it:
        // then the screens given back would be extra. What's in the way is looked at after this, for the same reason.
        if (!ScreenBlock.group(world, at).equals(members)) {
            say(player, "desktopscreens.builder.screen_changed");
            return;
        }
        Obstacles obstacles = obstacles(world, change, after, newFacing, true);
        if (!obstacles.inTheWay.isEmpty()) {
            Players.overlay(player, inTheWay(world, obstacles.inTheWay, newFacing, change.leaving));
            return;
        }
        if (!obstacles.joining.isEmpty()) {
            say(player, "desktopscreens.builder.joins_other");
            return;
        }
        boolean free = player.capabilities.isCreativeMode;
        int needed = change.added.size() - change.leaving.size();
        if (!free && needed > 0) {
            int have = screensIn(player);
            if (have < needed) {
                say(player, "desktopscreens.builder.need_screens", needed, have);
                return;
            }
        }

        // Its blocks go first, since the new ones may be where they were; if a claim refuses a new one, they come back.
        Map<BlockPos, IBlockState> gone = new LinkedHashMap<>();
        for (BlockPos cell : change.leaving) {
            IBlockState state = world.getBlockState(cell);
            if (world.setBlockToAir(cell)) gone.put(cell, state);
        }
        IBlockState panel = panel(newFacing);
        boolean placed = LandClaims.place(world, player, change.added, newFacing, () -> {
            for (BlockPos cell : change.added) world.setBlockState(cell, panel, 3);
        });
        // `was` may be gone from the world by now, but still holds who it was on for, and which screen it is (off too).
        if (!placed) {
            gone.forEach((cell, state) -> world.setBlockState(cell, state, 3));
            copyOwner(world, gone.keySet(), was);
            say(player, "desktopscreens.builder.cant_build");
            return;
        }
        copyOwner(world, change.added, was);
        if (!free && needed > 0) take(player, needed);
        if (!free && needed < 0) give(player, -needed);

        Vec3d middle = middle(after.isEmpty() ? members : after);
        world.playSound(null, middle.x, middle.y, middle.z, after.isEmpty() ? SoundType.METAL.getBreakSound() : SoundType.METAL.getPlaceSound(),
                SoundCategory.BLOCKS, 1.0F, 0.8F);
        switch (request.action) {
            case RESIZE: {
                int[] size = size(request.from, request.to, facing);
                say(player, "desktopscreens.builder.resized", size[0], size[1]);
                break;
            }
            case MOVE: say(player, "desktopscreens.builder.moved"); break;
            case TAKE_DOWN: say(player, "desktopscreens.builder.taken_down", before.cols, before.rows); break;
        }
    }

    private static void copyOwner(World world, Collection<BlockPos> cells, ScreenBlockEntity from) {
        for (BlockPos cell : cells) {
            TileEntity te = world.getTileEntity(cell);
            if (te instanceof ScreenBlockEntity) ((ScreenBlockEntity) te).copyOwner(from);
        }
    }

    /** Screens back into the inventory; what doesn't fit drops at the player's feet. */
    private static void give(EntityPlayer player, int count) {
        int stackSize = new ItemStack(DesktopScreens.SCREEN_ITEM).getMaxStackSize();
        while (count > 0) {
            int n = Math.min(count, stackSize);
            ItemHandlerHelper.giveItemToPlayer(player, new ItemStack(DesktopScreens.SCREEN_ITEM, n));
            count -= n;
        }
    }

    private static void take(EntityPlayer player, int count) {
        InventoryPlayer inventory = player.inventory;
        for (int i = 0; i < inventory.getSizeInventory() && count > 0; i++) {
            ItemStack stack = inventory.getStackInSlot(i);
            if (!isScreen(stack)) continue;
            int used = Math.min(count, stack.getCount());
            stack.shrink(used);
            count -= used;
        }
        inventory.markDirty();
    }

    private static void say(EntityPlayer player, String key, Object... args) {
        Players.overlay(player, new TextComponentTranslation(key, args));
    }
}
