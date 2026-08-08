package com.iafenvoy.uranus.client.render;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.logging.LogUtils;
import net.minecraft.client.renderer.texture.ReloadableTexture;
import net.minecraft.client.renderer.texture.TextureContents;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.ARGB;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;

public class ArrayLayeredTexture extends ReloadableTexture {
    private static final Logger LOGGER = LogUtils.getLogger();
    public final List<String> layeredTextureNames;

    public ArrayLayeredTexture(List<String> textureNames) {
        super(deriveResourceId(textureNames));
        this.layeredTextureNames = textureNames;
    }

    private static Identifier deriveResourceId(List<String> textureNames) {
        if (textureNames != null && !textureNames.isEmpty()) {
            Identifier id = Identifier.tryParse(textureNames.get(0));
            if (id != null) return id;
        }
        return Identifier.withDefaultNamespace("array_layered");
    }

    @Override
    public TextureContents loadContents(ResourceManager manager) throws IOException {
        Iterator<String> iterator = this.layeredTextureNames.iterator();
        String baseName = iterator.next();
        Optional<Resource> baseResource = manager.getResource(Identifier.tryParse(baseName));
        if (baseResource.isEmpty()) {
            LOGGER.error("Couldn't load layered image");
            return TextureContents.createMissing();
        }
        NativeImage base;
        try (InputStream in = baseResource.get().open()) {
            base = NativeImage.read(in);
        } catch (IOException e) {
            LOGGER.error("Couldn't load layered image", e);
            return TextureContents.createMissing();
        }
        while (iterator.hasNext()) {
            String layerName = iterator.next();
            if (layerName != null) {
                Optional<Resource> layerResource = manager.getResource(Identifier.tryParse(layerName));
                if (layerResource.isEmpty()) {
                    base.close();
                    LOGGER.error("Couldn't load layered image");
                    return TextureContents.createMissing();
                }
                try (InputStream in = layerResource.get().open(); NativeImage layer = NativeImage.read(in)) {
                    blendLayers(base, layer);
                } catch (IOException e) {
                    base.close();
                    LOGGER.error("Couldn't load layered image", e);
                    return TextureContents.createMissing();
                }
            }
        }
        // metadata null -> REPEAT/NEAREST sampler via apply(), matching the old default
        return new TextureContents(base, null);
    }

    private static void blendLayers(NativeImage base, NativeImage layer) {
        for (int i = 0; i < Math.min(layer.getHeight(), base.getHeight()); i++)
            for (int j = 0; j < Math.min(layer.getWidth(), base.getWidth()); j++)
                base.setPixel(j, i, blendPixel(layer.getPixel(j, i), base.getPixel(j, i)));
    }

    /**
     * Replicates the 1.21.1 NativeImage.blendPixel math byte-for-byte. The old code read/wrote raw
     * ABGR ints and, for every channel including alpha, computed out = src*factor + dst*(1-factor)
     * with factor = srcAlpha, clamped to 1.0, then *255 truncated. 26.2 getPixel/setPixel are ARGB
     * based (each internally converts through the same raw ABGR bytes), so blending matching ARGB
     * channels here produces identical physical bytes to the old ABGR blend; no manual R/B swap.
     */
    private static int blendPixel(int src, int dst) {
        float factor = ARGB.alpha(src) / 255.0F;
        float inv = 1.0F - factor;
        int outAlpha = (int) (Math.min(ARGB.alpha(src) * factor + ARGB.alpha(dst) * inv, 1.0F) * 255.0F);
        int outRed = (int) (Math.min(ARGB.red(src) * factor + ARGB.red(dst) * inv, 1.0F) * 255.0F);
        int outGreen = (int) (Math.min(ARGB.green(src) * factor + ARGB.green(dst) * inv, 1.0F) * 255.0F);
        int outBlue = (int) (Math.min(ARGB.blue(src) * factor + ARGB.blue(dst) * inv, 1.0F) * 255.0F);
        return ARGB.color(outAlpha, outRed, outGreen, outBlue);
    }
}
