package com.desktopscreens.client;

import com.desktopscreens.DesktopScreens;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.AudioStream;
import net.minecraft.client.sounds.SoundBufferLibrary;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.client.sounds.WeighedSoundEvents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.valueproviders.ConstantFloat;
import net.minecraft.world.phys.Vec3;

import java.util.concurrent.CompletableFuture;

/**
 * A shared screen's sound in Minecraft's sound engine: a stream of our own ({@link SharedSounds}), on the
 * Jukebox/Note Blocks volume. It's stereo, and OpenAL doesn't place stereo sound anywhere, so the position only
 * serves the subtitle's arrow; how near the screen is comes in through the volume, set every tick.
 *
 * <p>No sound file behind it: {@link #resolve} makes up the streamed {@link Sound} itself, and the stream comes from
 * {@link #getStream} on NeoForge and {@link #getAudioStream} on Fabric. Neither exists in plain Minecraft, where this
 * is compiled first, so neither says {@code @Override}; each loader compiles these same sources against its own
 * Minecraft, where its one overrides its loader's hook.
 */
final class SharedSound extends AbstractTickableSoundInstance {
    static final ResourceLocation ID = DesktopScreens.id("shared_screen");
    private final AudioStream stream;

    SharedSound(AudioStream stream, Vec3 at, float volume) {
        super(SoundEvent.createVariableRangeEvent(ID), SoundSource.RECORDS, SoundInstance.createUnseededRandom());
        this.stream = stream;
        this.attenuation = Attenuation.NONE;
        this.looping = false;
        move(at, volume);
    }

    void move(Vec3 at, float volume) {
        x = at.x;
        y = at.y;
        z = at.z;
        this.volume = volume;
    }

    void finish() {
        stop();
    }

    @Override
    public WeighedSoundEvents resolve(SoundManager manager) {
        sound = new Sound(ID, ConstantFloat.of(1), ConstantFloat.of(1), 1, Sound.Type.FILE, true, false, 16);
        WeighedSoundEvents events = new WeighedSoundEvents(ID, "desktopscreens.subtitle.shared_screen");
        events.addSound(sound);
        return events;
    }

    /** It may start at the edge of hearing. */
    @Override
    public boolean canStartSilent() {
        return true;
    }

    @Override
    public void tick() {
        // SharedSounds moves it and sets its volume.
    }

    /** NeoForge's hook ({@code SoundInstance.getStream}). */
    public CompletableFuture<AudioStream> getStream(SoundBufferLibrary buffers, Sound sound, boolean looping) {
        return CompletableFuture.completedFuture(stream);
    }

    /** Fabric's hook ({@code FabricSoundInstance.getAudioStream}). */
    public CompletableFuture<AudioStream> getAudioStream(SoundBufferLibrary buffers, ResourceLocation id, boolean repeatInstantly) {
        return CompletableFuture.completedFuture(stream);
    }
}
