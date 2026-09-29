package com.desktopscreens.neoforge;

import com.desktopscreens.DesktopScreens;
import com.desktopscreens.ServerCheck;
import com.desktopscreens.client.DesktopClient;
import com.desktopscreens.client.DesktopShaders;
import com.desktopscreens.client.ScreenRenderer;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.renderer.ShaderInstance;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.RegisterShadersEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.common.NeoForge;

import java.io.IOException;

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
            event.register(DesktopClient.OPEN_KEY);
            event.register(DesktopClient.SETTINGS_KEY);
            event.register(DesktopClient.PIP_KEY);
            event.register(DesktopClient.HOST_KEY);
        });
        modBus.addListener(RegisterShadersEvent.class, DesktopScreensNeoForgeClient::registerShaders);
        modBus.addListener(EntityRenderersEvent.RegisterRenderers.class,
                event -> event.registerBlockEntityRenderer(DesktopScreensNeoForge.SCREEN_ENTITY.get(), ScreenRenderer::new));
        modBus.addListener(RegisterGuiLayersEvent.class, event -> {
            // Picture-in-picture under the rest of the HUD; the hints over the crosshair.
            event.registerBelow(VanillaGuiLayers.CROSSHAIR, DesktopScreens.id("pip"), (g, deltaTracker) -> DesktopClient.renderUnderHud(g));
            event.registerAbove(VanillaGuiLayers.CROSSHAIR, DesktopScreens.id("screen_hint"), (g, deltaTracker) -> DesktopClient.renderHud(g));
        });
        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post.class, event -> DesktopClient.tick(Minecraft.getInstance()));
        NeoForge.EVENT_BUS.addListener(RenderLevelStageEvent.class, event -> {
            if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_PARTICLES) DesktopClient.renderWorld(event.getCamera());
        });
        // The "Config" button in the mods list.
        container.registerExtensionPoint(IConfigScreenFactory.class, (IConfigScreenFactory) (mod, parent) -> DesktopClient.settingsScreen(parent));
    }

    private static void registerShaders(RegisterShadersEvent event) {
        try {
            event.registerShader(new ShaderInstance(event.getResourceProvider(), DesktopShaders.DESKTOP_ID, DefaultVertexFormat.POSITION_TEX),
                    shader -> DesktopShaders.desktop = shader);
        } catch (IOException e) {
            DesktopScreens.LOG.error("Couldn't load the desktop shader; falling back to plain scaling", e);
        }
    }
}
