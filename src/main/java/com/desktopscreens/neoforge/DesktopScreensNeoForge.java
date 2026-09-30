package com.desktopscreens.neoforge;

import com.desktopscreens.BuildScreen;
import com.desktopscreens.ChangeScreen;
import com.desktopscreens.BuilderRules;
import com.desktopscreens.DesktopScreens;
import com.desktopscreens.LandClaims;
import com.desktopscreens.OwnedScreens;
import com.desktopscreens.ScreenBlock;
import com.desktopscreens.ScreenBlockEntity;
import com.desktopscreens.ScreenBuilder;
import com.desktopscreens.ScreenBuilderItem;
import com.desktopscreens.ScreenSharing;
import com.desktopscreens.ServerCheck;
import com.desktopscreens.ShareRelay;
import com.desktopscreens.ShareScreen;
import com.desktopscreens.ShareStream;
import com.desktopscreens.TabletItem;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

@Mod(DesktopScreens.MOD_ID)
public final class DesktopScreensNeoForge {
    private static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(DesktopScreens.MOD_ID);
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(DesktopScreens.MOD_ID);
    // Items in the same order as on Fabric, so their numbers match when a client of one joins a server of the other.
    static final DeferredItem<TabletItem> TABLET = ITEMS.registerItem(TabletItem.NAME, TabletItem::new, TabletItem.properties());
    static final DeferredBlock<ScreenBlock> SCREEN = BLOCKS.registerBlock(ScreenBlock.NAME, ScreenBlock::new, ScreenBlock.defaultProperties());
    static final DeferredItem<BlockItem> SCREEN_ITEM = ITEMS.registerSimpleBlockItem(SCREEN);
    static final DeferredItem<ScreenBuilderItem> SCREEN_BUILDER = ITEMS.registerItem(ScreenBuilderItem.NAME, ScreenBuilderItem::new, ScreenBuilderItem.properties());
    private static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES = DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, DesktopScreens.MOD_ID);
    static final DeferredHolder<BlockEntityType<?>, BlockEntityType<ScreenBlockEntity>> SCREEN_ENTITY = BLOCK_ENTITIES.register(
            ScreenBlock.NAME, () -> BlockEntityType.Builder.of(ScreenBlockEntity::new, SCREEN.get()).build(null));

    public DesktopScreensNeoForge(IEventBus modBus, ModContainer container, Dist dist) {
        ScreenBlockEntity.type = SCREEN_ENTITY;
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        BLOCK_ENTITIES.register(modBus);
        modBus.addListener(BuildCreativeModeTabContentsEvent.class, event -> {
            if (!ServerCheck.serverHasMod.getAsBoolean()) return;
            if (event.getTabKey() == CreativeModeTabs.TOOLS_AND_UTILITIES) {
                event.accept(TABLET);
                event.accept(SCREEN_BUILDER);
            }
            if (event.getTabKey() == CreativeModeTabs.FUNCTIONAL_BLOCKS) event.accept(SCREEN_ITEM);
        });
        // Optional, so clients and servers without the mod can still connect.
        modBus.addListener(RegisterPayloadHandlersEvent.class, event -> event.registrar("1").optional()
                .playToServer(ServerCheck.TYPE, ServerCheck.STREAM_CODEC, (payload, context) -> {})
                .playToServer(BuildScreen.TYPE, BuildScreen.STREAM_CODEC, (payload, context) -> {
                    if (context.player() instanceof ServerPlayer player) ScreenBuilder.build(player, payload);
                })
                .playToServer(ChangeScreen.TYPE, ChangeScreen.STREAM_CODEC, (payload, context) -> {
                    if (context.player() instanceof ServerPlayer player) ScreenBuilder.change(player, payload);
                })
                .playToServer(ShareScreen.TYPE, ShareScreen.STREAM_CODEC, (payload, context) -> {
                    if (context.player() instanceof ServerPlayer player) ScreenSharing.change(player, payload);
                })
                .playToServer(ShareStream.Watch.TYPE, ShareStream.Watch.STREAM_CODEC, (payload, context) -> {
                    if (context.player() instanceof ServerPlayer player) ShareRelay.watch(player, payload);
                })
                .playToServer(ShareStream.Chunk.TYPE, ShareStream.Chunk.STREAM_CODEC, (payload, context) -> {
                    if (context.player() instanceof ServerPlayer player) ShareRelay.chunk(player, payload);
                })
                .playToServer(ShareStream.Received.TYPE, ShareStream.Received.STREAM_CODEC, (payload, context) -> {
                    if (context.player() instanceof ServerPlayer player) ShareRelay.received(player, payload);
                })
                .playToServer(ShareStream.SoundUp.TYPE, ShareStream.SoundUp.STREAM_CODEC, (payload, context) -> {
                    if (context.player() instanceof ServerPlayer player) ShareRelay.sound(player, payload);
                })
                .playToServer(ShareStream.EndUp.TYPE, ShareStream.EndUp.STREAM_CODEC, (payload, context) -> {
                    if (context.player() instanceof ServerPlayer player) ShareRelay.end(player, payload);
                })
                // Client side through ShareStream.client, so a dedicated server never loads client code.
                .playToClient(ShareStream.Status.TYPE, ShareStream.Status.STREAM_CODEC, (payload, context) -> {
                    if (ShareStream.client != null) ShareStream.client.status(payload);
                })
                .playToClient(ShareStream.Taken.TYPE, ShareStream.Taken.STREAM_CODEC, (payload, context) -> {
                    if (ShareStream.client != null) ShareStream.client.taken(payload);
                })
                .playToClient(ShareStream.Frame.TYPE, ShareStream.Frame.STREAM_CODEC, (payload, context) -> {
                    if (ShareStream.client != null) ShareStream.client.frame(payload);
                })
                .playToClient(ScreenSharing.Lists.TYPE, ScreenSharing.Lists.STREAM_CODEC, (payload, context) -> {
                    if (ShareStream.client != null) ShareStream.client.lists(payload);
                })
                .playToClient(ShareStream.Keyframe.TYPE, ShareStream.Keyframe.STREAM_CODEC, (payload, context) -> {
                    if (ShareStream.client != null) ShareStream.client.keyframe(payload);
                })
                .playToClient(ShareStream.Sound.TYPE, ShareStream.Sound.STREAM_CODEC, (payload, context) -> {
                    if (ShareStream.client != null) ShareStream.client.sound(payload);
                })
                .playToClient(ShareStream.End.TYPE, ShareStream.End.STREAM_CODEC, (payload, context) -> {
                    if (ShareStream.client != null) ShareStream.client.end(payload);
                })
                .playToClient(BuilderRules.TYPE, BuilderRules.STREAM_CODEC, (payload, context) -> BuilderRules.received(payload)));
        NeoForge.EVENT_BUS.addListener(ServerTickEvent.Post.class, event -> DesktopScreens.serverTick(event.getServer()));
        NeoForge.EVENT_BUS.addListener(PlayerEvent.PlayerLoggedOutEvent.class, event -> {
            if (event.getEntity() instanceof ServerPlayer player) OwnedScreens.left(player);
        });
        ScreenSharing.canReceive = (player, type) -> player.connection.hasChannel(type);
        LandClaims.ask = new LandClaimsNeoForge();
        NeoForge.EVENT_BUS.addListener(PlayerEvent.PlayerLoggedInEvent.class, event -> {
            if (!(event.getEntity() instanceof ServerPlayer player)) return;
            OwnedScreens.joined(player);
            if (player.connection.hasChannel(BuilderRules.TYPE)) PacketDistributor.sendToPlayer(player, BuilderRules.of(player.server));
            ScreenSharing.sendLists(player);
        });
        if (dist.isClient()) DesktopScreensNeoForgeClient.init(modBus, container);
    }
}
