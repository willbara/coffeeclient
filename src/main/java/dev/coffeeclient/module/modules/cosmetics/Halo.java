package dev.coffeeclient.module.modules.cosmetics;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.coffeeclient.setting.BooleanSetting;
import dev.coffeeclient.setting.NumberSetting;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;

/** A glowing ring floating over the head, bobbing gently and turning slowly. */
public final class Halo extends CosmeticModule {
	public final NumberSetting size = setting(new NumberSetting("Size", "Ring radius", 1.0, 0.5, 1.6, 0.05));
	public final NumberSetting spin = setting(new NumberSetting("Spin", "Turning speed", 1.0, 0.0, 4.0, 0.1));
	public final BooleanSetting glow = setting(new BooleanSetting("Glow", "Soft light around the ring", true));

	public Halo() {
		super("Halo", "A glowing ring over your head (only you can see it).");
	}

	@Override
	protected void render(PoseStack pose, Player player, float pt, float bodyYaw) {
		float t = time();
		float headYaw = Mth.rotLerp(pt, player.yHeadRotO, player.yHeadRot);
		float bob = (float) Math.sin(t * 2.2f + player.getId()) * 0.04f;
		pose.translate(0, player.getBbHeight() + 0.28f + bob, 0);
		pose.mulPose(Axis.YP.rotationDegrees(-headYaw));
		pose.mulPose(Axis.XP.rotationDegrees(-14));
		pose.mulPose(Axis.YP.rotation(t * spin.floatValue()));
		float r = 0.3f * size.floatValue();
		ring(pose, r - 0.035f, r + 0.035f, 0xE0, 0f);
		if (glow.get()) {
			ring(pose, r + 0.035f, r + 0.11f, 0x38, 0.01f);
			ring(pose, r - 0.1f, r - 0.035f, 0x28, -0.01f);
		}
	}
}
