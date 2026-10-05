package com.desktopscreens;

import io.netty.buffer.ByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.MathHelper;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

/**
 * The server's rules for the Screen Builder: the biggest screen it may build, and how far away it works (to the
 * nearest block of where the screen goes), in blocks. From {@link ServerConfig}. The server sends them to each player
 * who joins, so the hologram stops where the server would refuse.
 */
public final class BuilderRules implements IMessage {
    public static final BuilderRules DEFAULT = new BuilderRules(32, 32, 32);
    /** One joined screen counts at most {@link ScreenBlock#MAX_GROUP} (64 x 64) blocks. */
    private static final int MOST = 64;

    /** What the server the client plays on allows. Client side; the default until a server says otherwise. */
    private static volatile BuilderRules received = DEFAULT;

    private int maxWidth, maxHeight, range;

    /** For the network: filled in by {@link #fromBytes}. */
    public BuilderRules() {
        this(DEFAULT.maxWidth, DEFAULT.maxHeight, DEFAULT.range);
    }

    public BuilderRules(int maxWidth, int maxHeight, int range) {
        set(maxWidth, maxHeight, range);
    }

    private void set(int width, int height, int reach) {
        maxWidth = MathHelper.clamp(width, 1, MOST);
        maxHeight = MathHelper.clamp(height, 1, MOST);
        range = MathHelper.clamp(reach, 8, 128);
    }

    public int maxWidth() {
        return maxWidth;
    }

    public int maxHeight() {
        return maxHeight;
    }

    public int range() {
        return range;
    }

    /** Server side: this server's rules. */
    public static BuilderRules of(MinecraftServer server) {
        ServerConfig config = ServerConfig.of(server);
        return new BuilderRules(config.builderMaxWidth, config.builderMaxHeight, config.builderRange);
    }

    /** Client side: what the server the client plays on allows. */
    public static BuilderRules current() {
        return received;
    }

    /** Client side: a server without the mod (or a new world) starts from the defaults again. Game thread. */
    public static void reset() {
        received = DEFAULT;
        ServerCheck.heard(false);
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        set(buf.readInt(), buf.readInt(), buf.readInt());
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(maxWidth);
        buf.writeInt(maxHeight);
        buf.writeInt(range);
    }

    /**
     * On the client, on the game's thread: it may come before the game has made its world for the new server, and
     * the game resets these every tick it has none ({@link #reset}), so on the network thread it could be lost.
     */
    public static final class Handler implements IMessageHandler<BuilderRules, IMessage> {
        @Override
        public IMessage onMessage(BuilderRules rules, MessageContext context) {
            FMLCommonHandler.instance().getWorldThread(context.netHandler).addScheduledTask(() -> {
                received = rules;
                ServerCheck.heard(true);
            });
            return null;
        }
    }
}
