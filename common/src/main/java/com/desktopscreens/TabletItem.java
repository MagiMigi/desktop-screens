package com.desktopscreens;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

import java.util.List;

/**
 * The tablet: right-click it to use your PC's desktop. Registered on both sides, so it must never touch client
 * or Windows code itself: the client plugs in {@link #onUseClient}, and on a dedicated server it's just an item.
 */
public final class TabletItem extends Item {
    public static final String NAME = "tablet";
    /** Set by the client when it starts; null on a dedicated server. */
    public static volatile Runnable onUseClient;

    public TabletItem(Properties properties) {
        super(properties);
    }

    /** One per stack, like tools. */
    public static Properties properties() {
        return new Properties().stacksTo(1);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        Runnable open = onUseClient;
        if (level.isClientSide() && open != null) open.run();
        return InteractionResultHolder.sidedSuccess(player.getItemInHand(hand), level.isClientSide());
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> lines, TooltipFlag flag) {
        lines.add(Component.translatable("item.desktopscreens.tablet.tooltip").withStyle(ChatFormatting.GRAY));
        // A keybind component shows whatever key it's bound to now, and needs no client code here.
        lines.add(Component.translatable("item.desktopscreens.tablet.tooltip_key", Component.keybind("key.desktopscreens.open"))
                .withStyle(ChatFormatting.DARK_GRAY));
    }
}
