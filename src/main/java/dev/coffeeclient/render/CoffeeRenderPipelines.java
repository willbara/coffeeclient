package dev.coffeeclient.render;

import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.shaders.ShaderSource;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import dev.coffeeclient.core.CoffeeClient;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;

/**
 * Client-side render pipelines for the world-space overlays (StorageESP, NewChunks, ...).
 *
 * <p>These deliberately use the mod's own tiny shaders rather than the vanilla {@code core/position_color}
 * and {@code core/rendertype_lines} programs:
 *
 * <ul>
 *   <li>Vanilla's line shader feeds the world fog into the line colour. Underground the fog colour is
 *       driven toward black by vanilla's "void darkness" term one the camera drops below
 *       {@code minY + 32} (see {@code FogRenderer#computeFogColor}), so ESP outlines faded to black
 *       the deeper you went.</li>
 *   <li>Shader packs remap the vanilla programs, which makes the overlays pick up the pack's lighting.
 *       Our own shaders are not remapped, so the overlays stay flat-coloured regardless.</li>
 * </ul>
 *
 * <p>The geometry only needs the projection and dynamic-transform uniforms (plus the screen size for the
 * line expansion), so the pipelines bind exactly those - no fog, no lighting, no lightmap.
 */
public abstract class CoffeeRenderPipelines {
    private static final List<RenderPipeline> PIPELINES = new ArrayList<>();

    private static final Identifier LINES_SHADER = Identifier.fromNamespaceAndPath("coffeeclient", "core/esp_lines");
    private static final Identifier COLOR_SHADER = Identifier.fromNamespaceAndPath("coffeeclient", "core/esp_color");

    /** Lines drawn through terrain (no depth test). */
    public static final RenderPipeline SEE_THROUGH_LINES = add(lines("pipeline/lines_seethrough", CompareOp.ALWAYS_PASS));
    /** Lines depth-tested against the world. */
    public static final RenderPipeline LINES = add(lines("pipeline/lines", DepthStencilState.DEFAULT.depthTest()));

    /** Filled faces drawn through terrain (no depth test). */
    public static final RenderPipeline SEE_THROUGH_FILLED = add(filled("pipeline/filled_seethrough", CompareOp.ALWAYS_PASS));
    /** Filled faces depth-tested against the world. */
    public static final RenderPipeline FILLED = add(filled("pipeline/filled", DepthStencilState.DEFAULT.depthTest()));
    /** Opaque faces that also write depth, so solid models hide their own back faces whatever the draw order. */
    public static final RenderPipeline SOLID = add(filled("pipeline/solid", DepthStencilState.DEFAULT.depthTest(), true));

    private static RenderPipeline lines(String location, CompareOp depthTest) {
        return RenderPipeline.builder()
            .withLocation(Identifier.fromNamespaceAndPath("coffeeclient", location))
            .withBindGroupLayout(BindGroupLayouts.GLOBALS)
            .withBindGroupLayout(BindGroupLayouts.PROJECTION)
            .withBindGroupLayout(BindGroupLayouts.DYNAMIC_TRANSFORMS)
            .withVertexShader(LINES_SHADER)
            .withFragmentShader(LINES_SHADER)
            .withVertexBinding(0, DefaultVertexFormat.POSITION_COLOR_NORMAL_LINE_WIDTH)
            .withPrimitiveTopology(PrimitiveTopology.LINES)
            .withDepthStencilState(new DepthStencilState(depthTest, false))
            .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
            .withCull(false)
            .build();
    }

    private static RenderPipeline filled(String location, CompareOp depthTest) {
        return filled(location, depthTest, false);
    }

    private static RenderPipeline filled(String location, CompareOp depthTest, boolean writeDepth) {
        return RenderPipeline.builder()
            .withLocation(Identifier.fromNamespaceAndPath("coffeeclient", location))
            .withBindGroupLayout(BindGroupLayouts.PROJECTION)
            .withBindGroupLayout(BindGroupLayouts.DYNAMIC_TRANSFORMS)
            .withVertexShader(COLOR_SHADER)
            .withFragmentShader(COLOR_SHADER)
            .withVertexBinding(0, DefaultVertexFormat.POSITION_COLOR)
            .withPrimitiveTopology(PrimitiveTopology.QUADS)
            .withDepthStencilState(new DepthStencilState(depthTest, writeDepth))
            .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
            .withCull(false)
            .build();
    }

    private static RenderPipeline add(RenderPipeline pipeline) {
        PIPELINES.add(pipeline);
        return pipeline;
    }

    /**
     * Compiles the pipelines into the device cache. Called after vanilla's shader reload, with the
     * reloaded shader sources (the shader manager loads every namespace's {@code shaders/}, ours included).
     */
    public static void precompile(ShaderSource shaderSource) {
        GpuDevice device = RenderSystem.getDevice();
        for (RenderPipeline pipeline : PIPELINES) {
            try {
                if (!device.precompilePipeline(pipeline, shaderSource).isValid()) {
                    CoffeeClient.LOG.warn("Overlay pipeline {} failed to compile; its overlays won't render", pipeline.getLocation());
                }
            } catch (Throwable t) {
                CoffeeClient.LOG.warn("Overlay pipeline {} failed to compile; its overlays won't render", pipeline.getLocation(), t);
            }
        }
    }

    private CoffeeRenderPipelines() {
    }
}
