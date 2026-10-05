package com.desktopscreens;

import net.minecraft.core.HolderLookup;
//? if >=26.1 {
/*import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.UUIDUtil;
import net.minecraft.world.level.saveddata.SavedDataType;
*///?}
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

    //? if >=26.1 {
    /*private static final Codec<ScreenBlockEntity.Watcher> WATCHER = RecordCodecBuilder.create(i -> i.group(
            UUIDUtil.CODEC.fieldOf("id").forGetter(ScreenBlockEntity.Watcher::id),
            Codec.STRING.fieldOf("name").forGetter(ScreenBlockEntity.Watcher::name)
    ).apply(i, ScreenBlockEntity.Watcher::new));
    /^* One owner's "all your screens" list. ^/
    private record Owner(UUID owner, List<ScreenBlockEntity.Watcher> allScreens) {}
    private static final Codec<Owner> OWNER = RecordCodecBuilder.create(i -> i.group(
            UUIDUtil.CODEC.fieldOf("owner").forGetter(Owner::owner),
            WATCHER.listOf().fieldOf("all_screens").forGetter(Owner::allScreens)
    ).apply(i, Owner::new));
    /^* The same layout as before 26.1, in data/desktopscreens/sharing.dat (no data fixer: it never changed). ^/
    private static final Codec<SharingData> CODEC = OWNER.listOf().fieldOf("owners").codec().xmap(owners -> {
        SharingData data = new SharingData();
        for (Owner o : owners) {
            if (!o.allScreens().isEmpty()) data.allScreens.put(o.owner(), List.copyOf(o.allScreens()));
        }
        return data;
    }, data -> {
        List<Owner> owners = new ArrayList<>();
        data.allScreens.forEach((owner, watchers) -> owners.add(new Owner(owner, watchers)));
        return owners;
    });
    private static final SavedDataType<SharingData> TYPE = new SavedDataType<>(DesktopScreens.id("sharing"), SharingData::new, CODEC, null);
    *///?}

    public static SharingData of(MinecraftServer server) {
        //? if >=26.1 {
        /*return server.overworld().getDataStorage().computeIfAbsent(TYPE);
        *///?} else if >=1.20.5 {
        return server.overworld().getDataStorage().computeIfAbsent(new SavedData.Factory<>(SharingData::new, SharingData::load, null), NAME);
        //?} else {
        /*return server.overworld().getDataStorage().computeIfAbsent(SharingData::load, SharingData::new, NAME);
        *///?}
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

    //? if <26.1 {
    //? if >=1.20.5 {
    private static SharingData load(CompoundTag tag, HolderLookup.Provider registries) {
    //?} else {
    /*private static SharingData load(CompoundTag tag) {
    *///?}
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
    //? if >=1.20.5 {
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
    //?} else {
    /*public CompoundTag save(CompoundTag tag) {
    *///?}
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
    //?}
}
