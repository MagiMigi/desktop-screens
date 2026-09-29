package com.desktopscreens;

import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The server's part of sharing a screen: who's watching which of whose screens, and passing the owners' pictures and
 * sound on to them. Each screen shows its own thing, so an owner sends a stream of pictures per thing shown on a
 * watched screen, each picture labelled with the screens it's for ({@link ShareStream.Label}). The server tells the
 * owner which of their screens someone has in sight ({@link ShareStream.Status}), never learning what they show, and
 * passes each picture to whoever has one of its screens in sight: H.264 while everyone watching that screen can
 * decode it, else JPEG. Sound works the same way, a stream per program heard (a window's program, or everything for a
 * whole monitor): each listener hears one screen per owner, the nearest they haven't muted, and gets that screen's
 * stream. Each player's game says what it wants of which screen ({@link ShareStream.Watch}).
 *
 * <p>One picture at a time per hop and stream: the owner sends a stream's next picture once the server has the last
 * ({@link ShareStream.Taken}), and each watcher gets the next once theirs arrived ({@link ShareStream.Received}).
 * Meanwhile a JPEG watcher only keeps the newest picture waiting. H.264 builds each picture on the ones before, so an
 * H.264 watcher gets them all, in order; one who falls too far behind (or starts watching) skips to the next keyframe,
 * which the owner is asked for. Sound packets are passed on as they come. Server thread only.
 */
public final class ShareRelay {
    /** A watcher's client repeats "watching" every 2 s; this long without it, and they're gone. */
    private static final long WATCH_TIMEOUT_MILLIS = 6000;
    /** A picture that got no receipt this long is taken as lost, so the stream can't stall. */
    private static final long RECEIPT_TIMEOUT_MILLIS = 5000;
    /** An H.264 watcher this many pictures behind (half a second) starts again at a keyframe. */
    private static final int MAX_BEHIND = 15;
    /** A watcher's stream that sent nothing this long is forgotten. */
    private static final long IDLE_DELIVERY_MILLIS = 60_000;
    /** How often the owner may be asked for a keyframe of a screen; each costs several ordinary pictures' worth. */
    private static final long KEYFRAME_ASK_MILLIS = 500;
    /** Screens are drawn up to 64 blocks away by default; a margin for other settings. */
    private static final double RANGE = 128;
    private static final int CHECK_TICKS = 10;

    private static final Map<UUID, Owner> OWNERS = new HashMap<>();
    private static MinecraftServer server;
    private static int ticks;

    private ShareRelay() {}

    /** One owner's watchers, and their pictures on the way in. */
    private static final class Owner {
        final Map<UUID, Watcher> watchers = new LinkedHashMap<>();
        /** Pictures being put together, per stream; in access order, so the one unheard of longest comes first. */
        final LinkedHashMap<Integer, Incoming> incoming = new LinkedHashMap<>(8, 0.75f, true);
        final Map<UUID, Long> keyframeAskedAt = new HashMap<>();
        // What the owner was last told:
        List<String> told = List.of();
        int flags = -1;
        Map<UUID, Boolean> toldScreens = Map.of();
        Set<UUID> toldSound = Set.of();
    }

    /** One stream's picture, arriving in pieces. */
    private static final class Incoming {
        int frame = -1, missing, bytes;
        byte[][] chunks;
    }

    /** A player watching or listening to one owner's screens. */
    private static final class Watcher {
        boolean h264; // their game can decode it
        final Map<UUID, Interest> screens = new HashMap<>();       // by screen id
        final Map<Integer, Delivery> deliveries = new HashMap<>(); // by stream
    }

    /** One of the owner's screens, which a watcher has in sight (pictures) or is near (sound). */
    private static final class Interest {
        BlockPos pos;
        ResourceKey<Level> dimension;
        boolean pictures, sound;
        long lastSeen;
    }

    /** One stream's pictures on their way to one watcher. */
    private static final class Delivery {
        boolean needsKeyframe = true;
        boolean h264;      // the last picture sent was
        UUID screen;       // one of the watcher's screens it's for, to ask a keyframe for
        int inFlight = -1; // the picture on its way to them, or -1
        long sentAt;
        final ArrayDeque<ShareStream.Frame> waiting = new ArrayDeque<>();
    }

    /** A player looks at a screen shared with them or is near it, or stopped. */
    public static void watch(ServerPlayer watcher, ShareStream.Watch request) {
        UUID me = watcher.getUUID();
        if (request.stopped()) {
            for (Owner o : OWNERS.values()) {
                Watcher w = o.watchers.get(me);
                if (w != null) w.screens.values().removeIf(i -> i.pos.equals(request.screen()));
            }
            return; // the check on the next tick drops watchers with nothing left, and tells the owners
        }
        ServerConfig config = ServerConfig.of(watcher.server);
        ServerLevel level = watcher.serverLevel();
        BlockPos pos = request.screen();
        if (!config.sharing || !level.isLoaded(pos) || !(level.getBlockEntity(pos) instanceof ScreenBlockEntity screen)) return;
        UUID owner = screen.owner();
        if (owner == null || owner.equals(me) || !ScreenSharing.mayWatch(watcher.server, screen, me)) return;
        if (watcher.distanceToSqr(Vec3.atCenterOf(pos)) > RANGE * RANGE) return;
        ServerPlayer ownerPlayer = watcher.server.getPlayerList().getPlayer(owner);
        if (ownerPlayer == null) return;
        UUID id = ShareStream.idOf(screen.screenId());
        Owner o = OWNERS.computeIfAbsent(owner, k -> new Owner());
        Watcher w = o.watchers.computeIfAbsent(me, k -> new Watcher());
        Interest i = w.screens.computeIfAbsent(id, k -> new Interest());
        boolean cameIntoSight = request.pictures() && !i.pictures;
        i.pictures = request.pictures();
        i.sound = request.sound();
        i.pos = pos;
        i.dimension = level.dimension();
        i.lastSeen = System.currentTimeMillis();
        w.h264 = request.h264();
        tell(ownerPlayer, o, config);
        // (Again) in sight: a picture to start from, even if the owner's desktop is still. After telling the owner, so
        // a screen new to them already has its stream (which starts with a keyframe anyway).
        if (cameIntoSight) askKeyframe(ownerPlayer, o, id, i.lastSeen);
    }

    /** A packet of one of the owner's sound streams: on to everyone who hears one of its screens, as it is. */
    public static void sound(ServerPlayer owner, ShareStream.SoundUp packet) {
        Owner o = OWNERS.get(owner.getUUID());
        if (o == null || !ServerConfig.of(owner.server).shareSound) return;
        for (Map.Entry<UUID, Watcher> e : o.watchers.entrySet()) {
            List<UUID> screens = null;
            for (UUID id : packet.label().screens()) {
                Interest i = e.getValue().screens.get(id);
                if (i == null || !i.sound) continue;
                if (screens == null) screens = new ArrayList<>(1);
                screens.add(id);
            }
            if (screens == null) continue;
            ServerPlayer player = owner.server.getPlayerList().getPlayer(e.getKey());
            if (player != null) {
                send(player, new ShareStream.Sound(owner.getUUID(), packet.label().stream(), screens, packet.seq(), packet.played(), packet.opus()));
            }
        }
    }

    /** A piece of a picture of one of the owner's streams. Once it's whole, it goes on to the watchers. */
    public static void chunk(ServerPlayer owner, ShareStream.Chunk chunk) {
        Owner o = OWNERS.get(owner.getUUID());
        if (o == null || o.watchers.isEmpty()) return; // nobody's watching; the owner has been told to stop
        int count = chunk.count(), index = chunk.index(), stream = chunk.label().stream();
        if (count < 1 || count > ShareStream.MAX_CHUNKS || index < 0 || index >= count) return;
        Incoming in = o.incoming.get(stream);
        if (in == null) {
            // More streams than the owner may send: the one unheard of longest makes room (it loses at most a
            // half-arrived picture), so this never holds more than the limit's worth of pictures.
            if (o.incoming.size() >= ServerConfig.of(owner.server).shareMaxStreams) {
                Iterator<Incoming> oldest = o.incoming.values().iterator();
                oldest.next();
                oldest.remove();
            }
            in = new Incoming();
            o.incoming.put(stream, in);
        }
        if (chunk.frame() != in.frame || in.chunks == null || in.chunks.length != count) {
            in.frame = chunk.frame();
            in.chunks = new byte[count][];
            in.missing = count;
            in.bytes = 0;
        }
        if (in.chunks[index] != null) return;
        in.chunks[index] = chunk.data();
        in.missing--;
        in.bytes += chunk.data().length;
        if (in.bytes > ShareStream.MAX_FRAME_BYTES) {
            in.chunks = null;
            in.frame = -1;
            return;
        }
        if (in.missing > 0) return;

        byte[] picture = new byte[in.bytes];
        int at = 0;
        for (byte[] part : in.chunks) {
            System.arraycopy(part, 0, picture, at, part.length);
            at += part.length;
        }
        in.chunks = null;
        in.frame = -1;
        send(owner, new ShareStream.Taken(stream, chunk.frame()));
        pass(owner, o, chunk.label(), chunk.frame(), chunk.taken(), picture);
    }

    /** One of the owner's streams ended: its watchers drop it (all of the owner's watchers are told; it's rare and tiny). */
    public static void end(ServerPlayer owner, ShareStream.EndUp end) {
        Owner o = OWNERS.get(owner.getUUID());
        if (o == null) return;
        o.incoming.remove(end.stream());
        ShareStream.End out = new ShareStream.End(owner.getUUID(), end.stream());
        for (Map.Entry<UUID, Watcher> e : o.watchers.entrySet()) {
            e.getValue().deliveries.remove(end.stream());
            ServerPlayer player = owner.server.getPlayerList().getPlayer(e.getKey());
            if (player != null) send(player, out);
        }
    }

    /** A whole picture: to each watcher who has one of its screens in sight, now or into their queue. */
    private static void pass(ServerPlayer owner, Owner o, ShareStream.Label label, int frame, long taken, byte[] picture) {
        boolean h264 = ShareStream.isH264(picture), keyframe = ShareStream.isKeyframe(picture);
        long now = System.currentTimeMillis();
        for (Map.Entry<UUID, Watcher> e : o.watchers.entrySet()) {
            Watcher w = e.getValue();
            List<UUID> screens = new ArrayList<>();
            for (UUID id : label.screens()) {
                Interest i = w.screens.get(id);
                if (i != null && i.pictures) screens.add(id);
            }
            if (screens.isEmpty()) { // none of its screens in sight (maybe near one, for the sound): nothing more for them
                w.deliveries.remove(label.stream());
                continue;
            }
            Delivery d = w.deliveries.computeIfAbsent(label.stream(), k -> new Delivery());
            d.screen = screens.get(0);
            if (h264) {
                if (!w.h264) continue; // a leftover from before they came; JPEG comes next
                if (d.needsKeyframe) {
                    if (!keyframe) {
                        askKeyframe(owner, o, d.screen, now);
                        continue;
                    }
                    d.needsKeyframe = false;
                    d.waiting.clear();
                }
            } else {
                d.needsKeyframe = true; // should H.264 come after JPEG, it starts at a keyframe
            }
            ShareStream.Frame out = new ShareStream.Frame(owner.getUUID(), label.stream(), frame, taken, List.copyOf(screens), picture);
            if (d.inFlight < 0) {
                ServerPlayer player = owner.server.getPlayerList().getPlayer(e.getKey());
                if (player != null) sendFrame(player, d, out, now);
            } else if (h264) {
                d.waiting.add(out);
                if (d.waiting.size() > MAX_BEHIND) fellBehind(owner, o, d, now);
            } else {
                d.waiting.clear(); // JPEG: they only need the newest
                d.waiting.add(out);
            }
        }
    }

    /** An H.264 watcher missed pictures: they wait for a keyframe, which the owner is asked for. */
    private static void fellBehind(ServerPlayer owner, Owner o, Delivery d, long now) {
        d.waiting.clear();
        d.needsKeyframe = true;
        askKeyframe(owner, o, d.screen, now);
    }

    private static void askKeyframe(ServerPlayer owner, Owner o, UUID screen, long now) {
        Long asked = o.keyframeAskedAt.get(screen);
        if (asked != null && now - asked < KEYFRAME_ASK_MILLIS) return;
        o.keyframeAskedAt.put(screen, now);
        send(owner, new ShareStream.Keyframe(screen));
    }

    /** A watcher got a picture: send them that stream's next one waiting, if there is one. */
    public static void received(ServerPlayer watcher, ShareStream.Received receipt) {
        Owner o = OWNERS.get(receipt.owner());
        Watcher w = o == null ? null : o.watchers.get(watcher.getUUID());
        Delivery d = w == null ? null : w.deliveries.get(receipt.stream());
        if (d == null || d.inFlight != receipt.frame()) return; // (a late receipt for one taken as lost)
        d.inFlight = -1;
        ShareStream.Frame next = d.waiting.poll();
        if (next != null) sendFrame(watcher, d, next, System.currentTimeMillis());
    }

    private static void sendFrame(ServerPlayer player, Delivery d, ShareStream.Frame frame, long now) {
        d.inFlight = frame.frame();
        d.h264 = ShareStream.isH264(frame.picture());
        d.sentAt = now;
        send(player, frame);
    }

    /**
     * Every server tick: twice a second, drops screens watchers stopped watching, went away from or lost the right
     * to, and watchers with none left, and tells owners when that changed what they should send; nobody means stop.
     */
    public static void tick(MinecraftServer current) {
        if (current != server) { // another world or server: nothing carries over
            server = current;
            OWNERS.clear();
        }
        if (++ticks % CHECK_TICKS != 0 || OWNERS.isEmpty()) return;
        ServerConfig config = ServerConfig.of(current);
        long now = System.currentTimeMillis();
        for (Iterator<Map.Entry<UUID, Owner>> it = OWNERS.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, Owner> e = it.next();
            UUID owner = e.getKey();
            Owner o = e.getValue();
            ServerPlayer ownerPlayer = current.getPlayerList().getPlayer(owner);
            if (ownerPlayer == null) {
                it.remove();
                continue;
            }
            for (Iterator<Map.Entry<UUID, Watcher>> ws = o.watchers.entrySet().iterator(); ws.hasNext(); ) {
                Map.Entry<UUID, Watcher> we = ws.next();
                Watcher w = we.getValue();
                ServerPlayer player = current.getPlayerList().getPlayer(we.getKey());
                if (config.sharing && player != null) {
                    w.screens.entrySet().removeIf(s -> now - s.getValue().lastSeen > WATCH_TIMEOUT_MILLIS
                            || !stillWatching(player, s.getValue(), owner, s.getKey()));
                }
                if (!config.sharing || player == null || w.screens.isEmpty()) {
                    ws.remove();
                    continue;
                }
                // A stream that stopped (its screens show something else now) or stands still: forgotten after a
                // while; should it send again, the watcher starts at a keyframe.
                w.deliveries.values().removeIf(d -> d.inFlight < 0 && d.waiting.isEmpty() && now - d.sentAt > IDLE_DELIVERY_MILLIS);
                for (Delivery d : w.deliveries.values()) {
                    if (d.inFlight < 0 || now - d.sentAt <= RECEIPT_TIMEOUT_MILLIS) continue;
                    d.inFlight = -1; // lost
                    if (d.h264) {
                        fellBehind(ownerPlayer, o, d, now);
                    } else {
                        ShareStream.Frame next = d.waiting.poll();
                        if (next != null) sendFrame(player, d, next, now);
                    }
                }
            }
            tell(ownerPlayer, o, config);
            if (o.watchers.isEmpty()) it.remove();
        }
    }

    private static boolean stillWatching(ServerPlayer player, Interest i, UUID owner, UUID id) {
        if (player.level().dimension() != i.dimension || player.distanceToSqr(Vec3.atCenterOf(i.pos)) > RANGE * RANGE) return false;
        ServerLevel level = player.serverLevel();
        return level.isLoaded(i.pos) && level.getBlockEntity(i.pos) instanceof ScreenBlockEntity screen
                && owner.equals(screen.owner()) && id.equals(ShareStream.idOf(screen.screenId()))
                && ScreenSharing.mayWatch(player.server, screen, player.getUUID());
    }

    /**
     * Tells the owner who's watching or listening, which of their screens someone has in sight (and whether all of
     * those watchers decode H.264), and whether anyone wants the sound, if any of that changed.
     */
    private static void tell(ServerPlayer owner, Owner o, ServerConfig config) {
        List<String> names = new ArrayList<>();
        Map<UUID, Boolean> screens = new LinkedHashMap<>();
        Set<UUID> sound = new LinkedHashSet<>();
        for (Map.Entry<UUID, Watcher> e : o.watchers.entrySet()) {
            ServerPlayer p = owner.server.getPlayerList().getPlayer(e.getKey());
            if (p != null) names.add(p.getGameProfile().getName());
            Watcher w = e.getValue();
            for (Map.Entry<UUID, Interest> s : w.screens.entrySet()) {
                if (s.getValue().pictures && (screens.containsKey(s.getKey()) || screens.size() < ShareStream.MAX_SCREENS)) {
                    screens.merge(s.getKey(), w.h264, Boolean::logicalAnd);
                }
                if (s.getValue().sound && config.shareSound && sound.size() < ShareStream.MAX_SCREENS) sound.add(s.getKey());
            }
        }
        int flags = (screens.isEmpty() ? 0 : ShareStream.Status.PICTURES) | (sound.isEmpty() ? 0 : ShareStream.Status.SOUND);
        if (names.equals(o.told) && flags == o.flags && screens.equals(o.toldScreens) && sound.equals(o.toldSound)) return;
        o.told = names;
        o.flags = flags;
        o.toldScreens = screens;
        o.toldSound = sound;
        List<ShareStream.Wanted> wanted = new ArrayList<>();
        for (Map.Entry<UUID, Boolean> s : screens.entrySet()) wanted.add(new ShareStream.Wanted(s.getKey(), s.getValue()));
        ShareStream.Limits limits = new ShareStream.Limits(config.shareMaxFps, config.shareMaxWidth, config.shareMaxHeight, config.shareMaxStreams);
        send(owner, new ShareStream.Status(names, limits, flags, wanted, List.copyOf(sound)));
    }

    private static void send(ServerPlayer player, CustomPacketPayload payload) {
        player.connection.send(new ClientboundCustomPayloadPacket(payload));
    }
}
