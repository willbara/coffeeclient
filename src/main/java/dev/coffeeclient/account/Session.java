package dev.coffeeclient.account;

import com.mojang.authlib.exceptions.AuthenticationException;
import com.mojang.authlib.minecraft.UserApiService;
import com.mojang.authlib.yggdrasil.FriendsService;
import com.mojang.authlib.yggdrasil.YggdrasilAuthenticationService;
import dev.coffeeclient.core.CoffeeClient;
import dev.coffeeclient.mixin.MinecraftAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.User;
import net.minecraft.client.gui.screens.social.PlayerSocialManager;
import net.minecraft.client.gui.screens.social.RemoteFriendListUpdateHandler;
import net.minecraft.client.multiplayer.ProfileKeyPairManager;
import net.minecraft.client.multiplayer.chat.report.ReportEnvironment;
import net.minecraft.client.multiplayer.chat.report.ReportingContext;
import net.minecraft.util.Util;

import java.util.concurrent.CompletableFuture;

/**
 * Switches the game to another user, rebuilding what vanilla derives from the user once at startup:
 * the user API service and its properties, chat signing
 * keys (or servers reject your signed chat), the report context, your profile, and the friends list.
 * Client thread only, and only from the menus: never while connected to a world.
 */
public final class Session {
	private Session() {
	}

	public static void apply(User user, boolean online) {
		Minecraft mc = Minecraft.getInstance();
		MinecraftAccessor access = (MinecraftAccessor) mc;
		YggdrasilAuthenticationService auth = new YggdrasilAuthenticationService(mc.getProxy());
		UserApiService api = online ? createApi(auth, user) : UserApiService.OFFLINE;

		access.coffeeclient$setUser(user);
		access.coffeeclient$setUserApiService(api);
		access.coffeeclient$setUserPropertiesFuture(CompletableFuture.supplyAsync(() -> {
			try {
				return api.fetchProperties();
			} catch (AuthenticationException e) {
				return UserApiService.OFFLINE_PROPERTIES;
			}
		}, Util.nonCriticalIoPool()));
		access.coffeeclient$setProfileKeyPairManager(online
			? ProfileKeyPairManager.create(api, user, mc.gameDirectory.toPath())
			: ProfileKeyPairManager.EMPTY_KEY_MANAGER);
		access.coffeeclient$setReportingContext(ReportingContext.create(ReportEnvironment.local(), api));
		access.coffeeclient$setProfileFuture(online
			? CompletableFuture.supplyAsync(() -> mc.services().sessionService().fetchProfile(user.getProfileId(), true), Util.nonCriticalIoPool())
			: CompletableFuture.completedFuture(null));

		// The friends list polls with the old token: replace it and its updater.
		FriendsService friends = auth.createFriendsService(user.getAccessToken());
		RemoteFriendListUpdateHandler old = access.coffeeclient$getRemoteFriendListUpdateHandler();
		RemoteFriendListUpdateHandler updater = new RemoteFriendListUpdateHandler(friends, mc);
		PlayerSocialManager social = new PlayerSocialManager(mc, api, friends, updater);
		try {
			old.close();
		} catch (RuntimeException e) {
			CoffeeClient.LOG.warn("[Accounts] Could not stop the old friends updater", e);
		}
		access.coffeeclient$setRemoteFriendListUpdateHandler(updater);
		access.coffeeclient$setPlayerSocialManager(social);
		if (online && social.isFriendListEnabled()) updater.start();
		CoffeeClient.LOG.info("[Accounts] Now playing as {}", user.getName());
	}

	private static UserApiService createApi(YggdrasilAuthenticationService auth, User user) {
		try {
			return auth.createUserApiService(user.getAccessToken());
		} catch (RuntimeException e) {
			CoffeeClient.LOG.warn("[Accounts] User API unavailable for {}: {}", user.getName(), e.toString());
			return UserApiService.OFFLINE;
		}
	}
}
