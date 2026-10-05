package com.desktopscreens;

import java.util.function.BooleanSupplier;

/**
 * Lets the client tell whether the server it plays on has this mod. On a server without it our items don't exist, so
 * the creative menu leaves them out there (on the newer versions taking one got the player kicked; a 1.12.2 server
 * reads an item it doesn't know as nothing, so the player would hold an item only their own game sees).
 *
 * <p>Forge 1.12.2 keeps no list of the server's mods on the client (only the server keeps the client's), but every
 * server with the mod sends each player who joins its {@link BuilderRules}: having them from this server is the sign.
 */
public final class ServerCheck {
    /** This connection's server sent its builder rules. Game thread. */
    private static volatile boolean heard;

    /**
     * Set by the client when it starts: also true while not connected (mods that list items at startup, JEI say, see
     * ours). Asked when the creative menu fills a tab or a search. A dedicated server never asks.
     */
    public static volatile BooleanSupplier serverHasMod = () -> true;

    private ServerCheck() {}

    /** Whether the server sent its builder rules since the client joined it. */
    public static boolean heard() {
        return heard;
    }

    static void heard(boolean value) {
        heard = value;
    }
}
