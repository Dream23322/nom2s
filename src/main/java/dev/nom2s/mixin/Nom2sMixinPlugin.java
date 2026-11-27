package dev.nom2s.mixin;

import java.util.Set;

import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

public final class Nom2sMixinPlugin implements IMixinConfigPlugin {

    private static final String MARKER = "nom2s$guardInstalled";

    private static final String FREE_TYPE = "org.lwjgl.util.freetype.FreeType";
    private static final String STB_TRUE_TYPE = "org.lwjgl.stb.STBTruetype";

    private static final String FREE_TYPE_MIXIN = "FreeTypeGuardMixin";
    private static final String STB_TWO_MIXIN = "StbTrueType2GuardMixin";
    private static final String STB_THREE_MIXIN = "StbTrueType3GuardMixin";

    private boolean freeTypeAvailable;
    private boolean stbTwoAvailable;
    private boolean stbThreeAvailable;

    @Override
    public void onLoad(String mixinPackage) {
        freeTypeAvailable = hasClassResource(FREE_TYPE);
        stbTwoAvailable = hasClassResource(STB_TRUE_TYPE);
        stbThreeAvailable = stbTwoAvailable;

        if (!freeTypeAvailable && !stbTwoAvailable && !stbThreeAvailable) {
            throw new IllegalStateException(
                    "[nom2s] no supported LWJGL font entry point was found; refusing to run unprotected");
        }
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (mixinClassName.endsWith(FREE_TYPE_MIXIN)) {
            return freeTypeAvailable;
        }
        if (mixinClassName.endsWith(STB_TWO_MIXIN)) {
            return stbTwoAvailable;
        }
        if (mixinClassName.endsWith(STB_THREE_MIXIN)) {
            return stbThreeAvailable;
        }
        return false;
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName,
                          IMixinInfo mixinInfo) {
        for (MethodNode method : targetClass.methods) {
            if (MARKER.equals(method.name)) {
                System.err.println("[nom2s] " + engineName(mixinClassName) + " font guard installed");
                return;
            }
        }
        throw new IllegalStateException(
                "[nom2s] " + mixinClassName + " was applied but its guard marker is missing");
    }

    @Override
    public void acceptTargets(Set<String> ownTargets, Set<String> otherTargets) {
        if (!freeTypeAvailable && !stbTwoAvailable && !stbThreeAvailable) {
            throw new IllegalStateException("[nom2s] no font guard hooks are active");
        }
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName,
                         IMixinInfo mixinInfo) {
    }

    @Override
    public java.util.List<String> getMixins() {
        return null;
    }

    private static String engineName(String mixinClassName) {
        if (mixinClassName.endsWith(FREE_TYPE_MIXIN)) {
            return "FreeType";
        }
        return "STB";
    }

    private static boolean hasClassResource(String className) {
        String resource = className.replace('.', '/') + ".class";
        ClassLoader[] loaders = new ClassLoader[]{
                Nom2sMixinPlugin.class.getClassLoader(),
                Thread.currentThread().getContextClassLoader()
        };

        for (ClassLoader loader : loaders) {
            if (loader != null && loader.getResource(resource) != null) {
                return true;
            }
        }
        return false;
    }
}

