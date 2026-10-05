package net.prason.xaeronav.client;

//? if >=1.21.11 {
/*import com.mojang.blaze3d.pipeline.RenderPipeline;
//? if >=26.1 {
/^import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.platform.CompareOp;
^///?} else {
import com.mojang.blaze3d.platform.DepthTestFunction;
//?}

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.LayeringTransform;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.resources.ResourceLocation;
import net.prason.xaeronav.XaeroNav;
*///?} else {
//? if >=1.21.5 {
/*import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.DepthTestFunction;

import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.ResourceLocation;
import net.prason.xaeronav.XaeroNav;
*///?} else {
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
//?}

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
//?}

/**
 * Render layers that stay visible even when blocked by terrain.
 *
 * <p>Standard layers like {@code RenderType.debugQuads()} have depth testing enabled, so anything drawn is
 * always hidden by blocks in front. Routes underwater (the water surface writes depth), and aerial routes and the dotted
 * line to the destination continuing beyond terrain, should show their hidden parts faintly too, so equivalent layers with
 * only the depth test turned off are provided.
 *
 * <p>Depth isn't written ({@code COLOR_WRITE}). Writing it would make translucent terrain drawn afterwards
 * go missing behind the route.
 */
final class NavRenderTypes {

    //? if >=1.21.11 {
    /*static final RenderType DEBUG_QUADS = RenderTypes.debugQuads();

    // Depth testing is owned by the RenderPipeline. Build one from the standard pipeline with only the depth test removed
    // (the original debug_quads doesn't write depth either)
    private static final RenderPipeline OCCLUDED_QUADS_PIPELINE = RenderPipeline.builder(RenderPipelines.DEBUG_FILLED_SNIPPET)
            .withLocation(ResourceLocation.fromNamespaceAndPath(XaeroNav.MOD_ID, "pipeline/occluded_quads"))
            .withCull(false)
            //? if >=26.1 {
            /^.withDepthStencilState(new DepthStencilState(CompareOp.ALWAYS_PASS, false))
            ^///?} else {
            .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
            //?}
            .build();

    static final RenderType OCCLUDED_QUADS = RenderType.create("xaeronav_occluded_quads",
            RenderSetup.builder(OCCLUDED_QUADS_PIPELINE).sortOnUpload().createRenderSetup());
    // Unlike vanilla (RenderTypes.lines()), lines are drawn to main rather than item_entity. Forge places its extra render pass
    // after Fabulous! compositing, so drawing to item_entity never gets composited onto the screen
    static final RenderType LINES = RenderType.create("xaeronav_lines",
            RenderSetup.builder(RenderPipelines.LINES)
                    .setLayeringTransform(LayeringTransform.VIEW_OFFSET_Z_LAYERING)
                    .createRenderSetup());

    /^* The pipeline turns off the depth test, so this just draws. ^/
    static void endOccludedBatch(MultiBufferSource.BufferSource bufferSource, RenderType type) {
        bufferSource.endBatch(type);
    }
    *///?} else if >=1.21.5 {
    /*static final RenderType DEBUG_QUADS = RenderType.debugQuads();
    //? if >=1.21.6 {
    /^// Forge's extra pass runs after Fabulous! compositing, so lines are also drawn to main rather than item_entity.
    static final RenderType LINES = createRenderType("xaeronav_lines", RenderPipelines.LINES,
            RenderType.CompositeState.builder()
                    .setLineState(new RenderStateShard.LineStateShard(java.util.OptionalDouble.empty()))
                    .setLayeringState(Shards.VIEW_OFFSET)
                    .createCompositeState(false));

    private static final class Shards extends RenderStateShard {
        static final LayeringStateShard VIEW_OFFSET = VIEW_OFFSET_Z_LAYERING;

        private Shards() {
            super("xaeronav_shards", () -> { }, () -> { });
        }
    }
    ^///?} else {
    static final RenderType LINES = RenderType.lines();
    //?}

    private static final RenderPipeline OCCLUDED_QUADS_PIPELINE = withoutDepthTest(RenderPipelines.DEBUG_QUADS);
    static final RenderType OCCLUDED_QUADS = createOccludedQuads();

    private static RenderType createOccludedQuads() {
        RenderType.CompositeState state = null;
        for (java.lang.reflect.Field field : DEBUG_QUADS.getClass().getDeclaredFields()) {
            if (field.getType() == RenderType.CompositeState.class) {
                try {
                    field.setAccessible(true);
                    state = (RenderType.CompositeState) field.get(DEBUG_QUADS);
                    break;
                } catch (ReflectiveOperationException exception) {
                    throw new ExceptionInInitializerError(exception);
                }
            }
        }
        if (state == null) {
            throw new ExceptionInInitializerError("RenderType composite state not found");
        }
        return createRenderType("xaeronav_occluded_quads", OCCLUDED_QUADS_PIPELINE, state);
    }

    private static RenderType createRenderType(String name, RenderPipeline pipeline, RenderType.CompositeState state) {
        for (java.lang.reflect.Method method : RenderType.class.getDeclaredMethods()) {
            Class<?>[] parameters = method.getParameterTypes();
            if (parameters.length == 4 && parameters[0] == String.class && parameters[1] == int.class
                    && parameters[2] == RenderPipeline.class && parameters[3] == RenderType.CompositeState.class) {
                try {
                    method.setAccessible(true);
                    return (RenderType) method.invoke(null, name, 1536, pipeline, state);
                } catch (ReflectiveOperationException exception) {
                    throw new ExceptionInInitializerError(exception);
                }
            }
        }
        throw new ExceptionInInitializerError("RenderType factory not found");
    }

    private static RenderPipeline withoutDepthTest(RenderPipeline source) {
        RenderPipeline.Builder builder = RenderPipeline.builder()
                .withLocation(ResourceLocation.fromNamespaceAndPath(XaeroNav.MOD_ID, "pipeline/occluded_quads"))
                .withVertexShader(source.getVertexShader())
                .withFragmentShader(source.getFragmentShader())
                .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
                .withPolygonMode(source.getPolygonMode())
                .withCull(source.isCull())
                .withColorWrite(source.isWriteColor(), source.isWriteAlpha())
                .withDepthWrite(false)
                //? if <1.21.6 {
                .withColorLogic(source.getColorLogic())
                //?}
                .withVertexFormat(source.getVertexFormat(), source.getVertexFormatMode())
                .withDepthBias(source.getDepthBiasScaleFactor(), source.getDepthBiasConstant());
        source.getBlendFunction().ifPresent(builder::withBlend);
        source.getSamplers().forEach(builder::withSampler);
        source.getUniforms().forEach(uniform -> builder.withUniform(uniform.name(), uniform.type()));
        source.getShaderDefines().flags().forEach(builder::withShaderDefine);
        source.getShaderDefines().values().forEach((name, value) -> {
            try {
                builder.withShaderDefine(name, Integer.parseInt(value));
            } catch (NumberFormatException ignored) {
                builder.withShaderDefine(name, Float.parseFloat(value));
            }
        });
        return builder.build();
    }

    static void endOccludedBatch(MultiBufferSource.BufferSource bufferSource, RenderType type) {
        bufferSource.endBatch(type);
    }
    *///?} else {
    static final RenderType DEBUG_QUADS =
            //? if >=1.20 {
            RenderType.debugQuads();
            //?} else {
            /*RenderType.lightning();
            *///?}
    static final RenderType LINES = RenderType.lines();

    //? if >=1.20 {
    static final RenderType OCCLUDED_QUADS = RenderType.create(
            "xaeronav_occluded_quads", DefaultVertexFormat.POSITION_COLOR, VertexFormat.Mode.QUADS, 1536, false, true,
            RenderType.CompositeState.builder()
                    .setShaderState(RenderStateShard.POSITION_COLOR_SHADER)
                    .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
                    .setCullState(RenderStateShard.NO_CULL)
                    .setDepthTestState(RenderStateShard.NO_DEPTH_TEST)
                    .setWriteMaskState(RenderStateShard.COLOR_WRITE)
                    .createCompositeState(false));
    //?} else if >=1.17 {
    /*// Fabric API before 1.20 doesn't widen the RenderStateShard constants, and while protected they can't be read from here.
    // From inside a subclass the inherited protected constants can be read, so an inner class exists just for that
    private static final class Shards extends RenderStateShard {
        static final ShaderStateShard POSITION_COLOR = POSITION_COLOR_SHADER;
        static final TransparencyStateShard TRANSLUCENT = TRANSLUCENT_TRANSPARENCY;
        static final CullStateShard NO_CULLING = NO_CULL;
        static final DepthTestStateShard NO_DEPTH = NO_DEPTH_TEST;
        static final WriteMaskStateShard COLOR_ONLY = COLOR_WRITE;

        private Shards() {
            super("xaeronav_shards", () -> { }, () -> { });
        }
    }

    static final RenderType OCCLUDED_QUADS = RenderType.create(
            "xaeronav_occluded_quads", DefaultVertexFormat.POSITION_COLOR, VertexFormat.Mode.QUADS, 1536, false, true,
            RenderType.CompositeState.builder()
                    .setShaderState(Shards.POSITION_COLOR)
                    .setTransparencyState(Shards.TRANSLUCENT)
                    .setCullState(Shards.NO_CULLING)
                    .setDepthTestState(Shards.NO_DEPTH)
                    .setWriteMaskState(Shards.COLOR_ONLY)
                    .createCompositeState(false));
    *///?} else {
    /*static final RenderType OCCLUDED_QUADS = RenderType.lightning();
    *///?}

    /**
     * Draws after turning off the depth test. {@code NO_DEPTH_TEST} (function "always") means, in vanilla's implementation,
     * <b>don't touch</b> the depth test state; it doesn't turn it off. NeoForge/Forge's
     * {@code AFTER_TRANSLUCENT_BLOCKS} is called <b>before</b> the cleanup after drawing translucent terrain, so
     * the depth test is still enabled. Without turning it off, lines underwater stay hidden.
     * No cleanup is needed; the next layer drawn sets its own depth test.
     */
    static void endOccludedBatch(MultiBufferSource.BufferSource bufferSource, RenderType type) {
        RenderSystem.disableDepthTest();
        bufferSource.endBatch(type);
    }
    //?}

    private NavRenderTypes() {
    }
}
