package com.desktopscreens;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.SimpleWaterloggedBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.pathfinder.PathComputationType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * A screen: a thin panel, always upright, black while it's off. The panel sits at the back of its block space, so
 * it's flush with a wall behind it, and screens facing the same way side by side are in one plane. They join into
 * one screen: each side that has such a neighbor shows glass instead of the bezel, so the bezel runs around the
 * outside of the whole group. It needs nothing to hold it up.
 *
 * <p>Right-clicking it with an empty hand turns the whole joined screen on or off; on, it shows the desktop of the
 * player who turned it on, only in their game ({@link ScreenBlockEntity}).
 */
public final class ScreenBlock extends Block implements SimpleWaterloggedBlock, EntityBlock {
    public static final String NAME = "screen";
    /** The most blocks one joined screen counts, so a huge wall of them can't stall the game. */
    public static final int MAX_GROUP = 4096;
    public static final MapCodec<ScreenBlock> CODEC = simpleCodec(ScreenBlock::new);
    /** The way the picture faces. */
    public static final DirectionProperty FACING = HorizontalDirectionalBlock.FACING;
    /** Joined to a screen facing the same way on that side; left and right as seen from the front. */
    public static final BooleanProperty UP = BlockStateProperties.UP;
    public static final BooleanProperty DOWN = BlockStateProperties.DOWN;
    public static final BooleanProperty LEFT = BooleanProperty.create("left");
    public static final BooleanProperty RIGHT = BooleanProperty.create("right");
    public static final BooleanProperty WATERLOGGED = BlockStateProperties.WATERLOGGED;

    /** Per facing: the panel at the back of the block. Matches the models in {@code models/block/screen*.json}. */
    private static final Map<Direction, VoxelShape> SHAPES = new EnumMap<>(Direction.class);

    static {
        for (Direction facing : Direction.Plane.HORIZONTAL) SHAPES.put(facing, box(facing, 0, 0, 14, 16, 16, 16));
    }

    public ScreenBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any()
                .setValue(FACING, Direction.NORTH)
                .setValue(UP, false)
                .setValue(DOWN, false)
                .setValue(LEFT, false)
                .setValue(RIGHT, false)
                .setValue(WATERLOGGED, false));
    }

    public static final SoundType SOUND = SoundType.METAL;

    public static Properties defaultProperties() {
        return Properties.of().mapColor(MapColor.COLOR_BLACK).strength(1.5F).sound(SOUND);
    }

    @Override
    protected MapCodec<ScreenBlock> codec() {
        return CODEC;
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        // Against a wall, the picture faces away from it; on a floor or ceiling, toward you.
        Direction clicked = context.getClickedFace();
        Direction facing = clicked.getAxis().isHorizontal() && !context.replacingClickedOnBlock()
                ? clicked : context.getHorizontalDirection().getOpposite();
        return stateFor(context.getLevel(), context.getClickedPos(), facing);
    }

    /** A screen facing {@code facing} placed at {@code pos}: joined to the screens around it, in water if there is some. */
    public BlockState stateFor(LevelReader level, BlockPos pos, Direction facing) {
        BlockState state = defaultBlockState()
                .setValue(FACING, facing)
                .setValue(WATERLOGGED, level.getFluidState(pos).getType() == Fluids.WATER);
        return withNeighbors(state, level, pos);
    }

    @Override
    protected BlockState updateShape(BlockState state, Direction direction, BlockState neighborState, LevelAccessor level,
                                     BlockPos pos, BlockPos neighborPos) {
        if (state.getValue(WATERLOGGED)) level.scheduleTick(pos, Fluids.WATER, Fluids.WATER.getTickDelay(level));
        return withNeighbors(state, level, pos);
    }

    /** Which sides join other screens, from the blocks around. */
    private static BlockState withNeighbors(BlockState state, LevelReader level, BlockPos pos) {
        Direction facing = state.getValue(FACING);
        // Seen from the front, the screen's left is clockwise from where it faces: facing north, you look south, east is left.
        Direction left = facing.getClockWise();
        return state
                .setValue(UP, joins(level, pos.above(), facing))
                .setValue(DOWN, joins(level, pos.below(), facing))
                .setValue(LEFT, joins(level, pos.relative(left), facing))
                .setValue(RIGHT, joins(level, pos.relative(left.getOpposite()), facing));
    }

    private static boolean joins(LevelReader level, BlockPos pos, Direction facing) {
        BlockState state = level.getBlockState(pos);
        return state.getBlock() instanceof ScreenBlock && state.getValue(FACING) == facing;
    }

    /**
     * All blocks of the joined screen {@code start} belongs to (at most {@link #MAX_GROUP}), following the sides
     * its blocks show as joined, which is also what the bezels show.
     */
    public static List<BlockPos> group(BlockGetter level, BlockPos start) {
        List<BlockPos> members = new ArrayList<>();
        Set<BlockPos> seen = new HashSet<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        seen.add(start.immutable());
        queue.add(start.immutable());
        while (!queue.isEmpty() && members.size() < MAX_GROUP) {
            BlockPos pos = queue.poll();
            BlockState state = level.getBlockState(pos);
            if (!(state.getBlock() instanceof ScreenBlock)) continue;
            members.add(pos);
            Direction left = state.getValue(FACING).getClockWise();
            if (state.getValue(UP)) visit(pos.above(), seen, queue);
            if (state.getValue(DOWN)) visit(pos.below(), seen, queue);
            if (state.getValue(LEFT)) visit(pos.relative(left), seen, queue);
            if (state.getValue(RIGHT)) visit(pos.relative(left.getOpposite()), seen, queue);
        }
        return members;
    }

    private static void visit(BlockPos pos, Set<BlockPos> seen, ArrayDeque<BlockPos> queue) {
        if (seen.add(pos)) queue.add(pos);
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new ScreenBlockEntity(pos, state);
    }

    /** Holding something, you use that (so blocks can be placed against a screen); only an empty hand switches it. */
    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player,
                                              InteractionHand hand, BlockHitResult hit) {
        return stack.isEmpty() ? ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION : ItemInteractionResult.SKIP_DEFAULT_BLOCK_INTERACTION;
    }

    /**
     * Turns the whole joined screen on for you, or off again. Someone else's stays theirs while they're online; one
     * left on by a player who's gone becomes yours (otherwise nobody could ever switch it again).
     */
    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (level.isClientSide()) return InteractionResult.SUCCESS;
        if (!(level.getBlockEntity(pos) instanceof ScreenBlockEntity clicked)) return InteractionResult.PASS;
        UUID owner = clicked.owner();
        boolean mine = player.getUUID().equals(owner);
        if (owner != null && !mine && level.getServer() != null && level.getServer().getPlayerList().getPlayer(owner) != null) {
            player.displayClientMessage(Component.translatable("desktopscreens.screen.in_use", clicked.ownerName()), true);
            return InteractionResult.CONSUME;
        }
        Player newOwner = mine ? null : player;
        List<BlockPos> members = group(level, pos);
        // Which screen it is: the one it was (so its owner's game still knows what it shows), else a new one.
        UUID id = clicked.screenId();
        for (BlockPos member : members) {
            if (id == null && level.getBlockEntity(member) instanceof ScreenBlockEntity screen) id = screen.screenId();
        }
        if (id == null) id = UUID.randomUUID();
        for (BlockPos member : members) {
            if (level.getBlockEntity(member) instanceof ScreenBlockEntity screen) {
                screen.setScreenId(id);
                screen.setOwner(newOwner);
            }
        }
        level.playSound(null, pos, newOwner != null ? SoundEvents.STONE_BUTTON_CLICK_ON : SoundEvents.STONE_BUTTON_CLICK_OFF,
                SoundSource.BLOCKS, 0.3F, newOwner != null ? 0.8F : 0.7F);
        player.displayClientMessage(Component.translatable(newOwner != null ? "desktopscreens.screen.on" : "desktopscreens.screen.off"), true);
        return InteractionResult.CONSUME;
    }

    /** A screen placed onto one that's on joins it: it takes over its owner. */
    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (level.isClientSide() || !(level.getBlockEntity(pos) instanceof ScreenBlockEntity placed)) return;
        Direction left = state.getValue(FACING).getClockWise();
        BlockPos[] sides = {pos.above(), pos.below(), pos.relative(left), pos.relative(left.getOpposite())};
        boolean[] joined = {state.getValue(UP), state.getValue(DOWN), state.getValue(LEFT), state.getValue(RIGHT)};
        for (int i = 0; i < sides.length; i++) {
            if (joined[i] && level.getBlockEntity(sides[i]) instanceof ScreenBlockEntity neighbor && neighbor.owner() != null) {
                placed.copyOwner(neighbor);
                return;
            }
        }
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPES.get(state.getValue(FACING));
    }

    /** A box given for a screen facing north, turned to face {@code facing} (the way the blockstate turns the model). */
    private static VoxelShape box(Direction facing, double x1, double y1, double z1, double x2, double y2, double z2) {
        return switch (facing) {
            case SOUTH -> Block.box(16 - x2, y1, 16 - z2, 16 - x1, y2, 16 - z1);
            case EAST -> Block.box(16 - z2, y1, x1, 16 - z1, y2, x2);
            case WEST -> Block.box(z1, y1, 16 - x2, z2, y2, 16 - x1);
            default -> Block.box(x1, y1, z1, x2, y2, z2);
        };
    }

    @Override
    protected boolean isPathfindable(BlockState state, PathComputationType type) {
        return false;
    }

    @Override
    protected FluidState getFluidState(BlockState state) {
        return state.getValue(WATERLOGGED) ? Fluids.WATER.getSource(false) : super.getFluidState(state);
    }

    @Override
    protected BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    @Override
    protected BlockState mirror(BlockState state, Mirror mirror) {
        BlockState turned = state.rotate(mirror.getRotation(state.getValue(FACING)));
        // A mirror image swaps the neighbors on the left and the right.
        return mirror == Mirror.NONE ? turned
                : turned.setValue(LEFT, state.getValue(RIGHT)).setValue(RIGHT, state.getValue(LEFT));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, UP, DOWN, LEFT, RIGHT, WATERLOGGED);
    }
}
