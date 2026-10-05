package com.desktopscreens;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.World;
import net.minecraft.world.storage.MapStorage;
import net.minecraft.world.storage.WorldSavedData;
import net.minecraftforge.common.util.Constants;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Whom each player shares all their screens with: every screen they turn on, now and later, on top of each screen's
 * own list (kept on the screen). Saved with the world (data/desktopscreens_sharing.dat, the same layout as on the
 * newer versions). Server thread only.
 */
public final class SharingData extends WorldSavedData {
    private static final String NAME = DesktopScreens.MOD_ID + "_sharing";

    private final Map<UUID, List<ScreenBlockEntity.Watcher>> allScreens = new HashMap<>();

    /** Minecraft makes it with this when it loads it. */
    public SharingData(String name) {
        super(name);
    }

    public static SharingData of(MinecraftServer server) {
        World overworld = server.getWorld(0);
        MapStorage storage = overworld.getMapStorage();
        SharingData data = (SharingData) storage.getOrLoadData(SharingData.class, NAME);
        if (data == null) {
            data = new SharingData(NAME);
            storage.setData(NAME, data);
        }
        return data;
    }

    /** Whom {@code owner} shares all their screens with. */
    public List<ScreenBlockEntity.Watcher> allScreens(UUID owner) {
        List<ScreenBlockEntity.Watcher> list = allScreens.get(owner);
        return list != null ? list : Collections.emptyList();
    }

    public void setAllScreens(UUID owner, List<ScreenBlockEntity.Watcher> watchers) {
        if (watchers.isEmpty()) allScreens.remove(owner);
        else allScreens.put(owner, Collections.unmodifiableList(new ArrayList<>(watchers)));
        markDirty();
    }

    public boolean sharesAll(UUID owner, UUID watcher) {
        for (ScreenBlockEntity.Watcher w : allScreens(owner)) {
            if (w.id.equals(watcher)) return true;
        }
        return false;
    }

    /** The players who share all their screens with {@code watcher}. */
    public List<UUID> ownersSharingAllWith(UUID watcher) {
        List<UUID> owners = new ArrayList<>();
        for (Map.Entry<UUID, List<ScreenBlockEntity.Watcher>> e : allScreens.entrySet()) {
            for (ScreenBlockEntity.Watcher w : e.getValue()) {
                if (w.id.equals(watcher)) owners.add(e.getKey());
            }
        }
        return owners;
    }

    @Override
    public void readFromNBT(NBTTagCompound tag) {
        allScreens.clear();
        NBTTagList owners = tag.getTagList("owners", Constants.NBT.TAG_COMPOUND);
        for (int i = 0; i < owners.tagCount(); i++) {
            NBTTagCompound owner = owners.getCompoundTagAt(i);
            if (!owner.hasUniqueId("owner")) continue;
            List<ScreenBlockEntity.Watcher> watchers = new ArrayList<>();
            NBTTagList list = owner.getTagList("all_screens", Constants.NBT.TAG_COMPOUND);
            for (int j = 0; j < list.tagCount(); j++) {
                NBTTagCompound watcher = list.getCompoundTagAt(j);
                if (watcher.hasUniqueId("id")) watchers.add(new ScreenBlockEntity.Watcher(watcher.getUniqueId("id"), watcher.getString("name")));
            }
            if (!watchers.isEmpty()) allScreens.put(owner.getUniqueId("owner"), Collections.unmodifiableList(watchers));
        }
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound tag) {
        NBTTagList owners = new NBTTagList();
        for (Map.Entry<UUID, List<ScreenBlockEntity.Watcher>> e : allScreens.entrySet()) {
            NBTTagCompound owner = new NBTTagCompound();
            owner.setUniqueId("owner", e.getKey());
            NBTTagList watchers = new NBTTagList();
            for (ScreenBlockEntity.Watcher w : e.getValue()) {
                NBTTagCompound watcher = new NBTTagCompound();
                watcher.setUniqueId("id", w.id);
                watcher.setString("name", w.name);
                watchers.appendTag(watcher);
            }
            owner.setTag("all_screens", watchers);
            owners.appendTag(owner);
        }
        tag.setTag("owners", owners);
        return tag;
    }
}
