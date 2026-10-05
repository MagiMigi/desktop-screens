package com.desktopscreens.boot;

import net.minecraft.client.gui.GuiScreen;
import net.minecraftforge.client.event.EntityViewRenderEvent;

/**
 * How the game's side reaches the client's code, which it can't name (see {@link ClientLoader}). Only Java's and
 * Minecraft's types (and ours outside com.desktopscreens.client) may appear here.
 */
public interface ClientHooks {
    /** Once, when the mod is set up (Forge's init). */
    void init();

    /** Every client tick, at its end. */
    void tick();

    /** The tablet was used (right-click), on the client. */
    void tabletUsed();

    /** The settings (for the mods list's config button), going back to {@code parent} when closed. */
    GuiScreen settingsScreen(GuiScreen parent);

    /** Every frame, before anything is drawn. */
    void frame();

    /** Under the HUD, before the hotbar: what goes under Minecraft's own UI (picture-in-picture). */
    void renderUnderHud();

    /** After the HUD is drawn, in its units. */
    void renderHud(int width, int height);

    /** Whether the HUD is left out this frame: hidden as with F1 while a screen in the world is used. */
    boolean hidesHud();

    /** A menu has darkened the world behind it (its default background), before its own panels and buttons. */
    void renderBehindMenu(GuiScreen screen);

    /** Forge's camera event, every frame before the view is turned (see client/CameraGlide). */
    void cameraSetup(EntityViewRenderEvent.CameraSetup event);

    /** After the world is drawn this frame. */
    void renderWorld(float partialTicks);

    /** A mouse button pressed while playing (0 left, 1 right...). True if the mod took it, so Minecraft does nothing with it. */
    boolean mouseButton(int button);

    /** The use key, bound to a key rather than a mouse button, while playing. True if the mod took it. */
    boolean use();

    /** The attack key, bound to a key rather than a mouse button, while playing. True if the mod took it. */
    boolean attack();
}
