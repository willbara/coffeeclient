package dev.coffeeclient.mixin;

import dev.coffeeclient.core.CoffeeClient;
import dev.coffeeclient.module.ModuleManager;
import dev.coffeeclient.module.modules.misc.AutoReconnect;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** AutoReconnect: adds a reconnect button and auto-reconnects on the disconnected screen. */
@Mixin(DisconnectedScreen.class)
public abstract class DisconnectedScreenMixin extends Screen {
	@Shadow
	@Final
	private LinearLayout layout;

	@Unique
	private Button reconnectBtn;

	@Unique
	private double time;

	protected DisconnectedScreenMixin(Component title) {
		super(title);
	}

	@Inject(method = "init", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/layouts/LinearLayout;arrangeElements()V", shift = At.Shift.BEFORE))
	private void coffeeclient$addButtons(CallbackInfo ci) {
		AutoReconnect module = CoffeeClientModule();
		if (module == null || module.lastServer == null) return;
		if (module.hideButtons.get()) return;

		time = module.delay.get() * 20.0;
		reconnectBtn = Button.builder(Component.literal(getText()), b -> tryConnecting()).build();
		layout.addChild(reconnectBtn);
		layout.addChild(Button.builder(Component.literal("Toggle Auto Reconnect"), b -> {
			module.toggle();
			if (reconnectBtn != null) reconnectBtn.setMessage(Component.literal(getText()));
			time = module.delay.get() * 20.0;
		}).build());
	}

	@Override
	public void tick() {
		AutoReconnect module = CoffeeClientModule();
		if (module == null || !module.isEnabled() || module.lastServer == null) return;

		if (time <= 0) {
			tryConnecting();
		} else {
			time -= 1;
			if (reconnectBtn != null) reconnectBtn.setMessage(Component.literal(getText()));
		}
	}

	@Unique
	private String getText() {
		AutoReconnect module = CoffeeClientModule();
		String t = "Reconnect";
		if (module != null && module.isEnabled()) t += " " + String.format("(%.1f)", time / 20.0);
		return t;
	}

	@Unique
	private void tryConnecting() {
		AutoReconnect module = CoffeeClientModule();
		if (module == null || module.lastServer == null) return;
		ServerData sd = module.lastServer;
		ServerAddress addr = ServerAddress.parseString(sd.ip);
		ConnectScreen.startConnecting(new TitleScreen(), Minecraft.getInstance(), addr, sd, false, null);
	}

	@Unique
	private static AutoReconnect CoffeeClientModule() {
		try {
			return (AutoReconnect) CoffeeClient.modules().get(AutoReconnect.class);
		} catch (Throwable t) {
			return null;
		}
	}
}
