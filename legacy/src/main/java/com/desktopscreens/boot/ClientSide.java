package com.desktopscreens.boot;

import com.desktopscreens.DesktopScreens;
import com.desktopscreens.ServerCheck;
import com.desktopscreens.Side;
import com.desktopscreens.TabletItem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.inventory.GuiContainerCreative;
import net.minecraft.client.renderer.block.model.ModelResourceLocation;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumActionResult;
import net.minecraftforge.client.event.EntityViewRenderEvent;
import net.minecraftforge.client.event.GuiScreenEvent;
import net.minecraftforge.client.event.ModelRegistryEvent;
import net.minecraftforge.client.event.MouseEvent;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.client.model.ModelLoader;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.player.AttackEntityEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

/**
 * The client: starts the client's code through {@link ClientLoader}, and passes Forge's events on to it (Forge's
 * event bus can't call into classes it doesn't load itself).
 */
@Mod.EventBusSubscriber(value = net.minecraftforge.fml.relauncher.Side.CLIENT, modid = DesktopScreens.MOD_ID)
public final class ClientSide extends Side {
    private static ClientHooks client;

    @Override
    public void init() {
        super.init();
        // Not connected (mods listing items at startup), or this connection's server sent its builder rules.
        ServerCheck.serverHasMod = () -> Minecraft.getMinecraft().getConnection() == null || ServerCheck.heard();
        // A mod that can't start its own part must not take the game down with it.
        try {
            ClientHooks hooks = ClientLoader.start();
            hooks.init();
            client = hooks;
        } catch (Exception | LinkageError e) {
            DesktopScreens.LOG.error("The client's part couldn't start; the mod does nothing in this game", e);
            return;
        }
        TabletItem.onUseClient = client::tabletUsed;
        MinecraftForge.EVENT_BUS.register(new Events());
    }

    /** Whether the client's part started (it does nothing otherwise, see {@link #init}). */
    static boolean running() {
        return client != null;
    }

    /** The settings screen, for the mods list. */
    static GuiScreen settingsScreen(GuiScreen parent) {
        return client != null ? client.settingsScreen(parent) : parent;
    }

    @SubscribeEvent
    public static void registerModels(ModelRegistryEvent event) {
        model(DesktopScreens.TABLET);
        model(DesktopScreens.SCREEN_ITEM);
        model(DesktopScreens.SCREEN_BUILDER);
    }

    private static void model(Item item) {
        ModelLoader.setCustomModelResourceLocation(item, 0, new ModelResourceLocation(item.getRegistryName(), "inventory"));
    }

    private static boolean ours(ItemStack stack) {
        Item item = stack.getItem();
        return item == DesktopScreens.TABLET || item == DesktopScreens.SCREEN_ITEM || item == DesktopScreens.SCREEN_BUILDER;
    }

    /** Registered once the client's code runs. */
    public static final class Events {
        @SubscribeEvent
        public void tick(TickEvent.ClientTickEvent event) {
            if (event.phase == TickEvent.Phase.END) client.tick();
        }

        @SubscribeEvent
        public void frame(TickEvent.RenderTickEvent event) {
            if (event.phase == TickEvent.Phase.START) client.frame();
        }

        /** Under the HUD: picture-in-picture goes before the hotbar, so the rest of the HUD goes over it. */
        @SubscribeEvent
        public void underHud(RenderGameOverlayEvent.Pre event) {
            if (event.getType() == RenderGameOverlayEvent.ElementType.HOTBAR) client.renderUnderHud();
        }

        /**
         * The whole HUD, left out while a screen in the world is used: that hides it as F1 does, but 1.12.2 draws it
         * under any open menu even with F1 on (the newer versions don't), so the crosshair stayed over the screen.
         */
        @SubscribeEvent
        public void hideHud(RenderGameOverlayEvent.Pre event) {
            if (event.getType() == RenderGameOverlayEvent.ElementType.ALL && client.hidesHud()) event.setCanceled(true);
        }

        @SubscribeEvent
        public void hud(RenderGameOverlayEvent.Post event) {
            if (event.getType() == RenderGameOverlayEvent.ElementType.ALL) {
                client.renderHud(event.getResolution().getScaledWidth(), event.getResolution().getScaledHeight());
            }
        }

        /** A menu darkened the world behind it: picture-in-picture goes there, under its panels. */
        @SubscribeEvent
        public void behindMenu(GuiScreenEvent.BackgroundDrawnEvent event) {
            client.renderBehindMenu(event.getGui());
        }

        @SubscribeEvent
        public void camera(EntityViewRenderEvent.CameraSetup event) {
            client.cameraSetup(event);
        }

        @SubscribeEvent
        public void world(RenderWorldLastEvent event) {
            client.renderWorld(event.getPartialTicks());
        }

        /** Clicks while playing: the Screen Builder takes use and attack while it's held, a shared screen an empty-hand use. */
        @SubscribeEvent
        public void mouse(MouseEvent event) {
            if (event.isButtonstate() && event.getButton() >= 0 && client.mouseButton(event.getButton())) event.setCanceled(true);
        }

        // Use and attack bound to keys don't come through the mouse event: they're taken where Minecraft acts on them.

        @SubscribeEvent
        public void useOnBlock(PlayerInteractEvent.RightClickBlock event) {
            if (event.getWorld().isRemote && client.use()) {
                event.setCanceled(true);
                event.setCancellationResult(EnumActionResult.SUCCESS);
            }
        }

        @SubscribeEvent
        public void useItem(PlayerInteractEvent.RightClickItem event) {
            if (event.getWorld().isRemote && client.use()) {
                event.setCanceled(true);
                event.setCancellationResult(EnumActionResult.SUCCESS);
            }
        }

        @SubscribeEvent
        public void useOnEntity(PlayerInteractEvent.EntityInteract event) {
            if (event.getWorld().isRemote && client.use()) {
                event.setCanceled(true);
                event.setCancellationResult(EnumActionResult.SUCCESS);
            }
        }

        @SubscribeEvent
        public void attackBlock(PlayerInteractEvent.LeftClickBlock event) {
            if (event.getWorld().isRemote && client.attack()) event.setCanceled(true);
        }

        @SubscribeEvent
        public void attackEntity(AttackEntityEvent event) {
            if (event.getEntityPlayer().world.isRemote && client.attack()) event.setCanceled(true);
        }

        /**
         * The creative menu's search on a server without the mod: our items out of what it found. Typing searches an
         * index of every item made when the game started, which the items' own check ({@link ServerCheck}) doesn't
         * reach; the tabs and the empty search do go through it.
         */
        @SubscribeEvent
        public void creativeSearch(GuiScreenEvent.KeyboardInputEvent.Post event) {
            if (!(event.getGui() instanceof GuiContainerCreative) || ServerCheck.serverHasMod.getAsBoolean()) return;
            GuiContainerCreative.ContainerCreative found = (GuiContainerCreative.ContainerCreative) ((GuiContainerCreative) event.getGui()).inventorySlots;
            if (found.itemList.removeIf(ClientSide::ours)) found.scrollTo(0); // the search shows from the top: again, without them
        }
    }
}
