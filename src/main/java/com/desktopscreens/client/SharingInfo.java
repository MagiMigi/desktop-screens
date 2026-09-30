package com.desktopscreens.client;

import com.desktopscreens.ScreenSharing;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * What the server says about all-screens lists, which aren't kept on the screens: whose screens are all shared with
 * this player, and whom this player shares all theirs with (for the sharing page). Each player only learns about
 * themselves. Sent when joining and whenever either changes. Client thread.
 */
final class SharingInfo {
    private static Set<UUID> sharingAllWithMe = Set.of();
    private static List<String> myAllScreens = List.of();

    private SharingInfo() {}

    static void received(ScreenSharing.Lists lists) {
        sharingAllWithMe = Set.copyOf(lists.sharingAllWithYou());
        myAllScreens = List.copyOf(lists.yourAllScreens());
    }

    /** Leaving a server: the next one says its own. */
    static void clear() {
        sharingAllWithMe = Set.of();
        myAllScreens = List.of();
    }

    /** Whether {@code owner} shares all their screens with this player. */
    static boolean sharesAll(UUID owner) {
        return sharingAllWithMe.contains(owner);
    }

    /** Whom this player shares all their screens with. */
    static List<String> myAllScreens() {
        return myAllScreens;
    }
}
