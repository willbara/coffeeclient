package dev.coffeeclient.module.modules.render;

import dev.coffeeclient.core.CoffeeClient;
import dev.coffeeclient.core.EventBus;
import dev.coffeeclient.core.Events;
import dev.coffeeclient.module.Category;
import dev.coffeeclient.module.Module;
import dev.coffeeclient.module.ModuleManager;
import dev.coffeeclient.setting.BooleanSetting;
import dev.coffeeclient.setting.KeybindSetting;
import dev.coffeeclient.setting.NumberSetting;
import dev.coffeeclient.util.KeyNames;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;

/**
 * Third-person camera distance, block clipping and scroll-wheel distance, plus
 * OptiFine-style hold-to-zoom.
 */
public final class CameraTweaks extends Module {
	public final BooleanSetting clip = setting(new BooleanSetting("Clip", "Let the camera pass through blocks", true));
	public final NumberSetting cameraDistance = setting(new NumberSetting("Camera distance", "Distance of the third-person camera", 4.0, 1.0, 24.0, 0.5));

	public final BooleanSetting scrolling = group(new BooleanSetting("Scrolling", "Scroll to change the camera distance", true), "Scrolling");
	public final NumberSetting sensitivity = group(new NumberSetting("Sensitivity", "Scroll sensitivity", 1.0, 0.1, 4.0, 0.1), "Scrolling");
	public final KeybindSetting holdKey = group(new KeybindSetting("Hold key", "Only scroll while this is held (unbound = always)", KeybindSetting.UNBOUND), "Scrolling");

	public final BooleanSetting zoom = group(new BooleanSetting("Zoom", "Hold a key to zoom in", true), "Zoom");
	public final KeybindSetting zoomKey = group(new KeybindSetting("Zoom key", "Hold to zoom", KeybindSetting.UNBOUND), "Zoom");
	public final NumberSetting zoomLevel = group(new NumberSetting("Zoom level", "How many times closer", 3, 1, 50, 0.5), "Zoom");
	public final BooleanSetting zoomScroll = group(new BooleanSetting("Scroll to zoom", "Scroll while zooming to zoom further in or out", true), "Zoom");
	public final BooleanSetting zoomInScreens = group(new BooleanSetting("Zoom in screens", "Keep zooming while chat or an inventory is open", false), "Zoom");
	public final BooleanSetting smoothZoom = group(new BooleanSetting("Smooth zoom", "Ease in and out of the zoom instead of snapping", false), "Zoom");
	public final NumberSetting zoomSpeed = group(new NumberSetting("Zoom speed", "How fast the smooth zoom eases", 10, 1, 30, 1), "Zoom");
	public final BooleanSetting cinematic = group(new BooleanSetting("Cinematic", "Smooth (cinematic) mouse movement while zoomed", false), "Zoom");

	public final KeybindSetting freeLookKey = group(new KeybindSetting("FreeLook key", "Hold to look around without turning your player or movement (unbound = off)", KeybindSetting.UNBOUND), "FreeLook");
	public final BooleanSetting freeLookThirdPerson = group(new BooleanSetting("Third person", "Pull the camera out behind you while free looking", true), "FreeLook");

	/** The zoom while the key is held (scrolling changes it); reset to the setting on release. */
	private double zoomNow = -1;
	/** The zoom on screen, easing toward the target with Smooth zoom; and when it last moved. */
	private double zoomShown = 1;
	private long zoomShownAt;
	private boolean cinematicOn, cinematicBefore;

	private boolean freeLooking;
	private float lookYaw, lookPitch;
	private CameraType lookPerspective = CameraType.FIRST_PERSON;

	/** Live distance, changed by scrolling without rewriting the saved setting. */
	public double distance = 4.0;

	public CameraTweaks() {
		super("CameraTweaks", java.util.List.of("Camera Tweaks"), Category.RENDER, "Changes the third-person camera distance and lets it clip through blocks.");
		sensitivity.visibleWhen(scrolling::get);
		holdKey.visibleWhen(scrolling::get);
		for (var s : java.util.List.of(zoomKey, zoomLevel, zoomScroll, zoomInScreens, smoothZoom, cinematic)) s.visibleWhen(zoom::get);
		zoomSpeed.visibleWhen(() -> zoom.get() && smoothZoom.get());
		cameraDistance.onChange(v -> distance = v);
	}

	@Override
	protected void onEnable() {
		distance = cameraDistance.get();
	}

	@Override
	protected void onDisable() {
		distance = cameraDistance.get();
		stopFreeLook();
		setCinematic(false);
	}

	/** Turns vanilla's cinematic camera on for the zoom and back off after, leaving the player's own setting alone. */
	private void setCinematic(boolean on) {
		Minecraft mc = mc();
		if (on == cinematicOn || mc.options == null) return;
		cinematicOn = on;
		if (on) cinematicBefore = mc.options.smoothCamera;
		mc.options.smoothCamera = on || cinematicBefore;
	}

	@Override
	public void onTick() {
		if (cinematic.get() || cinematicOn) setCinematic(cinematic.get() && zooming());
		boolean want = freeLookWanted();
		if (want && !freeLooking) startFreeLook();
		else if (!want && freeLooking) stopFreeLook();
	}

	private boolean freeLookWanted() {
		Minecraft mc = mc();
		return mc.player != null && mc.gui.screen() == null && !ModuleManager.on(Freecam.class)
			&& KeyNames.isBound(freeLookKey.key()) && KeyNames.isHeld(freeLookKey.key());
	}

	private void startFreeLook() {
		Minecraft mc = mc();
		freeLooking = true;
		lookYaw = mc.player.getYRot();
		lookPitch = mc.player.getXRot();
		lookPerspective = mc.options.getCameraType();
		if (freeLookThirdPerson.get()) mc.options.setCameraType(CameraType.THIRD_PERSON_BACK);
	}

	private void stopFreeLook() {
		if (!freeLooking) return;
		freeLooking = false;
		mc().options.setCameraType(lookPerspective);
	}

	/** True while the camera is detached from the player's facing (the camera mixin reads the angles below). */
	public static boolean freeLooking() {
		CameraTweaks self = ModuleManager.ifOn(CameraTweaks.class);
		return self != null && self.freeLooking;
	}

	public static float lookYaw() {
		return CoffeeClient.modules().get(CameraTweaks.class).lookYaw;
	}

	public static float lookPitch() {
		return CoffeeClient.modules().get(CameraTweaks.class).lookPitch;
	}

	/** Mouse turning while free looking moves the camera instead of the player. Returns true when it took the turn. */
	public static boolean turnFreeLook(double deltaX, double deltaY) {
		CameraTweaks self = ModuleManager.ifOn(CameraTweaks.class);
		if (self == null || !self.freeLooking) return false;
		self.lookYaw += (float) (deltaX * 0.15);
		self.lookPitch = Math.max(-90, Math.min(90, self.lookPitch + (float) (deltaY * 0.15)));
		return true;
	}

	@Override
	protected void init(EventBus bus) {
		listen(Events.MouseScroll.class, e -> {
			if (zoomScroll.get() && zooming()) {
				zoomNow = Math.max(zoomLevel.min(), Math.min(zoomLevel.max(), current() * (e.amount > 0 ? 1.1 : 0.9)));
				e.cancel();
				return;
			}
			if (!scrolling.get()) return;
			Minecraft mc = mc();
			if (mc.options.getCameraType() == CameraType.FIRST_PERSON) return;
			if (KeyNames.isBound(holdKey.key()) && !held(holdKey.key())) return;
			distance = Math.max(1.0, Math.min(32.0, distance - e.amount * 0.5 * sensitivity.get()));
			e.cancel();
		});
	}

	private boolean zooming() {
		Minecraft mc = mc();
		if (!zoom.get() || mc.player == null || mc.gui.screen() != null && !zoomInScreens.get()) return false;
		return KeyNames.isBound(zoomKey.key()) && KeyNames.isHeld(zoomKey.key());
	}

	private double current() {
		if (zoomNow < 0) zoomNow = zoomLevel.get();
		return zoomNow;
	}

	/** How much to divide the FOV and mouse sensitivity by right now (1 = no zoom). Called from mixins. */
	public static double zoomFactor() {
		if (!ModuleManager.on(CameraTweaks.class)) return 1;
		CameraTweaks self = CoffeeClient.modules().get(CameraTweaks.class);
		double target;
		if (self.zooming()) {
			target = self.current();
		} else {
			self.zoomNow = -1;
			target = 1;
		}
		if (!self.smoothZoom.get()) return self.zoomShown = target;
		long now = System.nanoTime();
		double dt = self.zoomShownAt == 0 ? 0 : Math.min(0.1, (now - self.zoomShownAt) / 1e9);
		self.zoomShownAt = now;
		self.zoomShown += (target - self.zoomShown) * (1 - Math.exp(-dt * self.zoomSpeed.get()));
		if (Math.abs(self.zoomShown - target) < 1e-3) self.zoomShown = target;
		return self.zoomShown;
	}

	/** True while a stored keyboard/mouse binding is held. */
	private static boolean held(int key) {
		return KeyNames.isHeld(key);
	}

	@Override
	public boolean hasConfigScreen() {
		return true;
	}
}
