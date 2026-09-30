package com.desktopscreens.fabric;

import com.desktopscreens.BuilderRules;
import com.desktopscreens.ScreenSharing;
import com.desktopscreens.ServerCheck;
import com.desktopscreens.ShareStream;
import com.desktopscreens.client.DesktopClient;
import com.desktopscreens.client.DesktopShaders;
import com.desktopscreens.client.ScreenRenderer;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.rendering.v1.CoreShaderRegistrationCallback;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderers;

public final class DesktopScreensFabricClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        DesktopClient.init();
        ServerCheck.serverHasMod = () -> Minecraft.getInstance().getConnection() == null
                || ClientPlayNetworking.canSend(ServerCheck.TYPE);
        KeyBindingHelper.registerKeyBinding(DesktopClient.OPEN_KEY);
        KeyBindingHelper.registerKeyBinding(DesktopClient.SETTINGS_KEY);
        KeyBindingHelper.registerKeyBinding(DesktopClient.PIP_KEY);
        KeyBindingHelper.registerKeyBinding(DesktopClient.HOST_KEY);
        ClientTickEvents.END_CLIENT_TICK.register(DesktopClient::tick);
        BlockEntityRenderers.register(DesktopScreensFabric.screenEntity, ScreenRenderer::new);
        // Picture-in-picture goes under the HUD from a mixin (GuiMixin): Fabric has no HUD layers for 1.21.1.
        HudRenderCallback.EVENT.register((g, deltaTracker) -> DesktopClient.renderHud(g));
        WorldRenderEvents.AFTER_TRANSLUCENT.register(context -> DesktopClient.renderWorld(context.camera()));
        ClientPlayNetworking.registerGlobalReceiver(BuilderRules.TYPE, (payload, context) -> BuilderRules.received(payload));
        ClientPlayNetworking.registerGlobalReceiver(ShareStream.Status.TYPE, (payload, context) -> ShareStream.client.status(payload));
        ClientPlayNetworking.registerGlobalReceiver(ShareStream.Taken.TYPE, (payload, context) -> ShareStream.client.taken(payload));
        ClientPlayNetworking.registerGlobalReceiver(ShareStream.Frame.TYPE, (payload, context) -> ShareStream.client.frame(payload));
        ClientPlayNetworking.registerGlobalReceiver(ScreenSharing.Lists.TYPE, (payload, context) -> ShareStream.client.lists(payload));
        ClientPlayNetworking.registerGlobalReceiver(ShareStream.Keyframe.TYPE, (payload, context) -> ShareStream.client.keyframe(payload));
        ClientPlayNetworking.registerGlobalReceiver(ShareStream.Sound.TYPE, (payload, context) -> ShareStream.client.sound(payload));
        ClientPlayNetworking.registerGlobalReceiver(ShareStream.End.TYPE, (payload, context) -> ShareStream.client.end(payload));
        CoreShaderRegistrationCallback.EVENT.register(context -> context.register(
                DesktopShaders.DESKTOP_ID, DefaultVertexFormat.POSITION_TEX, shader -> DesktopShaders.desktop = shader));
    }
}
