package com.desktopscreens;

import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Screens turn off when their owner leaves the server (2026-09-24: others saw the last picture frozen
 * on it): ownerless and dark, so anyone can turn them on again. Loaded screens that are on are tracked with their
 * owner; half a second after the owner leaves, theirs go off, and ones loaded later while the owner is still away go
 * off then. Not when the server is shutting down (quitting singleplayer, a restart): then they stay on for the owner's
 * return, and until then nobody sees a picture on them anyway. Server thread only.
 */
public final class OwnedScreens {
    private static final int CHECK_TICKS = 10;

    /** Loaded screens that are on, and whose they are. */
    private static final Map<GlobalPos, UUID> ON = new HashMap<>();
    /** Players who left this server since it started. */
    private static final Set<UUID> LEFT = new HashSet<>();
    private static MinecraftServer server;
    private static int ticks;

    private OwnedScreens() {}

    /** A screen block's owner, as it's loaded, switched or unloaded (null: off, or gone). */
    static void track(Level level, BlockPos pos, UUID owner) {
        if (!(level instanceof ServerLevel)) return;
        GlobalPos at = GlobalPos.of(level.dimension(), pos.immutable());
        if (owner != null) ON.put(at, owner);
        else ON.remove(at);
    }

    public static void joined(ServerPlayer player) {
        LEFT.remove(player.getUUID());
    }

    public static void left(ServerPlayer player) {
        // Shutting down: they stay on. The host of a singleplayer (or LAN) world leaves just before its server stops,
        // while it still counts as running, which turned their screens off on every quit (2026-09-26).
        if (!player.server.isRunning() || player.server.isSingleplayerOwner(player.getGameProfile())) return;
        LEFT.add(player.getUUID());
    }

    /** Every server tick: twice a second, turns off the screens of players who left. */
    public static void tick(MinecraftServer current) {
        if (current != server) { // another world: who left there doesn't matter here
            server = current;
            LEFT.clear();
        }
        if (++ticks % CHECK_TICKS != 0 || LEFT.isEmpty() || ON.isEmpty()) return;
        List<GlobalPos> off = new ArrayList<>();
        for (Map.Entry<GlobalPos, UUID> e : ON.entrySet()) {
            if (LEFT.contains(e.getValue()) && current.getPlayerList().getPlayer(e.getValue()) == null) off.add(e.getKey());
        }
        for (GlobalPos at : off) {
            ServerLevel level = current.getLevel(at.dimension());
            if (level == null || !level.isLoaded(at.pos()) || !(level.getBlockEntity(at.pos()) instanceof ScreenBlockEntity screen)) {
                ON.remove(at);
                continue;
            }
            UUID owner = screen.owner();
            if (owner == null) continue;
            String name = screen.ownerName();
            int count = 0;
            for (BlockPos member : ScreenBlock.group(level, at.pos())) {
                if (level.getBlockEntity(member) instanceof ScreenBlockEntity block && owner.equals(block.owner())) {
                    block.setOwner(null);
                    count++;
                }
            }
            DesktopScreens.LOG.info("Turned off a screen of {} blocks at {}: {} left the server", count, at.pos().toShortString(), name);
        }
    }
}
