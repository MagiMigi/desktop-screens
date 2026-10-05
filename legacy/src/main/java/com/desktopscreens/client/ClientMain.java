package com.desktopscreens.client;

import com.desktopscreens.DesktopScreens;
import com.desktopscreens.boot.ClientHooks;
import com.desktopscreens.core.Monitor;
import com.desktopscreens.core.Monitors;
import com.desktopscreens.core.Win32;
import com.sun.jna.Native;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraftforge.client.event.EntityViewRenderEvent;

/** The client's code starts here, loaded by boot/ClientLoader. */
public final class ClientMain implements ClientHooks {
    @Override
    public void init() {
        // Read from the loaded jar (Native.VERSION is a constant, copied in when this was compiled).
        DesktopScreens.LOG.info("Client part loaded apart from the game's classes, with JNA {} from {}",
                Native.class.getPackage().getImplementationVersion(),
                Native.class.getProtectionDomain().getCodeSource().getLocation());
        if (Win32.SUPPORTED) {
            long window = GameWindow.hwnd();
            DesktopScreens.LOG.info("Game window {}: {}", Long.toHexString(window), Win32.windowState(window));
            for (Monitor m : Monitors.list()) DesktopScreens.LOG.info("Monitor {}", m);
        }
        DesktopClient.init();
    }

    @Override
    public void tick() {
        DesktopClient.tick(Minecraft.getMinecraft());
    }

    @Override
    public void tabletUsed() {
        DesktopClient.tabletUsed();
    }

    @Override
    public GuiScreen settingsScreen(GuiScreen parent) {
        return DesktopClient.settingsScreen(parent);
    }

    @Override
    public void frame() {
        PictureInPicture.newFrame();
        WorldScreens.frame();
    }

    @Override
    public void renderUnderHud() {
        PictureInPicture.renderUnderHud(Minecraft.getMinecraft());
    }

    @Override
    public void renderBehindMenu(GuiScreen screen) {
        PictureInPicture.renderBehindMenu(Minecraft.getMinecraft(), screen);
    }

    @Override
    public void renderHud(int width, int height) {
        DesktopClient.renderHud(Minecraft.getMinecraft(), width, height);
    }

    @Override
    public boolean hidesHud() {
        return DesktopScreen.hidesHud(Minecraft.getMinecraft());
    }

    @Override
    public void cameraSetup(EntityViewRenderEvent.CameraSetup event) {
        CameraGlide.apply(event);
    }

    @Override
    public void renderWorld(float partialTicks) {
        ScreenBuilderClient.render(Minecraft.getMinecraft(), partialTicks);
    }

    @Override
    public boolean mouseButton(int button) {
        return DesktopClient.mouseButton(Minecraft.getMinecraft(), button);
    }

    @Override
    public boolean use() {
        return DesktopClient.use();
    }

    @Override
    public boolean attack() {
        return ScreenBuilderClient.onAttack();
    }
}
