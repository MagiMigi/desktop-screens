package com.desktopscreens.neoforge;

import com.desktopscreens.DesktopScreens;
import com.desktopscreens.ServerCheck;
import com.desktopscreens.client.DesktopClient;
import com.desktopscreens.client.ScreenRenderer;
//? if <26.1 {
import com.desktopscreens.client.DesktopShaders;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import net.minecraft.client.renderer.ShaderInstance;
import net.neoforged.neoforge.client.event.RegisterShadersEvent;
import java.io.IOException;
//?}
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
//? if >=26.2 {
/*import net.neoforged.neoforge.client.event.SubmitCustomGeometryEvent;
*///?} else {
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
//?}
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.common.NeoForge;

/** Separate from the @Mod class so a dedicated server never loads client classes. */
final class DesktopScreensNeoForgeClient {
    private DesktopScreensNeoForgeClient() {}

    static void init(IEventBus modBus, ModContainer container) {
        DesktopClient.init();
        ServerCheck.serverHasMod = () -> {
            ClientPacketListener connection = Minecraft.getInstance().getConnection();
            return connection == null || connection.hasChannel(ServerCheck.TYPE);
        };
        modBus.addListener(RegisterKeyMappingsEvent.class, event -> {
            //? if >=1.21.9 {
            /*event.registerCategory(DesktopClient.CATEGORY);
            *///?}
            event.register(DesktopClient.OPEN_KEY);
            event.register(DesktopClient.SETTINGS_KEY);
            event.register(DesktopClient.PIP_KEY);
            event.register(DesktopClient.HOST_KEY);
        });
        //? if <26.1 {
        // From 26.1 on our pipelines are compiled the first time they're drawn (DesktopShaders, PictureRenderType).
        modBus.addListener(RegisterShadersEvent.class, DesktopScreensNeoForgeClient::registerShaders);
        //?}
        modBus.addListener(EntityRenderersEvent.RegisterRenderers.class,
                event -> event.registerBlockEntityRenderer(DesktopScreensNeoForge.SCREEN_ENTITY.get(), ScreenRenderer::new));
        modBus.addListener(RegisterGuiLayersEvent.class, event -> {
            // Picture-in-picture under the rest of the HUD; the hints over the crosshair.
            event.registerBelow(VanillaGuiLayers.CROSSHAIR, DesktopScreens.id("pip"), (g, deltaTracker) -> DesktopClient.renderUnderHud(g));
            event.registerAbove(VanillaGuiLayers.CROSSHAIR, DesktopScreens.id("screen_hint"), (g, deltaTracker) -> DesktopClient.renderHud(g));
        });
        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post.class, event -> DesktopClient.tick(Minecraft.getInstance()));
        //? if >=26.2 {
        /*// Handed in with the world's other custom geometry; drawn after it all was.
        NeoForge.EVENT_BUS.addListener(SubmitCustomGeometryEvent.class, event -> DesktopClient.renderWorld(
                event.getLevelRenderState().cameraRenderState.pos, event.getSubmitNodeCollector(), event.getPoseStack()));
        *///?} else if >=26.1 {
        /*NeoForge.EVENT_BUS.addListener(RenderLevelStageEvent.AfterTranslucentParticles.class,
                event -> DesktopClient.renderWorld(event.getLevelRenderState().cameraRenderState.pos));
        *///?} else {
        NeoForge.EVENT_BUS.addListener(RenderLevelStageEvent.class, event -> {
            if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_PARTICLES) DesktopClient.renderWorld(event.getCamera());
        });
        //?}
        // The "Config" button in the mods list.
        container.registerExtensionPoint(IConfigScreenFactory.class, (IConfigScreenFactory) (mod, parent) -> DesktopClient.settingsScreen(parent));
    }

    //? if <26.1 {
    private static void registerShaders(RegisterShadersEvent event) {
        try {
            event.registerShader(new ShaderInstance(event.getResourceProvider(), DesktopShaders.DESKTOP_ID, DefaultVertexFormat.POSITION_TEX),
                    shader -> DesktopShaders.desktop = shader);
        } catch (IOException e) {
            DesktopScreens.LOG.error("Couldn't load the desktop shader; falling back to plain scaling", e);
        }
    }
    //?}
}
