package com.desktopscreens.mixin;

import com.desktopscreens.client.DesktopClient;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Picture-in-picture behind a menu's panels and buttons: drawn right after the menu's darkened background (containers
 * like the inventory) or its blurred one (the pause menu, most others), so whatever the menu draws next goes over it.
 * Neither loader has an event there for every menu (NeoForge's leaves out containers).
 */
@Mixin(Screen.class)
abstract class ScreenMixin {
    //? if >=26.1 {
    /*@Inject(method = "extractTransparentBackground", at = @At("TAIL"))
    private void desktopscreens$afterTransparentBackground(GuiGraphics g, CallbackInfo ci) {
        DesktopClient.renderBehindMenu((Screen) (Object) this, g);
    }

    @Inject(method = "extractMenuBackground(Lnet/minecraft/client/gui/GuiGraphics;)V", at = @At("TAIL"))
    private void desktopscreens$afterMenuBackground(GuiGraphics g, CallbackInfo ci) {
        DesktopClient.renderBehindMenu((Screen) (Object) this, g);
    }
    *///?} else if >=1.20.5 {
    @Inject(method = "renderTransparentBackground", at = @At("TAIL"))
    private void desktopscreens$afterTransparentBackground(GuiGraphics g, CallbackInfo ci) {
        DesktopClient.renderBehindMenu((Screen) (Object) this, g);
    }

    @Inject(method = "renderMenuBackground(Lnet/minecraft/client/gui/GuiGraphics;)V", at = @At("TAIL"))
    private void desktopscreens$afterMenuBackground(GuiGraphics g, CallbackInfo ci) {
        DesktopClient.renderBehindMenu((Screen) (Object) this, g);
    }
    //?} else {
    /*// Before 1.20.5 every menu, containers too, darkens the world through this one (or draws dirt, outside a world).
    @Inject(method = "renderBackground(Lnet/minecraft/client/gui/GuiGraphics;)V", at = @At("TAIL"))
    private void desktopscreens$afterBackground(GuiGraphics g, CallbackInfo ci) {
        DesktopClient.renderBehindMenu((Screen) (Object) this, g);
    }
    *///?}
}
