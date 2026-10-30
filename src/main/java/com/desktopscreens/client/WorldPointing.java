package com.desktopscreens.client;

import com.desktopscreens.DesktopScreens;
import com.desktopscreens.core.AppWindow;
import com.desktopscreens.core.Monitor;
import com.desktopscreens.core.PanelMapping;
import com.desktopscreens.mixin.GameRendererAccessor;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.Camera;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3fc;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Using a screen in the world by pointing at it, in VR with controllers ("VR screens: In the world", {@link VrMode#pointing}):
 * no Minecraft menu opens, so the player stays in the world. While the main hand's laser is on the screen's picture,
 * the real cursor goes to that spot on the PC; Minecraft's attack and use buttons click there (left, right), the right
 * stick scrolls, Vivecraft's keyboard types; the menu button goes back to the game, and the bar under the screen
 * ({@link WorldBar}) has the panel's commands. Those buttons never act in the game meanwhile, also when pointing away
 * ("screen only", 2026-10-30), and the screen can't be broken or switched off by hand; walking, turning and
 * the rest work as usual. With the test switch the mouse is the laser, aimed through the game view. The hooks are
 * called from mixins on Minecraft's mouse, keyboard, chat and block breaking, and swallow what they return true for.
 * Game thread.
 */
public final class WorldPointing {
    /** How far the laser reaches, and how far the player may walk from the screen before this ends: as "Use the screen". */
    private static final double RANGE = 32;
    /** Right stick: how far it must be pushed to scroll, and how many wheel notches a second it sends at full tilt. */
    private static final float STICK_DEAD_ZONE = 0.3f, STICK_NOTCHES_PER_SECOND = 8;

    private static ScreenGroups.Group group;
    private static Level level;
    /** Its blocks, to tell it apart from a screen joined, split or resized since (the groups are made anew then). */
    private static Set<BlockPos> members;
    private static DesktopSession session;
    private static LaserInput laser;
    /** Where the laser met the glass this frame, {col, row}, or null; and the desktop pixel there, or null off the picture. */
    private static double[] hit;
    private static int[] point;
    private static double stickNotches;
    private static long lastFrameNanos;
    /** Keys and mouse buttons whose press was kept from the game: their repeats and release are kept too ({@link #onKey}). */
    private static final Set<Integer> keysTaken = new HashSet<>(), buttonsTaken = new HashSet<>();
    /** Whether the last key pressed was kept from the game: with the test switch, the character it types follows it. */
    private static boolean lastKeyTaken;
    /** The command bar under the screen ({@link WorldBar}), and its item under the laser this frame, or -1. */
    private static WorldBar bar;
    private static int barAt = -1;
    /** The blocks that draw the bar under the screen and over it, and whether it goes over it ({@link #barAbove}). */
    private static BlockPos bottomBlock, topBlock;
    private static boolean barAbove;
    /** Laser buttons (0 left, 1 right) pressed on the bar or to pick a window: their release never reaches the PC either. */
    private static final boolean[] notForPc = new boolean[2];
    /** A menu is open over the world, since the last tick: pointing waits, and nothing stays pressed on the PC meanwhile. */
    private static boolean menuOpen;

    private WorldPointing() {}

    public static boolean active() {
        return group != null;
    }

    /** With the test switch the mouse is the laser, so the game doesn't take it while pointing. */
    public static boolean keepsMouseFree() {
        return group != null && VrMode.testing();
    }

    /** The screen being pointed at, or null. */
    static ScreenGroups.Group group() {
        return group;
    }

    static void start(Minecraft mc, ScreenGroups.Group screen) {
        if (group != null) stop("another screen");
        DesktopConfig.load();
        try {
            session = DesktopSession.open(mc, SourceChoice.forUsing(mc.level, screen));
        } catch (RuntimeException | LinkageError e) {
            DesktopScreens.LOG.error("Couldn't open the desktop", e);
            mc.gui.getChat().addMessage(Component.translatable("desktopscreens.error.open", String.valueOf(e.getMessage())));
            return;
        }
        group = screen;
        level = mc.level;
        members = new HashSet<>(screen.members);
        laser = new LaserInput(mc);
        keysTaken.clear();
        buttonsTaken.clear();
        lastKeyTaken = false;
        bar = new WorldBar();
        bottomBlock = barBlock(screen, screen.minRow);
        topBlock = barBlock(screen, screen.minRow + screen.rows - 1);
        barAbove = barAbove(mc.level, screen);
        bar.showHint();
        barAt = -1;
        menuOpen = false;
        Arrays.fill(notForPc, false);
        session.setPointing(true);
        session.startInput();
        FocusHandOff.begin(mc); // no pausing when an app on the PC takes the focus
        VrMode.quietTypingNote(true); // typing with no menu open is what this is for
        if (VrMode.testing()) mc.mouseHandler.releaseMouse(); // the mouse stands in for the laser
        DesktopScreens.LOG.info("Pointing at a screen in the world: {}", VrMode.describe());
    }

    static void stop(String why) {
        if (group == null) return;
        group = null; // first: its settings, if open, close to the game then, not back to pointing (see resume)
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof DesktopScreen screen && screen.whilePointing()) mc.setScreen(null);
        laser.releaseAll();
        session.stopInput();
        session.close();
        FocusHandOff.end(); // grabs the mouse again once the game has the focus (the test switch)
        VrMode.quietTypingNote(false);
        DesktopConfig.save();
        level = null;
        members = null;
        session = null;
        laser = null;
        bar = null;
        barAt = -1;
        bottomBlock = null;
        topBlock = null;
        hit = null;
        point = null;
        stickNotches = 0;
        DesktopScreens.LOG.info("Stopped pointing at the screen: {}", why);
    }

    /**
     * The settings of the screen pointed at (the bar's Settings, the settings key, Right Ctrl + O): Minecraft's menu
     * takes the laser meanwhile, and closing it goes back to pointing ({@link #resume}).
     */
    static void openSettings(Minecraft mc) {
        if (group == null || mc.screen != null) return;
        laser.releaseAll();
        session.stopInput();
        bar.setPicking(WorldBar.Pick.NONE);
        mc.setScreen(DesktopScreen.settingsWhilePointing(session, group));
    }

    /**
     * Back from the settings ({@link DesktopScreen#settingsWhilePointing}): the PC's input again. If they put VR screens
     * on a panel, the next tick ends this instead (not from here: the settings screen is still closing).
     */
    static void resume() {
        if (group == null || !VrMode.pointing()) return;
        session.startInput();
    }

    /** Every client tick: ends when the screen can't be used any more. */
    static void tick(Minecraft mc) {
        if (group == null) return;
        if (mc.screen == null) {
            menuOpen = false;
        } else if (!menuOpen) {
            // A menu took over (the inventory, the chat, a mod's): it gets the releases of what's held now, so let go of
            // it all on the PC, or a button held there would drag with the laser afterwards (2026-10-30).
            menuOpen = true;
            laser.releaseAll();
            Arrays.fill(notForPc, false);
            keysTaken.clear();
            buttonsTaken.clear();
        }
        session.trackWindow(); // the one window closed or moved meanwhile
        if (session.takeWindowClosed()) bar.flash(Component.translatable("desktopscreens.hint.window_closed"));
        String why = null;
        if (mc.level == null || mc.player == null || mc.level != level) why = "left the world";
        else if (!mc.player.isAlive()) why = "died";
        else if (!VrMode.pointing() && mc.screen == null) why = "VR stopped, or VR screens are on a panel now"; // not in its settings
        else if (!sameScreen(ScreenGroups.of(mc.level, group.anchor())) || !mc.player.getUUID().equals(group.owner(mc.level))) {
            why = "the screen was turned off, changed or broken";
        } else if (group.nearestPoint(mc.player.getEyePosition()).distanceTo(mc.player.getEyePosition()) > RANGE) {
            why = "more than " + (int) RANGE + " blocks away";
        }
        if (why != null) stop(why);
        else barAbove = barAbove(mc.level, group); // blocks placed or broken meanwhile
    }

    /**
     * Whether {@code now} (the group at the screen's anchor) is still the screen in use: the same blocks. The groups are
     * made anew whenever any screen changes, so it's taken over then, for its fresh owner.
     */
    private static boolean sameScreen(ScreenGroups.Group now) {
        if (now == null || now.members.size() != members.size() || !members.containsAll(now.members)) return false;
        group = now;
        return true;
    }

    /**
     * Each rendered frame: the laser's spot on the picture or the bar under it, the real cursor there, and the stick's
     * scrolling. The bar was drawn already, with the last frame's spot.
     */
    static void frame(Minecraft mc) {
        long now = System.nanoTime();
        double seconds = lastFrameNanos == 0 ? 0 : Math.min(0.1, (now - lastFrameNanos) / 1e9);
        lastFrameNanos = now;
        if (group == null) return;
        if (mc.screen != null) { // the inventory, the settings...: paused while a menu is open
            hit = null;
            point = null;
            barAt = -1;
            bar.setHover(-1);
            return;
        }
        if (!VrMode.testing() && VrMode.keyboardShowing()) {
            // Typing on Vivecraft's keyboard, which floats in front: the cursor stays where it was, or it followed the spot
            // behind the keyboard, and menus and tooltips there came and went (2026-10-31). The stick still scrolls there.
            stickScroll(seconds);
            return;
        }
        double[] ray = VrMode.testing() ? mouseRay(mc) : VrMode.mainHandRay();
        hit = ray == null ? null : group.onGlass(new Vec3(ray[0], ray[1], ray[2]), new Vec3(ray[3], ray[4], ray[5]), RANGE);
        WorldScreens.Picture picture = hit == null ? null : WorldScreens.picture(mc, group);
        float[] rect = picture == null ? null : group.pictureRect(picture.cropW(), picture.cropH()); // {left, bottom, width, height}, rows up
        barAt = rect == null ? -1 : WorldBar.layout(mc.font, group, bar.items(session), barAbove).at(hit[0], hit[1]);
        point = rect == null || barAt >= 0 ? null : desktopPoint(mc, hit, rect);
        bar.setHover(barAt);
        if (bar.picking() != WorldBar.Pick.NONE) bar.setOutline(pickOutline());
        laser.move(point);
        if (!VrMode.testing()) stickScroll(seconds);
    }

    /** The desktop pixel under a spot of the glass, or null off the picture (or where the game window is in the way). */
    private static int[] desktopPoint(Minecraft mc, double[] spot, float[] rect) {
        double u = (spot[0] - rect[0]) / rect[2], v = 1 - (spot[1] - rect[1]) / rect[3];
        PanelMapping mapping = session.fractionMapping();
        return mapping == null ? null : LaserInput.target(mc, mapping.atFraction(u, v), session.clickingThrough());
    }

    /** While picking: the window under the laser, as shares of the picture ({@link WorldBar#setOutline}); null for none. */
    private static double[] pickOutline() {
        AppWindow w = point != null ? AppWindow.under(point[0], point[1]) : null;
        int[] r = w != null ? w.rectOn(session.target()) : null; // {left, top, right, bottom}
        PanelMapping mapping = session.fractionMapping();
        return r == null || mapping == null ? null : mapping.toWindowRect(r[0], r[1], r[2] - r[0], r[3] - r[1]);
    }

    /**
     * What the bar draws this frame, for the block at {@code pos} of a screen whose picture is at {@code rect}: null but
     * for the block that draws it ({@link #barBlock}), on every pass (each eye's, in VR).
     */
    static WorldBar.Frame barFrame(Minecraft mc, ScreenGroups.Group screen, BlockPos pos, float[] rect) {
        if (group == null || !pos.equals(barAbove ? topBlock : bottomBlock)) return null;
        return bar.frame(mc.font, session, screen, barAbove, rect);
    }

    /**
     * The block that draws the bar: in the row next to it (the screen's bottom or top), the nearest to the middle, so
     * it's in view whenever the bar is (Minecraft draws the blocks of the parts of the world in view).
     */
    private static BlockPos barBlock(ScreenGroups.Group screen, int row) {
        double middle = screen.minCol + (screen.cols - 1) / 2.0;
        BlockPos best = null;
        for (BlockPos p : screen.members) {
            if (screen.row(p) != row) continue;
            if (best == null || Math.abs(screen.col(p) - middle) < Math.abs(screen.col(best) - middle)) best = p;
        }
        return best;
    }

    /**
     * Whether the bar goes over the screen: when solid blocks are right under it (a screen standing on the floor hid
     * the bar's text, Test 58b, 2026-10-30) and none right over it. Under it otherwise, also when both are.
     */
    private static boolean barAbove(Level level, ScreenGroups.Group screen) {
        return solidRow(level, screen, screen.minRow - 1) && !solidRow(level, screen, screen.minRow + screen.rows);
    }

    private static boolean solidRow(Level level, ScreenGroups.Group screen, int row) {
        for (int col = screen.minCol; col < screen.minCol + screen.cols; col++) {
            if (level.getBlockState(screen.at(col, row)).canOcclude()) return true;
        }
        return false;
    }

    /** A click with the laser on the bar under the screen: its button's command. */
    private static void barClick(Minecraft mc, int item) {
        if (!bar.isButton(item)) return;
        if (bar.picking() != WorldBar.Pick.NONE) { // Cancel
            bar.setPicking(WorldBar.Pick.NONE);
            return;
        }
        switch (item) {
            case 0 -> bar.setPicking(WorldBar.Pick.PIP);
            case 1 -> {
                if (session.window() == null) bar.setPicking(WorldBar.Pick.WINDOW);
                else bar.flash(Commands.oneWindow(session, null)); // straight back to the whole monitor: nothing to pick
            }
            case 2 -> nextMonitor();
            case 3 -> openSettings(mc);
            default -> stop("Back to the game (the bar)");
        }
    }

    /** A click on the picture while picking: the window there (or the empty desktop, the whole monitor) for the command. */
    private static void pick(Minecraft mc) {
        if (point == null) return; // off the picture: waits for another
        AppWindow w = AppWindow.under(point[0], point[1]);
        bar.flash(bar.picking() == WorldBar.Pick.PIP ? Commands.pin(mc, session, w) : Commands.oneWindow(session, w));
        bar.setPicking(WorldBar.Pick.NONE);
    }

    /** Next monitor, as Right Ctrl + M: the whole of it, on this screen from now on. */
    private static void nextMonitor() {
        Monitor next = Commands.nextMonitor(session);
        if (next == null) {
            bar.flash(Component.translatable("desktopscreens.hint.one_monitor"));
            return;
        }
        Commands.setMonitor(session, next);
        bar.flash(Component.translatable("desktopscreens.hint.monitor", Commands.monitorLabel(session, session.target())));
    }

    /** With the test switch: from the camera through the mouse, which is free over the game window meanwhile. */
    private static double[] mouseRay(Minecraft mc) {
        //? if >=26.2 {
        /*Camera camera = mc.gameRenderer.mainCamera();
        *///?} else {
        Camera camera = mc.gameRenderer.getMainCamera();
        //?}
        //? if >=26.1 {
        /*Vec3 from = camera.position();
        Vector3fc look = camera.forwardVector(), up = camera.upVector(), left = camera.leftVector();
        *///?} else {
        Vec3 from = camera.getPosition();
        Vector3fc look = camera.getLookVector(), up = camera.getUpVector(), left = camera.getLeftVector();
        //?}
        Window window = mc.getWindow();
        double nx = 2 * mc.mouseHandler.xpos() / window.getScreenWidth() - 1, ny = 1 - 2 * mc.mouseHandler.ypos() / window.getScreenHeight();
        // The field of view the world is drawn with, not just the setting: flying and sprinting widen it.
        //? if >=26.1 {
        /*double fov = camera.getFov();
        *///?} else if >=1.21 {
        double fov = ((GameRendererAccessor) mc.gameRenderer).desktopscreens$fov(camera, camera.getPartialTickTime(), true);
        //?} else {
        /*double fov = ((GameRendererAccessor) mc.gameRenderer).desktopscreens$fov(camera, mc.getFrameTime(), true);
        *///?}
        double tanY = Math.tan(Math.toRadians(fov) / 2), tanX = tanY * window.getScreenWidth() / window.getScreenHeight();
        double x = look.x() + up.x() * ny * tanY - left.x() * nx * tanX;
        double y = look.y() + up.y() * ny * tanY - left.y() * nx * tanX;
        double z = look.z() + up.z() * ny * tanY - left.z() * nx * tanX;
        double length = Math.sqrt(x * x + y * y + z * z);
        return new double[] {from.x, from.y, from.z, x / length, y / length, z / length};
    }

    /** The right stick up or down, with the laser on the picture: the wheel, faster the further it's pushed. */
    private static void stickScroll(double seconds) {
        float stick = VrMode.rightStickY();
        if (point == null || Math.abs(stick) < STICK_DEAD_ZONE) {
            stickNotches = 0;
            return;
        }
        stickNotches += stick * STICK_NOTCHES_PER_SECOND * seconds;
        while (Math.abs(stickNotches) >= 1) {
            double notch = Math.signum(stickNotches);
            laser.scroll(notch, point);
            stickNotches -= notch;
        }
    }

    /** A key or button is bound to {@code mapping} (attack, use) as it's set in Controls. */
    private static boolean bound(KeyMapping mapping, InputConstants.Type type, int value) {
        InputConstants.Key key = InputConstants.getKey(mapping.saveString());
        return key.getType() == type && key.getValue() == value;
    }

    /**
     * Minecraft's attack (0, left click) or use (1, right click) pressed or let go: the PC's button where the laser is.
     * On the bar, or while picking a window, the press is a command's (the left button only) and nothing reaches the PC,
     * not even the release. A press on the picture let go over the bar is the PC's as usual: the bar acts on presses.
     */
    private static void attackOrUse(int pcButton, int action) {
        Minecraft mc = Minecraft.getInstance();
        if (action == InputConstants.PRESS) {
            notForPc[pcButton] = barAt >= 0 || bar.picking() != WorldBar.Pick.NONE;
            if (!notForPc[pcButton]) laser.press(pcButton, point);
            else if (pcButton == 0 && barAt >= 0) barClick(mc, barAt);
            else if (pcButton == 0) pick(mc);
        } else if (action == InputConstants.RELEASE) {
            if (notForPc[pcButton]) notForPc[pcButton] = false;
            else laser.release(pcButton, point);
        }
    }

    /**
     * A mouse button: the laser's attack and use buttons click the PC; every button pressed meanwhile is kept from the
     * game. One held since before pointing is let go in the game, or it would stay held there.
     */
    public static boolean onButton(int button, int action) {
        Minecraft mc = Minecraft.getInstance();
        if (group == null || mc.screen != null) return false;
        if (action == InputConstants.PRESS) buttonsTaken.add(button);
        else if (!buttonsTaken.remove(button)) return false;
        if (bound(mc.options.keyAttack, InputConstants.Type.MOUSE, button)) attackOrUse(0, action);
        else if (bound(mc.options.keyUse, InputConstants.Type.MOUSE, button)) attackOrUse(1, action);
        return true;
    }

    /** The mouse wheel (the test switch's stand-in for the stick): the PC's, never the hotbar's, meanwhile. */
    public static boolean onScroll(double amount) {
        Minecraft mc = Minecraft.getInstance();
        // Only with the test switch: in VR the right stick scrolls ({@link #stickScroll}), and the wheel turns that do
        // arrive are Vivecraft's grips changing the hotbar, which stay the game's (2026-10-30).
        if (group == null || mc.screen != null || !VrMode.testing()) return false;
        laser.scroll(amount, point);
        return true;
    }

    /**
     * A key, kept from the game when this returns true ({@link #takesKey}). Its repeats and release go where its press
     * went: a key held since before pointing, or pressed off the picture and let go on it, is the game's. Walking onto
     * the screen with W held, the game never heard W let go, and the walking went on (Test 57, 2026-10-30).
     */
    public static boolean onKey(int key, int action) {
        Minecraft mc = Minecraft.getInstance();
        if (group == null || mc.screen != null) return false;
        boolean taken;
        if (action == InputConstants.PRESS) {
            taken = takesKey(mc, key, action);
            if (taken && group != null) keysTaken.add(key); // not Escape or the host key, which have just ended this
            else keysTaken.remove(key); // let go in a menu last time
        } else {
            taken = action == InputConstants.RELEASE ? keysTaken.remove(key) : keysTaken.contains(key);
            if (taken) takesKey(mc, key, action);
        }
        if (action != InputConstants.RELEASE) lastKeyTaken = taken;
        return taken;
    }

    /**
     * A key, for {@link #onKey}. The menu button (Escape) and the host key go back to the game. The editing keys of
     * Vivecraft's keyboard, Ctrl and Alt, and letters and digits while one of those is held (its Cut, Copy and Paste)
     * go to the PC. Everything else is the game's as usual: Vivecraft presses the controllers' game keys as real keys
     * (Space jumps, Shift sneaks).
     */
    private static boolean takesKey(Minecraft mc, int key, int action) {
        boolean down = action != InputConstants.RELEASE;
        if (key == InputConstants.KEY_ESCAPE && bar.picking() != WorldBar.Pick.NONE) { // stops picking first, as on the panel
            if (action == InputConstants.PRESS) bar.setPicking(WorldBar.Pick.NONE);
            return true;
        }
        if (key == InputConstants.KEY_ESCAPE || DesktopClient.isHostKey(key)) {
            if (action == InputConstants.PRESS) stop(key == InputConstants.KEY_ESCAPE ? "the menu button (Escape)" : "the host key");
            return true;
        }
        // The mod's own keys work as usual, from a keyboard or Vivecraft's radial menu (which presses them as keys):
        // Use the screen again (back to the game), picture-in-picture and the settings.
        for (KeyMapping own : new KeyMapping[] {DesktopClient.OPEN_KEY, DesktopClient.PIP_KEY, DesktopClient.SETTINGS_KEY}) {
            if (bound(own, InputConstants.Type.KEYSYM, key)) return false;
        }
        if (bound(mc.options.keyAttack, InputConstants.Type.KEYSYM, key)) {
            if (action != InputConstants.REPEAT) attackOrUse(0, action);
            return true;
        }
        if (bound(mc.options.keyUse, InputConstants.Type.KEYSYM, key)) {
            if (action != InputConstants.REPEAT) attackOrUse(1, action);
            return true;
        }
        // Vivecraft's keyboard sends "/" as a key too, after the character, which would open the chat.
        if (key == InputConstants.KEY_SLASH) return true;
        int vk = PanelKeys.vk(key);
        if (vk == 0) return key == InputConstants.KEY_SPACE && testTyping(); // else the game's, like Space to jump
        if (PanelKeys.isCharacter(vk)) {
            if (!down) {
                laser.key(vk, false); // lets go of a shortcut's letter, if it went to the PC
                return testTyping();
            }
            if (!laser.ctrlOrAltHeld()) return testTyping();
        } else if (PanelKeys.isModifier(vk) && !PanelKeys.isCtrlOrAlt(vk)) {
            return false; // Shift: sneaking
        }
        laser.key(vk, down);
        return true;
    }

    /**
     * A character from Vivecraft's keyboard: typed on the PC, wherever the laser is (it's on the keyboard while typing).
     * With the test switch it comes from a real key just pressed or repeating, and goes where that key went: the real
     * keyboard plays the game too (WASD), so a W held while walking onto the screen doesn't type there.
     */
    public static boolean onChar(int codePoint) {
        Minecraft mc = Minecraft.getInstance();
        if (group == null || mc.screen != null) return false;
        if (VrMode.testing() && !lastKeyTaken) return false;
        laser.character(codePoint);
        return true;
    }

    /**
     * With the test switch the real keyboard both types (its characters) and plays (its keys): with the mouse on the
     * picture its letters, digits and Space are kept from the game, or typing "e" would open the inventory. In VR,
     * Vivecraft's keyboard sends letters as characters only, and the controllers' game keys must stay the game's.
     */
    private static boolean testTyping() {
        return VrMode.testing() && point != null;
    }

    /** The screen in use can't be broken or switched by hand meanwhile (Vivecraft's swing and touch-and-trigger). */
    public static boolean protects(BlockPos pos) {
        return group != null && members.contains(pos);
    }

    /** Vivecraft's chat note about typing with no menu open: that's what pointing is for, so it's left out meanwhile. */
    public static boolean dropsMessage(Component message) {
        return group != null && message.getContents() instanceof TranslatableContents t && "vivecraft.messages.airtypingwarning".equals(t.getKey());
    }

    /**
     * Right Ctrl + P, + W, + M and + O from a real keyboard, and the picture-in-picture key: as on the panel, P and W
     * for the window under the cursor. The message shows under the bar.
     */
    static void onHostCombo(Minecraft mc, int virtualKey) {
        if (group == null) return;
        AppWindow w = session.windowUnderPointer();
        switch (virtualKey) {
            case 'P' -> bar.flash(Commands.pin(mc, session, w));
            case 'W' -> bar.flash(session.window() == null && w == null
                    ? Component.translatable("desktopscreens.hint.no_window", DesktopClient.hostKey("W")) // as on the panel
                    : Commands.oneWindow(session, session.window() != null ? null : w));
            case 'M' -> nextMonitor();
            case 'O' -> openSettings(mc);
            default -> {}
        }
    }
}
