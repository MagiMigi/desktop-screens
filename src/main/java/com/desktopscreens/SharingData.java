package com.desktopscreens;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Whom each player shares all their screens with: every screen they turn on, now and later, on top of each screen's
 * own list (kept on the screen). Saved with the world. Server thread only.
 */
public final class SharingData extends SavedData {
    private static final String NAME = DesktopScreens.MOD_ID + "_sharing";

    private final Map<UUID, List<ScreenBlockEntity.Watcher>> allScreens = new HashMap<>();

    public static SharingData of(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(new SavedData.Factory<>(SharingData::new, SharingData::load, null), NAME);
    }

    /** Whom {@code owner} shares all their screens with. */
    public List<ScreenBlockEntity.Watcher> allScreens(UUID owner) {
        return allScreens.getOrDefault(owner, List.of());
    }

    public void setAllScreens(UUID owner, List<ScreenBlockEntity.Watcher> watchers) {
        if (watchers.isEmpty()) allScreens.remove(owner);
        else allScreens.put(owner, List.copyOf(watchers));
        setDirty();
    }

    public boolean sharesAll(UUID owner, UUID watcher) {
        for (ScreenBlockEntity.Watcher w : allScreens(owner)) {
            if (w.id().equals(watcher)) return true;
        }
        return false;
    }

    /** The players who share all their screens with {@code watcher}. */
    public List<UUID> ownersSharingAllWith(UUID watcher) {
        List<UUID> owners = new ArrayList<>();
        for (Map.Entry<UUID, List<ScreenBlockEntity.Watcher>> e : allScreens.entrySet()) {
            for (ScreenBlockEntity.Watcher w : e.getValue()) {
                if (w.id().equals(watcher)) owners.add(e.getKey());
            }
        }
        return owners;
    }

    private static SharingData load(CompoundTag tag, HolderLookup.Provider registries) {
        SharingData data = new SharingData();
        for (Tag o : tag.getList("owners", Tag.TAG_COMPOUND)) {
            CompoundTag owner = (CompoundTag) o;
            if (!owner.hasUUID("owner")) continue;
            List<ScreenBlockEntity.Watcher> watchers = new ArrayList<>();
            for (Tag w : owner.getList("all_screens", Tag.TAG_COMPOUND)) {
                CompoundTag watcher = (CompoundTag) w;
                if (watcher.hasUUID("id")) watchers.add(new ScreenBlockEntity.Watcher(watcher.getUUID("id"), watcher.getString("name")));
            }
            if (!watchers.isEmpty()) data.allScreens.put(owner.getUUID("owner"), List.copyOf(watchers));
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag owners = new ListTag();
        for (Map.Entry<UUID, List<ScreenBlockEntity.Watcher>> e : allScreens.entrySet()) {
            CompoundTag owner = new CompoundTag();
            owner.putUUID("owner", e.getKey());
            ListTag watchers = new ListTag();
            for (ScreenBlockEntity.Watcher w : e.getValue()) {
                CompoundTag watcher = new CompoundTag();
                watcher.putUUID("id", w.id());
                watcher.putString("name", w.name());
                watchers.add(watcher);
            }
            owner.put("all_screens", watchers);
            owners.add(owner);
        }
        tag.put("owners", owners);
        return tag;
    }
}
