package com.desktopscreens.fabric.mixin;

//? if <26.1 {
import com.desktopscreens.client.DesktopClient;
import com.mojang.blaze3d.platform.InputConstants;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;
//?}
import net.minecraft.client.KeyMapping;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Before 26.1 Minecraft keeps one binding per key, and only that one hears of a press. If it was ours, it could take the
 * press and do nothing with it, and another mod's G wouldn't work (Test 87, 2026-11-06). {@link DesktopClient#onKey} reads
 * the key itself, so ours gives its place to any other binding on the key. NeoForge's lookup and 26.1's list keep them
 * all. Empty from 26.1 on.
 */
@Mixin(KeyMapping.class)
abstract class KeyMappingMixin {
    //? if <26.1 {
    @Shadow @Final private static Map<String, KeyMapping> ALL;
    @Shadow @Final private static Map<InputConstants.Key, KeyMapping> MAP;

    @Inject(method = "resetMapping", at = @At("RETURN"))
    private static void desktopscreens$stepAside(CallbackInfo ci) {
        for (Map.Entry<InputConstants.Key, KeyMapping> entry : MAP.entrySet()) {
            if (entry.getValue() != DesktopClient.OPEN_KEY) continue;
            for (KeyMapping other : ALL.values()) {
                if (other != DesktopClient.OPEN_KEY && other.same(DesktopClient.OPEN_KEY)) {
                    entry.setValue(other);
                    return;
                }
            }
            return;
        }
    }
    //?}
}
