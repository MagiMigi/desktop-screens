package com.desktopscreens.client;

import com.desktopscreens.ScreenBlockEntity;
import com.desktopscreens.core.FrameTint;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;

/**
 * The "Frames" setting: this player's view of every screen, framed (the default) or not. The frame is painted into the
 * block model (its eight unjoined edge and corner pieces carry tint indexes 1-3), so frames off tints those pieces to
 * the glass's color through each loader's block tint, and the picture loses its bezel. Nothing in the world changes.
 */
public final class ScreenFrames {
    private ScreenFrames() {}

    /** The block tint of a screen for a model tint index; asked by the threads that build the world's chunks. */
    public static int tint(int tintIndex) {
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
        if (mc.level == null) return; // from the title screen's mods list: the next world is built with it
        // The level's own call, the same in every version (26.2 moved the renderer's).
        for (ScreenBlockEntity screen : ScreenBlockEntity.clientLoaded()) {
            BlockPos p = screen.getBlockPos();
            mc.level.setSectionDirtyWithNeighbors(SectionPos.blockToSectionCoord(p.getX()), SectionPos.blockToSectionCoord(p.getY()),
                    SectionPos.blockToSectionCoord(p.getZ()));
        }
    }
}
