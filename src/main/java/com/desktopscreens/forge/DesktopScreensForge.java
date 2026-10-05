package com.desktopscreens.forge;

import com.desktopscreens.BuilderRules;
import com.desktopscreens.DesktopScreens;
import com.desktopscreens.LandClaims;
import com.desktopscreens.Net;
import com.desktopscreens.OwnedScreens;
import com.desktopscreens.Players;
import com.desktopscreens.ScreenBlock;
import com.desktopscreens.ScreenBlockEntity;
import com.desktopscreens.ScreenBuilderItem;
import com.desktopscreens.ScreenSharing;
import com.desktopscreens.ServerCheck;
import com.desktopscreens.TabletItem;
import com.desktopscreens.net.Payloads;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.event.EventNetworkChannel;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

import java.util.HashMap;
import java.util.Map;

/** Forge, for Minecraft 1.20.1 and older (NeoForge took over after it). Client-only setup is in {@link DesktopScreensForgeClient}. */
@Mod(DesktopScreens.MOD_ID)
public final class DesktopScreensForge {
    private static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(ForgeRegistries.BLOCKS, DesktopScreens.MOD_ID);
    private static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, DesktopScreens.MOD_ID);
    private static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(ForgeRegistries.BLOCK_ENTITY_TYPES, DesktopScreens.MOD_ID);
    // Items in the same order as on the other loaders.
    static final RegistryObject<TabletItem> TABLET = ITEMS.register(TabletItem.NAME, () -> new TabletItem(TabletItem.properties()));
    static final RegistryObject<ScreenBlock> SCREEN = BLOCKS.register(ScreenBlock.NAME, () -> new ScreenBlock(ScreenBlock.defaultProperties()));
    static final RegistryObject<BlockItem> SCREEN_ITEM = ITEMS.register(ScreenBlock.NAME, () -> new BlockItem(SCREEN.get(), new Item.Properties()));
    static final RegistryObject<ScreenBuilderItem> SCREEN_BUILDER =
            ITEMS.register(ScreenBuilderItem.NAME, () -> new ScreenBuilderItem(ScreenBuilderItem.properties()));
    static final RegistryObject<BlockEntityType<ScreenBlockEntity>> SCREEN_ENTITY = BLOCK_ENTITIES.register(
            ScreenBlock.NAME, () -> BlockEntityType.Builder.of(ScreenBlockEntity::new, SCREEN.get()).build(null));

    /**
     * A channel per message, each fine with a game or server that doesn't have it ("missing"), so clients and servers
     * without the mod can still connect. Whether the other side has one tells whether it has the mod.
     */
    static final Map<ResourceLocation, EventNetworkChannel> CHANNELS = new HashMap<>();

    public DesktopScreensForge() {
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
        ScreenBlockEntity.type = SCREEN_ENTITY;
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        BLOCK_ENTITIES.register(modBus);
        modBus.addListener(DesktopScreensForge::fillCreativeTabs);

        // Forge reads these on the network thread. Messages to the server are handled on its thread; to the
        // player, all but the sound on the game's.
        for (Payloads.ToServer<?> message : Payloads.TO_SERVER) {
            channel(message.type().id()).addListener(event -> {
                if (!(event instanceof NetworkEvent.ClientCustomPayloadEvent)) return;
                NetworkEvent.Context context = event.getSource().get();
                ServerPlayer player = context.getSender();
                if (player != null) context.enqueueWork(message.read(player, event.getPayload()));
                context.setPacketHandled(true);
            });
        }
        for (Payloads.ToClient<?> message : Payloads.TO_CLIENT) {
            channel(message.type().id()).addListener(event -> {
                if (!(event instanceof NetworkEvent.ServerCustomPayloadEvent)) return;
                NetworkEvent.Context context = event.getSource().get();
                Runnable handle = message.read(event.getPayload());
                if (message.networkThread()) handle.run();
                else context.enqueueWork(handle);
                context.setPacketHandled(true);
            });
        }
        ScreenSharing.canReceive = (player, type) -> CHANNELS.get(type.id()).isRemotePresent(player.connection.connection);
        LandClaims.ask = new LandClaimsForge();

        MinecraftForge.EVENT_BUS.addListener(DesktopScreensForge::serverTick);
        MinecraftForge.EVENT_BUS.addListener(DesktopScreensForge::loggedOut);
        MinecraftForge.EVENT_BUS.addListener(DesktopScreensForge::loggedIn);
        if (FMLEnvironment.dist.isClient()) DesktopScreensForgeClient.init(modBus);
    }

    private static EventNetworkChannel channel(ResourceLocation id) {
        EventNetworkChannel channel = NetworkRegistry.ChannelBuilder.named(id)
                .networkProtocolVersion(() -> "1")
                .clientAcceptedVersions(NetworkRegistry.acceptMissingOr("1"))
                .serverAcceptedVersions(NetworkRegistry.acceptMissingOr("1"))
                .eventNetworkChannel();
        CHANNELS.put(id, channel);
        return channel;
    }

    private static void fillCreativeTabs(BuildCreativeModeTabContentsEvent event) {
        if (!ServerCheck.serverHasMod.getAsBoolean()) return;
        if (event.getTabKey() == CreativeModeTabs.TOOLS_AND_UTILITIES) {
            event.accept(TABLET.get());
            event.accept(SCREEN_BUILDER.get());
        }
        if (event.getTabKey() == CreativeModeTabs.FUNCTIONAL_BLOCKS) event.accept(SCREEN_ITEM.get());
    }

    private static void serverTick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.END) DesktopScreens.serverTick(event.getServer());
    }

    private static void loggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) OwnedScreens.left(player);
    }

    private static void loggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        OwnedScreens.joined(player);
        if (ScreenSharing.canReceive.test(player, BuilderRules.TYPE)) Net.toPlayer(player, BuilderRules.of(Players.server(player)));
        ScreenSharing.sendLists(player);
    }
}
