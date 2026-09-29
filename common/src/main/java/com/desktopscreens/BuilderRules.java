package com.desktopscreens;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Mth;

/**
 * The server's rules for the Screen Builder: the biggest screen it may build, and how far away it works (to the
 * nearest block of where the screen goes), in blocks. From {@link ServerConfig}. The server sends them to each player
 * who joins, so the hologram stops where the server would refuse.
 */
public record BuilderRules(int maxWidth, int maxHeight, int range) implements CustomPacketPayload {
    public static final Type<BuilderRules> TYPE = new Type<>(DesktopScreens.id("builder_rules"));
    public static final StreamCodec<ByteBuf, BuilderRules> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, BuilderRules::maxWidth,
            ByteBufCodecs.VAR_INT, BuilderRules::maxHeight,
            ByteBufCodecs.VAR_INT, BuilderRules::range,
            BuilderRules::new);
    public static final BuilderRules DEFAULT = new BuilderRules(32, 32, 32);
    /** One joined screen counts at most {@link ScreenBlock#MAX_GROUP} (64 x 64) blocks. */
    private static final int MOST = 64;

    /** What the server the client plays on allows. Client side; the default until a server says otherwise. */
    private static volatile BuilderRules received = DEFAULT;

    public BuilderRules {
        maxWidth = Mth.clamp(maxWidth, 1, MOST);
        maxHeight = Mth.clamp(maxHeight, 1, MOST);
        range = Mth.clamp(range, 8, 128);
    }

    @Override
    public Type<BuilderRules> type() {
        return TYPE;
    }

    /** Server side: this server's rules. */
    public static BuilderRules of(MinecraftServer server) {
        ServerConfig config = ServerConfig.of(server);
        return new BuilderRules(config.builderMaxWidth, config.builderMaxHeight, config.builderRange);
    }

    /** Client side: the rules the server sent. */
    public static void received(BuilderRules rules) {
        received = rules;
    }

    /** Client side: what the server the client plays on allows. */
    public static BuilderRules current() {
        return received;
    }
}
