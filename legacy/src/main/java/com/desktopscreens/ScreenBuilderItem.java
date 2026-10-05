package com.desktopscreens;

import net.minecraft.client.resources.I18n;
import net.minecraft.client.util.ITooltipFlag;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.NonNullList;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.world.World;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import javax.annotation.Nullable;
import java.util.List;

/**
 * The Screen Builder: right-click a block for the first corner, look where the screen should end (a hologram
 * stretches there, in mid-air too), right-click again to build it. Left-click cancels. Shift-click a screen to move,
 * resize or take it down. The item itself does nothing: the client takes its clicks (client/ScreenBuilderClient) and
 * asks the server to build ({@link BuildScreen}) or change a screen ({@link ChangeScreen}).
 */
public final class ScreenBuilderItem extends Item {
    public static final String NAME = "screen_builder";

    public ScreenBuilderItem() {
        setRegistryName(DesktopScreens.MOD_ID, NAME);
        setTranslationKey(DesktopScreens.MOD_ID + "." + NAME); // item.desktopscreens.screen_builder.name
        setMaxStackSize(1); // one per stack, like tools
        setCreativeTab(CreativeTabs.TOOLS);
    }

    @Override
    @SideOnly(Side.CLIENT)
    public void addInformation(ItemStack stack, @Nullable World world, List<String> tooltip, ITooltipFlag flag) {
        tooltip.add(TextFormatting.GRAY + I18n.format("item.desktopscreens.screen_builder.tooltip"));
        tooltip.add(TextFormatting.DARK_GRAY + I18n.format("item.desktopscreens.screen_builder.tooltip_more"));
        tooltip.add(TextFormatting.DARK_GRAY + I18n.format("item.desktopscreens.screen_builder.tooltip_change"));
    }

    /** Left out of the creative menu on a server without the mod, where it doesn't exist ({@link ServerCheck}). */
    @Override
    public void getSubItems(CreativeTabs tab, NonNullList<ItemStack> items) {
        if (ServerCheck.serverHasMod.getAsBoolean()) super.getSubItems(tab, items);
    }
}
