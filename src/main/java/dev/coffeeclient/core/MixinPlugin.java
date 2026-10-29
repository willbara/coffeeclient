package dev.coffeeclient.core;

import net.fabricmc.loader.api.FabricLoader;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * Skips compat mixins for mods that are not installed. Runs before the game classes load, so it may only
 * touch {@link FabricLoader}, never Minecraft or client classes.
 */
public final class MixinPlugin implements IMixinConfigPlugin {
	private static final String SODIUM_PACKAGE = "dev.coffeeclient.mixin.sodium.";
	private static final String INDIGO_PACKAGE = "dev.coffeeclient.mixin.indigo.";

	public static final boolean SODIUM = FabricLoader.getInstance().isModLoaded("sodium");
	public static final boolean INDIGO = FabricLoader.getInstance().isModLoaded("fabric-renderer-indigo");

	@Override
	public void onLoad(String mixinPackage) {
	}

	@Override
	public String getRefMapperConfig() {
		return null;
	}

	@Override
	public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
		if (mixinClassName.startsWith(SODIUM_PACKAGE)) return SODIUM;
		if (mixinClassName.startsWith(INDIGO_PACKAGE)) return INDIGO;
		return true;
	}

	@Override
	public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
	}

	@Override
	public List<String> getMixins() {
		return null;
	}

	@Override
	public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
	}

	@Override
	public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
	}
}
