package dev.coffeeclient.render;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Stick figures for EntityESP: the player model is posed for this frame (walk cycle, head turn),
 * then lines are drawn along its limbs, leaning for sneaking and lying flat for swimming and
 * elytra flight.
 */
public final class Skeletons {
	private Skeletons() {
	}

	public static void draw(VertexConsumer vc, Player player, float pt, Vec3 cam, int argb, float width) {
		if (!(Minecraft.getInstance().getEntityRenderDispatcher().getRenderer(player) instanceof AvatarRenderer<?> renderer)) return;
		if (!(renderer.getModel() instanceof PlayerModel model)) return;
		float bodyYaw = Mth.rotLerp(pt, player.yBodyRotO, player.yBodyRot);
		float headYaw = Mth.rotLerp(pt, player.yHeadRotO, player.yHeadRot);
		float pitch = player.getViewXRot(pt);
		boolean swimming = player.isVisuallySwimming(), sneaking = player.isCrouching(), flying = player.isFallFlying();

		AvatarRenderState state = new AvatarRenderState();
		state.walkAnimationPos = player.walkAnimation.position(pt);
		state.walkAnimationSpeed = player.walkAnimation.speed(pt);
		state.ageInTicks = player.tickCount + pt;
		state.yRot = headYaw - bodyYaw;
		state.xRot = pitch;
		model.setupAnim(state);

		Vec3 feet = player.getPosition(pt).subtract(cam);
		Matrix4f outer = new Matrix4f().translate((float) feet.x, (float) feet.y, (float) feet.z);
		if (swimming) outer.translate(0, 0.35f, 0);
		outer.rotateY((float) Math.toRadians(-(bodyYaw + 180)));
		if (swimming || flying) outer.rotateX((float) Math.toRadians(-(90 + pitch)));
		if (swimming) outer.translate(0, -0.95f, 0);

		Matrix4f tilted = new Matrix4f(outer).translate(0, 0.75f, 0);
		if (sneaking && !swimming && !flying) tilted.rotate(0.5f, -1, 0, 0);
		tilted.translate(0, -0.75f, 0);

		float hipY = sneaking ? 0.6f : 0.7f, hipZ = sneaking ? 0.23f : 0, shoulderY = sneaking ? 1.05f : 1.35f, neckY = sneaking ? 1.05f : 1.4f;
		bone(vc, tilted, 0, hipY, hipZ, 0, neckY, 0, argb, width);
		bone(vc, tilted, -0.37f, shoulderY, 0, 0.37f, shoulderY, 0, argb, width);
		bone(vc, outer, -0.15f, hipY, hipZ, 0.15f, hipY, hipZ, argb, width);
		limb(vc, new Matrix4f(tilted).translate(0, neckY, 0), model.head, 0.15f, argb, width);
		limb(vc, new Matrix4f(outer).translate(0.15f, hipY, hipZ), model.rightLeg, -0.6f, argb, width);
		limb(vc, new Matrix4f(outer).translate(-0.15f, hipY, hipZ), model.leftLeg, -0.6f, argb, width);
		limb(vc, new Matrix4f(tilted).translate(0.37f, shoulderY, 0), model.rightArm, -0.55f, argb, width);
		limb(vc, new Matrix4f(tilted).translate(-0.37f, shoulderY, 0), model.leftArm, -0.55f, argb, width);
	}

	/** A bone from the joint along the part's rotation, {@code length} up (or down when negative). */
	private static void limb(VertexConsumer vc, Matrix4f m, ModelPart part, float length, int argb, float width) {
		if (part.zRot != 0) m.rotate(part.zRot, 0, 0, 1);
		if (part.yRot != 0) m.rotate(part.yRot, 0, -1, 0);
		if (part.xRot != 0) m.rotate(part.xRot, -1, 0, 0);
		bone(vc, m, 0, 0, 0, 0, length, 0, argb, width);
	}

	private static void bone(VertexConsumer vc, Matrix4f m, float x1, float y1, float z1, float x2, float y2, float z2, int argb, float width) {
		Vector3f a = m.transformPosition(new Vector3f(x1, y1, z1)), b = m.transformPosition(new Vector3f(x2, y2, z2));
		Vector3f d = new Vector3f(b).sub(a);
		float len = d.length();
		if (len < 1e-6f) return;
		d.div(len);
		WorldOverlays.line(vc, a.x, a.y, a.z, b.x, b.y, b.z, d.x, d.y, d.z, argb, width);
	}
}
