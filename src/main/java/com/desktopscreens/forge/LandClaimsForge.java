package com.desktopscreens.forge;

import com.desktopscreens.LandClaims;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.util.BlockSnapshot;
import net.minecraftforge.event.ForgeEventFactory;
import net.minecraftforge.event.level.BlockEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * Asks through the events a player's own placing and breaking fire, which is what claim mods listen to: Open Parties
 * and Claims directly, FTB Chunks through Architectury, which passes them on. As on NeoForge ({@code LandClaimsNeoForge}).
 */
final class LandClaimsForge implements LandClaims.Ask {
    @Override
    public boolean mayBreak(ServerLevel level, BlockPos pos, BlockState state, ServerPlayer player) {
        return !MinecraftForge.EVENT_BUS.post(new BlockEvent.BreakEvent(level, pos, state, player));
    }

    /**
     * As for a player's own placing ({@code ForgeHooks.onPlaceItemIntoWorld}): the blocks go in while the level only
     * notes what was there (no updates to neighbors or clients yet), each is asked about with the block in place, and
     * then either all are undone or the updates go out. Claim mods expect the block to be there. One event per block:
     * listeners of the single event get the multi one too, but only look at its first block.
     */
    @Override
    public boolean place(ServerLevel level, ServerPlayer player, List<BlockPos> cells, Direction facing, Runnable place) {
        List<BlockSnapshot> placed;
        level.captureBlockSnapshots = true;
        try {
            place.run();
        } finally {
            level.captureBlockSnapshots = false;
            placed = new ArrayList<>(level.capturedBlockSnapshots);
            level.capturedBlockSnapshots.clear();
        }
        for (BlockSnapshot snapshot : placed) {
            if (ForgeEventFactory.onBlockPlace(player, snapshot, facing)) {
                LandClaims.refused(level, player, snapshot.getPos(), "placing");
                for (int i = placed.size() - 1; i >= 0; i--) {
                    BlockSnapshot undo = placed.get(i);
                    level.restoringBlockSnapshots = true;
                    undo.restore(true, false);
                    level.restoringBlockSnapshots = false;
                }
                return false;
            }
        }
        for (BlockSnapshot snapshot : placed) {
            BlockPos pos = snapshot.getPos();
            BlockState before = snapshot.getReplacedBlock(), now = level.getBlockState(pos);
            now.onPlace(level, pos, before, false);
            level.markAndNotifyBlock(pos, level.getChunkAt(pos), before, now, snapshot.getFlag(), Block.UPDATE_LIMIT);
        }
        return true;
    }
}
