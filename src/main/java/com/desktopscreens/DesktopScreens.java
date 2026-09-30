package com.desktopscreens;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class DesktopScreens {
    public static final String MOD_ID = "desktopscreens";
    public static final Logger LOG = LoggerFactory.getLogger("Desktop Screens");

    private DesktopScreens() {}

    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
    }

    /** Loaders call this every server tick. */
    public static void serverTick(MinecraftServer server) {
        OwnedScreens.tick(server);
        ShareRelay.tick(server);
    }
}
