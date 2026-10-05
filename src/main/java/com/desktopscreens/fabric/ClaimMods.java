package com.desktopscreens.fabric;

import dev.architectury.event.events.common.BlockEvent;
import eu.pb4.common.protection.api.CommonProtection;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;
import xaero.pac.common.server.api.OpenPACServerAPI;
//? if >=26.1 {
/*import dev.ftb.mods.ftbchunks.api.FTBChunksAPI;
import dev.ftb.mods.ftbchunks.api.Protection;
import net.minecraft.world.InteractionHand;
*///?}

/**
 * The claim mods {@link LandClaimsFabric} asks directly, one class each, so each is only loaded (and its mod's
 * classes looked up) once that mod is known to be there. We only compile against them.
 */
final class ClaimMods {
    private ClaimMods() {}

    /** Patbox's Common Protection API, which Flan, GOML and others answer through; inside those mods' jars. */
    static final class CommonProtectionApi {
        private CommonProtectionApi() {}

        static boolean mayBreak(ServerLevel level, BlockPos pos, ServerPlayer player) {
            //? if >=1.21.9 {
            /*return CommonProtection.canBreakBlock(level, pos, player.nameAndId(), player);
            *///?} else {
            return CommonProtection.canBreakBlock(level, pos, player.getGameProfile(), player);
            //?}
        }

        static boolean mayPlace(ServerLevel level, BlockPos pos, ServerPlayer player) {
            //? if >=1.21.9 {
            /*return CommonProtection.canPlaceBlock(level, pos, player.nameAndId(), player);
            *///?} else {
            return CommonProtection.canPlaceBlock(level, pos, player.getGameProfile(), player);
            //?}
        }
    }

    /** Architectury's block events, which FTB Chunks listens to. */
    static final class Architectury {
        private Architectury() {}

        static boolean mayBreak(ServerLevel level, BlockPos pos, BlockState state, ServerPlayer player) {
            //? if >=26.2 {
            /*return !BlockEvent.BREAK.invoker().breakBlock(level, pos, state, player).isFalse();
            *///?} else {
            return !BlockEvent.BREAK.invoker().breakBlock(level, pos, state, player, null).isFalse();
            //?}
        }

        static boolean mayPlace(ServerLevel level, BlockPos pos, BlockState placed, ServerPlayer player) {
            return !BlockEvent.PLACE.invoker().placeBlock(level, pos, placed, player).isFalse();
        }
    }

    //? if >=26.1 {
    /*/^*
     * FTB Chunks' own API, which its placing hook asks too: from 26.1 on it no longer listens to Architectury, and on
     * Fabric it guards placing in {@code BlockItem.place}, which the builder doesn't go through. Breaking it hears
     * through Fabric API's break event, which {@link LandClaimsFabric} fires anyway.
     ^/
    static final class FtbChunks {
        private FtbChunks() {}

        static boolean mayPlace(BlockPos pos, ServerPlayer player) {
            FTBChunksAPI.API api = FTBChunksAPI.api();
            return !api.isManagerLoaded()
                    || !api.getManager().shouldPreventInteraction(player, InteractionHand.MAIN_HAND, pos, Protection.EDIT_BLOCK, null);
        }
    }

    *///?}
    /** Open Parties and Claims' API: true there means the place is protected. It says nothing in the chat. */
    static final class OpenPartiesAndClaims {
        private OpenPartiesAndClaims() {}

        static boolean mayPlace(ServerLevel level, BlockPos pos, BlockState placed, ServerPlayer player) {
            return !OpenPACServerAPI.get(level.getServer()).getChunkProtection().onEntityPlaceBlock(placed, player, level, pos);
        }
    }
}
