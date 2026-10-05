package com.desktopscreens.net;

import net.minecraft.resources.ResourceLocation;

/**
 * For Minecraft before 1.20.5, which has no {@code CustomPacketPayload}: the same shape, so our messages are written
 * once for every version (their files import this one or Minecraft's). {@link Payloads} turns them into Minecraft's
 * custom payload packets of that time: the type's id and the bytes.
 */
public interface CustomPacketPayload {
    Type<? extends CustomPacketPayload> type();

    record Type<T extends CustomPacketPayload>(ResourceLocation id) {}
}
