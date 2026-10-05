package com.desktopscreens.client;

import com.desktopscreens.BuilderRules;
import com.desktopscreens.DesktopScreens;
import com.desktopscreens.ScreenBlockEntity;
import com.desktopscreens.ScreenSharing;
import com.desktopscreens.ShareStream;
import com.desktopscreens.TabletItem;
import com.desktopscreens.core.Win32;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiErrorScreen;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.resources.I18n;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraft.util.text.TextFormatting;
import net.minecraftforge.fml.client.registry.ClientRegistry;
import org.lwjgl.input.Keyboard;

/** The client's entry point (from {@link ClientMain}): the keys, every tick, and opening the desktop. */
final class DesktopClient {
    private static final String CATEGORY = "key.categories.desktopscreens";
    static final KeyBinding OPEN_KEY = new KeyBinding("key.desktopscreens.open", Keyboard.KEY_G, CATEGORY);
    /** Unbound by default; the settings are also one host key command away (Right Ctrl + O). */
    static final KeyBinding SETTINGS_KEY = new KeyBinding("key.desktopscreens.settings", Keyboard.KEY_NONE, CATEGORY);
    /** Shows or hides picture-in-picture. Unbound by default: big packs are short of keys. */
    static final KeyBinding PIP_KEY = new KeyBinding("key.desktopscreens.pip", Keyboard.KEY_NONE, CATEGORY);
    /**
     * The host key: back to the game from the desktop, and with another key a command. Only read when the desktop
     * opens; Minecraft never acts on it. Settable: many new laptops have no Right Ctrl (a Copilot key sits there).
     */
    static final KeyBinding HOST_KEY = new KeyBinding("key.desktopscreens.host", Keyboard.KEY_RCONTROL, CATEGORY);

    /** Keys in hints get their own color, so they stand out from the words (1.12.2 has 16 text colors). */
    private static final TextFormatting KEY_COLOR = TextFormatting.YELLOW;
    /** Minecraft 1.12.2 numbers mouse buttons in its key bindings from -100 (left). */
    private static final int MOUSE_LEFT = -100, MOUSE_RIGHT = -99, MOUSE_MIDDLE = -98;

    /** The tablet was used; open once the use button (right-click) is let go. */
    private static boolean tabletPending;

    private DesktopClient() {}

    static void init() {
        ClientRegistry.registerKeyBinding(OPEN_KEY);
        ClientRegistry.registerKeyBinding(SETTINGS_KEY);
        ClientRegistry.registerKeyBinding(PIP_KEY);
        ClientRegistry.registerKeyBinding(HOST_KEY);
        ClientRegistry.bindTileEntitySpecialRenderer(ScreenBlockEntity.class, new ScreenRenderer());
        // The server's sharing messages arrive on the network thread: on to the game's thread, except the receipts
        // (which only note a number) and the sound (which goes on to the sound engine's own thread).
        Minecraft mc = Minecraft.getMinecraft();
        ShareStream.client = new ShareStream.Client() {
            @Override
            public void status(ShareStream.Status status) {
                mc.addScheduledTask(() -> {
                    ShareStreamer.status(status);
                    ShareSound.status(status);
                });
            }

            @Override
            public void taken(ShareStream.Taken taken) {
                ShareStreamer.taken(taken);
            }

            @Override
            public void frame(ShareStream.Frame frame) {
                mc.addScheduledTask(() -> SharedPictures.frame(frame));
            }

            @Override
            public void lists(ScreenSharing.Lists lists) {
                mc.addScheduledTask(() -> SharingInfo.received(lists));
            }

            @Override
            public void keyframe(ShareStream.Keyframe keyframe) {
                mc.addScheduledTask(() -> ShareStreamer.keyframe(keyframe));
            }

            @Override
            public void sound(ShareStream.Sound sound) {
                SharedSounds.sound(sound);
            }

            @Override
            public void end(ShareStream.End end) {
                mc.addScheduledTask(() -> SharedPictures.end(end));
            }
        };
        H264Support.check();
    }

    static void tabletUsed() {
        tabletPending = true;
    }

    static void tick(Minecraft mc) {
        FocusGuard.tick(mc);
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
        if (mc.world == null) {
            LiveCapture.worldLeft();
            SharingInfo.clear();
            BuilderRules.reset(); // the next server says its own
        }
        while (OPEN_KEY.isPressed()) {
            if (mc.currentScreen != null) continue;
            ScreenGroups.Group screen = ScreenAim.usable();
            if (screen != null) useScreen(mc, screen);
            else if (hasTablet(mc)) openDesktop(mc);
            // Else nothing, and quietly: the desktop comes with a tablet or a screen, not with the key alone, and big
            // packs often have G on something else too.
        }
        // Not right away: the mouse moves onto the desktop when it opens, and letting go of the right button there
        // would be a right-click release in some app (on the desktop itself, that opens its context menu).
        if (tabletPending && !mc.gameSettings.keyBindUseItem.isKeyDown()) {
            tabletPending = false;
            if (mc.currentScreen == null) openDesktop(mc);
        }
        while (SETTINGS_KEY.isPressed()) {
            if (mc.currentScreen == null) mc.displayGuiScreen(settingsScreen(null));
        }
        while (PIP_KEY.isPressed()) {
            if (mc.currentScreen == null) PictureInPicture.toggle(mc);
        }
    }

    /** Whether the player carries a tablet anywhere in their inventory (the off hand too), so G opens the desktop. */
    static boolean hasTablet(Minecraft mc) {
        if (mc.player == null) return false;
        InventoryPlayer inventory = mc.player.inventory;
        for (int i = 0; i < inventory.getSizeInventory(); i++) {
            if (inventory.getStackInSlot(i).getItem() instanceof TabletItem) return true;
        }
        return false;
    }

    private static void openDesktop(Minecraft mc) {
        if (!Win32.SUPPORTED) {
            mc.ingameGUI.getChatGUI().printChatMessage(new TextComponentTranslation("desktopscreens.error.windows_only"));
            return;
        }
        DesktopConfig.load();
        try {
            mc.displayGuiScreen(DesktopScreen.desktop(DesktopSession.open(mc), null));
        } catch (RuntimeException | LinkageError e) {
            DesktopScreens.LOG.error("Couldn't open the desktop", e);
            mc.ingameGUI.getChatGUI().printChatMessage(new TextComponentTranslation("desktopscreens.error.open", String.valueOf(e.getMessage())));
        }
    }

    /** G while looking at a screen in the world that's on for this player. */
    private static void useScreen(Minecraft mc, ScreenGroups.Group screen) {
        if (!Win32.SUPPORTED) {
            mc.ingameGUI.getChatGUI().printChatMessage(new TextComponentTranslation("desktopscreens.error.windows_only"));
            return;
        }
        DesktopConfig.load();
        try {
            // What this screen shows, and changing it (Right Ctrl + M, + W, the settings) changes only this screen.
            mc.displayGuiScreen(DesktopScreen.onScreen(DesktopSession.open(mc, SourceChoice.forUsing(mc.world, screen)), screen));
        } catch (RuntimeException | LinkageError e) {
            DesktopScreens.LOG.error("Couldn't open the desktop", e);
            mc.ingameGUI.getChatGUI().printChatMessage(new TextComponentTranslation("desktopscreens.error.open", String.valueOf(e.getMessage())));
        }
    }

    /**
     * The text under the crosshair: the Screen Builder's while building or changing a screen, else the builder's keys
     * for a screen looked at, else the hint for a screen looked at, else how to start with the builder. Drawn after the
     * rest of the HUD.
     */
    static void renderHud(Minecraft mc, int width, int height) {
        PictureInPicture.renderAfterHud(mc); // only if it wasn't drawn under the HUD
        if (!mc.gameSettings.hideGUI) ShareStreamer.renderNotice(mc, width);
        if (!ScreenBuilderClient.renderHud(mc, width, height) && !ScreenBuilderClient.renderScreenHint(mc, width, height)
                && !ScreenAim.renderHud(mc, width, height)) {
            ScreenBuilderClient.renderStartHint(mc, width, height);
        }
    }

    /**
     * A mouse button pressed while playing: the builder takes the ones bound to use and attack while it's held, and an
     * empty hand's right-click on a screen shared with us mutes or hides it. True if one took it, so Minecraft does
     * nothing with it.
     */
    static boolean mouseButton(Minecraft mc, int button) {
        int code = button - 100; // how key bindings number mouse buttons
        if (mc.gameSettings.keyBindUseItem.getKeyCode() == code) return use();
        if (mc.gameSettings.keyBindAttack.getKeyCode() == code) return ScreenBuilderClient.onAttack();
        return false;
    }

    /** The use button or key: the builder's, or muting a shared screen. True if one took it. */
    static boolean use() {
        return ScreenBuilderClient.onUse() || WatchChoices.onUse();
    }

    /** The settings, with a live preview of the desktop. Also what the mods list's config button opens. */
    static GuiScreen settingsScreen(GuiScreen parent) {
        Minecraft mc = Minecraft.getMinecraft();
        ITextComponent title = new TextComponentTranslation("desktopscreens.settings.title");
        if (!Win32.SUPPORTED) return new GuiErrorScreen(title.getFormattedText(), I18n.format("desktopscreens.error.windows_only"));
        DesktopConfig.load();
        try {
            return DesktopScreen.settings(DesktopSession.open(mc), parent);
        } catch (RuntimeException | LinkageError e) {
            DesktopScreens.LOG.error("Couldn't open the desktop", e);
            return new GuiErrorScreen(title.getFormattedText(), I18n.format("desktopscreens.error.open", String.valueOf(e.getMessage())));
        }
    }

    /** A key as the hints show it, before what it does: its name in brackets, in the key color, like "[G]". */
    static ITextComponent key(ITextComponent name) {
        ITextComponent key = new TextComponentTranslation("desktopscreens.key", name);
        key.getStyle().setColor(KEY_COLOR);
        return key;
    }

    /** A Minecraft key as it's bound now, like "[G]" or "[RMB]". */
    static ITextComponent key(KeyBinding binding) {
        return key(keyName(binding));
    }

    /** Two Minecraft keys together, like "[LSHIFT + RMB]". */
    static ITextComponent key(KeyBinding held, KeyBinding then) {
        return key(new TextComponentTranslation("desktopscreens.key.together", keyName(held), keyName(then)));
    }

    /** The host key: "[Right Ctrl]". */
    static ITextComponent hostKey() {
        return key(hostKeyName());
    }

    /** A host key command on its own: "[Right Ctrl + W]". */
    static ITextComponent hostKey(String then) {
        return key(new TextComponentTranslation("desktopscreens.key.host_plus", hostKeyName(), then));
    }

    /**
     * The host key's name, without brackets: "Right Ctrl" (shorter than the key's own name, so the hints fit), or the
     * name Controls shows for the key picked there. Right Ctrl also when it can't be used (see {@link #hostScancode}).
     */
    static ITextComponent hostKeyName() {
        if (hostScancode() <= 0 || HOST_KEY.getKeyCode() == Keyboard.KEY_RCONTROL) {
            return new TextComponentTranslation("desktopscreens.key.host");
        }
        return new TextComponentString(HOST_KEY.getDisplayName());
    }

    /**
     * Where the host key is on the keyboard, for {@link com.desktopscreens.core.DesktopInput#setHostKey}: its scan code
     * as Windows has it (+0x100 for extended keys). LWJGL 2's key codes are DirectInput's, which are the scan codes
     * with 0x80 for extended keys, except Pause and Num Lock, which Windows numbers the other way round. 0 means Right
     * Ctrl: unbound, or a mouse button (the keyboard hook can't see those).
     */
    static int hostScancode() {
        int code = HOST_KEY.getKeyCode();
        if (code <= 0 || code > 0xFF) return 0;
        if (code == Keyboard.KEY_PAUSE) return 0x45;
        if (code == Keyboard.KEY_NUMLOCK) return 0x145;
        return (code & 0x80) != 0 ? 0x100 | code & 0x7F : code;
    }

    /** A host key command in a hint that has just named the host key: "[+O]". */
    static ITextComponent plus(ITextComponent then) {
        return key(new TextComponentTranslation("desktopscreens.key.plus", then));
    }

    static ITextComponent plus(String then) {
        return plus(new TextComponentString(then));
    }

    /** A key's name, like "G": mouse buttons as "LMB", "RMB" and "MMB". Follows rebinding. */
    private static ITextComponent keyName(KeyBinding binding) {
        switch (binding.getKeyCode()) {
            case MOUSE_LEFT: return new TextComponentTranslation("desktopscreens.key.mouse_left");
            case MOUSE_RIGHT: return new TextComponentTranslation("desktopscreens.key.mouse_right");
            case MOUSE_MIDDLE: return new TextComponentTranslation("desktopscreens.key.mouse_middle");
            default: return new TextComponentString(binding.getDisplayName());
        }
    }

    /** The host key was tapped. Called on the input hook thread, so hop over to the game thread. */
    static void onHostKey() {
        Minecraft mc = Minecraft.getMinecraft();
        mc.addScheduledTask(() -> {
            if (mc.currentScreen instanceof DesktopScreen) ((DesktopScreen) mc.currentScreen).close();
        });
    }

    /** You switched to Minecraft yourself while using the desktop (Alt+Tab, its taskbar button). Input hook thread. */
    static void onGameChosen() {
        Minecraft mc = Minecraft.getMinecraft();
        mc.addScheduledTask(() -> {
            if (mc.currentScreen instanceof DesktopScreen) ((DesktopScreen) mc.currentScreen).onGameChosen();
        });
    }

    /** A key was pressed while the host key was held. Called on the input hook thread. */
    static void onHostCombo(int virtualKey) {
        Minecraft mc = Minecraft.getMinecraft();
        mc.addScheduledTask(() -> {
            if (mc.currentScreen instanceof DesktopScreen) ((DesktopScreen) mc.currentScreen).onHostCombo(virtualKey);
        });
    }

    /** The mouse wheel turned while the host key was held. Called on the input hook thread. */
    static void onHostWheel(int delta) {
        Minecraft mc = Minecraft.getMinecraft();
        mc.addScheduledTask(() -> {
            if (mc.currentScreen instanceof DesktopScreen) ((DesktopScreen) mc.currentScreen).onHostWheel(delta);
        });
    }
}
