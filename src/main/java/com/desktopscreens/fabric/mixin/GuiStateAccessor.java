package com.desktopscreens.fabric.mixin;

import net.minecraft.client.gui.GuiGraphics;
//? if >=26.1 {
/*import net.minecraft.client.renderer.state.gui.GuiRenderState;
import org.spongepowered.asm.mixin.gen.Accessor;
*///?}
import org.spongepowered.asm.mixin.Mixin;

/**
 * Where 26.1's GUI collects what it draws, so our own pieces (the desktop picture with its shader, the rainbow border)
 * can go in with the rest: vanilla keeps it to itself, NeoForge has a method for it. Empty before 26.1.
 */
@Mixin(GuiGraphics.class)
public interface GuiStateAccessor {
    //? if >=26.1 {
    /*@Accessor("guiRenderState")
    GuiRenderState desktopscreens$guiRenderState();
    *///?}
}
