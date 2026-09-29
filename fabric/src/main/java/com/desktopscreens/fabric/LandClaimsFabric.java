package com.desktopscreens.fabric;

import com.desktopscreens.LandClaims;
import com.desktopscreens.ScreenBuilder;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;

/**
 * Fabric has no one event that claim mods all listen to (read in their 1.21.1 code, 2026-09-27), so the builder asks
 * each way they do, before anything changes: Fabric API's break event (Open Parties and Claims, Flan...), the Common
 * Protection API (Flan, GOML...), Architectury's place and break events (FTB Chunks; Architectury fires them from its
 * own hooks into a player's clicks, which the builder doesn't go through), and Open Parties and Claims' own API for
 * placing (on Fabric it stops a player's placing at their right-click, which the builder never makes). The last three
 * only while that mod is there, each through its own class ({@link ClaimMods}), so none is loaded without it.
 */
final class LandClaimsFabric implements LandClaims.Ask {
    private final boolean commonProtection, architectury, openPac;

    LandClaimsFabric() {
        FabricLoader loader = FabricLoader.getInstance();
        commonProtection = loader.isModLoaded("common-protection-api");
        architectury = loader.isModLoaded("architectury");
        openPac = loader.isModLoaded("openpartiesandclaims");
    }

    @Override
    public boolean mayBreak(ServerLevel level, BlockPos pos, BlockState state, ServerPlayer player) {
        if (!PlayerBlockBreakEvents.BEFORE.invoker().beforeBlockBreak(level, player, pos, state, level.getBlockEntity(pos))) return false;
        if (commonProtection && !ClaimMods.CommonProtectionApi.mayBreak(level, pos, player)) return false;
        return !architectury || ClaimMods.Architectury.mayBreak(level, pos, state, player);
    }

    /** Asked before placing, since none of these read the block from the world; then placed if all agree. */
    @Override
    public boolean place(ServerLevel level, ServerPlayer player, List<BlockPos> cells, Direction facing, Runnable place) {
        BlockState placed = ScreenBuilder.panel(facing);
        for (BlockPos pos : cells) {
            if (!mayPlace(level, pos, placed, player)) {
                LandClaims.refused(level, player, pos, "placing");
                return false;
            }
        }
        place.run();
        return true;
    }

    private boolean mayPlace(ServerLevel level, BlockPos pos, BlockState placed, ServerPlayer player) {
        if (commonProtection && !ClaimMods.CommonProtectionApi.mayPlace(level, pos, player)) return false;
        if (architectury && !ClaimMods.Architectury.mayPlace(level, pos, placed, player)) return false;
        return !openPac || ClaimMods.OpenPartiesAndClaims.mayPlace(level, pos, placed, player);
    }
}
