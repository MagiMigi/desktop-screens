package com.desktopscreens.client;

import com.desktopscreens.DesktopScreens;
import com.desktopscreens.core.AppWindow;
import com.desktopscreens.core.Monitor;
import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import java.util.List;

/**
 * Picture-in-picture: one window or monitor, pinned with Right Ctrl + P in the desktop view, drawn small in a corner
 * of the game while playing and in menus like the inventory and chat, so a video or a chat can be followed without
 * a screen in the world. Behind Minecraft's own UI: the HUD and menus go over it. Shown and hidden with its own key
 * (none by default), on its settings page ({@code DesktopScreen}'s PiP page, with the corner, the
 * size and the gap to the edges; the picture can be dragged anywhere in its preview too), and turned off with
 * Right Ctrl + P over the same thing again. Like G, only with a Tablet in the inventory: without one it hides, and comes back when one is picked up.
 * Hidden while the desktop view or its settings are open (the desktop is right there) and with the rest of the HUD
 * (F1). What's pinned is remembered; the picture starts hidden after a restart. A pinned window is captured by itself
 * ({@link WorldScreens}, as for screens), so it shows while other windows cover it; once it's closed, its monitor
 * shows. Decided 2026-09-25 and 26. Client thread only.
 */
final class PictureInPicture {
    /** How long a menu counts as drawing it behind its buttons (not the HUD under it) after it last did. */
    private static final long MENU_NANOS = 250_000_000L;
    /** How close a dragged picture comes to an edge or the middle before it snaps there, in GUI units. */
    private static final int SNAP = 5;
    private static final double[] SNAPS = {0, 0.5, 1};

    /**
     * Where in the game window it sits: one of the corners, or CUSTOM, where it was dragged to on its settings page
     * (dragging, with the corners kept, 2026-09-27).
     */
    enum Corner {
        BOTTOM_RIGHT(1, 1), BOTTOM_LEFT(0, 1), TOP_RIGHT(1, 0), TOP_LEFT(0, 0), CUSTOM(-1, -1);

        /** How far along the room it has, left to right and top to bottom, 0 to 1 (CUSTOM: the dragged spot). */
        private final double x, y;

        Corner(double x, double y) {
            this.x = x;
            this.y = y;
        }

        double x() {
            return this == CUSTOM ? DesktopConfig.pipX : x;
        }

        double y() {
            return this == CUSTOM ? DesktopConfig.pipY : y;
        }

        /** The corner at {@code x, y}, or CUSTOM. */
        static Corner at(double x, double y) {
            for (Corner corner : values()) {
                if (corner != CUSTOM && corner.x == x && corner.y == y) return corner;
            }
            return CUSTOM;
        }

        /** The Corner button's choices: the four corners, and the dragged spot once there is one. Asked on every click. */
        static List<Corner> choices() {
            return DesktopConfig.pipX >= 0 ? List.of(values()) : List.of(BOTTOM_RIGHT, BOTTOM_LEFT, TOP_RIGHT, TOP_LEFT);
        }

        Component label() {
            return Component.translatable("desktopscreens.pip.corner." + name().toLowerCase(java.util.Locale.ROOT));
        }
    }

    private static boolean shown;
    /** The frame the HUD last asked for it under itself ({@code Minecraft.getFrameTimeNs}, new every frame). */
    private static long hudFrame;
    /** The menu that last drew it behind its buttons and panels, when, and in which frame; null after it closed. */
    private static Screen menu;
    private static long menuAt, menuFrame;
    /**
     * The settings preview as last drawn, in window pixels: its copy of the game window {x, y, w, h}, the margin in
     * it (the border included) and the picture {x, y, w, h}; null until drawn. What a drag on it goes by.
     */
    private static int[] previewGame, previewPicture;
    private static int previewMargin;
    /** A drag of the picture in the preview: where it was grabbed, from its top-left corner, and the dragged spot before. */
    private static boolean dragging;
    private static double grabX, grabY, beforeX, beforeY;

    private PictureInPicture() {}

    /** Whether it's on (it only draws with something pinned and a tablet on you, see {@link #render}). */
    static boolean shown() {
        return shown && DesktopConfig.pip != null;
    }

    /** From its settings page. */
    static void setShown(boolean show) {
        if (show == shown) return;
        shown = show;
        DesktopScreens.LOG.info("Picture-in-picture: {}", show ? "shown" : "hidden");
    }

    /**
     * Right Ctrl + P over a window (or, with none, the empty desktop of {@code monitor}): pins it and shows it. Returns
     * false if it was showing just that already, and hides it instead.
     */
    static boolean pinOrHide(AppWindow window, Monitor monitor) {
        DesktopConfig.load();
        if (showing(window, monitor)) {
            setShown(false);
            return false;
        }
        DesktopConfig.pip = new SourceChoice(null, monitor.name, window != null ? window.key() : "", window != null ? window.handle() : 0);
        DesktopConfig.save();
        shown = true;
        DesktopScreens.LOG.info("Picture-in-picture: showing {}", window != null ? window : monitor);
        return true;
    }

    /** Whether it shows exactly this now: this window, or with none, this whole monitor. */
    private static boolean showing(AppWindow window, Monitor monitor) {
        SourceChoice pinned = DesktopConfig.pip;
        if (!shown || pinned == null) return false;
        if (window != null) return window.handle() == pinned.handle || pinned.handle == 0 && window.key().equals(pinned.window);
        return pinned.window.isEmpty() && monitor.name.equals(pinned.monitor);
    }

    /** Its key, while playing: shows or hides it. */
    static void toggle(Minecraft mc) {
        DesktopConfig.load();
        if (DesktopConfig.pip == null) {
            mc.gui.setOverlayMessage(Component.translatable("desktopscreens.pip.nothing", DesktopClient.hostKey("P")), false);
        } else if (!shown && !DesktopClient.hasTablet(mc)) {
            mc.gui.setOverlayMessage(Component.translatable("desktopscreens.pip.needs_tablet"), false);
        } else {
            setShown(!shown);
        }
    }

    /**
     * Under the HUD: loaders call it before the crosshair, so the hotbar, chat and the rest of the HUD go over it. The
     * HUD is drawn under menus too; menus with a background of their own draw it themselves ({@link #renderBehindMenu}),
     * others (chat) leave it here. Minecraft's own UI always goes over it (2026-09-27).
     */
    static void renderUnderHud(Minecraft mc, GuiGraphics g) {
        hudFrame = mc.getFrameTimeNs();
        if (mc.screen != menu) menu = null; // don't keep a closed menu (and its world) alive
        if (!drawnByMenu(mc)) render(mc, g);
    }

    /** At the end of the HUD, in case something left out {@link #renderUnderHud} (a mod skipping the crosshair). */
    static void renderAfterHud(Minecraft mc, GuiGraphics g) {
        if (hudFrame != mc.getFrameTimeNs() && !drawnByMenu(mc)) render(mc, g);
    }

    /**
     * Once a menu has drawn its darkened or blurred background (the mixin on {@code Screen}; containers like the
     * inventory, the pause menu, most mods' menus): its panels, buttons and text go over it, and it isn't darkened or
     * blurred itself. Once a frame, and only for the menu that's open, not one drawn behind it.
     */
    static void renderBehindMenu(Minecraft mc, Screen screen, GuiGraphics g) {
        long frame = mc.getFrameTimeNs();
        if (screen != mc.screen || screen == menu && frame == menuFrame) return;
        menu = screen;
        menuAt = System.nanoTime();
        menuFrame = frame;
        render(mc, g);
    }

    /** Whether the open menu draws it (it drew its background lately), so the HUD under it doesn't. */
    private static boolean drawnByMenu(Minecraft mc) {
        return mc.screen != null && mc.screen == menu && System.nanoTime() - menuAt < MENU_NANOS;
    }

    /** Draws it where it goes, if it's showing. */
    private static void render(Minecraft mc, GuiGraphics g) {
        SourceChoice choice = DesktopConfig.pip;
        if (!shown || choice == null || mc.player == null || mc.level == null || mc.options.hideGui) return;
        if (mc.screen instanceof DesktopScreen || !DesktopClient.hasTablet(mc)) return;
        WorldScreens.Picture p = WorldScreens.picture(mc, choice);
        if (p == null || p.cropW() <= 0 || p.cropH() <= 0) return;
        // In window pixels, like the desktop view, so the picture lands on whole pixels.
        Window window = mc.getWindow();
        double scale = window.getGuiScale();
        int[] r = place(p.cropW(), p.cropH(), 0, 0, window.getWidth(), window.getHeight(),
                (int) Math.round(DesktopConfig.pipGap * scale) + BorderRenderer.thickness());
        g.flush(); // what was drawn before goes under it, not over it
        g.pose().pushPose();
        g.pose().scale((float) (1 / scale), (float) (1 / scale), 1);
        draw(g, p, r);
        g.pose().popPose();
    }

    /**
     * For its settings page: a small copy of the game window in the preview area ({@code x, y, w, h} in window
     * pixels), with the hotbar's place and the picture where it goes, at its size; a grey stand-in while nothing is
     * pinned. Live, whether it's on or not, so the corner and the size can be tried out. The picture can be dragged
     * anywhere in it ({@link #startDrag}); it lights up under the mouse ({@code mouseX, mouseY}, GUI units).
     */
    static void renderPreview(Minecraft mc, GuiGraphics g, int x, int y, int w, int h, int mouseX, int mouseY) {
        Window window = mc.getWindow();
        double scale = window.getGuiScale();
        double shrink = Math.min(w / (double) window.getWidth(), h / (double) window.getHeight());
        int gw = (int) (window.getWidth() * shrink), gh = (int) (window.getHeight() * shrink);
        int gx = x + (w - gw) / 2, gy = y + (h - gh) / 2;
        WorldScreens.Picture p = DesktopConfig.pip != null ? WorldScreens.picture(mc, DesktopConfig.pip) : null;
        boolean live = p != null && p.cropW() > 0 && p.cropH() > 0;
        g.flush();
        g.pose().pushPose();
        g.pose().scale((float) (1 / scale), (float) (1 / scale), 1);
        g.fill(gx - 1, gy - 1, gx + gw + 1, gy + gh + 1, 0xFF5A5A5A); // the game window's outline
        g.fill(gx, gy, gx + gw, gy + gh, 0xFF202830);
        int barW = (int) Math.round(182 * scale * shrink), barH = (int) Math.round(22 * scale * shrink);
        g.fill(gx + (gw - barW) / 2, gy + gh - barH, gx + (gw + barW) / 2, gy + gh, 0xFF3A424A); // the hotbar
        int margin = (int) Math.round(DesktopConfig.pipGap * scale * shrink) + BorderRenderer.thickness();
        int[] r = place(live ? p.cropW() : 16, live ? p.cropH() : 9, gx, gy, gw, gh, margin);
        previewGame = new int[] {gx, gy, gw, gh};
        previewPicture = r;
        previewMargin = margin;
        if (live) {
            g.flush();
            draw(g, p, r);
        } else {
            g.fill(r[0], r[1], r[0] + r[2], r[1] + r[3], 0xFF6A6A6A);
        }
        if (dragging || overPreview(mouseX * scale, mouseY * scale)) {
            // A white line round it, outside its border: this can be picked up.
            int t = Math.max(1, (int) Math.round(scale / 2)), e = BorderRenderer.thickness() + t;
            int color = dragging ? 0xFFFFFFFF : 0xB0FFFFFF;
            int x0 = r[0] - e, y0 = r[1] - e, x1 = r[0] + r[2] + e, y1 = r[1] + r[3] + e;
            g.fill(x0, y0, x1, y0 + t, color);
            g.fill(x0, y1 - t, x1, y1, color);
            g.fill(x0, y0 + t, x0 + t, y1 - t, color);
            g.fill(x1 - t, y0 + t, x1, y1 - t, color);
        }
        g.pose().popPose();
        // Under the small game window, if there's room.
        int textY = (int) ((gy + gh) / scale) + 6;
        if (textY + mc.font.lineHeight <= (y + h) / scale) {
            g.drawCenteredString(mc.font, Component.translatable("desktopscreens.pip.drag"), (int) ((gx + gw / 2) / scale), textY, 0xB0B0B0);
        }
    }

    /** Whether a point (window pixels) is on the picture in the settings preview, its border included. */
    private static boolean overPreview(double x, double y) {
        int[] r = previewPicture;
        if (r == null) return false;
        int e = BorderRenderer.thickness();
        return x >= r[0] - e && x < r[0] + r[2] + e && y >= r[1] - e && y < r[1] + r[3] + e;
    }

    /** When its settings page opens (or is laid out again): nothing picked up, and no preview until it's drawn. */
    static void resetPreview() {
        dragging = false;
        previewGame = previewPicture = null;
    }

    /**
     * On its settings page, a press on the picture in the preview (GUI units) picks it up; false if it's elsewhere.
     */
    static boolean startDrag(Minecraft mc, double mouseX, double mouseY) {
        double scale = mc.getWindow().getGuiScale();
        dragging = overPreview(mouseX * scale, mouseY * scale);
        if (!dragging) return false;
        grabX = mouseX * scale - previewPicture[0];
        grabY = mouseY * scale - previewPicture[1];
        beforeX = DesktopConfig.pipX;
        beforeY = DesktopConfig.pipY;
        return true;
    }

    /**
     * Moves the picture that was picked up to the mouse (GUI units). It's kept as its share of the room in the
     * preview's game window, which is the same share in the game at any window and picture size. It snaps to the
     * edges and the middle; in a corner it's that corner, and the dragged spot from before stays for the Corner
     * button. Returns where it is now.
     */
    static Corner drag(Minecraft mc, double mouseX, double mouseY) {
        if (!dragging || previewGame == null) return DesktopConfig.pipCorner;
        double scale = mc.getWindow().getGuiScale();
        int[] game = previewGame, r = previewPicture;
        int m = previewMargin;
        Corner now = DesktopConfig.pipCorner;
        double x = along(mouseX * scale - grabX - game[0] - m, game[2] - 2 * m - r[2], SNAP * scale, now.x());
        double y = along(mouseY * scale - grabY - game[1] - m, game[3] - 2 * m - r[3], SNAP * scale, now.y());
        Corner corner = Corner.at(x, y);
        DesktopConfig.pipX = corner == Corner.CUSTOM ? x : beforeX;
        DesktopConfig.pipY = corner == Corner.CUSTOM ? y : beforeY;
        DesktopConfig.pipCorner = corner;
        return corner;
    }

    static boolean dragging() {
        return dragging;
    }

    /** Lets go of the picture; false if none was picked up. */
    static boolean endDrag() {
        if (!dragging) return false;
        dragging = false;
        return true;
    }

    /**
     * How far along {@code room} (window pixels) a picture {@code offset} into it is, 0 to 1, snapped to the ends and the
     * middle within {@code snap}; {@code current} when there's no room that way.
     */
    private static double along(double offset, int room, double snap, double current) {
        if (room <= 0) return current;
        double f = Mth.clamp(offset / room, 0, 1);
        for (double s : SNAPS) {
            if (Math.abs(f - s) * room <= snap) return s;
        }
        return f;
    }

    /**
     * Where it goes in an area ({@code {x, y, w, h}}, window pixels): the picture's shape, the chosen share of the
     * area's width, in the chosen corner or dragged spot, at least {@code margin} (the border included) from the
     * area's edges. At most as tall as fits: a cap at half the height made 38 % to 50 % all the same size for a 4:3
     * monitor (2026-09-27).
     */
    private static int[] place(int pictureW, int pictureH, int x, int y, int w, int h, int margin) {
        int pw = Math.max(1, w * DesktopConfig.pipSize / 100);
        int ph = Math.max(1, (int) Math.round(pw * (double) pictureH / pictureW));
        if (ph > h - 2 * margin) {
            ph = Math.max(1, h - 2 * margin);
            pw = Math.max(1, (int) Math.round(ph * (double) pictureW / pictureH));
        }
        // Along the room left beside and above it, so it stays inside at any window size.
        Corner corner = DesktopConfig.pipCorner;
        int roomW = Math.max(0, w - 2 * margin - pw), roomH = Math.max(0, h - 2 * margin - ph);
        return new int[] {x + margin + (int) Math.round(corner.x() * roomW), y + margin + (int) Math.round(corner.y() * roomH), pw, ph};
    }

    /** The picture into {@code r}, with the desktop view's border around it, and the game covered where it shows. */
    private static void draw(GuiGraphics g, WorldScreens.Picture p, int[] r) {
        DesktopSession.drawPicture(g, p.textureId(), p.frameWidth(), p.frameHeight(), r[0], r[1], r[2], r[3],
                p.cropX(), p.cropY(), p.cropW(), p.cropH(), DesktopConfig.scaling != Scaling.SMOOTH);
        int[] cover = GameCover.place(p.game(), p.cropX(), p.cropY(), p.cropW(), p.cropH(), r[0], r[1], r[2], r[3]);
        if (cover != null) GameCover.drawGui(g, cover);
        BorderRenderer.draw(g, r[0], r[1], r[2], r[3]);
    }
}
