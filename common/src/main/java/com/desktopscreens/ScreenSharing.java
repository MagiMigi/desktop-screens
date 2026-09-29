package com.desktopscreens;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.BiPredicate;

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

    /** Whether a player's game has our messages (set by each loader). */
    public static volatile BiPredicate<ServerPlayer, CustomPacketPayload.Type<?>> canReceive = (player, type) -> false;

    private ScreenSharing() {}

    /**
     * Server to player: what they need to know about all-screens lists, which aren't on the screens: whose screens
     * are all shared with them, and whom they share all their own with. Sent when they join and when either changes.
     */
    public record Lists(List<UUID> sharingAllWithYou, List<String> yourAllScreens) implements CustomPacketPayload {
        public static final Type<Lists> TYPE = new Type<>(DesktopScreens.id("share_lists"));
        public static final StreamCodec<ByteBuf, Lists> STREAM_CODEC = StreamCodec.composite(
                UUIDUtil.STREAM_CODEC.apply(ByteBufCodecs.list(4096)), Lists::sharingAllWithYou,
                ByteBufCodecs.stringUtf8(ShareScreen.MAX_NAME).apply(ByteBufCodecs.list(MAX_WATCHERS)), Lists::yourAllScreens,
                Lists::new);

        @Override
        public Type<Lists> type() {
            return TYPE;
        }
    }

    /** Whether {@code watcher} may see the desktop on {@code screen}: it's on, and shared with them one way or the other. */
    public static boolean mayWatch(MinecraftServer server, ScreenBlockEntity screen, UUID watcher) {
        UUID owner = screen.owner();
        return owner != null && (screen.sharedWith(watcher) || SharingData.of(server).sharesAll(owner, watcher));
    }

    /** Tells a player their lists ({@link Lists}), if their game has the mod. */
    public static void sendLists(ServerPlayer player) {
        if (!canReceive.test(player, Lists.TYPE)) return;
        SharingData data = SharingData.of(player.server);
        List<String> names = new ArrayList<>();
        for (ScreenBlockEntity.Watcher w : data.allScreens(player.getUUID())) names.add(w.name());
        player.connection.send(new ClientboundCustomPayloadPacket(new Lists(data.ownersSharingAllWith(player.getUUID()), names)));
    }

    /** The owner adds or removes a player, on one screen or on all of theirs. Says in the action bar what happened. */
    public static void change(ServerPlayer player, ShareScreen request) {
        if (request.allScreens()) {
            changeAll(player, request);
            return;
        }
        ServerLevel level = player.serverLevel();
        BlockPos pos = request.screen();
        if (!level.isLoaded(pos) || !(level.getBlockEntity(pos) instanceof ScreenBlockEntity screen)) return;
        if (player.distanceToSqr(Vec3.atCenterOf(pos)) > RANGE * RANGE) return;
        if (!player.getUUID().equals(screen.owner())) {
            say(player, "desktopscreens.share.not_yours");
            return;
        }
        List<ScreenBlockEntity.Watcher> watchers = new ArrayList<>(screen.shared());
        String name = edit(player, watchers, request);
        if (name == null) return;
        // The whole joined screen holds the same list, like its owner.
        for (BlockPos member : ScreenBlock.group(level, pos)) {
            if (level.getBlockEntity(member) instanceof ScreenBlockEntity block) block.setShared(watchers);
        }
        say(player, request.add() ? "desktopscreens.share.added" : "desktopscreens.share.removed", name);
    }

    private static void changeAll(ServerPlayer player, ShareScreen request) {
        SharingData data = SharingData.of(player.server);
        List<ScreenBlockEntity.Watcher> watchers = new ArrayList<>(data.allScreens(player.getUUID()));
        String name = edit(player, watchers, request);
        if (name == null) return;
        data.setAllScreens(player.getUUID(), watchers);
        sendLists(player);
        ServerPlayer other = player.server.getPlayerList().getPlayerByName(name);
        if (other != null) sendLists(other);
        say(player, request.add() ? "desktopscreens.share.added_all" : "desktopscreens.share.removed_all", name);
    }

    /**
     * Adds the named player to {@code watchers}, or removes them. Returns their name if the list changed, else null
     * (saying why, where it's worth saying).
     */
    private static String edit(ServerPlayer player, List<ScreenBlockEntity.Watcher> watchers, ShareScreen request) {
        if (!request.add()) {
            return watchers.removeIf(w -> w.name().equalsIgnoreCase(request.name())) ? request.name() : null;
        }
        // Only players who are here now: that's who they are, not just a name someone else could take later.
        ServerPlayer target = player.server.getPlayerList().getPlayerByName(request.name());
        if (target == null) {
            say(player, "desktopscreens.share.not_online", request.name());
            return null;
        }
        if (target == player) {
            say(player, "desktopscreens.share.yourself");
            return null;
        }
        for (ScreenBlockEntity.Watcher w : watchers) {
            if (w.id().equals(target.getUUID())) return null;
        }
        if (watchers.size() >= MAX_WATCHERS) {
            say(player, "desktopscreens.share.full", MAX_WATCHERS);
            return null;
        }
        String name = target.getGameProfile().getName();
        watchers.add(new ScreenBlockEntity.Watcher(target.getUUID(), name));
        return name;
    }

    private static void say(ServerPlayer player, String key, Object... args) {
        player.displayClientMessage(Component.translatable(key, args), true);
    }
}
