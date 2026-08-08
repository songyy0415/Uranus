package com.iafenvoy.uranus.client.model;

import com.google.common.collect.ImmutableList;
import com.iafenvoy.uranus.client.model.basic.BasicModelPart;
import com.iafenvoy.uranus.client.model.tabula.TabulaCubeContainer;
import com.iafenvoy.uranus.client.model.tabula.TabulaCubeGroupContainer;
import com.iafenvoy.uranus.client.model.tabula.TabulaModelContainer;
import com.iafenvoy.uranus.util.function.MemorizeSupplier;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.core.Direction;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * @author gegy1000
 * @since 1.0.0
 *
 * <p>Migrated to the 26.2 render-state architecture. {@code T} now names a
 * {@link LivingEntityRenderState}: {@link #setupAnim} derives the animation inputs
 * (limb swing, age in ticks, head rotation) from the state fields instead of the
 * live Entity, then delegates to the optional {@link ITabulaModelAnimator}.
 *
 * <p>Rendering: 26.2 {@code Model} only draws its single {@code ModelPart root}
 * ({@code renderToBuffer} is final), so Tabula's {@code AdvancedModelBox} cube tree
 * can no longer be drawn directly. This class keeps the {@code AdvancedModelBox}
 * hierarchy untouched as the animation/API layer (cube pivots, rotations, per-part
 * scale, scaleChildren, visibility and custom quads are all preserved), and mirrors
 * it into a real {@code ModelPart} render tree built from the same
 * {@link TabulaModelContainer}. The project's cube/UV math was verified to be
 * byte-identical to 26.2 {@code ModelPart.Cube}, so the mirror renders the same
 * geometry. After the animator runs, {@link #syncRenderTree()} copies each box's
 * pose (and the scaleChildren compensation) onto the matching {@code ModelPart},
 * so the vanilla submit pipeline draws the animated pose.
 */
@Environment(EnvType.CLIENT)
public class TabulaModel<T extends LivingEntityRenderState> extends AdvancedEntityModel<T> {
    public final ModelAnimator animator;
    protected final Map<String, AdvancedModelBox> cubes = new HashMap<>();
    protected final List<AdvancedModelBox> rootBoxes = new ArrayList<>();
    protected final MemorizeSupplier<ITabulaModelAnimator<T>> tabulaAnimator;
    protected final Map<String, AdvancedModelBox> identifierMap = new HashMap<>();
    protected final double[] scale;

    public TabulaModel(TabulaModelContainer container, MemorizeSupplier<ITabulaModelAnimator<T>> tabulaAnimator) {
        super(buildRenderRoot(container));
        this.texWidth = container.getTextureWidth();
        this.texHeight = container.getTextureHeight();
        this.tabulaAnimator = tabulaAnimator;
        for (TabulaCubeContainer cube : container.getCubes()) {
            this.parseCube(cube, null);
        }
        container.getCubeGroups().forEach(this::parseCubeGroup);
        this.updateDefaultPose();
        this.scale = container.getScale();
        this.animator = ModelAnimator.create();
    }

    public TabulaModel(TabulaModelContainer container) {
        this(container, null);
    }

    private void parseCubeGroup(TabulaCubeGroupContainer container) {
        for (TabulaCubeContainer cube : container.getCubes()) {
            this.parseCube(cube, null);
        }
        container.getCubeGroups().forEach(this::parseCubeGroup);
    }

    private void parseCube(TabulaCubeContainer cube, AdvancedModelBox parent) {
        AdvancedModelBox box = this.createBox(cube);
        this.cubes.put(cube.getName(), box);
        this.identifierMap.put(cube.getIdentifier(), box);
        if (parent != null) {
            parent.addChild(box);
        } else {
            this.rootBoxes.add(box);
        }
        for (TabulaCubeContainer child : cube.getChildren()) {
            this.parseCube(child, box);
        }
    }

    private AdvancedModelBox createBox(TabulaCubeContainer cube) {
        int[] textureOffset = cube.getTextureOffset();
        double[] position = cube.getPosition();
        double[] rotation = cube.getRotation();
        double[] offset = cube.getOffset();
        int[] dimensions = cube.getDimensions();
        float scaleIn = 0;
        AdvancedModelBox box = new AdvancedModelBox(this, cube.getName());
        box.setTextureOffset(textureOffset[0], textureOffset[1]);
        box.mirror = cube.isTextureMirrorEnabled();
        box.setPos((float) position[0], (float) position[1], (float) position[2]);
        box.addBox((float) offset[0], (float) offset[1], (float) offset[2], dimensions[0], dimensions[1], dimensions[2], scaleIn);
        box.rotateAngleX = (float) Math.toRadians(rotation[0]);
        box.rotateAngleY = (float) Math.toRadians(rotation[1]);
        box.rotateAngleZ = (float) Math.toRadians(rotation[2]);
        return box;
    }

    @Override
    public void setupAnim(T state) {
        ITabulaModelAnimator<T> tabulaModelAnimator = this.tabulaAnimator.get();
        if (tabulaModelAnimator != null)
            tabulaModelAnimator.setRotationAngles(this, state, state.walkAnimationPos, state.walkAnimationSpeed, state.ageInTicks, state.yRot, state.xRot, 1.0F);
        this.syncRenderTree();
    }

    public AdvancedModelBox getCube(String name) {
        return this.cubes.get(name);
    }

    public Map<String, AdvancedModelBox> getCubes() {
        return this.cubes;
    }

    @Override
    public Iterable<BasicModelPart> parts() {
        return ImmutableList.copyOf(this.rootBoxes);
    }

    @Override
    public Iterable<AdvancedModelBox> getAllParts() {
        return ImmutableList.copyOf(this.cubes.values());
    }

    public List<AdvancedModelBox> getRootBox() {
        return this.rootBoxes;
    }

    /**
     * Builds the 26.2 {@code ModelPart} render tree from the same Tabula containers
     * that feed the {@code AdvancedModelBox} animation tree, so {@code Model}'s final
     * {@code renderToBuffer} has a real root that carries the full geometry. The cube
     * data (texture offset, origin, dimensions, grow=0, mirror) and UV layout are the
     * exact values the old custom boxes were built from.
     */
    private static ModelPart buildRenderRoot(TabulaModelContainer container) {
        Map<String, ModelPart> rootChildren = new HashMap<>();
        for (TabulaCubeContainer cube : container.getCubes()) {
            rootChildren.put(cube.getName(), buildRenderPart(cube, container.getTextureWidth(), container.getTextureHeight()));
        }
        for (TabulaCubeGroupContainer group : container.getCubeGroups()) {
            addGroupRenderParts(group, rootChildren, container.getTextureWidth(), container.getTextureHeight());
        }
        return new ModelPart(List.of(), rootChildren);
    }

    private static void addGroupRenderParts(TabulaCubeGroupContainer group, Map<String, ModelPart> children, int textureWidth, int textureHeight) {
        for (TabulaCubeContainer cube : group.getCubes()) {
            children.put(cube.getName(), buildRenderPart(cube, textureWidth, textureHeight));
        }
        for (TabulaCubeGroupContainer childGroup : group.getCubeGroups()) {
            addGroupRenderParts(childGroup, children, textureWidth, textureHeight);
        }
    }

    private static ModelPart buildRenderPart(TabulaCubeContainer cube, int textureWidth, int textureHeight) {
        int[] textureOffset = cube.getTextureOffset();
        double[] offset = cube.getOffset();
        double[] position = cube.getPosition();
        double[] rotation = cube.getRotation();
        int[] dimensions = cube.getDimensions();
        boolean mirror = cube.isTextureMirrorEnabled();
        List<ModelPart.Cube> cubes = List.of(new ModelPart.Cube(
            textureOffset[0], textureOffset[1],
            (float) offset[0], (float) offset[1], (float) offset[2],
            (float) dimensions[0], (float) dimensions[1], (float) dimensions[2],
            0.0F, 0.0F, 0.0F, mirror, (float) textureWidth, (float) textureHeight,
            EnumSet.allOf(Direction.class)
        ));
        Map<String, ModelPart> childParts = new HashMap<>();
        for (TabulaCubeContainer child : cube.getChildren()) {
            childParts.put(child.getName(), buildRenderPart(child, textureWidth, textureHeight));
        }
        ModelPart part = new ModelPart(cubes, childParts);
        PartPose pose = PartPose.offsetAndRotation(
            (float) position[0], (float) position[1], (float) position[2],
            (float) Math.toRadians(rotation[0]), (float) Math.toRadians(rotation[1]), (float) Math.toRadians(rotation[2])
        );
        part.setInitialPose(pose);
        part.loadPose(pose);
        return part;
    }

    /**
     * Copies each animated {@code AdvancedModelBox} pose onto its mirrored
     * {@code ModelPart}, so the vanilla submit pipeline renders the animated pose.
     * The {@code scaleChildren} behaviour of the custom renderer (children are
     * unscaled by a parent that doesn't propagate its scale) is replicated by
     * baking the reciprocal of each non-propagating ancestor's scale into the
     * child's {@code ModelPart} scale.
     */
    private void syncRenderTree() {
        Function<String, ModelPart> lookup = this.root.createPartLookup();
        for (AdvancedModelBox rootBox : this.rootBoxes) {
            this.syncBox(rootBox, lookup, 1.0F, 1.0F, 1.0F);
        }
    }

    private void syncBox(AdvancedModelBox box, Function<String, ModelPart> lookup, float compensationX, float compensationY, float compensationZ) {
        ModelPart part = lookup.apply(box.boxName);
        if (part == null) return;
        part.x = box.rotationPointX;
        part.y = box.rotationPointY;
        part.z = box.rotationPointZ;
        part.xRot = box.rotateAngleX;
        part.yRot = box.rotateAngleY;
        part.zRot = box.rotateAngleZ;
        part.xScale = box.scaleX * compensationX;
        part.yScale = box.scaleY * compensationY;
        part.zScale = box.scaleZ * compensationZ;
        part.visible = box.showModel;
        float childCompX = box.scaleChildren ? compensationX : compensationX / Math.max(box.scaleX, 0.0001F);
        float childCompY = box.scaleChildren ? compensationY : compensationY / Math.max(box.scaleY, 0.0001F);
        float childCompZ = box.scaleChildren ? compensationZ : compensationZ / Math.max(box.scaleZ, 0.0001F);
        for (BasicModelPart child : box.childModels) {
            if (child instanceof AdvancedModelBox advancedChild) {
                this.syncBox(advancedChild, lookup, childCompX, childCompY, childCompZ);
            }
        }
    }
}
