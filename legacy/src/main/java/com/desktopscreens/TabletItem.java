package com.desktopscreens;

import net.minecraft.client.resources.I18n;
import net.minecraft.client.util.ITooltipFlag;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ActionResult;
import net.minecraft.util.EnumActionResult;
import net.minecraft.util.EnumHand;
import net.minecraft.util.NonNullList;
import net.minecraft.util.text.TextComponentKeybind;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.world.World;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import javax.annotation.Nullable;
import java.util.List;

/**
 * The tablet: right-click it to use your PC's desktop. Registered on both sides, so it must never touch client
 * or Windows code itself: the client plugs in {@link #onUseClient}, and on a dedicated server it's just an item.
 */
public final class TabletItem extends Item {
    public static final String NAME = "tablet";
    /** Set by the client when it starts; null on a dedicated server. */
    public static volatile Runnable onUseClient;

    public TabletItem() {
        setRegistryName(DesktopScreens.MOD_ID, NAME);
        setTranslationKey(DesktopScreens.MOD_ID + "." + NAME); // item.desktopscreens.tablet.name
        setMaxStackSize(1); // one per stack, like tools
        setCreativeTab(CreativeTabs.TOOLS);
    }

    @Override
    public ActionResult<ItemStack> onItemRightClick(World world, EntityPlayer player, EnumHand hand) {
        Runnable open = onUseClient;
        if (world.isRemote && open != null) open.run();
        return new ActionResult<>(EnumActionResult.SUCCESS, player.getHeldItem(hand));
    }

    @Override
    @SideOnly(Side.CLIENT)
    public void addInformation(ItemStack stack, @Nullable World world, List<String> tooltip, ITooltipFlag flag) {
        tooltip.add(TextFormatting.GRAY + I18n.format("item.desktopscreens.tablet.tooltip"));
        // A keybind component shows whatever key it's bound to now.
        tooltip.add(TextFormatting.DARK_GRAY + I18n.format("item.desktopscreens.tablet.tooltip_key",
                new TextComponentKeybind("key.desktopscreens.open").getUnformattedText()));
    }

    /** Left out of the creative menu on a server without the mod, where it doesn't exist ({@link ServerCheck}). */
    @Override
    public void getSubItems(CreativeTabs tab, NonNullList<ItemStack> items) {
        if (ServerCheck.serverHasMod.getAsBoolean()) super.getSubItems(tab, items);
    }
}
