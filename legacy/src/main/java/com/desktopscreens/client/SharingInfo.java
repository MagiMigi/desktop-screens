package com.desktopscreens.client;

import com.desktopscreens.ScreenSharing;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * What the server says about all-screens lists, which aren't kept on the screens: whose screens are all shared with
 * this player, and whom this player shares all theirs with (for the sharing page). Each player only learns about
 * themselves. Sent when joining and whenever either changes. Client thread.
 */
final class SharingInfo {
    private static Set<UUID> sharingAllWithMe = Collections.emptySet();
    private static List<String> myAllScreens = Collections.emptyList();

    private SharingInfo() {}

    static void received(ScreenSharing.Lists lists) {
        sharingAllWithMe = Collections.unmodifiableSet(new HashSet<>(lists.sharingAllWithYou()));
        myAllScreens = Collections.unmodifiableList(new ArrayList<>(lists.yourAllScreens()));
    }

    /** Leaving a server: the next one says its own. */
    static void clear() {
        sharingAllWithMe = Collections.emptySet();
        myAllScreens = Collections.emptyList();
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
