package com.desktopscreens;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Screens turn off when their owner leaves the server (others saw the last picture frozen on it): ownerless and dark,
 * so anyone can turn them on again. Loaded screens that are on are tracked with their owner; half a second after the
 * owner leaves, theirs go off, and ones loaded later while the owner is still away go off then. Not when the server is
 * shutting down (quitting singleplayer, a restart): then they stay on for the owner's return, and until then nobody
 * sees a picture on them anyway. Server thread only.
 */
public final class OwnedScreens {
    private static final int CHECK_TICKS = 10;

    /** A block in a dimension. */
    private static final class At {
        final int dimension;
        final BlockPos pos;

        At(int dimension, BlockPos pos) {
            this.dimension = dimension;
            this.pos = pos;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof At && ((At) o).dimension == dimension && ((At) o).pos.equals(pos);
        }

        @Override
        public int hashCode() {
            return Objects.hash(dimension, pos);
        }
    }

    /** Loaded screens that are on, and whose they are. */
    private static final Map<At, UUID> ON = new HashMap<>();
    /** Players who left this server since it started. */
    private static final Set<UUID> LEFT = new HashSet<>();
    private static MinecraftServer server;
    private static int ticks;

    private OwnedScreens() {}

    /** A screen block's owner, as it's loaded, switched or unloaded (null: off, or gone). */
    static void track(World world, BlockPos pos, UUID owner) {
        if (!(world instanceof WorldServer)) return;
        At at = new At(world.provider.getDimension(), pos.toImmutable());
        if (owner != null) ON.put(at, owner);
        else ON.remove(at);
    }

    public static void joined(EntityPlayerMP player) {
        LEFT.remove(player.getUniqueID());
    }

    public static void left(EntityPlayerMP player) {
        // Shutting down: they stay on. The host of a singleplayer (or LAN) world leaves just before its server stops,
        // while it still counts as running, which turned their screens off on every quit.
        MinecraftServer current = player.getServer();
        if (current == null || !current.isServerRunning()
                || current.isSinglePlayer() && player.getGameProfile().getName().equals(current.getServerOwner())) return;
        LEFT.add(player.getUniqueID());
    }

    /** Every server tick: twice a second, turns off the screens of players who left. */
    public static void tick(MinecraftServer current) {
        if (current != server) { // another world: who left there doesn't matter here
            server = current;
            LEFT.clear();
        }
        if (++ticks % CHECK_TICKS != 0 || LEFT.isEmpty() || ON.isEmpty()) return;
        List<At> off = new ArrayList<>();
        for (Map.Entry<At, UUID> e : ON.entrySet()) {
            if (LEFT.contains(e.getValue()) && current.getPlayerList().getPlayerByUUID(e.getValue()) == null) off.add(e.getKey());
        }
        for (At at : off) {
            WorldServer world = current.getWorld(at.dimension);
            TileEntity te = world != null && world.isBlockLoaded(at.pos) ? world.getTileEntity(at.pos) : null;
            if (!(te instanceof ScreenBlockEntity)) {
                ON.remove(at);
                continue;
            }
            UUID owner = ((ScreenBlockEntity) te).owner();
            if (owner == null) continue;
            String name = ((ScreenBlockEntity) te).ownerName();
            int count = 0;
            for (BlockPos member : ScreenBlock.group(world, at.pos)) {
                TileEntity block = world.getTileEntity(member);
                if (block instanceof ScreenBlockEntity && owner.equals(((ScreenBlockEntity) block).owner())) {
                    ((ScreenBlockEntity) block).setOwner(null);
                    count++;
                }
            }
            DesktopScreens.LOG.info("Turned off a screen of {} blocks at {}: {} left the server", count, at.pos, name);
        }
    }
}
