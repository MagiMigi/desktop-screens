package com.desktopscreens.mixin;

import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.ChannelAccess;
import net.minecraft.client.sounds.SoundEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;

/**
 * Each playing sound's channel. A shared screen's sound refills its channel more often than Minecraft's once a tick,
 * to keep the delay short (see {@code SharedSounds}). Client thread only, like the map itself.
 */
@Mixin(SoundEngine.class)
public interface SoundEngineAccessor {
    @Accessor("instanceToChannel")
    Map<SoundInstance, ChannelAccess.ChannelHandle> desktopscreens$channels();
}
