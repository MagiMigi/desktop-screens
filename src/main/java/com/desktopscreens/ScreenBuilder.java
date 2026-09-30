package com.desktopscreens;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentUtils;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
    public static InteractionHand builderHand(Player player) {
        if (player.getMainHandItem().getItem() instanceof ScreenBuilderItem) return InteractionHand.MAIN_HAND;
        if (player.getMainHandItem().isEmpty() && player.getOffhandItem().getItem() instanceof ScreenBuilderItem) return InteractionHand.OFF_HAND;
        return null;
    }

    /**
     * Columns and rows of the screen from {@code from} to {@code to}, as seen from the front, or null unless it's
     * upright, flat and facing one of the four horizontal directions.
     */
    public static int[] size(BlockPos from, BlockPos to, Direction facing) {
        if (!facing.getAxis().isHorizontal()) return null;
        Direction.Axis depth = facing.getAxis();
        if (depth.choose(from.getX(), from.getY(), from.getZ()) != depth.choose(to.getX(), to.getY(), to.getZ())) return null;
        int across = depth == Direction.Axis.Z ? to.getX() - from.getX() : to.getZ() - from.getZ();
        return new int[] {Math.abs(across) + 1, Math.abs(to.getY() - from.getY()) + 1};
    }

    /** A plain screen facing {@code facing}, for the checks. */
    public static BlockState panel(Direction facing) {
        return screenBlock().defaultBlockState().setValue(ScreenBlock.FACING, facing);
    }

    private static ScreenBlock screenBlock() {
        return (ScreenBlock) BuiltInRegistries.BLOCK.get(DesktopScreens.id(ScreenBlock.NAME));
    }

    /**
     * Whether a screen can go at {@code pos}: inside the world and its border, with only replaceable blocks there
     * (air, water, grass), and nobody standing where the panel would be.
     */
    public static boolean fits(Level level, BlockPos pos, BlockState panel) {
        return !level.isOutsideBuildHeight(pos) && level.getWorldBorder().isWithinBounds(pos) && level.isLoaded(pos)
                && level.getBlockState(pos).canBeReplaced() && level.isUnobstructed(panel, pos, CollisionContext.empty());
    }

    /**
     * Server side: how far away (to its nearest block) a screen may go, be changed or taken down: the server's
     * {@code builder.range}, 32 blocks by default. A move or a resize counts where the screen goes, not where it was:
     * counting both said "Too far away" 5 blocks from the hologram of a screen moved far (2026-09-25).
     */
    public static double reach(ServerPlayer player) {
        return BuilderRules.of(player.server).range();
    }

    /** Whether the screen is close enough to {@code player} to be built. */
    public static boolean inRange(Player player, BlockPos from, BlockPos to, double reach) {
        return AABB.encapsulatingFullBlocks(from, to).distanceToSqr(player.getEyePosition()) <= reach * reach;
    }

    /** Whether blocks of a screen are close enough to {@code player} to be changed (none: yes). */
    public static boolean inRange(Player player, Collection<BlockPos> cells, double reach) {
        Optional<BoundingBox> box = BoundingBox.encapsulatingPositions(cells);
        return box.isEmpty() || AABB.of(box.get()).distanceToSqr(player.getEyePosition()) <= reach * reach;
    }

    /**
     * What's in the way of new screens facing {@code facing} at {@code cells}, for the message: "In the way (red):
     * Cobblestone, Dirt, Sand and 2 more", the most common first, at most {@link #NAMED} named, so the others show as
     * those are cleared away (2026-09-25). Blocks by name; players and mobs standing there; the build
     * limit, the world border, land not loaded. Where the screen's own blocks will go ({@code leaving}), only what
     * stands there counts.
     */
    public static Component inTheWay(Level level, List<BlockPos> cells, Direction facing, Collection<BlockPos> leaving) {
        BlockState panel = panel(facing);
        Set<BlockPos> vacated = new HashSet<>(leaving);
        Map<Object, Component> names = new HashMap<>();
        Map<Object, Integer> counts = new LinkedHashMap<>(); // in the order found, so equal counts don't swap places
        for (BlockPos cell : cells) {
            Obstacle o = obstacle(level, cell, panel, vacated.contains(cell));
            names.putIfAbsent(o.key(), o.name());
            counts.merge(o.key(), 1, Integer::sum);
        }
        List<Map.Entry<Object, Integer>> kinds = new ArrayList<>(counts.entrySet());
        kinds.sort((a, b) -> b.getValue() - a.getValue()); // stable
        List<Component> shown = new ArrayList<>();
        for (int i = 0; i < Math.min(NAMED, kinds.size()); i++) shown.add(names.get(kinds.get(i).getKey()));
        Component list = ComponentUtils.formatList(shown, Component.literal(", "));
        int more = kinds.size() - shown.size();
        return more > 0 ? Component.translatable("desktopscreens.builder.blocked_more", list, more)
                : Component.translatable("desktopscreens.builder.blocked", list);
    }

    /** One thing in the way: what tells it apart from others of its kind (a block, an entity type...), and its name. */
    private record Obstacle(Object key, Component name) {}

    private static Obstacle obstacle(Level level, BlockPos pos, BlockState panel, boolean vacated) {
        if (level.isOutsideBuildHeight(pos)) return reason("desktopscreens.builder.build_limit");
        if (!level.getWorldBorder().isWithinBounds(pos)) return reason("desktopscreens.builder.world_border");
        if (!level.isLoaded(pos)) return reason("desktopscreens.builder.unloaded");
        BlockState state = level.getBlockState(pos);
        if (!vacated && !state.canBeReplaced()) return new Obstacle(state.getBlock(), state.getBlock().getName());
        // Else something standing there, as Level.isUnobstructed finds it.
        VoxelShape shape = panel.getCollisionShape(level, pos, CollisionContext.empty()).move(pos.getX(), pos.getY(), pos.getZ());
        if (!shape.isEmpty()) {
            for (Entity e : level.getEntities((Entity) null, shape.bounds())) {
                if (!e.isRemoved() && e.blocksBuilding && Shapes.joinIsNotEmpty(shape, Shapes.create(e.getBoundingBox()), BooleanOp.AND)) {
                    return e instanceof Player ? new Obstacle(e.getUUID(), e.getName()) : new Obstacle(e.getType(), e.getType().getDescription());
                }
            }
        }
        return reason("desktopscreens.builder.something");
    }

    private static Obstacle reason(String key) {
        return new Obstacle(key, Component.translatable(key));
    }

    // ---- Where a screen's blocks are, seen from the front ----

    /** The viewer's right, looking at the front of a screen facing {@code facing}: where its columns count up. */
    public static Direction right(Direction facing) {
        return facing.getCounterClockWise();
    }

    /** The column {@code pos} is in, for a screen facing {@code facing}: counted to the viewer's right. */
    public static int column(BlockPos pos, Direction facing) {
        Direction right = right(facing);
        return pos.getX() * right.getStepX() + pos.getZ() * right.getStepZ();
    }

    /** The block in column {@code col} ({@link #column}) at height {@code y}, in the plane of {@code inPlane}. */
    public static BlockPos cellAt(BlockPos inPlane, Direction facing, int col, int y) {
        Direction right = right(facing);
        return right.getAxis() == Direction.Axis.X
                ? new BlockPos(col * right.getStepX(), y, inPlane.getZ())
                : new BlockPos(inPlane.getX(), y, col * right.getStepZ());
    }

    /** A screen's outline seen from the front: its lowest column ({@link #column}) and row (y), how many of each. */
    public record Bounds(int minCol, int minRow, int cols, int rows) {
        public int maxCol() {
            return minCol + cols - 1;
        }

        public int maxRow() {
            return minRow + rows - 1;
        }
    }

    public static Bounds bounds(Collection<BlockPos> cells, Direction facing) {
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
        for (BlockPos p : BlockPos.betweenClosed(from, to)) out.add(p.immutable());
        return out;
    }

    /**
     * Where the blocks of a screen facing {@code from} go when it's moved so that the bottom-left of its outline is at
     * {@code base}, facing {@code to}: the same shape, seen from the front.
     */
    public static List<BlockPos> moved(List<BlockPos> members, Direction from, BlockPos base, Direction to) {
        Bounds b = bounds(members, from);
        Direction right = right(to);
        List<BlockPos> out = new ArrayList<>(members.size());
        for (BlockPos p : members) out.add(base.relative(right, column(p, from) - b.minCol()).above(p.getY() - b.minRow()));
        return out;
    }

    // ---- Changing a screen that's there ----

    /** What changing a screen does: the blocks to place, and its own blocks that go. The others stay as they are. */
    public record Change(List<BlockPos> added, List<BlockPos> leaving) {}

    /** From a screen's blocks {@code members}, facing {@code oldFacing}, to blocks {@code after}, facing {@code newFacing}. */
    public static Change change(List<BlockPos> members, Direction oldFacing, List<BlockPos> after, Direction newFacing) {
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
    public record Obstacles(List<BlockPos> inTheWay, List<BlockPos> joining) {}

    /**
     * What's in the way of {@code change}, for a screen facing {@code facing} whose blocks will be {@code after}. Its
     * own leaving blocks count as free. With {@code apart}, a new block may not touch another screen either: moving or
     * resizing a screen never merges it into another (a new build may join one, as a screen placed by hand does).
     */
    public static Obstacles obstacles(Level level, Change change, Collection<BlockPos> after, Direction facing, boolean apart) {
        BlockState panel = panel(facing);
        Set<BlockPos> leaving = new HashSet<>(change.leaving()), then = new HashSet<>(after);
        Direction left = facing.getClockWise();
        List<BlockPos> inTheWay = new ArrayList<>(), joining = new ArrayList<>();
        for (BlockPos p : change.added()) {
            boolean free = leaving.contains(p) ? level.isUnobstructed(panel, p, CollisionContext.empty()) : fits(level, p, panel);
            if (!free) {
                inTheWay.add(p);
            } else if (apart && (joinsOther(level, p.above(), facing, then, leaving) || joinsOther(level, p.below(), facing, then, leaving)
                    || joinsOther(level, p.relative(left), facing, then, leaving) || joinsOther(level, p.relative(left.getOpposite()), facing, then, leaving))) {
                joining.add(p);
            }
        }
        return new Obstacles(inTheWay, joining);
    }

    private static boolean joinsOther(Level level, BlockPos neighbor, Direction facing, Set<BlockPos> then, Set<BlockPos> leaving) {
        if (then.contains(neighbor) || leaving.contains(neighbor) || !level.isLoaded(neighbor)) return false;
        BlockState state = level.getBlockState(neighbor);
        return state.getBlock() instanceof ScreenBlock && state.getValue(ScreenBlock.FACING) == facing;
    }

    /**
     * Someone else's screen that's on, while they're online: only they may change it (one left on by a player who's
     * gone is anyone's, as for turning it on).
     */
    public static boolean inUseByOther(ServerPlayer player, ScreenBlockEntity screen) {
        UUID owner = screen.owner();
        return owner != null && !owner.equals(player.getUUID()) && player.server.getPlayerList().getPlayer(owner) != null;
    }

    /** How many screens {@code player} carries. */
    public static int screensIn(Player player) {
        Inventory inventory = player.getInventory();
        int count = 0;
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (isScreen(stack)) count += stack.getCount();
        }
        return count;
    }

    private static boolean isScreen(ItemStack stack) {
        return stack.getItem() instanceof BlockItem item && item.getBlock() instanceof ScreenBlock;
    }

    /** Server side, when a player asks for a screen. Says in the action bar what happened. */
    public static void build(ServerPlayer player, BuildScreen request) {
        BlockPos from = request.from(), to = request.to();
        Direction facing = request.facing();
        int[] size = size(from, to, facing);
        if (size == null || player.isSpectator() || builderHand(player) == null) return;
        if (!player.mayBuild()) {
            say(player, "desktopscreens.builder.cant_build");
            return;
        }
        BuilderRules rules = BuilderRules.of(player.server);
        if (size[0] > rules.maxWidth() || size[1] > rules.maxHeight()) {
            say(player, "desktopscreens.builder.too_big", rules.maxWidth(), rules.maxHeight());
            return;
        }
        if (!inRange(player, from, to, reach(player))) {
            say(player, "desktopscreens.builder.too_far");
            return;
        }
        ServerLevel level = player.serverLevel();
        List<BlockPos> cells = cells(from, to);
        for (BlockPos cell : cells) {
            if (!level.mayInteract(player, cell)) { // spawn protection
                say(player, "desktopscreens.builder.cant_build");
                return;
            }
        }
        // Before looking at what's in the way, so that also sees anything a mod told of the breaks changed meanwhile.
        if (!LandClaims.mayBreak(level, player, replaced(level, cells, Set.of()))) {
            say(player, "desktopscreens.builder.cant_build");
            return;
        }
        BlockState panel = panel(facing);
        List<BlockPos> blocked = new ArrayList<>();
        for (BlockPos cell : cells) {
            if (!fits(level, cell, panel)) blocked.add(cell);
        }
        if (!blocked.isEmpty()) {
            player.displayClientMessage(inTheWay(level, blocked, facing, List.of()), true);
            return;
        }
        boolean free = player.hasInfiniteMaterials();
        if (!free) {
            int have = screensIn(player);
            if (have < cells.size()) {
                say(player, "desktopscreens.builder.need_screens", cells.size(), have);
                return;
            }
        }

        ScreenBlock block = screenBlock();
        boolean placed = LandClaims.ask.place(level, player, cells, facing, () -> {
            // Each new screen joins the ones placed before it, and they update to join it (updateShape) once they're
            // told of it, which a loader may hold back until the claims have been asked.
            for (BlockPos cell : cells) level.setBlock(cell, block.stateFor(level, cell, facing), Block.UPDATE_ALL);
        });
        if (!placed) {
            say(player, "desktopscreens.builder.cant_build");
            return;
        }
        announcePlaced(level, player, cells);
        joinOwner(level, cells);
        if (!free) take(player, cells.size());
        AABB box = AABB.encapsulatingFullBlocks(from, to);
        level.playSound(null, box.getCenter().x, box.getCenter().y, box.getCenter().z,
                ScreenBlock.SOUND.getPlaceSound(), SoundSource.BLOCKS, 1.0F, 0.8F);
        say(player, "desktopscreens.builder.built", size[0], size[1]);
    }

    /**
     * Where new screens go at {@code cells}, what they'd replace (grass, snow, water; not the screen's own blocks at
     * {@code leaving}), which the claims are asked about as broken. Asked only about the screen placed there, Open
     * Parties and Claims lets it through whenever it replaces something other than air: for a player's own clicks it
     * leaves that to its right-click check, which the builder never goes through (read in its code, 2026-09-27).
     */
    private static List<BlockPos> replaced(Level level, List<BlockPos> cells, Collection<BlockPos> leaving) {
        Set<BlockPos> own = new HashSet<>(leaving);
        List<BlockPos> out = new ArrayList<>();
        for (BlockPos cell : cells) {
            BlockState state = level.getBlockState(cell);
            if (!own.contains(cell) && !state.isAir() && state.canBeReplaced()) out.add(cell);
        }
        return out;
    }

    /** Tells what listens for placed blocks (sculk sensors...) about new screens, once they're there for good. */
    private static void announcePlaced(Level level, Player player, List<BlockPos> cells) {
        for (BlockPos cell : cells) {
            BlockState state = level.getBlockState(cell);
            if (state.getBlock() instanceof ScreenBlock) level.gameEvent(GameEvent.BLOCK_PLACE, cell, GameEvent.Context.of(player, state));
        }
    }

    /** New screens joined to one that's on take over its owner, like a screen placed by hand does. */
    private static void joinOwner(Level level, List<BlockPos> built) {
        Set<BlockPos> fresh = new HashSet<>(built);
        ScreenBlockEntity on = null;
        for (BlockPos member : ScreenBlock.group(level, built.get(0))) {
            if (!fresh.contains(member) && level.getBlockEntity(member) instanceof ScreenBlockEntity screen && screen.owner() != null) {
                on = screen;
                break;
            }
        }
        if (on == null) return;
        for (BlockPos cell : built) {
            if (level.getBlockEntity(cell) instanceof ScreenBlockEntity screen) screen.copyOwner(on);
        }
    }

    /**
     * Server side, when a player resizes, moves or takes down a screen with the builder (Shift-click). Nothing changes
     * unless all of it can: nothing is ever replaced, and it never joins another screen. It stays on or off, for the
     * same owner, shared with the same players. Says in the action bar what happened.
     */
    public static void change(ServerPlayer player, ChangeScreen request) {
        ServerLevel level = player.serverLevel();
        BlockPos at = request.screen();
        if (player.isSpectator() || builderHand(player) == null) return;
        if (!level.isLoaded(at)) { // carried away from where it was, beyond what's loaded
            say(player, "desktopscreens.builder.too_far");
            return;
        }
        BlockState clicked = level.getBlockState(at);
        if (!(clicked.getBlock() instanceof ScreenBlock) || !(level.getBlockEntity(at) instanceof ScreenBlockEntity was)) return;
        if (!player.mayBuild()) {
            say(player, "desktopscreens.builder.cant_build");
            return;
        }
        if (inUseByOther(player, was)) {
            say(player, "desktopscreens.screen.in_use", was.ownerName());
            return;
        }
        Direction facing = clicked.getValue(ScreenBlock.FACING), newFacing = facing;
        List<BlockPos> members = ScreenBlock.group(level, at);
        Bounds before = bounds(members, facing);
        List<BlockPos> after = List.of();
        switch (request.action()) {
            case RESIZE -> {
                BlockPos from = request.from(), to = request.to();
                int[] size = size(from, to, facing);
                Direction.Axis depth = facing.getAxis();
                if (size == null || depth.choose(from.getX(), from.getY(), from.getZ()) != depth.choose(at.getX(), at.getY(), at.getZ())) return;
                BuilderRules rules = BuilderRules.of(player.server);
                if (size[0] > rules.maxWidth() || size[1] > rules.maxHeight()) {
                    say(player, "desktopscreens.builder.too_big", rules.maxWidth(), rules.maxHeight());
                    return;
                }
                after = cells(from, to);
            }
            case MOVE -> {
                if (!request.facing().getAxis().isHorizontal()) return;
                newFacing = request.facing();
                after = moved(members, facing, request.from(), newFacing);
            }
            case TAKE_DOWN -> {}
        }
        Change change = change(members, facing, after, newFacing);
        if (change.added().isEmpty() && change.leaving().isEmpty()) return;
        // Where it goes; or, taken down, where it is.
        if (!inRange(player, after.isEmpty() ? members : after, reach(player))) {
            say(player, "desktopscreens.builder.too_far");
            return;
        }
        for (List<BlockPos> cells : List.of(change.added(), change.leaving())) {
            for (BlockPos cell : cells) {
                if (!level.mayInteract(player, cell)) { // spawn protection
                    say(player, "desktopscreens.builder.cant_build");
                    return;
                }
            }
        }
        List<BlockPos> broken = new ArrayList<>(change.leaving());
        broken.addAll(replaced(level, change.added(), change.leaving()));
        if (!LandClaims.mayBreak(level, player, broken)) {
            say(player, "desktopscreens.builder.cant_build");
            return;
        }
        // A mod told of the breaks may have changed the screen meanwhile, say a vein miner breaking the rest of it:
        // then the screens given back would be extra. What's in the way is looked at after this, for the same reason.
        if (!ScreenBlock.group(level, at).equals(members)) {
            say(player, "desktopscreens.builder.screen_changed");
            return;
        }
        Obstacles obstacles = obstacles(level, change, after, newFacing, true);
        if (!obstacles.inTheWay().isEmpty()) {
            player.displayClientMessage(inTheWay(level, obstacles.inTheWay(), newFacing, change.leaving()), true);
            return;
        }
        if (!obstacles.joining().isEmpty()) {
            say(player, "desktopscreens.builder.joins_other");
            return;
        }
        boolean free = player.hasInfiniteMaterials();
        int needed = change.added().size() - change.leaving().size();
        if (!free && needed > 0) {
            int have = screensIn(player);
            if (have < needed) {
                say(player, "desktopscreens.builder.need_screens", needed, have);
                return;
            }
        }

        // Its blocks go first, since the new ones may be where they were; if a claim refuses a new one, they come back.
        Map<BlockPos, BlockState> gone = new LinkedHashMap<>();
        for (BlockPos cell : change.leaving()) {
            BlockState state = level.getBlockState(cell);
            if (level.removeBlock(cell, false)) gone.put(cell, state);
        }
        ScreenBlock block = screenBlock();
        Direction to = newFacing;
        boolean placed = LandClaims.ask.place(level, player, change.added(), to, () -> {
            for (BlockPos cell : change.added()) level.setBlock(cell, block.stateFor(level, cell, to), Block.UPDATE_ALL);
        });
        // `was` may be gone from the world by now, but still holds who it was on for, and which screen it is (off too).
        if (!placed) {
            gone.forEach((cell, state) -> level.setBlock(cell, state, Block.UPDATE_ALL));
            copyOwner(level, gone.keySet(), was);
            say(player, "desktopscreens.builder.cant_build");
            return;
        }
        gone.forEach((cell, state) -> level.gameEvent(GameEvent.BLOCK_DESTROY, cell, GameEvent.Context.of(player, state)));
        announcePlaced(level, player, change.added());
        copyOwner(level, change.added(), was);
        if (!free && needed > 0) take(player, needed);
        if (!free && needed < 0) give(player, -needed);

        List<BlockPos> heard = after.isEmpty() ? members : after;
        Vec3 middle = AABB.of(BoundingBox.encapsulatingPositions(heard).orElseThrow()).getCenter();
        level.playSound(null, middle.x, middle.y, middle.z, after.isEmpty() ? ScreenBlock.SOUND.getBreakSound() : ScreenBlock.SOUND.getPlaceSound(),
                SoundSource.BLOCKS, 1.0F, 0.8F);
        switch (request.action()) {
            case RESIZE -> {
                int[] size = size(request.from(), request.to(), facing);
                say(player, "desktopscreens.builder.resized", size[0], size[1]);
            }
            case MOVE -> say(player, "desktopscreens.builder.moved");
            case TAKE_DOWN -> say(player, "desktopscreens.builder.taken_down", before.cols(), before.rows());
        }
    }

    private static void copyOwner(Level level, Collection<BlockPos> cells, ScreenBlockEntity from) {
        for (BlockPos cell : cells) {
            if (level.getBlockEntity(cell) instanceof ScreenBlockEntity screen) screen.copyOwner(from);
        }
    }

    /** Screens back into the inventory; what doesn't fit drops at the player's feet. */
    private static void give(Player player, int count) {
        Item item = screenBlock().asItem();
        int stackSize = new ItemStack(item).getMaxStackSize();
        while (count > 0) {
            int n = Math.min(count, stackSize);
            player.getInventory().placeItemBackInInventory(new ItemStack(item, n));
            count -= n;
        }
    }

    private static void take(Player player, int count) {
        Inventory inventory = player.getInventory();
        for (int i = 0; i < inventory.getContainerSize() && count > 0; i++) {
            ItemStack stack = inventory.getItem(i);
            if (!isScreen(stack)) continue;
            int used = Math.min(count, stack.getCount());
            stack.shrink(used);
            count -= used;
        }
        inventory.setChanged();
    }

    private static void say(Player player, String key, Object... args) {
        player.displayClientMessage(Component.translatable(key, args), true);
    }
}
