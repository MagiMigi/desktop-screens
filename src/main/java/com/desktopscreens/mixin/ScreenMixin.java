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
    @Inject(method = "renderTransparentBackground", at = @At("TAIL"))
    private void desktopscreens$afterTransparentBackground(GuiGraphics g, CallbackInfo ci) {
        DesktopClient.renderBehindMenu((Screen) (Object) this, g);
    }

    @Inject(method = "renderMenuBackground(Lnet/minecraft/client/gui/GuiGraphics;)V", at = @At("TAIL"))
    private void desktopscreens$afterMenuBackground(GuiGraphics g, CallbackInfo ci) {
        DesktopClient.renderBehindMenu((Screen) (Object) this, g);
    }
}
