package com.desktopscreens.fabric;

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
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.entity.BlockEntityType;

/** Runs on both sides: game content only. Client-only setup is in {@link DesktopScreensFabricClient}. */
public final class DesktopScreensFabric implements ModInitializer {
    static TabletItem tablet;
    static ScreenBlock screen;
    static BlockItem screenItem;
    static ScreenBuilderItem screenBuilder;
    static BlockEntityType<ScreenBlockEntity> screenEntity;

    @Override
    public void onInitialize() {
        // Items in the same order as on NeoForge, so their numbers match when a client of one joins a server of the other.
        tablet = Registry.register(BuiltInRegistries.ITEM, DesktopScreens.id(TabletItem.NAME), new TabletItem(TabletItem.properties()));
        screen = Registry.register(BuiltInRegistries.BLOCK, DesktopScreens.id(ScreenBlock.NAME), new ScreenBlock(ScreenBlock.defaultProperties()));
        screenItem = Registry.register(BuiltInRegistries.ITEM, DesktopScreens.id(ScreenBlock.NAME), new BlockItem(screen, new Item.Properties()));
        screenBuilder = Registry.register(BuiltInRegistries.ITEM, DesktopScreens.id(ScreenBuilderItem.NAME),
                new ScreenBuilderItem(ScreenBuilderItem.properties()));
        screenEntity = Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE, DesktopScreens.id(ScreenBlock.NAME),
                BlockEntityType.Builder.of(ScreenBlockEntity::new, screen).build(null));
        ScreenBlockEntity.type = () -> screenEntity;

        ItemGroupEvents.modifyEntriesEvent(CreativeModeTabs.TOOLS_AND_UTILITIES).register(entries -> {
            if (!ServerCheck.serverHasMod.getAsBoolean()) return;
            entries.accept(tablet);
            entries.accept(screenBuilder);
        });
        ItemGroupEvents.modifyEntriesEvent(CreativeModeTabs.FUNCTIONAL_BLOCKS).register(entries -> {
            if (ServerCheck.serverHasMod.getAsBoolean()) entries.accept(screenItem);
        });

        PayloadTypeRegistry.playC2S().register(ServerCheck.TYPE, ServerCheck.STREAM_CODEC);
        ServerPlayNetworking.registerGlobalReceiver(ServerCheck.TYPE, (payload, context) -> {});
        PayloadTypeRegistry.playC2S().register(BuildScreen.TYPE, BuildScreen.STREAM_CODEC);
        ServerPlayNetworking.registerGlobalReceiver(BuildScreen.TYPE, (payload, context) -> ScreenBuilder.build(context.player(), payload));
        PayloadTypeRegistry.playC2S().register(ChangeScreen.TYPE, ChangeScreen.STREAM_CODEC);
        ServerPlayNetworking.registerGlobalReceiver(ChangeScreen.TYPE, (payload, context) -> ScreenBuilder.change(context.player(), payload));
        PayloadTypeRegistry.playC2S().register(ShareScreen.TYPE, ShareScreen.STREAM_CODEC);
        ServerPlayNetworking.registerGlobalReceiver(ShareScreen.TYPE, (payload, context) -> ScreenSharing.change(context.player(), payload));
        PayloadTypeRegistry.playC2S().register(ShareStream.Watch.TYPE, ShareStream.Watch.STREAM_CODEC);
        PayloadTypeRegistry.playC2S().register(ShareStream.Chunk.TYPE, ShareStream.Chunk.STREAM_CODEC);
        PayloadTypeRegistry.playC2S().register(ShareStream.Received.TYPE, ShareStream.Received.STREAM_CODEC);
        PayloadTypeRegistry.playC2S().register(ShareStream.SoundUp.TYPE, ShareStream.SoundUp.STREAM_CODEC);
        PayloadTypeRegistry.playC2S().register(ShareStream.EndUp.TYPE, ShareStream.EndUp.STREAM_CODEC);
        ServerPlayNetworking.registerGlobalReceiver(ShareStream.Watch.TYPE, (payload, context) -> ShareRelay.watch(context.player(), payload));
        ServerPlayNetworking.registerGlobalReceiver(ShareStream.Chunk.TYPE, (payload, context) -> ShareRelay.chunk(context.player(), payload));
        ServerPlayNetworking.registerGlobalReceiver(ShareStream.Received.TYPE, (payload, context) -> ShareRelay.received(context.player(), payload));
        ServerPlayNetworking.registerGlobalReceiver(ShareStream.SoundUp.TYPE, (payload, context) -> ShareRelay.sound(context.player(), payload));
        ServerPlayNetworking.registerGlobalReceiver(ShareStream.EndUp.TYPE, (payload, context) -> ShareRelay.end(context.player(), payload));
        PayloadTypeRegistry.playS2C().register(ShareStream.Status.TYPE, ShareStream.Status.STREAM_CODEC);
        PayloadTypeRegistry.playS2C().register(ShareStream.Taken.TYPE, ShareStream.Taken.STREAM_CODEC);
        PayloadTypeRegistry.playS2C().register(ShareStream.Frame.TYPE, ShareStream.Frame.STREAM_CODEC);
        PayloadTypeRegistry.playS2C().register(ScreenSharing.Lists.TYPE, ScreenSharing.Lists.STREAM_CODEC);
        PayloadTypeRegistry.playS2C().register(ShareStream.Keyframe.TYPE, ShareStream.Keyframe.STREAM_CODEC);
        PayloadTypeRegistry.playS2C().register(ShareStream.Sound.TYPE, ShareStream.Sound.STREAM_CODEC);
        PayloadTypeRegistry.playS2C().register(ShareStream.End.TYPE, ShareStream.End.STREAM_CODEC);
        ScreenSharing.canReceive = ServerPlayNetworking::canSend;
        LandClaims.ask = new LandClaimsFabric();
        ServerTickEvents.END_SERVER_TICK.register(DesktopScreens::serverTick);
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> OwnedScreens.left(handler.player));
        PayloadTypeRegistry.playS2C().register(BuilderRules.TYPE, BuilderRules.STREAM_CODEC);
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            OwnedScreens.joined(handler.player);
            if (ServerPlayNetworking.canSend(handler, BuilderRules.TYPE)) sender.sendPacket(BuilderRules.of(server));
            ScreenSharing.sendLists(handler.player);
        });
    }
}
