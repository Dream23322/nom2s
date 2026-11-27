package dev.nom2s.mixin;

import java.nio.ByteBuffer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import dev.nom2s.font.FontGuard;

@Mixin(targets = "org.lwjgl.stb.STBTruetype", remap = false)
public abstract class StbTrueType2GuardMixin {

    @ModifyVariable(
            method = "stbtt_InitFont(Lorg/lwjgl/stb/STBTTFontinfo;Ljava/nio/ByteBuffer;)Z",
            at = @At("HEAD"),
            argsOnly = true,
            index = 1,
            remap = false)
    private static ByteBuffer nom2s$sanitizeFont(ByteBuffer fontData) {
        FontGuard.sanitize(fontData);
        return fontData;
    }

    @Unique
    private static void nom2s$guardInstalled() {
    }
}
