package dev.coffeeclient.mixin;

import com.mojang.authlib.minecraft.UserApiService;
import com.mojang.authlib.yggdrasil.ProfileResult;
import net.minecraft.client.Minecraft;
import net.minecraft.client.User;
import net.minecraft.client.gui.screens.social.PlayerSocialManager;
import net.minecraft.client.gui.screens.social.RemoteFriendListUpdateHandler;
import net.minecraft.client.multiplayer.ProfileKeyPairManager;
import net.minecraft.client.multiplayer.chat.report.ReportingContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.concurrent.CompletableFuture;

/** The account switcher swaps the logged-in user and everything built from it (dev.coffeeclient.account.Session). */
@Mixin(Minecraft.class)
public interface MinecraftAccessor {
	@Mutable
	@Accessor("user")
	void coffeeclient$setUser(User user);

	@Mutable
	@Accessor("userApiService")
	void coffeeclient$setUserApiService(UserApiService service);

	@Mutable
	@Accessor("userPropertiesFuture")
	void coffeeclient$setUserPropertiesFuture(CompletableFuture<UserApiService.UserProperties> future);

	@Mutable
	@Accessor("profileKeyPairManager")
	void coffeeclient$setProfileKeyPairManager(ProfileKeyPairManager manager);

	@Accessor("reportingContext")
	void coffeeclient$setReportingContext(ReportingContext context);

	@Mutable
	@Accessor("profileFuture")
	void coffeeclient$setProfileFuture(CompletableFuture<ProfileResult> future);

	@Mutable
	@Accessor("playerSocialManager")
	void coffeeclient$setPlayerSocialManager(PlayerSocialManager manager);

	@Accessor("remoteFriendListUpdateHandler")
	RemoteFriendListUpdateHandler coffeeclient$getRemoteFriendListUpdateHandler();

	@Mutable
	@Accessor("remoteFriendListUpdateHandler")
	void coffeeclient$setRemoteFriendListUpdateHandler(RemoteFriendListUpdateHandler handler);
}
