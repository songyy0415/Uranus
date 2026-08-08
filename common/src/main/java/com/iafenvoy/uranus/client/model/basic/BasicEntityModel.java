package com.iafenvoy.uranus.client.model.basic;

import java.util.function.Function;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.resources.Identifier;

/**
 * Base class for the project's entity models, migrated to the 26.2 render-state model.
 *
 * <p>26.2 changed {@code EntityModel} from {@code EntityModel<T extends Entity>} to
 * {@code EntityModel<T extends EntityRenderState>}; the model generic now names the
 * renderer-extracted render state instead of the live entity. The old
 * {@code setupAnim(T entity, limbAngle, limbDistance, animationProgress, headYaw, headPitch)}
 * becomes {@code setupAnim(T state)}, where the state carries the animation inputs
 * ({@code walkAnimationPos/Speed}, {@code ageInTicks}, head/body rotation, pose, ...).
 *
 * <p>Rendering: 26.2 {@code Model.renderToBuffer} is final and renders the single
 * {@code ModelPart root}. The old override that iterated {@link #parts()} and drew each
 * {@link BasicModelPart} through a {@code VertexConsumer} can no longer be hooked here;
 * wiring {@code parts()} into the 26.2 submit pipeline (or rebuilding the parts as a
 * {@code ModelPart} tree) is out of this phase's scope. {@code parts()} is retained as
 * the project's top-level-parts accessor for subclasses and the future rendering path.
 */
public abstract class BasicEntityModel<T extends EntityRenderState> extends EntityModel<T> {
    public final int textureWidth = 64;
    public final int textureHeight = 32;

    protected BasicEntityModel(ModelPart root) {
        this(root, RenderTypes::entityCutout);
    }

    protected BasicEntityModel(ModelPart root, Function<Identifier, RenderType> layerFunction) {
        super(root, layerFunction);
    }

    public abstract Iterable<BasicModelPart> parts();

    @Override
    public abstract void setupAnim(T state);
}
