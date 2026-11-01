package com.desktopscreens.client;

import com.desktopscreens.DesktopScreens;
import com.desktopscreens.core.AppWindow;
import com.desktopscreens.core.InjectedInput;
import com.desktopscreens.core.Win32;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * Real Windows input from a laser on a picture of the desktop: the cursor follows it, its buttons press and let go
 * there, its keys and characters type. For the VR panel view ({@link DesktopScreen}), where Vivecraft's laser, buttons
 * and keyboard arrive as a Minecraft screen's mouse and key events, and for screens pointed at in the world. With the
 * test switch the laser is the tester's real mouse, which changes how clicks and keys go out ({@link #testClick}).
 * A point is a desktop pixel {x, y}, or null off the picture. One per use of the desktop. Game thread.
 */
final class LaserInput {
    private final Minecraft mc;
    /** Where the laser last put the real cursor, so a laser held still doesn't pin it (a real mouse can move it too). */
    private int[] laserAt;
    /** Mouse buttons (0 left, 1 right, 2 middle) and keys (Windows virtual keys) pressed on the PC and not let go yet. */
    private final boolean[] pressed = new boolean[3];
    private final Set<Integer> keysPressed = new HashSet<>();
    /** With the test switch: the window last clicked, which the keys typed after it go to; 0 for none. */
    private long lastClicked;
    /** With the test switch: where each button went down on the picture; the whole click goes once it comes up. */
    private final int[][] testClicks = new int[3][];
    /** Those with a button down on the PC or waiting as a test click ({@link #anyHeld}). */
    private static final Set<LaserInput> holding = Collections.newSetFromMap(new IdentityHashMap<>());

    LaserInput(Minecraft mc) {
        this.mc = mc;
    }

    /**
     * A desktop point the laser may act on, or null: off the picture, or where the game window covers the desktop
     * while it doesn't let clicks through (input sent there would come straight back to the game, and again).
     */
    static int[] target(Minecraft mc, int[] p, boolean clickingThrough) {
        if (p == null || !clickingThrough && Win32.windowContains(GameWindow.hwnd(mc), p[0], p[1])) return null;
        return p;
    }

    /**
     * Minecraft's mouse button as the PC's: 0 left, 1 right, 2 middle, or -1. By Minecraft's own names, since the
     * numbers differ: 26.3 counts as SDL does (left 1, middle 2, right 3), and so does Vivecraft for it.
     */
    static int pcButton(int button) {
        if (button == InputConstants.MOUSE_BUTTON_LEFT) return 0;
        if (button == InputConstants.MOUSE_BUTTON_RIGHT) return 1;
        if (button == InputConstants.MOUSE_BUTTON_MIDDLE) return 2;
        return -1;
    }

    /**
     * A button is down on the PC through a laser, or waiting to go as a test click: a one-window picture doesn't zoom
     * for a menu meanwhile ({@link com.desktopscreens.core.MenuArea}), as the zoom would move the cursor under a still
     * laser and letting go would pick whatever is there by then (2026-11-01).
     */
    static boolean anyHeld() {
        return !holding.isEmpty();
    }

    private void updateHeld() {
        boolean held = false;
        for (int i = 0; i < pressed.length; i++) held |= pressed[i] || testClicks[i] != null;
        if (held) holding.add(this);
        else holding.remove(this);
    }

    /** The laser moved: the real cursor follows while it's on the picture. Not with the test switch (one real mouse). */
    void move(int[] p) {
        if (VrMode.testing() || p == null || Arrays.equals(p, laserAt)) return;
        InjectedInput.moveTo(p[0], p[1]);
        laserAt = p;
    }

    /** A button (0 left, 1 right, 2 middle) pressed with the laser at {@code p}; off the picture, nothing. */
    void press(int button, int[] p) {
        if (p == null || button < 0) return;
        if (VrMode.testing()) {
            // The tester's real button is down now, and the game window holds the mouse until it comes up (GLFW
            // captures it on Windows): anything sent meanwhile would go to the game. The click goes on release.
            testClicks[button] = p;
        } else {
            InjectedInput.buttonAt(button, true, p[0], p[1]);
            pressed[button] = true;
            laserAt = p;
        }
        updateHeld();
    }

    /** A button let go: on the PC too, if it was pressed there; where the cursor is, once the laser left the picture. */
    void release(int button, int[] p) {
        if (button < 0) return;
        if (VrMode.testing()) {
            int[] at = testClicks[button];
            testClicks[button] = null;
            updateHeld();
            if (at != null) testClick(button, at);
            return;
        }
        if (!pressed[button]) return;
        pressed[button] = false;
        updateHeld();
        if (p != null) InjectedInput.buttonAt(button, false, p[0], p[1]);
        else InjectedInput.button(button, false);
    }

    /**
     * With the test switch: one real mouse, the tester's, so a whole click at once, then the cursor back over the
     * game. Holding the button instead would drag in the app as the cursor came back, or leave it held: no dragging.
     * <p>
     * Fast clicks went wrong twice (2026-10-29), as the game handles a click a frame or more after it happened: the
     * next press could come in the same frame as this release, while the earlier click's moves were still on their way,
     * so "where the cursor is" read the other monitor and the cursor stayed there; and a real button already down again
     * keeps the mouse with the game window, which takes whatever is sent meanwhile. So: nothing is sent while a real
     * button is down (that click is skipped), and each click is done, the cursor back where it was, before the next.
     * Where it was is read now, on release, so after a drag it stays where the tester let go.
     */
    private void testClick(int button, int[] p) {
        if (InjectedInput.anyButtonDown()) {
            DesktopScreens.LOG.info("VR panel test: button {} at {},{} skipped: a mouse button is down again already", button, p[0], p[1]);
            return;
        }
        int[] from = Win32.cursorPos();
        boolean sent = InjectedInput.buttonAt(button, true, p[0], p[1]) & InjectedInput.buttonAt(button, false, p[0], p[1]);
        AppWindow clicked = AppWindow.under(p[0], p[1]);
        lastClicked = clicked != null ? clicked.handle() : 0;
        backTo(from);
        DesktopScreens.LOG.info("VR panel test: button {} clicked at {},{}{}", button, p[0], p[1], sent ? "" : ", but Windows refused it");
    }

    /** With the test switch: the cursor back where it was, once Windows has handled what was sent (see {@link #testClick}). */
    private static void backTo(int[] from) {
        if (from == null) return;
        InjectedInput.moveTo(from[0], from[1]);
        InjectedInput.waitForCursor(from[0], from[1], 100);
    }

    /** The wheel turned with the laser at {@code p}; off the picture, nothing. */
    void scroll(double amount, int[] p) {
        if (p == null || VrMode.testing() && InjectedInput.anyButtonDown()) return;
        int[] from = VrMode.testing() ? Win32.cursorPos() : null;
        InjectedInput.wheelAt(amount, p[0], p[1]);
        backTo(from);
    }

    /**
     * A key (a Windows virtual key, see {@link PanelKeys}) from Vivecraft's keyboard. Letters and digits arrive again as
     * characters ({@link #character}), so they're sent as keys only with Ctrl or Alt held, for shortcuts.
     */
    void key(int vk, boolean down) {
        if (vk == 0) return;
        if (VrMode.testing()) {
            // The tester's real keyboard: once a key brought the app forward, the game never hears it let go, so a
            // key goes as a whole tap at once, and Shift, Ctrl and Alt not at all (they'd stay held on the PC).
            if (down && !PanelKeys.isCharacter(vk) && !PanelKeys.isModifier(vk) && mayType()) {
                InjectedInput.key(vk, true);
                InjectedInput.key(vk, false);
            }
            return;
        }
        if (!down) {
            if (keysPressed.remove(vk)) InjectedInput.key(vk, false);
            return;
        }
        if (PanelKeys.isCharacter(vk) && !ctrlOrAltHeld() || !mayType()) return;
        keysPressed.add(vk);
        InjectedInput.key(vk, true); // a held key's repeats too, as Windows' own
    }

    /** A character typed on Vivecraft's keyboard. */
    void character(int codePoint) {
        if (codePoint < 32 || ctrlOrAltHeld() || !mayType()) return;
        for (char c : Character.toChars(codePoint)) InjectedInput.typeChar(c);
    }

    /** Ctrl or Alt is held on the PC by this laser's keys. */
    boolean ctrlOrAltHeld() {
        for (int vk : keysPressed) {
            if (PanelKeys.isCtrlOrAlt(vk)) return true;
        }
        return false;
    }

    /**
     * Keys go to the window in front, and never back into the game, which would send them on again. With the test
     * switch the game is in front after every click on it, so the window clicked last comes forward first.
     */
    private boolean mayType() {
        if (Win32.foregroundWindow() != GameWindow.hwnd(mc)) return true;
        return VrMode.testing() && lastClicked != 0 && InjectedInput.bringForward(lastClicked);
    }

    /** Lets go of everything pressed on the PC, so nothing stays held after the desktop closes. */
    void releaseAll() {
        InjectedInput.releaseAll();
        Arrays.fill(pressed, false);
        Arrays.fill(testClicks, null);
        keysPressed.clear();
        updateHeld();
    }
}
