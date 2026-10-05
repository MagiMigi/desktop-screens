package com.desktopscreens;

import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraft.world.World;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Sharing a screen: its owner lets other players see their desktop on it, by name. Two lists: each screen's own, kept
 * on its block entities like the owner and ended when the screen is turned off; and the owner's list for all their
 * screens, now and later ({@link SharingData}). Someone on either may watch. Server side.
 */
public final class ScreenSharing {
    /** Enough for a group of friends, and it keeps the saved data small. */
    public static final int MAX_WATCHERS = 16;
    /** The owner changes the list while using the screen, which works up to 32 blocks away; a little extra for moving. */
    private static final double RANGE = 48;

    private ScreenSharing() {}

    /**
     * Server to player: what they need to know about all-screens lists, which aren't on the screens: whose screens
     * are all shared with them, and whom they share all their own with. Sent when they join and when either changes.
     */
    public static final class Lists implements IMessage {
        private List<UUID> sharingAllWithYou;
        private List<String> yourAllScreens;

        public Lists() {}

        public Lists(List<UUID> sharingAllWithYou, List<String> yourAllScreens) {
            this.sharingAllWithYou = sharingAllWithYou;
            this.yourAllScreens = yourAllScreens;
        }

        public List<UUID> sharingAllWithYou() { return sharingAllWithYou; }
        public List<String> yourAllScreens() { return yourAllScreens; }

        @Override
        public void fromBytes(ByteBuf buf) {
            sharingAllWithYou = Bytes.readUuids(buf, 4096);
            yourAllScreens = Bytes.readStrings(buf, MAX_WATCHERS, ShareScreen.MAX_NAME);
        }

        @Override
        public void toBytes(ByteBuf buf) {
            Bytes.writeUuids(buf, sharingAllWithYou);
            Bytes.writeStrings(buf, yourAllScreens);
        }

        public static final class Handler implements IMessageHandler<Lists, IMessage> {
            @Override
            public IMessage onMessage(Lists m, MessageContext c) {
                if (ShareStream.client != null) ShareStream.client.lists(m);
                return null;
            }
        }
    }

    /** Whether {@code watcher} may see the desktop on {@code screen}: it's on, and shared with them one way or the other. */
    public static boolean mayWatch(MinecraftServer server, ScreenBlockEntity screen, UUID watcher) {
        UUID owner = screen.owner();
        return owner != null && (screen.sharedWith(watcher) || SharingData.of(server).sharesAll(owner, watcher));
    }

    /** Tells a player their lists ({@link Lists}). A game without the mod doesn't know the channel, and ignores it. */
    public static void sendLists(EntityPlayerMP player) {
        SharingData data = SharingData.of(player.getServer());
        List<String> names = new ArrayList<>();
        for (ScreenBlockEntity.Watcher w : data.allScreens(player.getUniqueID())) names.add(w.name);
        Net.CHANNEL.sendTo(new Lists(data.ownersSharingAllWith(player.getUniqueID()), names), player);
    }

    /** The owner adds or removes a player, on one screen or on all of theirs. Says in the action bar what happened. */
    static void change(EntityPlayerMP player, ShareScreen request) {
        if (request.allScreens()) {
            changeAll(player, request);
            return;
        }
        World world = player.world;
        BlockPos pos = request.screen();
        TileEntity te = world.isBlockLoaded(pos) ? world.getTileEntity(pos) : null;
        if (!(te instanceof ScreenBlockEntity)) return;
        ScreenBlockEntity screen = (ScreenBlockEntity) te;
        if (player.getDistanceSqToCenter(pos) > RANGE * RANGE) return;
        if (!player.getUniqueID().equals(screen.owner())) {
            say(player, "desktopscreens.share.not_yours");
            return;
        }
        List<ScreenBlockEntity.Watcher> watchers = new ArrayList<>(screen.shared());
        String name = edit(player, watchers, request);
        if (name == null) return;
        // The whole joined screen holds the same list, like its owner.
        for (BlockPos member : ScreenBlock.group(world, pos)) {
            TileEntity block = world.getTileEntity(member);
            if (block instanceof ScreenBlockEntity) ((ScreenBlockEntity) block).setShared(watchers);
        }
        say(player, request.add() ? "desktopscreens.share.added" : "desktopscreens.share.removed", name);
    }

    private static void changeAll(EntityPlayerMP player, ShareScreen request) {
        SharingData data = SharingData.of(player.getServer());
        List<ScreenBlockEntity.Watcher> watchers = new ArrayList<>(data.allScreens(player.getUniqueID()));
        String name = edit(player, watchers, request);
        if (name == null) return;
        data.setAllScreens(player.getUniqueID(), watchers);
        sendLists(player);
        EntityPlayerMP other = player.getServer().getPlayerList().getPlayerByUsername(name);
        if (other != null) sendLists(other);
        say(player, request.add() ? "desktopscreens.share.added_all" : "desktopscreens.share.removed_all", name);
    }

    /**
     * Adds the named player to {@code watchers}, or removes them. Returns their name if the list changed, else null
     * (saying why, where it's worth saying).
     */
    private static String edit(EntityPlayerMP player, List<ScreenBlockEntity.Watcher> watchers, ShareScreen request) {
        if (!request.add()) {
            return watchers.removeIf(w -> w.name.equalsIgnoreCase(request.name())) ? request.name() : null;
        }
        // Only players who are here now: that's who they are, not just a name someone else could take later.
        EntityPlayerMP target = player.getServer().getPlayerList().getPlayerByUsername(request.name());
        if (target == null) {
            say(player, "desktopscreens.share.not_online", request.name());
            return null;
        }
        if (target == player) {
            say(player, "desktopscreens.share.yourself");
            return null;
        }
        for (ScreenBlockEntity.Watcher w : watchers) {
            if (w.id.equals(target.getUniqueID())) return null;
        }
        if (watchers.size() >= MAX_WATCHERS) {
            say(player, "desktopscreens.share.full", MAX_WATCHERS);
            return null;
        }
        String name = Players.name(target);
        watchers.add(new ScreenBlockEntity.Watcher(target.getUniqueID(), name));
        return name;
    }

    private static void say(EntityPlayerMP player, String key, Object... args) {
        Players.overlay(player, new TextComponentTranslation(key, args));
    }
}
