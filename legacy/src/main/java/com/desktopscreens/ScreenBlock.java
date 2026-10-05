package com.desktopscreens;

import net.minecraft.block.Block;
import net.minecraft.block.SoundType;
import net.minecraft.block.material.MapColor;
import net.minecraft.block.material.Material;
import net.minecraft.block.properties.PropertyBool;
import net.minecraft.block.properties.PropertyDirection;
import net.minecraft.block.state.BlockFaceShape;
import net.minecraft.block.state.BlockStateContainer;
import net.minecraft.block.state.IBlockState;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.SoundEvents;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.Mirror;
import net.minecraft.util.NonNullList;
import net.minecraft.util.Rotation;
import net.minecraft.util.SoundCategory;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;

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
 * player who turned it on, in their game and for those they share it with ({@link ScreenBlockEntity}).
 *
 * <p>On 1.12.2 only the facing is stored (a block has 16 values); which sides join is worked out from the neighbors
 * when it's drawn ({@link #getActualState}), as fences do. 1.12.2 has no waterlogging.
 */
public final class ScreenBlock extends Block {
    public static final String NAME = "screen";
    /** The most blocks one joined screen counts, so a huge wall of them can't stall the game. */
    public static final int MAX_GROUP = 4096;
    /** The way the picture faces. */
    public static final PropertyDirection FACING = PropertyDirection.create("facing", EnumFacing.Plane.HORIZONTAL);
    /** Joined to a screen facing the same way on that side; left and right as seen from the front. */
    public static final PropertyBool UP = PropertyBool.create("up");
    public static final PropertyBool DOWN = PropertyBool.create("down");
    public static final PropertyBool LEFT = PropertyBool.create("left");
    public static final PropertyBool RIGHT = PropertyBool.create("right");

    /** Per facing: the panel at the back of the block. Matches the models in {@code models/block/screen*.json}. */
    private static final Map<EnumFacing, AxisAlignedBB> SHAPES = new EnumMap<>(EnumFacing.class);

    static {
        for (EnumFacing facing : EnumFacing.Plane.HORIZONTAL) SHAPES.put(facing, box(facing, 0, 0, 14, 16, 16, 16));
    }

    public ScreenBlock() {
        super(Material.GLASS, MapColor.BLACK); // breaks by hand, as on the newer versions; faster with a pickaxe
        setRegistryName(DesktopScreens.MOD_ID, NAME);
        setTranslationKey(DesktopScreens.MOD_ID + "." + NAME); // tile.desktopscreens.screen.name
        setHardness(1.5F);
        setSoundType(SoundType.METAL);
        setHarvestLevel("pickaxe", 0);
        setCreativeTab(CreativeTabs.DECORATIONS);
        setDefaultState(blockState.getBaseState().withProperty(FACING, EnumFacing.NORTH)
                .withProperty(UP, false).withProperty(DOWN, false).withProperty(LEFT, false).withProperty(RIGHT, false));
    }

    @Override
    protected BlockStateContainer createBlockState() {
        return new BlockStateContainer(this, FACING, UP, DOWN, LEFT, RIGHT);
    }

    @Override
    public IBlockState getStateFromMeta(int meta) {
        return getDefaultState().withProperty(FACING, EnumFacing.byHorizontalIndex(meta));
    }

    @Override
    public int getMetaFromState(IBlockState state) {
        return state.getValue(FACING).getHorizontalIndex();
    }

    @Override
    public IBlockState getStateForPlacement(World world, BlockPos pos, EnumFacing clicked, float hitX, float hitY, float hitZ, int meta,
                                            EntityLivingBase placer, EnumHand hand) {
        // Against a wall, the picture faces away from it; on a floor or ceiling, toward you.
        EnumFacing facing = clicked.getAxis().isHorizontal() ? clicked : placer.getHorizontalFacing().getOpposite();
        return getDefaultState().withProperty(FACING, facing);
    }

    /** Which sides join other screens, from the blocks around. */
    @Override
    public IBlockState getActualState(IBlockState state, IBlockAccess world, BlockPos pos) {
        EnumFacing facing = state.getValue(FACING);
        // Seen from the front, the screen's left is clockwise from where it faces: facing north, you look south, east is left.
        EnumFacing left = facing.rotateY();
        return state
                .withProperty(UP, joins(world, pos.up(), facing))
                .withProperty(DOWN, joins(world, pos.down(), facing))
                .withProperty(LEFT, joins(world, pos.offset(left), facing))
                .withProperty(RIGHT, joins(world, pos.offset(left.getOpposite()), facing));
    }

    private static boolean joins(IBlockAccess world, BlockPos pos, EnumFacing facing) {
        IBlockState state = world.getBlockState(pos);
        return state.getBlock() instanceof ScreenBlock && state.getValue(FACING) == facing;
    }

    /**
     * All blocks of the joined screen {@code start} belongs to (at most {@link #MAX_GROUP}), following the sides
     * its blocks join, which is also what the bezels show.
     */
    public static List<BlockPos> group(IBlockAccess world, BlockPos start) {
        List<BlockPos> members = new ArrayList<>();
        Set<BlockPos> seen = new HashSet<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        seen.add(start.toImmutable());
        queue.add(start.toImmutable());
        while (!queue.isEmpty() && members.size() < MAX_GROUP) {
            BlockPos pos = queue.poll();
            IBlockState state = world.getBlockState(pos);
            if (!(state.getBlock() instanceof ScreenBlock)) continue;
            members.add(pos);
            EnumFacing facing = state.getValue(FACING), left = facing.rotateY();
            if (joins(world, pos.up(), facing)) visit(pos.up(), seen, queue);
            if (joins(world, pos.down(), facing)) visit(pos.down(), seen, queue);
            if (joins(world, pos.offset(left), facing)) visit(pos.offset(left), seen, queue);
            if (joins(world, pos.offset(left.getOpposite()), facing)) visit(pos.offset(left.getOpposite()), seen, queue);
        }
        return members;
    }

    private static void visit(BlockPos pos, Set<BlockPos> seen, ArrayDeque<BlockPos> queue) {
        if (seen.add(pos)) queue.add(pos);
    }

    @Override
    public boolean hasTileEntity(IBlockState state) {
        return true;
    }

    @Override
    public TileEntity createTileEntity(World world, IBlockState state) {
        return new ScreenBlockEntity();
    }

    /**
     * Turns the whole joined screen on for you, or off again, with an empty main hand (holding something, you use
     * that, so blocks can be placed against a screen). Someone else's stays theirs while they're online; one left on
     * by a player who's gone becomes yours (otherwise nobody could ever switch it again).
     */
    @Override
    public boolean onBlockActivated(World world, BlockPos pos, IBlockState state, EntityPlayer player, EnumHand hand, EnumFacing side,
                                    float hitX, float hitY, float hitZ) {
        if (hand != EnumHand.MAIN_HAND || !player.getHeldItem(hand).isEmpty()) return false;
        if (world.isRemote) return true;
        TileEntity te = world.getTileEntity(pos);
        if (!(te instanceof ScreenBlockEntity)) return false;
        ScreenBlockEntity clicked = (ScreenBlockEntity) te;
        UUID owner = clicked.owner();
        boolean mine = player.getUniqueID().equals(owner);
        if (owner != null && !mine && world.getMinecraftServer() != null && world.getMinecraftServer().getPlayerList().getPlayerByUUID(owner) != null) {
            Players.overlay(player, new TextComponentTranslation("desktopscreens.screen.in_use", clicked.ownerName()));
            return true;
        }
        EntityPlayer newOwner = mine ? null : player;
        List<BlockPos> members = group(world, pos);
        // Which screen it is: the one it was (so its owner's game still knows what it shows), else a new one.
        UUID id = clicked.screenId();
        for (BlockPos member : members) {
            TileEntity m = world.getTileEntity(member);
            if (id == null && m instanceof ScreenBlockEntity) id = ((ScreenBlockEntity) m).screenId();
        }
        if (id == null) id = UUID.randomUUID();
        for (BlockPos member : members) {
            TileEntity m = world.getTileEntity(member);
            if (m instanceof ScreenBlockEntity) {
                ((ScreenBlockEntity) m).setScreenId(id);
                ((ScreenBlockEntity) m).setOwner(newOwner);
            }
        }
        world.playSound(null, pos, newOwner != null ? SoundEvents.BLOCK_STONE_BUTTON_CLICK_ON : SoundEvents.BLOCK_STONE_BUTTON_CLICK_OFF,
                SoundCategory.BLOCKS, 0.3F, newOwner != null ? 0.8F : 0.7F);
        Players.overlay(player, new TextComponentTranslation(newOwner != null ? "desktopscreens.screen.on" : "desktopscreens.screen.off"));
        return true;
    }

    /** A screen placed onto one that's on joins it: it takes over its owner. */
    @Override
    public void onBlockPlacedBy(World world, BlockPos pos, IBlockState state, EntityLivingBase placer, ItemStack stack) {
        super.onBlockPlacedBy(world, pos, state, placer, stack);
        TileEntity te = world.getTileEntity(pos);
        if (world.isRemote || !(te instanceof ScreenBlockEntity)) return;
        EnumFacing facing = state.getValue(FACING), left = facing.rotateY();
        for (BlockPos side : new BlockPos[] {pos.up(), pos.down(), pos.offset(left), pos.offset(left.getOpposite())}) {
            if (!joins(world, side, facing)) continue;
            TileEntity neighbor = world.getTileEntity(side);
            if (neighbor instanceof ScreenBlockEntity && ((ScreenBlockEntity) neighbor).owner() != null) {
                ((ScreenBlockEntity) te).copyOwner((ScreenBlockEntity) neighbor);
                return;
            }
        }
    }

    @Override
    public AxisAlignedBB getBoundingBox(IBlockState state, IBlockAccess source, BlockPos pos) {
        return SHAPES.get(state.getValue(FACING));
    }

    /** A box given for a screen facing north (in sixteenths), turned to face {@code facing} (the way the blockstate turns the model). */
    private static AxisAlignedBB box(EnumFacing facing, double x1, double y1, double z1, double x2, double y2, double z2) {
        switch (facing) {
            case SOUTH: return sixteenths(16 - x2, y1, 16 - z2, 16 - x1, y2, 16 - z1);
            case EAST: return sixteenths(16 - z2, y1, x1, 16 - z1, y2, x2);
            case WEST: return sixteenths(z1, y1, 16 - x2, z2, y2, 16 - x1);
            default: return sixteenths(x1, y1, z1, x2, y2, z2);
        }
    }

    private static AxisAlignedBB sixteenths(double x1, double y1, double z1, double x2, double y2, double z2) {
        return new AxisAlignedBB(x1 / 16, y1 / 16, z1 / 16, x2 / 16, y2 / 16, z2 / 16);
    }

    @Override
    public boolean isOpaqueCube(IBlockState state) {
        return false;
    }

    @Override
    public boolean isFullCube(IBlockState state) {
        return false;
    }

    /** Only the back is a whole face (things can be attached there); the front is glass in a bezel. */
    @Override
    public BlockFaceShape getBlockFaceShape(IBlockAccess world, IBlockState state, BlockPos pos, EnumFacing face) {
        return face == state.getValue(FACING).getOpposite() ? BlockFaceShape.SOLID : BlockFaceShape.UNDEFINED;
    }

    @Override
    public IBlockState withRotation(IBlockState state, Rotation rotation) {
        return state.withProperty(FACING, rotation.rotate(state.getValue(FACING)));
    }

    @Override
    public IBlockState withMirror(IBlockState state, Mirror mirror) {
        return state.withRotation(mirror.toRotation(state.getValue(FACING)));
    }

    /** Left out of the creative menu on a server without the mod, where it doesn't exist ({@link ServerCheck}). */
    @Override
    public void getSubBlocks(CreativeTabs tab, NonNullList<ItemStack> items) {
        if (ServerCheck.serverHasMod.getAsBoolean()) super.getSubBlocks(tab, items);
    }
}
