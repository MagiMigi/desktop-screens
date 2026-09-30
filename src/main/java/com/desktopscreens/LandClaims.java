package com.desktopscreens;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Collection;
import java.util.List;

/**
 * Asks land-claim mods (FTB Chunks, Open Parties and Claims, Flan...) whether a player may break or place the blocks
 * the Screen Builder is about to change. The builder changes them itself rather than through the player's clicks, so
 * without this the claims never hear of it. Each loader says how to ask; until one does, everything is allowed.
 */
public final class LandClaims {
    /** How a loader asks. On the server thread. */
    public interface Ask {
        /** Whether {@code player} may break {@code state} at {@code pos}. Asked before it's broken, as for a player's own. */
        boolean mayBreak(ServerLevel level, BlockPos pos, BlockState state, ServerPlayer player);

        /**
         * Runs {@code place}, which puts screens facing {@code facing} at {@code cells}, and returns true; or, if a
         * claim refuses one of them, leaves those places as they were before and returns false. A loader may ask
         * before or after placing (then without telling anyone of the new blocks until all are allowed), whichever
         * its claim mods expect.
         */
        boolean place(ServerLevel level, ServerPlayer player, List<BlockPos> cells, Direction facing, Runnable place);
    }

    /** Set by each loader. */
    public static volatile Ask ask = new Ask() {
        @Override
        public boolean mayBreak(ServerLevel level, BlockPos pos, BlockState state, ServerPlayer player) {
            return true;
        }

        @Override
        public boolean place(ServerLevel level, ServerPlayer player, List<BlockPos> cells, Direction facing, Runnable place) {
            place.run();
            return true;
        }
    };

    private LandClaims() {}

    /** Whether {@code player} may break the blocks at {@code cells}. Stops at the first no, so a claim mod that says why in the chat says it once. */
    public static boolean mayBreak(ServerLevel level, ServerPlayer player, Collection<BlockPos> cells) {
        Ask ask = LandClaims.ask;
        for (BlockPos pos : cells) {
            if (!ask.mayBreak(level, pos, level.getBlockState(pos), player)) {
                refused(level, player, pos, "breaking");
                return false;
            }
        }
        return true;
    }

    /** For the log (a server's admin may wonder), when a claim or another mod refused {@code player} something at {@code pos}. */
    public static void refused(ServerLevel level, ServerPlayer player, BlockPos pos, String what) {
        DesktopScreens.LOG.info("Screen Builder: {} may not change blocks here, {} at {} {} {} in {} was refused (a land claim or another mod)",
                player.getGameProfile().getName(), what, pos.getX(), pos.getY(), pos.getZ(), level.dimension().location());
    }
}
