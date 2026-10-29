package dev.coffeeclient.module.modules.movement;

import dev.coffeeclient.module.Category;
import dev.coffeeclient.module.Module;
import dev.coffeeclient.setting.BooleanSetting;
import dev.coffeeclient.setting.EnumSetting;
import dev.coffeeclient.setting.Labeled;
import dev.coffeeclient.setting.NumberSetting;
import dev.coffeeclient.setting.Setting;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Walk up full blocks (or higher) without jumping. Simple raises your step height; Legit (from
 * Step) sends the positions of a jump and then moves you up, using a table of offsets per
 * height up to 2.5 blocks. Strict uses a tighter table and steps one block at most.
 */
public final class Step extends Module {
	public enum Mode { SIMPLE, LEGIT }

	public enum Active implements Labeled {
		ALWAYS("Always"), NOT_SNEAKING("Not sneaking"), SNEAKING("Sneaking");

		private final String label;

		Active(String label) {
			this.label = label;
		}

		@Override
		public String label() {
			return label;
		}
	}

	private static final double VANILLA = 0.6;

	public final EnumSetting<Mode> mode = setting(new EnumSetting<>("Mode", "Simple: higher step height. Legit: jump packets from an offset table", Mode.SIMPLE));
	public final NumberSetting height = setting(new NumberSetting("Height", "Highest step in blocks", 1.0, 0.6, 2.5, 0.1));
	public final BooleanSetting strict = setting(new BooleanSetting("Strict", "Tighter offsets for strict servers, one block at most", false));
	public final NumberSetting cooldown = setting(new NumberSetting("Cooldown", "Wait between steps", 0, 0, 1000, 50)
		.format(v -> v == 0 ? "Off" : (int) v + " ms"));
	public final EnumSetting<Active> active = setting(new EnumSetting<>("Active", "When stepping is allowed", Active.NOT_SNEAKING));
	public final NumberSetting safeHealth = setting(new NumberSetting("Safe step", "Stop stepping at or below this health (0 = off)", 0, 0, 20, 1)
		.format(v -> v == 0 ? "Off" : (int) v + " hp"));
	public final BooleanSetting groundOnly = setting(new BooleanSetting("Ground only", "Only step while on the ground", true));

	private long lastStep;

	public Step() {
		super("Step", Category.MOVEMENT, "Step up blocks without jumping.");
		height.visibleWhen(() -> mode.get() == Mode.SIMPLE || !strict.get());
		for (Setting<?> s : java.util.List.of(strict, cooldown)) s.visibleWhen(() -> mode.get() == Mode.LEGIT);
		groundOnly.visibleWhen(() -> mode.get() == Mode.SIMPLE);
	}

	@Override
	public void onTick() {
		LocalPlayer p = mc().player;
		if (p == null) return;
		boolean allowed = switch (active.get()) {
			case ALWAYS -> true;
			case NOT_SNEAKING -> !p.isShiftKeyDown();
			case SNEAKING -> p.isShiftKeyDown();
		} && (safeHealth.get() == 0 || p.getHealth() > safeHealth.get());
		if (mode.get() == Mode.LEGIT) {
			set(p, VANILLA);
			if (allowed && System.currentTimeMillis() - lastStep >= cooldown.get()) legitStep(p);
			return;
		}
		set(p, !allowed || (groundOnly.get() && !p.onGround()) ? VANILLA : height.get());
	}

	@Override
	protected void onDisable() {
		LocalPlayer p = mc().player;
		if (p != null) set(p, VANILLA);
	}

	/** Legit step: the jump positions from the offset table, then the top of the block. */
	private void legitStep(LocalPlayer p) {
		if (!p.horizontalCollision || !p.onGround() || p.onClimbable() || p.isInWater() || p.isInLava()) return;
		if (p.input.getMoveVector().length() <= 1e-5F || mc().options.keyJump.isDown()) return;
		double max = strict.get() ? 1 : height.get();
		AABB box = p.getBoundingBox().move(0, 0.05, 0).inflate(0.05);
		// The lowest block top in reach with room to stand on it (a ceiling above is not a step).
		double step = Double.NaN;
		for (VoxelShape shape : mc().level.getBlockCollisions(p, box.expandTowards(0, max - 0.05, 0))) {
			if (shape.isEmpty()) continue;
			double rise = shape.max(Direction.Axis.Y) - p.getY();
			if (rise <= 0.6 || rise > max + 1e-6 || rise >= step) continue;
			if (mc().level.noCollision(p, p.getBoundingBox().move(0, rise + 0.01, 0).inflate(0.05, 0, 0.05))) step = rise;
		}
		double[] offsets = offsets(step, strict.get());
		if (offsets == null) return;
		double y = 0;
		for (double o : offsets) {
			y += o;
			p.connection.send(new ServerboundMovePlayerPacket.Pos(p.getX(), p.getY() + y, p.getZ(), false, p.horizontalCollision));
		}
		p.setPos(p.getX(), p.getY() + step, p.getZ());
		lastStep = System.currentTimeMillis();
	}

	/** Per-tick rises for a step of {@code step} blocks, or null when it can't be stepped. */
	static double[] offsets(double step, boolean strict) {
		if (!(step > 0.6) || step > (strict ? 1.000001 : 2.5 + 1e-6)) return null;
		if (strict) return new double[]{0.424, 0.33712, 0.2519776};
		if (step > 2.019) return new double[]{0.425, 0.396, -0.122, -0.1, 0.423, 0.35, 0.28, 0.217, 0.15, -0.1};
		if (step > 1.5) return new double[]{0.42, 0.36, -0.15, -0.12, 0.39, 0.31, 0.24, -0.02};
		if (step > 1.015) return new double[]{0.42, 0.3332, 0.2568, 0.083, -0.078};
		return new double[]{0.42, 0.3332};
	}

	private static void set(LocalPlayer p, double value) {
		AttributeInstance attr = p.getAttribute(Attributes.STEP_HEIGHT);
		if (attr != null && attr.getBaseValue() != value) attr.setBaseValue(value);
	}

	@Override
	public String infoString() {
		return mode.display();
	}
}
