package com.iafenvoy.uranus.client.render;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.textures.AddressMode;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuTexture;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.renderer.texture.AbstractTexture;

@Environment(EnvType.CLIENT)
public class SimpleTexture extends AbstractTexture {
    private final NativeImage nativeImage;

    public SimpleTexture(NativeImage nativeImage) {
        this.nativeImage = nativeImage;
    }

    public void upload(boolean blur, boolean clamp) {
        GpuDevice device = RenderSystem.getDevice();
        this.close(); // release any previously uploaded GPU texture before re-creating
        this.texture = device.createTexture(
            "SimpleTexture",
            GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING,
            GpuFormat.RGBA8_UNORM,
            this.nativeImage.getWidth(), this.nativeImage.getHeight(), 1, 1
        );
        AddressMode addressMode = clamp ? AddressMode.CLAMP_TO_EDGE : AddressMode.REPEAT;
        FilterMode minMag = blur ? FilterMode.LINEAR : FilterMode.NEAREST;
        this.sampler = RenderSystem.getSamplerCache().getSampler(addressMode, addressMode, minMag, minMag, false);
        this.textureView = device.createTextureView(this.texture);
        device.createCommandEncoder().writeToTexture(this.texture, this.nativeImage);
    }
}
