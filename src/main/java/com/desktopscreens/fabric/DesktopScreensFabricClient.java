package com.desktopscreens.fabric;

import com.desktopscreens.BuilderRules;
import com.desktopscreens.ScreenSharing;
import com.desktopscreens.ServerCheck;
import com.desktopscreens.ShareStream;
import com.desktopscreens.client.DesktopClient;
//? if <26.1 {
import com.desktopscreens.client.DesktopShaders;
//?}
import com.desktopscreens.client.ScreenRenderer;
//? if <1.20.5 {
/*import com.desktopscreens.net.Payloads;
*///?}
//? if <26.1 {
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.rendering.v1.CoreShaderRegistrationCallback;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderers;
//?} else {
/*import com.desktopscreens.DesktopScreens;
import net.fabricmc.fabric.api.client.rendering.v1.BlockEntityRendererRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.KeyMapping;
*///?}
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;

public final class DesktopScreensFabricClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        DesktopClient.init();
        ServerCheck.serverHasMod = () -> Minecraft.getInstance().getConnection() == null
                //? if >=1.20.5 {
                || ClientPlayNetworking.canSend(ServerCheck.TYPE);
                //?} else {
                /*|| ClientPlayNetworking.canSend(ServerCheck.TYPE.id());
                *///?}
        //? if >=26.1 {
        /*KeyMapping.Category.register(DesktopClient.CATEGORY.id());
        *///?}
        KeyBindingHelper.registerKeyBinding(DesktopClient.OPEN_KEY);
        KeyBindingHelper.registerKeyBinding(DesktopClient.SETTINGS_KEY);
        KeyBindingHelper.registerKeyBinding(DesktopClient.PIP_KEY);
        KeyBindingHelper.registerKeyBinding(DesktopClient.HOST_KEY);
        ClientTickEvents.END_CLIENT_TICK.register(DesktopClient::tick);
        //? if >=26.1 {
        /*BlockEntityRendererRegistry.register(DesktopScreensFabric.screenEntity, ScreenRenderer::new);
        // Picture-in-picture under the rest of the HUD; the hints over the crosshair (as NeoForge does it).
        HudElementRegistry.attachElementBefore(VanillaHudElements.CROSSHAIR, DesktopScreens.id("pip"), (g, deltaTracker) -> DesktopClient.renderUnderHud(g));
        HudElementRegistry.attachElementAfter(VanillaHudElements.CROSSHAIR, DesktopScreens.id("screen_hint"), (g, deltaTracker) -> DesktopClient.renderHud(g));
        *///?} else {
        BlockEntityRenderers.register(DesktopScreensFabric.screenEntity, ScreenRenderer::new);
        // Picture-in-picture goes under the HUD from a mixin (GuiMixin): Fabric has no HUD layers for 1.21.1.
        HudRenderCallback.EVENT.register((g, deltaTracker) -> DesktopClient.renderHud(g));
        //?}
        //? if >=26.2 {
        /*// Handed in with the world's other custom geometry; drawn after it all was.
        LevelRenderEvents.COLLECT_SUBMITS.register(context -> DesktopClient.renderWorld(context.levelState().cameraRenderState.pos,
                context.submitNodeCollector(), context.poseStack()));
        *///?} else if >=26.1 {
        /*LevelRenderEvents.END_MAIN.register(context -> DesktopClient.renderWorld(context.levelState().cameraRenderState.pos));
        *///?} else {
        WorldRenderEvents.AFTER_TRANSLUCENT.register(context -> DesktopClient.renderWorld(context.camera()));
        //?}
        //? if >=1.20.5 {
        ClientPlayNetworking.registerGlobalReceiver(BuilderRules.TYPE, (payload, context) -> BuilderRules.received(payload));
        ClientPlayNetworking.registerGlobalReceiver(ShareStream.Status.TYPE, (payload, context) -> ShareStream.client.status(payload));
        ClientPlayNetworking.registerGlobalReceiver(ShareStream.Taken.TYPE, (payload, context) -> ShareStream.client.taken(payload));
        ClientPlayNetworking.registerGlobalReceiver(ShareStream.Frame.TYPE, (payload, context) -> ShareStream.client.frame(payload));
        ClientPlayNetworking.registerGlobalReceiver(ScreenSharing.Lists.TYPE, (payload, context) -> ShareStream.client.lists(payload));
        ClientPlayNetworking.registerGlobalReceiver(ShareStream.Keyframe.TYPE, (payload, context) -> ShareStream.client.keyframe(payload));
        ClientPlayNetworking.registerGlobalReceiver(ShareStream.Sound.TYPE, (payload, context) -> ShareStream.client.sound(payload));
        ClientPlayNetworking.registerGlobalReceiver(ShareStream.End.TYPE, (payload, context) -> ShareStream.client.end(payload));
        //?} else {
        /*// Fabric API reads these on the network thread; all but the sound are handled on the game's.
        for (Payloads.ToClient<?> message : Payloads.TO_CLIENT) {
            ClientPlayNetworking.registerGlobalReceiver(message.type().id(), (client, handler, buf, sender) -> {
                Runnable handle = message.read(buf);
                if (message.networkThread()) handle.run();
                else client.execute(handle);
            });
        }
        *///?}
        //? if <26.1 {
        CoreShaderRegistrationCallback.EVENT.register(context -> context.register(
                DesktopShaders.DESKTOP_ID, DefaultVertexFormat.POSITION_TEX, shader -> DesktopShaders.desktop = shader));
        //?}
    }
}
