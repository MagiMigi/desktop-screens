package com.desktopscreens.fabric.mixin;

//? if <26.1 {
import com.desktopscreens.client.DesktopClient;
//? if >=1.21 {
import net.minecraft.client.DeltaTracker;
//?}
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
//?}
import net.minecraft.client.gui.Gui;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Picture-in-picture under the rest of the HUD, drawn just before the crosshair, where NeoForge puts its layer:
 * Fabric API has no HUD layers for 1.21.1, only a callback after the whole HUD. If another mod skips the crosshair,
 * {@link com.desktopscreens.client.DesktopClient#renderHud} draws it after the HUD instead. Empty from 26.1 on, where
 * Fabric API has HUD layers (see DesktopScreensFabricClient).
 */
@Mixin(Gui.class)
abstract class GuiMixin {
    //? if <26.1 {
    @Inject(method = "renderCrosshair", at = @At("HEAD"))
    //? if >=1.21 {
    private void desktopscreens$beforeCrosshair(GuiGraphics g, DeltaTracker deltaTracker, CallbackInfo ci) {
    //?} else {
    /*private void desktopscreens$beforeCrosshair(GuiGraphics g, CallbackInfo ci) {
    *///?}
        DesktopClient.renderUnderHud(g);
    }
    //?}
}
