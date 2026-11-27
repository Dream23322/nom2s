package dev.nom2s.mixin;

import java.nio.ByteBuffer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import dev.nom2s.font.FontGuard;

@Mixin(targets = "org.lwjgl.util.freetype.FreeType", remap = false)
public abstract class FreeTypeGuardMixin {

    @ModifyVariable(
            method = "FT_New_Memory_Face(JLjava/nio/ByteBuffer;JLorg/lwjgl/PointerBuffer;)I",
            at = @At("HEAD"),
            argsOnly = true,
            index = 2,
            remap = false)
    private static ByteBuffer nom2s$sanitizeFont(ByteBuffer fontData) {
        FontGuard.sanitize(fontData);
        return fontData;
    }

    @Unique
    private static void nom2s$guardInstalled() {
    }
}
