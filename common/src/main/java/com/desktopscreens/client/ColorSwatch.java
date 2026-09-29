package com.desktopscreens.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;

import java.util.function.IntConsumer;
import java.util.function.IntSupplier;

/** A clickable square of color. The one matching the current color gets a white outline. */
final class ColorSwatch extends AbstractButton {
    private final int rgb;
    private final IntSupplier current;
    private final IntConsumer onPick;

    ColorSwatch(int x, int y, int size, int rgb, IntSupplier current, IntConsumer onPick) {
        super(x, y, size, size, Component.literal(DesktopConfig.hex(rgb)));
        this.rgb = rgb;
        this.current = current;
        this.onPick = onPick;
    }

    @Override
    public void onPress() {
        onPick.accept(rgb);
    }

    @Override
    protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        int outline;
        if (!active) outline = 0xFF2A2A2A;
        else if (current.getAsInt() == rgb) outline = 0xFFFFFFFF;
        else if (isHoveredOrFocused()) outline = 0xFFA0A0A0;
        else outline = 0xFF505050;
        g.fill(getX(), getY(), getX() + width, getY() + height, outline);
        g.fill(getX() + 2, getY() + 2, getX() + width - 2, getY() + height - 2, (active ? 0xFF000000 : 0x50000000) | rgb);
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        defaultButtonNarrationText(output);
    }
}
