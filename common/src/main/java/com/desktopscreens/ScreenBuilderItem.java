package com.desktopscreens;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

import java.util.List;

/**
 * The Screen Builder: right-click a block for the first corner, look where the screen should end (a hologram
 * stretches there, in mid-air too), right-click again to build it. Left-click cancels. Shift-click a screen to move,
 * resize or take it down. The item itself does nothing: the client takes its clicks ({@code ScreenBuilderClient}) and
 * asks the server to build ({@link BuildScreen}) or change a screen ({@link ChangeScreen}).
 */
public final class ScreenBuilderItem extends Item {
    public static final String NAME = "screen_builder";

    public ScreenBuilderItem(Properties properties) {
        super(properties);
    }

    /** One per stack, like tools. */
    public static Properties properties() {
        return new Properties().stacksTo(1);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> lines, TooltipFlag flag) {
        lines.add(Component.translatable("item.desktopscreens.screen_builder.tooltip").withStyle(ChatFormatting.GRAY));
        lines.add(Component.translatable("item.desktopscreens.screen_builder.tooltip_more").withStyle(ChatFormatting.DARK_GRAY));
        lines.add(Component.translatable("item.desktopscreens.screen_builder.tooltip_change").withStyle(ChatFormatting.DARK_GRAY));
    }
}
