package com.desktopscreens.forge;

import com.desktopscreens.DesktopScreens;
import com.desktopscreens.ServerCheck;
import com.desktopscreens.client.DesktopClient;
import com.desktopscreens.client.DesktopShaders;
import com.desktopscreens.client.ScreenRenderer;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraftforge.client.ConfigScreenHandler;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.client.event.RegisterGuiOverlaysEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.event.RegisterShadersEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModLoadingContext;

import java.io.IOException;

/** Separate from the @Mod class so a dedicated server never loads client classes. */
final class DesktopScreensForgeClient {
    private DesktopScreensForgeClient() {}

    static void init(IEventBus modBus) {
        DesktopClient.init();
        ServerCheck.serverHasMod = () -> {
            ClientPacketListener connection = Minecraft.getInstance().getConnection();
            return connection == null
                    || DesktopScreensForge.CHANNELS.get(ServerCheck.TYPE.id()).isRemotePresent(connection.getConnection());
        };
        modBus.addListener(DesktopScreensForgeClient::registerKeys);
        modBus.addListener(DesktopScreensForgeClient::registerShaders);
        modBus.addListener(DesktopScreensForgeClient::registerRenderers);
        modBus.addListener(DesktopScreensForgeClient::registerOverlays);
        MinecraftForge.EVENT_BUS.addListener(DesktopScreensForgeClient::clientTick);
        MinecraftForge.EVENT_BUS.addListener(DesktopScreensForgeClient::renderLevel);
        // The "Config" button in the mods list.
        ModLoadingContext.get().registerExtensionPoint(ConfigScreenHandler.ConfigScreenFactory.class,
                () -> new ConfigScreenHandler.ConfigScreenFactory((mc, parent) -> DesktopClient.settingsScreen(parent)));
    }

    private static void registerKeys(RegisterKeyMappingsEvent event) {
        event.register(DesktopClient.OPEN_KEY);
        event.register(DesktopClient.SETTINGS_KEY);
        event.register(DesktopClient.PIP_KEY);
        event.register(DesktopClient.HOST_KEY);
    }

    private static void registerShaders(RegisterShadersEvent event) {
        try {
            event.registerShader(new ShaderInstance(event.getResourceProvider(), DesktopShaders.DESKTOP_ID, DefaultVertexFormat.POSITION_TEX),
                    shader -> DesktopShaders.desktop = shader);
        } catch (IOException e) {
            DesktopScreens.LOG.error("Couldn't load the desktop shader; falling back to plain scaling", e);
        }
    }

    private static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(DesktopScreensForge.SCREEN_ENTITY.get(), ScreenRenderer::new);
    }

    /** Picture-in-picture under the rest of the HUD; the hints over the crosshair. */
    private static void registerOverlays(RegisterGuiOverlaysEvent event) {
        event.registerBelow(VanillaGuiOverlay.CROSSHAIR.id(), "pip", (gui, g, partialTick, width, height) -> DesktopClient.renderUnderHud(g));
        event.registerAbove(VanillaGuiOverlay.CROSSHAIR.id(), "screen_hint", (gui, g, partialTick, width, height) -> DesktopClient.renderHud(g));
    }

    private static void clientTick(TickEvent.ClientTickEvent event) {
        if (event.phase == TickEvent.Phase.END) DesktopClient.tick(Minecraft.getInstance());
    }

    private static void renderLevel(RenderLevelStageEvent event) {
        if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_PARTICLES) DesktopClient.renderWorld(event.getCamera());
    }
}
