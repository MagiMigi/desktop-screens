package com.desktopscreens.client;

import com.desktopscreens.DesktopScreens;
import com.desktopscreens.ScreenSharing;
import com.desktopscreens.ShareStream;
import com.desktopscreens.TabletItem;
import com.desktopscreens.core.Win32;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Camera;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.AlertScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/**
 * Client entry point shared by both loaders. Loaders register the key mappings, call {@link #tick} every
 * client tick, and hook {@link #settingsScreen} up to their mods list.
 */
public final class DesktopClient {
    private static final String CATEGORY = "key.categories.desktopscreens";
    public static final KeyMapping OPEN_KEY = new KeyMapping(
            "key.desktopscreens.open", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_G, CATEGORY);
    /** Unbound by default; the settings are also one host key command away (Right Ctrl + O). */
    public static final KeyMapping SETTINGS_KEY = new KeyMapping(
            "key.desktopscreens.settings", InputConstants.Type.KEYSYM, InputConstants.UNKNOWN.getValue(), CATEGORY);
    /** Shows or hides picture-in-picture. Unbound by default: big packs are short of keys. */
    public static final KeyMapping PIP_KEY = new KeyMapping(
            "key.desktopscreens.pip", InputConstants.Type.KEYSYM, InputConstants.UNKNOWN.getValue(), CATEGORY);
    /**
     * The host key: back to the game from the desktop, and with another key a command. Only read when the desktop
     * opens; Minecraft never acts on it. Settable since 2026-09-29: many new laptops have no Right Ctrl (a Copilot key
     * sits there), and neither do Macs running Windows.
     */
    public static final KeyMapping HOST_KEY = new KeyMapping(
            "key.desktopscreens.host", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_RIGHT_CONTROL, CATEGORY);

    /** The tablet was used; open once the use button (right-click) is let go. */
    private static boolean tabletPending;

    private DesktopClient() {}

    /** Called once by each loader's client entry point. */
    public static void init() {
        TabletItem.onUseClient = () -> tabletPending = true;
        ShareStream.client = new ShareStream.Client() {
            @Override
            public void status(ShareStream.Status status) {
                ShareStreamer.status(status);
                ShareSound.status(status);
            }

            @Override
            public void taken(ShareStream.Taken taken) {
                ShareStreamer.taken(taken);
            }

            @Override
            public void frame(ShareStream.Frame frame) {
                SharedPictures.frame(frame);
            }

            @Override
            public void lists(ScreenSharing.Lists lists) {
                SharingInfo.received(lists);
            }

            @Override
            public void keyframe(ShareStream.Keyframe keyframe) {
                ShareStreamer.keyframe(keyframe);
            }

            @Override
            public void sound(ShareStream.Sound sound) {
                SharedSounds.sound(sound);
            }

            @Override
            public void end(ShareStream.End end) {
                SharedPictures.end(end);
            }
        };
        H264Support.check();
    }

    public static void tick(Minecraft mc) {
        FocusHandOff.tick(mc);
        FullscreenFocus.tick(mc);
        WorldScreens.tick(mc);
        LiveCapture.tickAll();
        LiveWindow.tickAll(mc);
        ScreenAim.tick(mc);
        ScreenBuilderClient.tick(mc);
        WatchChoices.tick(mc);
        ShareStreamer.tick(mc);
        ShareSound.tick(mc);
        SharedPictures.tick(mc);
        SharedSounds.tick(mc);
        ShareWatching.tick(mc);
        ShareTiming.tick(mc);
        if (mc.level == null) {
            SharingInfo.clear();
            LiveCapture.worldLeft();
        }
        while (OPEN_KEY.consumeClick()) {
            if (mc.screen != null) continue;
            ScreenGroups.Group screen = ScreenAim.usable();
            if (screen != null) useScreen(mc, screen);
            else if (hasTablet(mc)) openDesktop(mc);
            // Else nothing, and quietly: the desktop comes with a tablet or a screen, not with the key alone
            // (2026-09-25), and big packs often have G on something else too.
        }
        while (PIP_KEY.consumeClick()) {
            if (mc.screen == null) PictureInPicture.toggle(mc);
        }
        // Not right away: the mouse moves onto the desktop when it opens, and letting go of the right button there
        // would be a right-click release in some app (on the desktop itself, that opens its context menu).
        if (tabletPending && !mc.options.keyUse.isDown()) {
            tabletPending = false;
            if (mc.screen == null) openDesktop(mc);
        }
        while (SETTINGS_KEY.consumeClick()) {
            if (mc.screen == null) mc.setScreen(settingsScreen(null));
        }
    }

    /** Whether the player carries a tablet anywhere in their inventory (the off hand too), so G opens the desktop. */
    static boolean hasTablet(Minecraft mc) {
        return mc.player != null && mc.player.getInventory().contains(stack -> stack.getItem() instanceof TabletItem);
    }

    private static void openDesktop(Minecraft mc) {
        if (!Win32.SUPPORTED) {
            mc.gui.getChat().addMessage(Component.translatable("desktopscreens.error.windows_only"));
            return;
        }
        DesktopConfig.load();
        try {
            mc.setScreen(DesktopScreen.desktop(DesktopSession.open(mc), null));
        } catch (RuntimeException | LinkageError e) {
            DesktopScreens.LOG.error("Couldn't open the desktop", e);
            mc.gui.getChat().addMessage(Component.translatable("desktopscreens.error.open", String.valueOf(e.getMessage())));
        }
    }

    /** G while looking at a screen in the world that's on for this player. */
    private static void useScreen(Minecraft mc, ScreenGroups.Group screen) {
        if (!Win32.SUPPORTED) {
            mc.gui.getChat().addMessage(Component.translatable("desktopscreens.error.windows_only"));
            return;
        }
        DesktopConfig.load();
        try {
            // What this screen shows, and changing it (Right Ctrl + M, + W, the settings) changes only this screen.
            mc.setScreen(DesktopScreen.onScreen(DesktopSession.open(mc, SourceChoice.forUsing(mc.level, screen)), screen));
        } catch (RuntimeException | LinkageError e) {
            DesktopScreens.LOG.error("Couldn't open the desktop", e);
            mc.gui.getChat().addMessage(Component.translatable("desktopscreens.error.open", String.valueOf(e.getMessage())));
        }
    }

    /**
     * The text under the crosshair: the Screen Builder's while building or changing a screen, else the builder's keys
     * for a screen looked at, else the hint for a screen looked at, else how to start with the builder. Loaders call it
     * while drawing the HUD, after the crosshair.
     */
    public static void renderHud(GuiGraphics g) {
        Minecraft mc = Minecraft.getInstance();
        PictureInPicture.renderAfterHud(mc, g); // only if it wasn't drawn under the HUD
        ShareStreamer.renderNotice(mc, g, g.guiWidth());
        if (!ScreenBuilderClient.renderHud(mc, g) && !ScreenBuilderClient.renderScreenHint(mc, g) && !ScreenAim.renderHud(mc, g)) {
            ScreenBuilderClient.renderStartHint(mc, g);
        }
    }

    /** Picture-in-picture under the rest of the HUD. Loaders call it while drawing the HUD, before the crosshair. */
    public static void renderUnderHud(GuiGraphics g) {
        PictureInPicture.renderUnderHud(Minecraft.getInstance(), g);
    }

    /** Picture-in-picture behind a menu's buttons and panels: called once a menu has drawn its background (a mixin). */
    public static void renderBehindMenu(Screen screen, GuiGraphics g) {
        PictureInPicture.renderBehindMenu(Minecraft.getInstance(), screen, g);
    }

    /** The Screen Builder's hologram. Loaders call it every frame, after the world's see-through parts are drawn. */
    public static void renderWorld(Camera camera) {
        ScreenBuilderClient.render(Minecraft.getInstance(), camera);
    }

    /** Keys in hints get their own color, so they stand out from the words. */
    private static final int KEY_COLOR = 0xFFD866;

    /** A key as the hints show it, before what it does: its name in brackets, in the key color, like "[G]". */
    static Component key(Component name) {
        return Component.translatable("desktopscreens.key", name).withColor(KEY_COLOR);
    }

    /** A Minecraft key as it's bound now, like "[G]" or "[RMB]". */
    static Component key(KeyMapping mapping) {
        return key(keyName(mapping));
    }

    /** Two Minecraft keys together, like "[Left Shift + RMB]". */
    static Component key(KeyMapping held, KeyMapping then) {
        return key(Component.translatable("desktopscreens.key.together", keyName(held), keyName(then)));
    }

    /** The host key: "[Right Ctrl]". */
    static Component hostKey() {
        return key(hostKeyName());
    }

    /** A host key command on its own: "[Right Ctrl + W]". */
    static Component hostKey(String then) {
        return key(Component.translatable("desktopscreens.key.host_plus", hostKeyName(), then));
    }

    /**
     * The host key's name, without brackets: "Right Ctrl" (shorter than Controls' "Right Control", so the hints fit),
     * or the name Controls shows for the key picked there. Right Ctrl also when it can't be used (see {@link #hostScancode}).
     */
    static Component hostKeyName() {
        InputConstants.Key key = InputConstants.getKey(HOST_KEY.saveString());
        if (hostScancode() <= 0 || key.getType() == InputConstants.Type.KEYSYM && key.getValue() == GLFW.GLFW_KEY_RIGHT_CONTROL) {
            return Component.translatable("desktopscreens.key.host");
        }
        return HOST_KEY.getTranslatedKeyMessage();
    }

    /**
     * Where the host key is on the keyboard, for {@link com.desktopscreens.core.DesktopInput#setHostKey}: its scan code
     * as GLFW has it (+0x100 for extended keys). 0 means Right Ctrl: unbound, or a mouse button (the keyboard hook
     * can't see those).
     */
    static int hostScancode() {
        InputConstants.Key key = InputConstants.getKey(HOST_KEY.saveString());
        if (key.getType() == InputConstants.Type.SCANCODE) return key.getValue();
        if (key.getType() != InputConstants.Type.KEYSYM || key.getValue() == InputConstants.UNKNOWN.getValue()) return 0;
        return Math.max(0, GLFW.glfwGetKeyScancode(key.getValue()));
    }

    /** A host key command in a hint that has just named the host key: "[+O]". */
    static Component plus(Component then) {
        return key(Component.translatable("desktopscreens.key.plus", then));
    }

    static Component plus(String then) {
        return plus(Component.literal(then));
    }

    /** A key's name, like "G": mouse buttons as "LMB", "RMB" and "MMB". Follows rebinding. */
    private static Component keyName(KeyMapping key) {
        return switch (key.saveString()) {
            case "key.mouse.left" -> Component.translatable("desktopscreens.key.mouse_left");
            case "key.mouse.right" -> Component.translatable("desktopscreens.key.mouse_right");
            case "key.mouse.middle" -> Component.translatable("desktopscreens.key.mouse_middle");
            default -> key.getTranslatedKeyMessage();
        };
    }

    /** The settings, with a live preview of the desktop. Also what the mods list's config button opens. */
    public static Screen settingsScreen(Screen parent) {
        Minecraft mc = Minecraft.getInstance();
        Component title = Component.translatable("desktopscreens.settings.title");
        if (!Win32.SUPPORTED) {
            return new AlertScreen(() -> mc.setScreen(parent), title, Component.translatable("desktopscreens.error.windows_only"));
        }
        DesktopConfig.load();
        try {
            return DesktopScreen.settings(DesktopSession.open(mc), parent);
        } catch (RuntimeException | LinkageError e) {
            DesktopScreens.LOG.error("Couldn't open the desktop", e);
            return new AlertScreen(() -> mc.setScreen(parent), title,
                    Component.translatable("desktopscreens.error.open", String.valueOf(e.getMessage())));
        }
    }

    /** The host key was tapped. Called on the input hook thread, so hop over to the game thread. */
    static void onHostKey() {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            if (mc.screen instanceof DesktopScreen screen) screen.onClose();
        });
    }

    /** You switched to Minecraft yourself while using the desktop (Alt+Tab, its taskbar button). Input hook thread. */
    static void onGameChosen() {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            if (mc.screen instanceof DesktopScreen screen) screen.onGameChosen();
        });
    }

    /** A key was pressed while the host key was held. Called on the input hook thread. */
    static void onHostCombo(int virtualKey) {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            if (mc.screen instanceof DesktopScreen screen) screen.onHostCombo(virtualKey);
        });
    }

    /** The mouse wheel turned while the host key was held. Called on the input hook thread. */
    static void onHostWheel(int delta) {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            if (mc.screen instanceof DesktopScreen screen) screen.onHostWheel(delta);
        });
    }
}
