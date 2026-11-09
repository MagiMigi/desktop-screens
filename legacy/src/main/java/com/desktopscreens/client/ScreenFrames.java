package com.desktopscreens.client;

import com.desktopscreens.ScreenBlockEntity;
import com.desktopscreens.core.FrameTint;
import net.minecraft.client.Minecraft;
import net.minecraft.util.math.BlockPos;

/**
 * The "Frames" setting: this player's view of every screen, framed (the default) or not. The frame is painted into the
 * block model (its eight unjoined edge and corner pieces carry tint indexes 1-3), so frames off tints those pieces to
 * the glass's color through the block color handler, and the picture loses its bezel. Nothing in the world changes.
 */
final class ScreenFrames {
    private ScreenFrames() {}

    /** The block tint of a screen for a model tint index; asked by the threads that build the world's chunks. */
    static int tint(int tintIndex) {
        return FrameTint.color(DesktopConfig.frames, tintIndex);
    }

    /** The bezel the picture keeps clear of on a screen's outer sides, in blocks: none without frames. */
    static float bezel() {
        return DesktopConfig.frames ? ScreenGroups.BEZEL : 0;
    }

    /**
     * Switches frames on or off, saves it, and has the chunks holding loaded screens built again, as their tint is
     * drawn into them (those and their neighbours, not the whole world, which would stall a big pack).
     */
    static void set(Minecraft mc, boolean frames) {
        DesktopConfig.frames = frames;
        DesktopConfig.save();
        if (mc.world == null) return; // from the title screen's mods list: the next world is built with it
        for (ScreenBlockEntity screen : ScreenBlockEntity.clientLoaded()) {
            BlockPos p = screen.getPos();
            mc.renderGlobal.markBlockRangeForRenderUpdate(p.getX(), p.getY(), p.getZ(), p.getX(), p.getY(), p.getZ());
        }
    }
}
