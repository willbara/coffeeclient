package dev.coffeeclient.mixin;

import com.mojang.authlib.GameProfile;
import dev.coffeeclient.module.ModuleManager;
import dev.coffeeclient.module.modules.cosmetics.Cape;
import dev.coffeeclient.module.modules.client.PlayerProtect;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.world.entity.player.PlayerSkin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** PlayerProtect: your own skin (model, tab list, inventory) shown as Steve or a borrowed tab-list skin; SkinChanger; Cape cosmetic. */
@Mixin(PlayerInfo.class)
public abstract class PlayerInfoMixin {
	@Shadow
	public abstract GameProfile getProfile();

	/**
	 * Your skin as PlayerProtect shows it (when it swaps yours), then SkinChanger's chosen skin, then the Cape cosmetic adds the client-side
	 * cape on top (only this client sees it). One pass, so the cape is added once.
	 */
	@Inject(method = "getSkin", at = @At("RETURN"), cancellable = true)
	private void coffeeclient$skin(CallbackInfoReturnable<PlayerSkin> cir) {
		PlayerSkin real = cir.getReturnValue();
		PlayerSkin skin = real;
		PlayerProtect protect = ModuleManager.ifOn(PlayerProtect.class);
		if (protect != null && getProfile().name().equals(Minecraft.getInstance().getUser().getName())) {
			PlayerSkin own = protect.ownSkin();
			if (own != null) skin = own;
		}
		skin = dev.coffeeclient.module.modules.client.SkinChanger.apply(skin, getProfile().name());
		PlayerSkin caped = Cape.apply(skin, getProfile().name());
		if (caped != real) cir.setReturnValue(caped);
	}
}
