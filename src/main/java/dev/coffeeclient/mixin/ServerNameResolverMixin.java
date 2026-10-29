package dev.coffeeclient.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dev.coffeeclient.module.modules.exploit.Spoofs;
import net.minecraft.client.multiplayer.resolver.AddressCheck;
import net.minecraft.client.multiplayer.resolver.ResolvedServerAddress;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.client.multiplayer.resolver.ServerNameResolver;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/** Spoofs: lets every address through instead of checking Mojang's blocklist. */
@Mixin(ServerNameResolver.class)
abstract class ServerNameResolverMixin {
	@Unique
	private static final AddressCheck coffeeclient$ALLOW_ALL = new AddressCheck() {
		@Override
		public boolean isAllowed(ResolvedServerAddress address) {
			return true;
		}

		@Override
		public boolean isAllowed(ServerAddress address) {
			return true;
		}
	};

	@ModifyExpressionValue(method = "resolveAddress", at = @At(value = "FIELD",
		target = "Lnet/minecraft/client/multiplayer/resolver/ServerNameResolver;addressCheck:Lnet/minecraft/client/multiplayer/resolver/AddressCheck;"))
	private AddressCheck coffeeclient$unblock(AddressCheck original) {
		return Spoofs.unblocked() ? coffeeclient$ALLOW_ALL : original;
	}
}
