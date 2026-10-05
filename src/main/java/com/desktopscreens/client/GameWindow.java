package com.desktopscreens.client;

import net.minecraft.client.Minecraft;
//? if >=26.3 {
/*import org.lwjgl.sdl.SDLProperties;
import org.lwjgl.sdl.SDLVideo;
*///?} else {
import org.lwjgl.glfw.GLFWNativeWin32;
//?}

/** The game's window as Windows knows it. GLFW opened it up to 26.2, SDL does from 26.3 on. */
final class GameWindow {
    private GameWindow() {}

    /** Its Windows handle (HWND). */
    static long hwnd(Minecraft mc) {
        //? if >=26.3 {
        /*return SDLProperties.SDL_GetPointerProperty(SDLVideo.SDL_GetWindowProperties(mc.getWindow().getWindow()),
                SDLVideo.SDL_PROP_WINDOW_WIN32_HWND_POINTER, 0L);
        *///?} else {
        return GLFWNativeWin32.glfwGetWin32Window(mc.getWindow().getWindow());
        //?}
    }
}
