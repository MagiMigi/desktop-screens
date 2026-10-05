package com.desktopscreens;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
//? if >=1.21.6 {
/*import net.minecraft.core.UUIDUtil;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
*///?}
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
 * blocks hold the same owner. Its picture (the owner's desktop) shows only in the owner's game and those of the
 * players it's shared with; to everyone else it stays dark. The client's renderer draws from it (one per block).
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
        ownerName = player == null ? "" : Players.name(player);
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

    //? if >=1.21.6 {
    /*// Saved the way 1.21.1 did (UUIDs as four ints), so worlds carry over.
    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        owner = input.read("owner", UUIDUtil.CODEC).orElse(null);
        ownerName = input.getStringOr("owner_name", "");
        List<Watcher> watchers = new ArrayList<>();
        for (ValueInput w : input.childrenListOrEmpty("shared")) {
            w.read("id", UUIDUtil.CODEC).ifPresent(id -> watchers.add(new Watcher(id, w.getStringOr("name", ""))));
        }
        shared = List.copyOf(watchers);
        screenId = input.read("screen_id", UUIDUtil.CODEC).orElse(null);
        changed();
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        output.storeNullable("screen_id", UUIDUtil.CODEC, screenId);
        if (owner != null) {
            output.store("owner", UUIDUtil.CODEC, owner);
            output.putString("owner_name", ownerName);
            if (!shared.isEmpty()) {
                ValueOutput.ValueOutputList list = output.childrenList("shared");
                for (Watcher w : shared) {
                    ValueOutput entry = list.addChild();
                    entry.store("id", UUIDUtil.CODEC, w.id());
                    entry.putString("name", w.name());
                }
            }
        }
    }
    *///?} else {
    @Override
    //? if >=1.20.5 {
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
    //?} else {
    /*public void load(CompoundTag tag) {
        super.load(tag);
    *///?}
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
    //? if >=1.20.5 {
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
    //?} else {
    /*protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
    *///?}
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
    //?}

    /** The owner goes to the clients with the chunk and with every change. */
    @Override
    //? if >=1.20.5 {
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = saveCustomOnly(registries);
    //?} else {
    /*public CompoundTag getUpdateTag() {
        CompoundTag tag = saveWithoutMetadata();
    *///?}
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
