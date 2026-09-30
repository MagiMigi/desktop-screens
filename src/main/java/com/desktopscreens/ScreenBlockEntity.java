package com.desktopscreens;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * What a screen block remembers: who turned it on, if anyone. A joined screen is switched as a whole, so all its
 * blocks hold the same owner. Only the owner's game shows a picture on it (their own desktop); to everyone else it
 * stays dark. Also what the client draws the picture from (the renderer is per block entity).
 */
public final class ScreenBlockEntity extends BlockEntity {
    /** Set by each loader when it registers the type, before any screen exists. */
    public static Supplier<BlockEntityType<ScreenBlockEntity>> type;
    /**
     * Goes up whenever a screen in the client's world appears, goes, changes shape or changes owner, so the client
     * knows its worked-out screen groups are stale. Written on the client thread only.
     */
    private static volatile int clientChanges;
    /** The screens in the client's world, so their sound can play while they're out of sight. Client thread only. */
    private static final Set<ScreenBlockEntity> CLIENT_LOADED = new HashSet<>();

    private UUID owner;
    private String ownerName = "";
    /** Who else sees the owner's desktop on it (see {@link ScreenSharing}). Empty while it's off. */
    private List<Watcher> shared = List.of();
    /**
     * Which screen this is, the same in all its blocks: given when it's first turned on, kept while it's off, and
     * taken along when it's joined, moved or resized. The owner's game remembers what each screen shows under it
     * (which window, which monitor), so other players never learn which programs someone uses.
     */
    private UUID screenId;

    /** A player a screen is shared with: who they are, and their name for showing. */
    public record Watcher(UUID id, String name) {}

    public ScreenBlockEntity(BlockPos pos, BlockState state) {
        super(type.get(), pos, state);
    }

    public static int clientChanges() {
        return clientChanges;
    }

    /** Every screen block in the client's world. Client thread only. */
    public static Set<ScreenBlockEntity> clientLoaded() {
        return CLIENT_LOADED;
    }

    /** The player who turned it on, or null while it's off. */
    public UUID owner() {
        return owner;
    }

    public String ownerName() {
        return ownerName;
    }

    /** The players the owner shares it with. */
    public List<Watcher> shared() {
        return shared;
    }

    /** Which screen this is ({@link #screenId}), or null for one never turned on (or turned on before screens had ids). */
    public UUID screenId() {
        return screenId;
    }

    /** Server side, before turning it on: which screen it is. Sent to the clients with the owner. */
    void setScreenId(UUID id) {
        screenId = id;
    }

    public boolean sharedWith(UUID player) {
        for (Watcher w : shared) {
            if (w.id().equals(player)) return true;
        }
        return false;
    }

    /** Server side: on for {@code player}, or off with null. Either way, sharing ends. Sends the change to the clients. */
    void setOwner(Player player) {
        owner = player == null ? null : player.getUUID();
        ownerName = player == null ? "" : player.getGameProfile().getName();
        shared = List.of();
        if (level != null) OwnedScreens.track(level, worldPosition, owner);
        sendChange();
    }

    /** Server side: takes over the owner (whom it's shared with, which screen it is) of a screen it just joined. */
    void copyOwner(ScreenBlockEntity from) {
        owner = from.owner;
        ownerName = from.ownerName;
        shared = from.shared;
        screenId = from.screenId;
        if (level != null) OwnedScreens.track(level, worldPosition, owner);
        sendChange();
    }

    /** Server side: whom the owner shares it with now. */
    void setShared(List<Watcher> watchers) {
        shared = List.copyOf(watchers);
        sendChange();
    }

    private void sendChange() {
        setChanged();
        if (level != null) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        owner = tag.hasUUID("owner") ? tag.getUUID("owner") : null;
        ownerName = tag.getString("owner_name");
        List<Watcher> watchers = new ArrayList<>();
        for (Tag entry : tag.getList("shared", Tag.TAG_COMPOUND)) {
            CompoundTag w = (CompoundTag) entry;
            if (w.hasUUID("id")) watchers.add(new Watcher(w.getUUID("id"), w.getString("name")));
        }
        shared = List.copyOf(watchers);
        screenId = tag.hasUUID("screen_id") ? tag.getUUID("screen_id") : null;
        changed();
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        if (screenId != null) tag.putUUID("screen_id", screenId);
        if (owner != null) {
            tag.putUUID("owner", owner);
            tag.putString("owner_name", ownerName);
            if (!shared.isEmpty()) {
                ListTag list = new ListTag();
                for (Watcher w : shared) {
                    CompoundTag entry = new CompoundTag();
                    entry.putUUID("id", w.id());
                    entry.putString("name", w.name());
                    list.add(entry);
                }
                tag.put("shared", list);
            }
        }
    }

    /** The owner goes to the clients with the chunk and with every change. */
    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = saveCustomOnly(registries);
        // An off screen saves nothing, and the client ignores an empty update, so it would stay on there.
        tag.putBoolean("on", owner != null);
        return tag;
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public void setBlockState(BlockState state) {
        super.setBlockState(state);
        changed();
    }

    @Override
    public void setRemoved() {
        super.setRemoved();
        if (level != null) OwnedScreens.track(level, worldPosition, null); // broken or unloaded
        if (level != null && level.isClientSide()) CLIENT_LOADED.remove(this);
        changed();
    }

    /** Added to the world: loaded with its chunk, or placed. */
    @Override
    public void clearRemoved() {
        super.clearRemoved();
        if (level != null) OwnedScreens.track(level, worldPosition, owner);
        if (level != null && level.isClientSide()) CLIENT_LOADED.add(this);
        changed();
    }

    private void changed() {
        if (level != null && level.isClientSide()) clientChanges++;
    }
}
