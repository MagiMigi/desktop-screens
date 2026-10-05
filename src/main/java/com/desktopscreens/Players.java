package com.desktopscreens;

import com.mojang.authlib.GameProfile;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;

import java.util.UUID;

/** What we ask of a player, the same way in every Minecraft version. */
public final class Players {
    private Players() {}

    public static MinecraftServer server(ServerPlayer player) {
        //? if >=1.21.6 {
        /*return player.level().getServer();
        *///?} else {
        return player.server;
        //?}
    }

    public static ServerLevel level(ServerPlayer player) {
        //? if >=1.21.6 {
        /*return player.level();
        *///?} else {
        return player.serverLevel();
        //?}
    }

    public static String name(Player player) {
        return name(player.getGameProfile());
    }

    /** 1.21.9 made the profile a record. */
    public static String name(GameProfile profile) {
        //? if >=1.21.9 {
        /*return profile.name();
        *///?} else {
        return profile.getName();
        //?}
    }

    public static UUID id(GameProfile profile) {
        //? if >=1.21.9 {
        /*return profile.id();
        *///?} else {
        return profile.getId();
        //?}
    }

    /** A line over the hotbar. */
    public static void overlay(Player player, Component message) {
        //? if >=1.21.9 {
        /*player.sendOverlayMessage(message);
        *///?} else {
        player.displayClientMessage(message, true);
        //?}
    }
}
