package com.desktopscreens.neoforge;

import com.desktopscreens.LandClaims;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.BlockSnapshot;
import net.neoforged.neoforge.event.EventHooks;
import net.neoforged.neoforge.event.level.BlockEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * Asks through the events a player's own placing and breaking fire, which is what claim mods listen to: Open Parties
 * and Claims directly, FTB Chunks through Architectury, which passes them on.
 */
final class LandClaimsNeoForge implements LandClaims.Ask {
    @Override
    public boolean mayBreak(ServerLevel level, BlockPos pos, BlockState state, ServerPlayer player) {
        return !NeoForge.EVENT_BUS.post(new BlockEvent.BreakEvent(level, pos, state, player)).isCanceled();
    }

    /**
     * As for a player's own placing ({@code CommonHooks.onPlaceItemIntoWorld}): the blocks go in while the level only
     * notes what was there (no updates to neighbors or clients yet), each is asked about with the block in place, and
     * then either all are undone or the updates go out. Claim mods expect the block to be there: asked before, the
     * event's placed block is air, which Open Parties and Claims lets through (found in testing, 2026-09-27).
     * <p>
     * One event per block, not one {@code EntityMultiPlaceEvent} for all: listeners of the single event get the multi
     * one too, but only look at its first block. A screen is placed against the block behind it.
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
            if (EventHooks.onBlockPlace(player, snapshot, facing)) {
                LandClaims.refused(level, player, snapshot.getPos(), "placing");
                for (BlockSnapshot undo : placed.reversed()) {
                    level.restoringBlockSnapshots = true;
                    undo.restore(undo.getFlags() | Block.UPDATE_CLIENTS);
                    level.restoringBlockSnapshots = false;
                }
                return false;
            }
        }
        for (BlockSnapshot snapshot : placed) {
            BlockPos pos = snapshot.getPos();
            BlockState before = snapshot.getState(), now = level.getBlockState(pos);
            now.onPlace(level, pos, before, false);
            level.markAndNotifyBlock(pos, level.getChunkAt(pos), before, now, snapshot.getFlags(), Block.UPDATE_LIMIT);
        }
        return true;
    }
}
