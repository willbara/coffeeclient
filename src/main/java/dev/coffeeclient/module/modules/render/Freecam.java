package dev.coffeeclient.module.modules.render;

import dev.coffeeclient.core.CoffeeClient;
import dev.coffeeclient.core.EventBus;
import dev.coffeeclient.core.Events;
import dev.coffeeclient.module.Category;
import dev.coffeeclient.module.Module;
import dev.coffeeclient.setting.BooleanSetting;
import dev.coffeeclient.setting.NumberSetting;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Camera;
import net.minecraft.client.CameraType;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundPlayerCombatKillPacket;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import net.minecraft.network.protocol.game.ClientboundSetHealthPacket;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Method;

/** Moves the view independently while holding the real player in place. */
public final class Freecam extends Module {
	public final NumberSetting speed = group(new NumberSetting("Speed", "Your speed while in Freecam", 1.0, 0, 10, 0.05), "General");
	public final BooleanSetting staySneaking = group(new BooleanSetting("Stay sneaking", "Keep the player sneaking if you were sneaking when Freecam started", true), "General");
	public final BooleanSetting toggleOnDamage = group(new BooleanSetting("Toggle on damage", "Disable Freecam when you take damage", false), "General");
	public final BooleanSetting toggleOnDeath = group(new BooleanSetting("Toggle on death", "Disable Freecam when you die", false), "General");
	public final BooleanSetting toggleOnLog = group(new BooleanSetting("Toggle on disconnect", "Disable Freecam when leaving a world or server", true), "General");
	public final BooleanSetting reloadChunks = group(new BooleanSetting("Reload chunks", "Refresh chunk rendering when Freecam starts or stops", true), "General");
	public final BooleanSetting renderHands = group(new BooleanSetting("Show hands", "Render first-person hands and held items", true), "General");
	public final BooleanSetting rotate = group(new BooleanSetting("Rotate to target", "Turn the Freecam view toward the current target", false), "General");
	public final NumberSetting smoothing = group(new NumberSetting("Smoothing", "Ease into and out of moving (0 = instant)", 0, 0, 0.95, 0.05), "General");
	public final BooleanSetting interactFromBody = group(new BooleanSetting("Interact from body", "Aim clicks from your player's position along the camera's view, instead of from the camera", false), "General");
	public final BooleanSetting staticView = group(new BooleanSetting("Static view", "Disable view bobbing and FOV effects while active", true), "General");
	public final BooleanSetting clickToPath = group(new BooleanSetting("Click to path", "Use Baritone to path to clicked blocks or entities when available", false), "Pathing");
	public final BooleanSetting requireDoubleClick = group(new BooleanSetting("Require double-click", "Require two quick clicks before starting a path", false), "Pathing");

	private Vec3 position = Vec3.ZERO;

	private Vec3 velocity = Vec3.ZERO;
	private Vec3 previousPosition = Vec3.ZERO;
	private Vec3 playerPosition = Vec3.ZERO;
	private ClientLevel level;
	private boolean session;
	private boolean wasSneaking;
	private boolean previousSmartCull;
	private CameraType perspective = CameraType.FIRST_PERSON;
	private double previousFovEffectScale;
	private boolean previousBobView;
	private double speedValue;
	private float yaw;
	private float previousYaw;
	private float pitch;
	private float previousPitch;
	private long lastPathClick;
	private boolean warnedBaritoneMissing;

	public Freecam() {
		super("Freecam", Category.RENDER, "Move the camera independently from your player.");
		requireDoubleClick.visibleWhen(clickToPath::get);
	}

	@Override
	protected void init(EventBus bus) {
		bus.subscribe(Events.MouseScroll.class, this::onScroll);
		bus.subscribe(Events.MouseClick.class, this::onMouseClick);
		listen(Events.GameLeft.class, event -> {
			if (toggleOnLog.get()) setEnabled(false);
		});
		// Before the health packet applies, while the player still has the old health to compare with.
		listen(Events.PacketApply.Pre.class, EventBus.LOWEST, event -> {
			LocalPlayer player = mc().player;
			if (!event.isCancelled() && toggleOnDamage.get() && player != null
				&& event.packet instanceof ClientboundSetHealthPacket health && player.getHealth() > health.getHealth()) setEnabled(false);
		});
		listen(Events.PacketApply.Post.class, event -> onPacketApplied(event.packet()));
		// The real player stands still while the camera flies: hold back its movement packets.
		listen(Events.PacketSend.class, EventBus.LOW + 10, event -> {
			if (event.packet instanceof net.minecraft.network.protocol.game.ServerboundMovePlayerPacket) event.cancel();
		});
	}

	@Override
	protected void onEnable() {
		LocalPlayer player = mc().player;
		if (player == null || mc().level == null) {
			setEnabled(false);
			return;
		}

		session = true;
		level = mc().level;
		Camera camera = mc().gameRenderer.mainCamera();
		perspective = mc().options.getCameraType();
		previousFovEffectScale = mc().options.fovEffectScale().get();
		previousBobView = mc().options.bobView().get();
		playerPosition = player.position();
		wasSneaking = player.isShiftKeyDown();

		position = camera.position();
		velocity = Vec3.ZERO;
		previousPosition = position;
		yaw = previousYaw = camera.yRot();
		pitch = previousPitch = camera.xRot();
		speedValue = speed.get();

		if (staticView.get()) {
			mc().options.fovEffectScale().set(0.0);
			mc().options.bobView().set(false);
		}
		mc().options.setCameraType(CameraType.FIRST_PERSON);

		// Disable cave/occlusion culling while detached so underground chunks keep rendering
		// (vanilla VisGraph + Sodium's smartCull-based occlusion). Restored on disable.
		previousSmartCull = mc().smartCull;
		mc().smartCull = false;

		if (reloadChunks.get()) mc().levelExtractor.allChanged();
	}

	@Override
	protected void onDisable() {
		if (session) {
			LocalPlayer player = mc().player;
			mc().options.setCameraType(perspective);
			if (staticView.get()) {
				mc().options.fovEffectScale().set(previousFovEffectScale);
				mc().options.bobView().set(previousBobView);
			}
			if (player != null) {
				player.setPos(playerPosition);
				player.setDeltaMovement(Vec3.ZERO);
				player.resetFallDistance();
			}
			// Restore cave/occlusion culling state.
			mc().smartCull = previousSmartCull;
		}
		if (session && reloadChunks.get()) mc().execute(mc().levelExtractor::allChanged);
		session = false;
		level = null;
	}

	@Override
	public void onTick() {
		LocalPlayer player = mc().player;
		if (player == null || mc().level == null || mc().level != level) {
			setEnabled(false);
			return;
		}

		player.setPos(playerPosition);
		player.setDeltaMovement(Vec3.ZERO);
		player.resetFallDistance();
		if (mc().gui.screen() != null) return;
		if (rotate.get()) rotateToTarget();

		previousPosition = position;
		previousYaw = yaw;
		previousPitch = pitch;

		// Movement input - spectator-style (yaw-only forward, like vanilla spectator)
		boolean forward = mc().options.keyUp.isDown();
		boolean backward = mc().options.keyDown.isDown();
		boolean left = mc().options.keyLeft.isDown();
		boolean right = mc().options.keyRight.isDown();
		boolean up = mc().options.keyJump.isDown();
		boolean down = mc().options.keyShift.isDown();
		boolean sprint = mc().options.keySprint.isDown();

		double moveSpeed = speedValue * (sprint ? 1.0 : 0.5);

		// Forward/back based on yaw only (not pitch) - like vanilla spectator
		Vec3 forwardVec = Vec3.directionFromRotation(0, yaw);
		Vec3 rightVec = Vec3.directionFromRotation(0, yaw + 90);

		double forwardAmount = (forward ? 1 : 0) - (backward ? 1 : 0);
		double sideAmount = (right ? 1 : 0) - (left ? 1 : 0);
		double verticalAmount = (up ? 1 : 0) - (down ? 1 : 0);

		double horizontalLength = Math.hypot(forwardAmount, sideAmount);
		double x = 0, z = 0;
		if (horizontalLength > 0) {
			forwardAmount /= horizontalLength;
			sideAmount /= horizontalLength;
			x = (forwardVec.x * forwardAmount + rightVec.x * sideAmount) * moveSpeed;
			z = (forwardVec.z * forwardAmount + rightVec.z * sideAmount) * moveSpeed;
		}
		Vec3 want = new Vec3(x, verticalAmount * moveSpeed, z);
		double keep = smoothing.get();
		velocity = keep <= 0 ? want : velocity.scale(keep).add(want.scale(1 - keep));
		if (velocity.lengthSqr() < 1e-6 && want.lengthSqr() == 0) velocity = Vec3.ZERO;
		position = position.add(velocity);

		// Noclip: allow camera to move through blocks
		BlockPos camPos = BlockPos.containing(position.x, position.y, position.z);
		if (!mc().level.getBlockState(camPos).isAir()) {
			mc().getCameraEntity().noPhysics = true;
		}
	}

	private void onScroll(Events.MouseScroll event) {
		if (!isEnabled() || mc().gui.screen() != null) return;
		speedValue += event.amount * 0.25 * speedValue;
		speedValue = Math.max(0.1, Math.min(10, speedValue));
		event.cancel();
	}

	private void onPacketApplied(net.minecraft.network.protocol.Packet<?> packet) {
		LocalPlayer player = mc().player;
		if (player == null) return;
		boolean disable = packet instanceof ClientboundRespawnPacket
			|| (toggleOnDeath.get() && packet instanceof ClientboundPlayerCombatKillPacket kill && kill.playerId() == player.getId());
		if (disable) setEnabled(false);
	}

	private void rotateToTarget() {
		HitResult hit = mc().hitResult;
		if (hit == null || hit.getType() == HitResult.Type.MISS) return;
		Vec3 target = hit.getLocation();
		if (hit instanceof EntityHitResult entityHit) target = entityHit.getEntity().getEyePosition();
		Vec3 delta = target.subtract(position);
		double horizontal = Math.sqrt(delta.x * delta.x + delta.z * delta.z);
		if (horizontal < 1.0e-4 && Math.abs(delta.y) < 1.0e-4) return;
		yaw = (float) (Math.toDegrees(Math.atan2(delta.z, delta.x)) - 90.0);
		pitch = (float) -Math.toDegrees(Math.atan2(delta.y, horizontal));
	}

	private void pathTo(BlockPos pos) {
		if (!isEnabled() || !clickToPath.get()) return;
		long now = System.currentTimeMillis();
		if (requireDoubleClick.get() && now - lastPathClick > 500) {
			lastPathClick = now;
			return;
		}
		lastPathClick = 0;
		try {
			Class<?> api = Class.forName("baritone.api.BaritoneAPI");
			Object provider = api.getMethod("getProvider").invoke(null);
			Object baritone = provider.getClass().getMethod("getPrimaryBaritone").invoke(provider);
			Object process = baritone.getClass().getMethod("getCustomGoalProcess").invoke(baritone);
			Class<?> goalType = Class.forName("baritone.api.pathing.goals.GoalGetToBlock");
			Object goal = goalType.getConstructor(BlockPos.class).newInstance(pos);
			for (Method method : process.getClass().getMethods()) {
				if (method.getName().equals("setGoalAndPath") && method.getParameterCount() == 1) {
					method.invoke(process, goal);
					return;
				}
			}
		} catch (ClassNotFoundException ignored) {
			if (!warnedBaritoneMissing) {
				warnedBaritoneMissing = true;
				notify("Click to path requires Baritone");
			}
		} catch (ReflectiveOperationException e) {
			CoffeeClient.LOG.warn("Could not request Freecam path to {}", pos, e);
		}
	}

	private void onMouseClick(Events.MouseClick event) {
		if (!isEnabled() || !clickToPath.get() || event.button != InputConstants.MOUSE_BUTTON_LEFT || event.action != 1) return;
		HitResult hit = mc().hitResult;
		if (hit instanceof BlockHitResult blockHit) pathTo(blockHit.getBlockPos());
		else if (hit instanceof EntityHitResult entityHit) pathTo(entityHit.getEntity().blockPosition());
	}

	public void changeLookDirection(double deltaX, double deltaY) {
		yaw += (float) (deltaX * 0.15);
		pitch = Math.max(-90, Math.min(90, pitch + (float) (deltaY * 0.15)));
	}

	public double x(float partialTick) {
		return previousPosition.x + (position.x - previousPosition.x) * partialTick;
	}

	public double y(float partialTick) {
		return previousPosition.y + (position.y - previousPosition.y) * partialTick;
	}

	public double z(float partialTick) {
		return previousPosition.z + (position.z - previousPosition.z) * partialTick;
	}

	public float yaw(float partialTick) {
		return previousYaw + (yaw - previousYaw) * partialTick;
	}

	public float pitch(float partialTick) {
		return previousPitch + (pitch - previousPitch) * partialTick;
	}

	public boolean shouldStaySneaking() {
		return isEnabled() && staySneaking.get() && wasSneaking;
	}

	public boolean renderHands() {
		return !isEnabled() || renderHands.get();
	}
}