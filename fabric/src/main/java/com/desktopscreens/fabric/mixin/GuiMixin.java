package com.desktopscreens.fabric.mixin;

import com.desktopscreens.client.DesktopClient;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Picture-in-picture under the rest of the HUD, drawn just before the crosshair, where NeoForge puts its layer:
 * Fabric API has no HUD layers for 1.21.1, only a callback after the whole HUD. If another mod skips the crosshair,
 * {@link DesktopClient#renderHud} draws it after the HUD instead.
 */
@Mixin(Gui.class)
abstract class GuiMixin {
    @Inject(method = "renderCrosshair", at = @At("HEAD"))
    private void desktopscreens$beforeCrosshair(GuiGraphics g, DeltaTracker deltaTracker, CallbackInfo ci) {
        DesktopClient.renderUnderHud(g);
    }
}
