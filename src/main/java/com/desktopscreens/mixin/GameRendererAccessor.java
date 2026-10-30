package com.desktopscreens.mixin;

//? if <26.1 {
import net.minecraft.client.Camera;
//?}
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
//? if <26.1 {
import org.spongepowered.asm.mixin.gen.Invoker;
//?}

/**
 * The field of view the world is drawn with: the setting, widened by flying and sprinting, narrowed under water. For
 * the VR test switch, whose mouse aims through the game view ({@code WorldPointing}): with the setting alone, a field
 * of view of 110 while flying fast put the bar's buttons well below where they showed (Test 58b, 2026-10-30). Private
 * before 26.1; 26.1 has {@code Camera.getFov}, so nothing here then.
 */
@Mixin(GameRenderer.class)
public interface GameRendererAccessor {
    //? if <26.1 {
    @Invoker("getFov")
    double desktopscreens$fov(Camera camera, float partialTick, boolean useFovSetting);
    //?}
}
