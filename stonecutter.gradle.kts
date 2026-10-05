plugins {
    id("dev.kikugie.stonecutter")
    // Loaded here once, so every version's project shares them.
    // Fabric: one for obfuscated versions (1.21.1 and older), one for 26.1 on (see loom-back-compat in settings.gradle.kts).
    id("net.fabricmc.fabric-loom-remap") version "1.17-SNAPSHOT" apply false
    id("net.fabricmc.fabric-loom") version "1.17-SNAPSHOT" apply false
    id("net.neoforged.moddev") version "2.0.147" apply false
    id("net.neoforged.moddev.legacyforge") version "2.0.147" apply false // Forge up to 1.20.1
}

// The version the code in src/ is set up for right now (Stonecutter rewrites this line when switching).
stonecutter active "1.21.1-neoforge"

stonecutter parameters {
    val (version, loader) = current.project.split('-', limit = 2)
    // Properties from stonecutter.properties.toml: the ones for this version and this loader.
    properties {
        tags(version, loader)
    }
    // "//? if fabric {" and friends in the code.
    constants {
        match(loader, "fabric", "neoforge", "forge")
    }
    // Plain renames, made in the code of the versions that have them (it's written for 1.21.1).
    replacements {
        string(current.parsed >= "1.21.11") {
            replace("ResourceLocation", "Identifier")
            replace("import net.minecraft.Util;", "import net.minecraft.util.Util;")
            replace("import net.minecraft.client.renderer.RenderType;", "import net.minecraft.client.renderer.rendertype.RenderType;")
        }
        string(current.parsed >= "1.21.9") {
            replace("getWindow().getWindow()", "getWindow().handle()")
        }
        string(current.parsed >= "26.1") {
            replace("GuiGraphics", "GuiGraphicsExtractor")
            replace("mc.getTimer()", "mc.getDeltaTracker()")
            // Fabric API took Mojang's names too.
            replace("PayloadTypeRegistry.playC2S()", "PayloadTypeRegistry.serverboundPlay()")
            replace("PayloadTypeRegistry.playS2C()", "PayloadTypeRegistry.clientboundPlay()")
            replace("import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;", "import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents;")
            replace("ItemGroupEvents.modifyEntriesEvent(", "CreativeModeTabEvents.modifyOutputEvent(")
            replace("import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;", "import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;")
            replace("KeyBindingHelper.registerKeyBinding(", "KeyMappingHelper.registerKeyMapping(")
        }
        string(current.parsed >= "26.1" && current.parsed < "26.2") {
            replace("getChat().addMessage(", "getChat().addClientSystemMessage(")
            replace("mc.getToasts()", "mc.getToastManager()")
            replace("getMainCamera().getPosition()", "getMainCamera().position()")
        }
        // 26.2 moved the open screen, toasts, chat and the hidden HUD (F1) from Minecraft to its Gui and Hud.
        string(current.parsed >= "26.2") {
            replace("mc.getToasts()", "mc.gui.toastManager()")
            replace("getMainCamera().getPosition()", "mainCamera().position()")
            replace("mc.screen", "mc.gui.screen()")
            replace("mc.setScreen(", "mc.gui.setScreen(")
            replace("minecraft.setScreen(", "minecraft.gui.setScreen(")
            // The same text as 26.1's: when two texts overlap, the longer one is picked before its version is checked.
            replace("getChat().addMessage(", "hud.getChat().addClientSystemMessage(")
            replace("mc.gui.setOverlayMessage(", "mc.gui.hud.setOverlayMessage(")
            replace("mc.options.hideGui", "mc.gui.hud.isHidden()")
        }
        // 26.3 moved Blaze3D's drawing API into Renderpearl, and draws its window with SDL instead of GLFW.
        string(current.parsed >= "26.3") {
            replace("import com.mojang.blaze3d.GpuFormat;", "import com.mojang.renderpearl.api.GpuFormat;")
            replace("import com.mojang.blaze3d.PrimitiveTopology;", "import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;")
            replace("import com.mojang.blaze3d.opengl.GlStateManager;", "import com.mojang.renderpearl.backend.opengl.GlStateManager;")
            replace("import com.mojang.blaze3d.opengl.GlTexture;", "import com.mojang.renderpearl.backend.opengl.GlTexture;")
            replace("import com.mojang.blaze3d.pipeline.ColorTargetState;", "import com.mojang.renderpearl.api.pipeline.ColorTargetState;")
            replace("import com.mojang.blaze3d.pipeline.DepthStencilState;", "import com.mojang.renderpearl.api.pipeline.DepthStencilState;")
            replace(".withBindGroupLayout(BindGroupLayouts.MATRICES_PROJECTION)",
                ".withBindGroupLayout(BindGroupLayouts.PROJECTION).withBindGroupLayout(BindGroupLayouts.DYNAMIC_TRANSFORMS)")
            replace("import com.mojang.blaze3d.pipeline.RenderPipeline;", "import com.mojang.renderpearl.api.pipeline.RenderPipeline;")
            replace("import com.mojang.blaze3d.platform.CompareOp;", "import com.mojang.renderpearl.api.pipeline.CompareOp;")
            replace("import com.mojang.blaze3d.systems.CommandEncoder;", "import com.mojang.renderpearl.api.commands.CommandEncoder;")
            replace("import com.mojang.blaze3d.systems.GpuDevice;", "import com.mojang.renderpearl.api.device.GpuDevice;")
            replace("import com.mojang.blaze3d.systems.RenderPass;", "import com.mojang.renderpearl.api.commands.RenderPass;")
            replace("import com.mojang.blaze3d.textures.FilterMode;", "import com.mojang.renderpearl.api.textures.FilterMode;")
            replace("import com.mojang.blaze3d.textures.GpuTexture;", "import com.mojang.renderpearl.api.textures.GpuTexture;")
            replace("import com.mojang.blaze3d.textures.GpuTextureView;", "import com.mojang.renderpearl.api.textures.GpuTextureView;")
            replace("import com.mojang.blaze3d.vertex.VertexFormat;", "import com.mojang.renderpearl.api.vertex.VertexFormat;")
            replace("InputConstants.Type.KEYSYM", "InputConstants.Type.KEYBOARD")
            // The window no longer says whether it's fullscreen; it follows the option.
            replace("mc.getWindow().isFullscreen()", "mc.options.fullscreen().get()")
            // Pipelines are compiled ahead and handed to a pass compiled; textures are bound as uniforms.
            replace("pass.setPipeline(pipeline);", "pass.setPipeline(RenderSystem.getCompiledPipeline(pipeline));")
            replace("pass.bindTexture(", "pass.setUniform(")
            replace("RenderSystem.getDevice().precompilePipeline(UNPACK).isValid()", "RenderSystem.getCompiledPipelineNullable(UNPACK) != null")
            replace("RenderSystem.getDevice().precompilePipeline(SHARP).isValid()", "RenderSystem.getCompiledPipelineNullable(SHARP) != null")
            replace("pose.mulPose(", "pose.rotate(")
            replace("player.swing(hand);", "player.swing(hand, SwingAnimation.DEFAULT, false);")
        }
    }
}
