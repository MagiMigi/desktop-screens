package com.desktopscreens.client;

import com.desktopscreens.DesktopScreens;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;

import java.util.HashMap;
import java.util.Map;

/**
 * A grey drawn in the world like the desktop picture ({@link PictureRenderType}): a one-pixel texture, solid, hiding
 * what's behind it. For the game's cover on screens and the VR bar's plates: see-through ones mix with the text over
 * them on 26.3, which blends everything see-through regardless of order (Test 60b, 2026-10-30). Render thread.
 */
final class SolidColor {
    private static final Map<String, RenderType> TYPES = new HashMap<>();

    private SolidColor() {}

    /** A grey {@code grey} (0-255) called {@code name}, made the first time it's asked for. Quads take the texture's middle (0.5, 0.5). */
    static RenderType of(Minecraft mc, String name, int grey) {
        return TYPES.computeIfAbsent(name, n -> {
            ResourceLocation texture = DesktopScreens.id(n);
            NativeImage pixel = new NativeImage(1, 1, false);
            // Grey reads the same in any byte order.
            //? if >=26.1 {
            /*pixel.setPixel(0, 0, 0xFF000000 | grey << 16 | grey << 8 | grey);
            mc.getTextureManager().register(texture, new DynamicTexture(() -> "Desktop Screens " + n, pixel));
            *///?} else {
            pixel.setPixelRGBA(0, 0, 0xFF000000 | grey << 16 | grey << 8 | grey);
            mc.getTextureManager().register(texture, new DynamicTexture(pixel));
            //?}
            return PictureRenderType.of(texture);
        });
    }
}
