package com.desktopscreens;

import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.util.BlockSnapshot;
import net.minecraftforge.event.ForgeEventFactory;
import net.minecraftforge.event.world.BlockEvent;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Asks land-claim mods (FTB Utilities, and others) whether a player may break or place the blocks the Screen Builder
 * is about to change. The builder changes them itself rather than through the player's clicks, so without this the
 * claims never hear of it. Through the events a player's own breaking and placing fire, which is what claim mods
 * listen to, as on the newer versions' Forge and NeoForge. Server thread.
 */
public final class LandClaims {
    private LandClaims() {}

    /** Whether {@code player} may break the blocks at {@code cells}. Stops at the first no, so a claim mod that says why in the chat says it once. */
    public static boolean mayBreak(World world, EntityPlayerMP player, Collection<BlockPos> cells) {
        for (BlockPos pos : cells) {
            IBlockState state = world.getBlockState(pos);
            if (MinecraftForge.EVENT_BUS.post(new BlockEvent.BreakEvent(world, pos, state, player))) {
                refused(world, player, pos, "breaking");
                return false;
            }
        }
        return true;
    }

    /**
     * Runs {@code place}, which puts screens facing {@code facing} at {@code cells}, and returns true; or, if a claim
     * refuses one of them, leaves those places as they were before and returns false. As for a player's own placing
     * (Forge's {@code ForgeHooks.onPlaceItemIntoWorld}): the blocks go in while the world only notes what was there
     * (no updates to neighbors or clients yet), each is asked about with the block in place, and then either all are
     * undone or the updates go out. One event per block: listeners of the single event get the multi one too, but
     * only look at its first block.
     */
    public static boolean place(World world, EntityPlayerMP player, List<BlockPos> cells, EnumFacing facing, Runnable place) {
        List<BlockSnapshot> placed;
        world.captureBlockSnapshots = true;
        try {
            place.run();
        } finally {
            world.captureBlockSnapshots = false;
            placed = new ArrayList<>(world.capturedBlockSnapshots);
            world.capturedBlockSnapshots.clear();
        }
        for (BlockSnapshot snapshot : placed) {
            if (ForgeEventFactory.onPlayerBlockPlace(player, snapshot, facing, EnumHand.MAIN_HAND).isCanceled()) {
                refused(world, player, snapshot.getPos(), "placing");
                for (int i = placed.size() - 1; i >= 0; i--) {
                    world.restoringBlockSnapshots = true;
                    placed.get(i).restore(true, false);
                    world.restoringBlockSnapshots = false;
                }
                return false;
            }
        }
        for (BlockSnapshot snapshot : placed) {
            BlockPos pos = snapshot.getPos();
            IBlockState before = snapshot.getReplacedBlock(), now = world.getBlockState(pos);
            if (!now.getBlock().hasTileEntity(now)) now.getBlock().onBlockAdded(world, pos, now);
            world.markAndNotifyBlock(pos, null, before, now, snapshot.getFlag());
        }
        return true;
    }

    /** For the log (a server's admin may wonder), when a claim or another mod refused {@code player} something at {@code pos}. */
    private static void refused(World world, EntityPlayerMP player, BlockPos pos, String what) {
        DesktopScreens.LOG.info("Screen Builder: {} may not change blocks here, {} at {} {} {} in dimension {} was refused (a land claim or another mod)",
                Players.name(player), what, pos.getX(), pos.getY(), pos.getZ(), world.provider.getDimension());
    }
}
