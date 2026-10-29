package dev.coffeeclient.util;

import com.mojang.authlib.GameProfile;
import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ResolvableProfile;

/**
 * Player-head item stacks carrying a real skin.
 *
 * <p>The {@code PROFILE} component is read by the item renderer, which resolves the skin through the
 * game's {@code PlayerSkinRenderCache}. Lookups are asynchronous, so a head shows the default skin
 * for a frame or two and then updates in place once the session server replies.
 */
public final class Skins {
	private Skins() {
	}

	/** A {@code PLAYER_HEAD} drawn with {@code name}'s skin. */
	public static ItemStack head(String name) {
		if (name == null) return new ItemStack(Items.PLAYER_HEAD);
		String trimmed = name.trim();
		return trimmed.isEmpty() ? new ItemStack(Items.PLAYER_HEAD) : head(ResolvableProfile.createUnresolved(trimmed));
	}

	/** A {@code PLAYER_HEAD} drawn with the local player's own skin. */
	public static ItemStack ownHead() {
		GameProfile profile = Minecraft.getInstance().getGameProfile();
		return profile == null ? new ItemStack(Items.PLAYER_HEAD) : head(ResolvableProfile.createResolved(profile));
	}

	private static ItemStack head(ResolvableProfile profile) {
		ItemStack stack = new ItemStack(Items.PLAYER_HEAD);
		stack.set(DataComponents.PROFILE, profile);
		return stack;
	}
}
