package com.desktopscreens;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.text.ITextComponent;

/** What we ask of a player. */
public final class Players {
    private Players() {}

    public static String name(EntityPlayer player) {
        return player.getGameProfile().getName();
    }

    /** A line over the hotbar. */
    public static void overlay(EntityPlayer player, ITextComponent message) {
        player.sendStatusMessage(message, true);
    }
}
