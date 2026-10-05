package com.desktopscreens;

import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.Item;
import net.minecraft.item.ItemBlock;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.event.RegistryEvent;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.SidedProxy;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.PlayerEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.common.registry.GameRegistry;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * The mod on Minecraft 1.12.2 (Forge). Its client side lives in com.desktopscreens.client, loaded apart from the
 * game's classes with our own JNA (see boot/ClientLoader).
 */
@Mod(modid = DesktopScreens.MOD_ID, name = "Desktop Screens", version = Tags.VERSION, useMetadata = true,
        acceptedMinecraftVersions = "[1.12.2]", guiFactory = "com.desktopscreens.boot.ConfigGuiFactory",
        // Servers and clients without the mod can still connect, as on the newer versions.
        acceptableRemoteVersions = "*")
@Mod.EventBusSubscriber(modid = DesktopScreens.MOD_ID)
public final class DesktopScreens {
    public static final String MOD_ID = "desktopscreens";
    public static final Logger LOG = LogManager.getLogger("Desktop Screens");

    public static final TabletItem TABLET = new TabletItem();
    public static final ScreenBlock SCREEN = new ScreenBlock();
    public static final Item SCREEN_ITEM = new ItemBlock(SCREEN).setRegistryName(SCREEN.getRegistryName());
    public static final ScreenBuilderItem SCREEN_BUILDER = new ScreenBuilderItem();

    @SidedProxy(clientSide = "com.desktopscreens.boot.ClientSide", serverSide = "com.desktopscreens.Side")
    public static Side side;

    public static ResourceLocation id(String path) {
        return new ResourceLocation(MOD_ID, path);
    }

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        Net.register();
        side.init();
    }

    @SubscribeEvent
    public static void registerBlocks(RegistryEvent.Register<Block> event) {
        event.getRegistry().register(SCREEN);
        GameRegistry.registerTileEntity(ScreenBlockEntity.class, id(ScreenBlock.NAME));
    }

    @SubscribeEvent
    public static void registerItems(RegistryEvent.Register<Item> event) {
        // In the same order as on the newer versions.
        event.getRegistry().register(TABLET);
        event.getRegistry().register(SCREEN_ITEM);
        event.getRegistry().register(SCREEN_BUILDER);
    }

    @SubscribeEvent
    public static void serverTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        MinecraftServer server = FMLCommonHandler.instance().getMinecraftServerInstance();
        OwnedScreens.tick(server);
        ShareRelay.tick(server);
    }

    @SubscribeEvent
    public static void playerJoined(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.player instanceof EntityPlayerMP)) return;
        EntityPlayerMP player = (EntityPlayerMP) event.player;
        OwnedScreens.joined(player);
        // A game without the mod doesn't know the channel, and ignores it.
        Net.CHANNEL.sendTo(BuilderRules.of(player.getServer()), player);
        ScreenSharing.sendLists(player);
    }

    @SubscribeEvent
    public static void playerLeft(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.player instanceof EntityPlayerMP) OwnedScreens.left((EntityPlayerMP) event.player);
    }
}
