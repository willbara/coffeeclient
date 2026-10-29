package dev.coffeeclient.module.modules.cosmetics;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import dev.coffeeclient.core.CoffeeClient;
import dev.coffeeclient.module.Category;
import dev.coffeeclient.module.Module;
import dev.coffeeclient.module.modules.misc.Friends;
import dev.coffeeclient.render.WorldOverlays;
import dev.coffeeclient.setting.ColorSetting;
import dev.coffeeclient.setting.EnumSetting;
import dev.coffeeclient.util.Colors;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * Base for client-side cosmetics. They are drawn only on this client (nobody else sees them), on you in
 * third person and optionally on friends or everyone. Geometry goes through the world overlay pipeline,
 * depth-tested against the world.
 */
public abstract class CosmeticModule extends Module {
	private static final int RING_SEGMENTS = 36;
	/** Height of the eyes above the base of the neck, in blocks. */
	protected static final float NECK_BELOW_EYE = 0.214f;
	/** Forward lean of the body while crouching, in degrees. */
	private static final float CROUCH_LEAN = 28.6f;

	public enum ShowOn { SELF, FRIENDS, EVERYONE }

	public enum ColorMode { THEME, CUSTOM }

	public final EnumSetting<ShowOn> showOn = setting(new EnumSetting<>("Show on", "Who wears it: just you, you and friends, or every player", ShowOn.SELF));
	public final EnumSetting<ColorMode> colorMode = setting(new EnumSetting<>("Color", "Follow the theme accent (including moving accents) or use a fixed colour", ColorMode.THEME));
	public final ColorSetting customColor = setting(new ColorSetting("Custom color", "Fixed colour when not following the theme", 0xFFFFD27A));

	protected CosmeticModule(String name, String description) {
		this(name, List.of(), description);
	}

	protected CosmeticModule(String name, List<String> aliases, String description) {
		super(name, aliases, Category.COSMETICS, description);
		customColor.visibleWhen(() -> colorMode.get() == ColorMode.CUSTOM);
	}

	/** Colour at a position along the cosmetic (moving accents flow along it). */
	protected int color(float pos, int alpha) {
		if (colorMode.get() == ColorMode.CUSTOM) return Colors.withAlpha(customColor.argb(), alpha);
		return CoffeeClient.theme().accentAt(pos, alpha);
	}

	/** Players wearing this cosmetic right now. You are skipped in first person. */
	protected List<Player> wearers() {
		Minecraft mc = mc();
		List<Player> out = new ArrayList<>();
		if (mc.level == null || mc.player == null) return out;
		boolean firstPerson = mc.options.getCameraType().isFirstPerson() && mc.getCameraEntity() == mc.player;
		for (Player p : mc.level.players()) {
			if (p.isInvisible() || p.isSpectator()) continue;
			if (p == mc.player) {
				if (!firstPerson) out.add(p);
				continue;
			}
			if (wears(p)) out.add(p);
		}
		return out;
	}

	/** Whether {@code p} wears this cosmetic: you always, others as the Show on setting says. */
	protected boolean wears(Player p) {
		if (p == mc().player) return true;
		return switch (showOn.get()) {
			case SELF -> false;
			case FRIENDS -> Friends.is(p.getGameProfile().name(), dev.coffeeclient.friend.FriendTrait.COSMETICS);
			case EVERYONE -> true;
		};
	}

	/** Whether {@code name}'s skin should carry this cosmetic (for texture-based ones like capes). */
	protected boolean wornBy(String name) {
		Minecraft mc = mc();
		if (name.equals(mc.getUser().getName())) return true;
		return switch (showOn.get()) {
			case SELF -> false;
			case FRIENDS -> Friends.is(name, dev.coffeeclient.friend.FriendTrait.COSMETICS);
			case EVERYONE -> true;
		};
	}

	protected static float partialTick() {
		return Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(false);
	}

	/** Seconds of game-independent time, for spin and bob. */
	protected static float time() {
		return dev.coffeeclient.gui.anim.Motion.time();
	}

	@Override
	public void renderWorld(PoseStack pose, Vec3 cam) {
		float pt = partialTick();
		for (Player p : wearers()) {
			Vec3 pos = p.getPosition(pt);
			float bodyYaw = Mth.rotLerp(pt, p.yBodyRotO, p.yBodyRot);
			pose.pushPose();
			pose.translate(pos.x - cam.x, pos.y - cam.y, pos.z - cam.z);
			try {
				occlude(pose, p, pt, bodyYaw);
				render(pose, p, pt, bodyYaw);
			} finally {
				pose.popPose();
			}
		}
	}

	/**
	 * Stages invisible head and torso boxes that only write depth. The game draws skins as translucent,
	 * which leaves no depth behind, so without these anything worn behind the body shows through it.
	 * Alpha 1 rather than 0, because the overlay shader discards fully clear pixels.
	 */
	private static void occlude(PoseStack pose, Player player, float pt, float bodyYaw) {
		if (player.isVisuallySwimming()) return;
		final int clear = 0x01000000;
		final float half = 0.234f, torso = 0.703f, depth = 0.117f;
		pose.pushPose();
		toNeck(pose, player, pt, bodyYaw);
		solids(pose, (p, vc) -> {
			box(p, vc, clear, -half, -torso, -depth, half, 0, depth);
			// arms at rest, slim width: shortcut: they don't follow the swing, a pose-aware proxy would need the model's arm angles
			box(p, vc, clear, half, -torso, -depth, half + 0.176f, 0, depth);
			box(p, vc, clear, -half - 0.176f, -torso, -depth, -half, 0, depth);
		});
		pose.popPose();
		if (player.isFallFlying()) return;
		pose.pushPose();
		pose.translate(0, player.getEyeHeight() - NECK_BELOW_EYE, 0);
		pose.mulPose(Axis.YP.rotationDegrees(-Mth.rotLerp(pt, player.yHeadRotO, player.yHeadRot)));
		pose.mulPose(Axis.XP.rotationDegrees(Mth.lerp(pt, player.xRotO, player.getXRot())));
		solids(pose, (p, vc) -> box(p, vc, clear, -half, 0, -half, half, half * 2, half));
		pose.popPose();
	}

	/** An axis-aligned box between two corners. */
	protected static void box(PoseStack.Pose p, VertexConsumer vc, int argb, float a, float b, float c, float d, float e, float f) {
		quad(p, vc, argb, a, b, c, d, b, c, d, b, f, a, b, f);
		quad(p, vc, argb, a, e, c, a, e, f, d, e, f, d, e, c);
		quad(p, vc, argb, a, b, c, a, e, c, d, e, c, d, b, c);
		quad(p, vc, argb, a, b, f, d, b, f, d, e, f, a, e, f);
		quad(p, vc, argb, a, b, c, a, b, f, a, e, f, a, e, c);
		quad(p, vc, argb, d, b, c, d, e, c, d, e, f, d, b, f);
	}

	/** Draw on one player; the pose is at the player's feet (camera-relative), not yet rotated. */
	protected abstract void render(PoseStack pose, Player player, float partialTick, float bodyYaw);

	/**
	 * Moves the pose from the feet to the base of the neck in the body's frame (+Z forward), following the
	 * elytra tilt and the forward lean of a crouch, so things worn on the shoulders stay on them.
	 */
	protected static void toNeck(PoseStack pose, Player player, float pt, float bodyYaw) {
		pose.mulPose(Axis.YP.rotationDegrees(-bodyYaw));
		if (player.isFallFlying()) fallFlightTilt(pose, player, pt);
		// the standing or crouching eye height: the live one drops to 0.4 while flying or swimming
		float eye = player.getDimensions(player.isCrouching() ? Pose.CROUCHING : Pose.STANDING).eyeHeight();
		pose.translate(0, eye - NECK_BELOW_EYE, 0);
		if (player.isCrouching()) pose.mulPose(Axis.XP.rotationDegrees(CROUCH_LEAN));
	}

	/**
	 * With an elytra the game tilts the whole model around the feet (AvatarRenderer.setupRotations), so
	 * worn things tilt with it. The model is drawn flipped, which turns its pitch into the negative here;
	 * the yaw toward the flight path comes after it, as in the renderer.
	 */
	protected static void fallFlightTilt(PoseStack pose, Player player, float pt) {
		float t = player.getFallFlyingTicks() + pt;
		float scale = Mth.clamp(t * t / 100f, 0f, 1f);
		pose.mulPose(Axis.XP.rotationDegrees(scale * (90f + player.getXRot(pt))));
		pose.mulPose(Axis.YP.rotation(flightYaw(player, pt)));
	}

	/** Yaw from the look direction to the way you are moving; 0 when you are not moving sideways. */
	private static float flightYaw(Player player, float pt) {
		Vec3 move = player.getDeltaMovement();
		Vec3 look = player.getViewVector(pt);
		if (move.horizontalDistanceSqr() <= 1.0E-5 || look.horizontalDistanceSqr() <= 1.0E-5) return 0f;
		double dot = move.horizontal().normalize().dot(look.horizontal().normalize());
		double sign = move.x * look.z - move.z * look.x;
		return (float) (Math.signum(sign) * Math.acos(Math.min(1.0, Math.abs(dot))));
	}

	/** Stage opaque quads that write depth, for solid models, in the pose's current space. */
	protected static void solids(PoseStack pose, WorldOverlays.Geometry geometry) {
		WorldOverlays.solid(pose, geometry);
	}

	/** {@code argb} lit by a fixed soft light from the front-left and above, for a face with normal (nx, ny, nz). */
	protected static int lit(int argb, float nx, float ny, float nz) {
		float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
		if (len < 1e-6f) return argb;
		float d = (nx * 0.32f + ny * 0.84f + nz * 0.44f) / len;
		return Colors.shade(argb, 0.62f + 0.38f * Math.max(0, d));
	}

	/** Stage filled quads in the pose's current space. */
	protected static void quads(PoseStack pose, WorldOverlays.Geometry geometry) {
		WorldOverlays.fill(pose, false, geometry);
	}

	/** A flat ring in the XZ plane at height {@code y}, coloured along its length. */
	protected void ring(PoseStack pose, float inner, float outer, int alpha, float y) {
		quads(pose, (p, vc) -> {
			for (int i = 0; i < RING_SEGMENTS; i++) {
				double a0 = i * Math.PI * 2 / RING_SEGMENTS, a1 = (i + 1) * Math.PI * 2 / RING_SEGMENTS;
				float c0 = (float) Math.cos(a0), s0 = (float) Math.sin(a0), c1 = (float) Math.cos(a1), s1 = (float) Math.sin(a1);
				int color = color(i / (float) RING_SEGMENTS, alpha);
				quad(p, vc, color, c0 * inner, y, s0 * inner, c0 * outer, y, s0 * outer, c1 * outer, y, s1 * outer, c1 * inner, y, s1 * inner);
			}
		});
	}

	/** One quad (four corners in order). */
	protected static void quad(PoseStack.Pose p, VertexConsumer vc, int argb,
							   float x0, float y0, float z0, float x1, float y1, float z1,
							   float x2, float y2, float z2, float x3, float y3, float z3) {
		vc.addVertex(p, x0, y0, z0).setColor(argb);
		vc.addVertex(p, x1, y1, z1).setColor(argb);
		vc.addVertex(p, x2, y2, z2).setColor(argb);
		vc.addVertex(p, x3, y3, z3).setColor(argb);
	}

	/** A small cube centred on (x, y, z). */
	protected static void cube(PoseStack.Pose p, VertexConsumer vc, int argb, float x, float y, float z, float r) {
		box(p, vc, argb, x - r, y - r, z - r, x + r, y + r, z + r);
	}
}
