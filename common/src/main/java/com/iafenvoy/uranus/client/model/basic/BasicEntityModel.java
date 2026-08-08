package com.iafenvoy.uranus.client.model.basic;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.function.Function;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;

public abstract class BasicEntityModel<T extends Entity> extends EntityModel<T> {
    public final int textureWidth = 64;
    public final int textureHeight = 32;

    protected BasicEntityModel() {
        this(RenderType::entityCutoutNoCull);
    }

    protected BasicEntityModel(Function<ResourceLocation, RenderType> layerFunction) {
        super(layerFunction);
    }

    @Override
    public void renderToBuffer(PoseStack matrices, VertexConsumer vertices, int light, int overlay, int color) {
        this.parts().forEach(part -> part.render(matrices, vertices, light, overlay, color));
    }

    public abstract Iterable<BasicModelPart> parts();

    @Override
    public abstract void setupAnim(T entity, float limbAngle, float limbDistance, float animationProgress, float headYaw, float headPitch);

    @Override
    public void prepareMobModel(T entity, float limbAngle, float limbDistance, float tickDelta) {
    }
}