package com.desktopscreens.boot;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraftforge.fml.client.IModGuiFactory;

import java.util.Collections;
import java.util.Set;

/** The Config button in Forge's mods list: opens the settings, with a live preview. */
public final class ConfigGuiFactory implements IModGuiFactory {
    @Override
    public void initialize(Minecraft minecraft) {}

    @Override
    public boolean hasConfigGui() {
        return ClientSide.running();
    }

    @Override
    public GuiScreen createConfigGui(GuiScreen parent) {
        return ClientSide.settingsScreen(parent);
    }

    @Override
    public Set<RuntimeOptionCategoryElement> runtimeGuiCategories() {
        return Collections.emptySet();
    }
}
