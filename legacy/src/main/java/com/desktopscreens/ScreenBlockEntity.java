package com.desktopscreens;

import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.play.server.SPacketUpdateTileEntity;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.common.util.Constants;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * What a screen block remembers: who turned it on, if anyone. A joined screen is switched as a whole, so all its
 * blocks hold the same owner. Only the owner's game shows a picture on it (their own desktop); to everyone else it
 * stays dark. Also what the client draws the picture from (the renderer is per block entity).
 */
public final class ScreenBlockEntity extends TileEntity {
    /**
     * Goes up whenever a screen in the client's world appears, goes or changes owner, so the client knows its
     * worked-out screen groups are stale. Written on the client thread only.
     */
    private static volatile int clientChanges;
    /** The screens in the client's world, so their sound can play while they're out of sight. Client thread only. */
    private static final Set<ScreenBlockEntity> CLIENT_LOADED = new HashSet<>();

    private UUID owner;
    private String ownerName = "";
    /** Who else sees the owner's desktop on it (see ScreenSharing, with part D). Empty while it's off. */
    private List<Watcher> shared = Collections.emptyList();
    /**
     * Which screen this is, the same in all its blocks: given when it's first turned on, kept while it's off, and
     * taken along when it's joined, moved or resized. The owner's game remembers what each screen shows under it
     * (which window, which monitor), so other players never learn which programs someone uses.
     */
    private UUID screenId;

    /** A player a screen is shared with: who they are, and their name for showing. */
    public static final class Watcher {
        public final UUID id;
        public final String name;

        public Watcher(UUID id, String name) {
            this.id = id;
            this.name = name;
        }
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

    /** Which screen this is ({@link #screenId}), or null for one never turned on. */
    public UUID screenId() {
        return screenId;
    }

    /** Server side, before turning it on: which screen it is. Sent to the clients with the owner. */
    void setScreenId(UUID id) {
        screenId = id;
    }

    public boolean sharedWith(UUID player) {
        for (Watcher w : shared) {
            if (w.id.equals(player)) return true;
        }
        return false;
    }

    /** Server side: on for {@code player}, or off with null. Either way, sharing ends. Sends the change to the clients. */
    void setOwner(EntityPlayer player) {
        owner = player == null ? null : player.getUniqueID();
        ownerName = player == null ? "" : Players.name(player);
        shared = Collections.emptyList();
        if (world != null) OwnedScreens.track(world, pos, owner);
        sendChange();
    }

    /** Server side: takes over the owner (whom it's shared with, which screen it is) of a screen it just joined. */
    void copyOwner(ScreenBlockEntity from) {
        owner = from.owner;
        ownerName = from.ownerName;
        shared = from.shared;
        screenId = from.screenId;
        if (world != null) OwnedScreens.track(world, pos, owner);
        sendChange();
    }

    /** Server side: whom the owner shares it with now. */
    void setShared(List<Watcher> watchers) {
        shared = Collections.unmodifiableList(new ArrayList<>(watchers));
        sendChange();
    }

    private void sendChange() {
        markDirty();
        if (world != null) {
            IBlockState state = world.getBlockState(pos);
            world.notifyBlockUpdate(pos, state, state, 2); // to the clients
        }
    }

    @Override
    public void readFromNBT(NBTTagCompound tag) {
        super.readFromNBT(tag);
        owner = tag.hasUniqueId("owner") ? tag.getUniqueId("owner") : null;
        ownerName = tag.getString("owner_name");
        List<Watcher> watchers = new ArrayList<>();
        NBTTagList list = tag.getTagList("shared", Constants.NBT.TAG_COMPOUND);
        for (int i = 0; i < list.tagCount(); i++) {
            NBTTagCompound w = list.getCompoundTagAt(i);
            if (w.hasUniqueId("id")) watchers.add(new Watcher(w.getUniqueId("id"), w.getString("name")));
        }
        shared = Collections.unmodifiableList(watchers);
        screenId = tag.hasUniqueId("screen_id") ? tag.getUniqueId("screen_id") : null;
        changed();
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound tag) {
        super.writeToNBT(tag);
        if (screenId != null) tag.setUniqueId("screen_id", screenId);
        if (owner != null) {
            tag.setUniqueId("owner", owner);
            tag.setString("owner_name", ownerName);
            if (!shared.isEmpty()) {
                NBTTagList list = new NBTTagList();
                for (Watcher w : shared) {
                    NBTTagCompound entry = new NBTTagCompound();
                    entry.setUniqueId("id", w.id);
                    entry.setString("name", w.name);
                    list.appendTag(entry);
                }
                tag.setTag("shared", list);
            }
        }
        return tag;
    }

    /** The owner goes to the clients with the chunk and with every change. */
    @Override
    public NBTTagCompound getUpdateTag() {
        return writeToNBT(new NBTTagCompound());
    }

    @Override
    public SPacketUpdateTileEntity getUpdatePacket() {
        return new SPacketUpdateTileEntity(pos, 0, getUpdateTag());
    }

    @Override
    public void onDataPacket(NetworkManager net, SPacketUpdateTileEntity packet) {
        readFromNBT(packet.getNbtCompound());
    }

    /** Only the facing is the block's own; joining, on or off never replace this. */
    @Override
    public boolean shouldRefresh(World world, BlockPos pos, IBlockState oldState, IBlockState newState) {
        return oldState.getBlock() != newState.getBlock();
    }

    /** Taken away: broken, or unloaded with its chunk. */
    @Override
    public void invalidate() {
        super.invalidate();
        removed();
    }

    @Override
    public void onChunkUnload() {
        super.onChunkUnload();
        removed();
    }

    private void removed() {
        if (world != null) OwnedScreens.track(world, pos, null);
        if (world != null && world.isRemote) CLIENT_LOADED.remove(this);
        changed();
    }

    /** Added to the world: loaded with its chunk, or placed. */
    @Override
    public void onLoad() {
        super.onLoad();
        if (world != null) OwnedScreens.track(world, pos, owner);
        if (world != null && world.isRemote) CLIENT_LOADED.add(this);
        changed();
    }

    private void changed() {
        if (world != null && world.isRemote) clientChanges++;
    }
}
