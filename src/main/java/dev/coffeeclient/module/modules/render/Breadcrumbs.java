package dev.coffeeclient.module.modules.render;

import dev.coffeeclient.core.EventBus;
import dev.coffeeclient.core.Events;
import dev.coffeeclient.gui.Draw;
import dev.coffeeclient.gui.Ui;
import dev.coffeeclient.module.Category;
import dev.coffeeclient.module.Module;
import dev.coffeeclient.setting.ActionSetting;
import dev.coffeeclient.setting.BooleanSetting;
import dev.coffeeclient.setting.ColorSetting;
import dev.coffeeclient.setting.EnumSetting;
import dev.coffeeclient.setting.IdListSetting;
import dev.coffeeclient.setting.NumberSetting;
import dev.coffeeclient.setting.Setting;
import dev.coffeeclient.util.Colors;
import dev.coffeeclient.util.WorldProjector;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * A trail along where you've walked: a fading line, dots, and/or real particles (embers, flames,
 * sparkles...) emitted along the path. Particles are spawned client-side only. Can also trail other
 * players, each in their own colour, kept after they leave.
 */
public final class Breadcrumbs extends Module {
	public enum Style { LINE, DOTS, PARTICLES, LINE_AND_PARTICLES }
	public enum Particles { EMBERS, FLAME, SOUL_FIRE, END_ROD, GLOW, SPARKS, CHERRY, ASH, HEARTS, NOTES, CUSTOM }
	public enum Target { YOU, OTHERS, BOTH }

	public final EnumSetting<Style> style = setting(new EnumSetting<>("Style", "How the trail is drawn", Style.LINE));
	public final ColorSetting color = setting(new ColorSetting("Line color", "Colour of the line/dots", 0xFFE7C88B));
	public final NumberSetting thickness = setting(new NumberSetting("Thickness", "Line/dot size in pixels", 2, 1, 4, 1));
	public final BooleanSetting fade = setting(new BooleanSetting("Fade", "Older parts fade out", true));
	public final EnumSetting<Particles> particle = setting(new EnumSetting<>("Particle", "Particle used in particle styles", Particles.EMBERS));
	public final IdListSetting customParticle = setting(new IdListSetting("Custom particle", "Any simple particle type",
		List.of("minecraft:flame"), Breadcrumbs::simpleParticles, true));
	public final NumberSetting density = setting(new NumberSetting("Density", "Particles per trail point each emit (lower = sparser)", 0.3, 0.05, 2, 0.05));
	public final NumberSetting emitEvery = setting(new NumberSetting("Emit every (ticks)", "How often particles are spawned", 4, 1, 20, 1));
	public final NumberSetting particleLength = setting(new NumberSetting("Particle trail length", "Newest points that emit particles", 40, 4, 400, 4));
	public final NumberSetting drift = setting(new NumberSetting("Drift", "Upward particle speed", 0.02, 0, 0.2, 0.01));
	public final NumberSetting length = setting(new NumberSetting("Length", "Points kept", 200, 16, 2000, 16));
	public final NumberSetting spacing = setting(new NumberSetting("Spacing", "Blocks between points", 1.0, 0.25, 8, 0.25));
	public final BooleanSetting paused = setting(new BooleanSetting("Paused", "Stop adding points but keep drawing", false));
	public final ActionSetting clear = setting(new ActionSetting("Clear trail", "Erase the current trails", () -> {
		this.points.clear();
		this.others.clear();
	}));

	public final EnumSetting<Target> target = group(new EnumSetting<>("Trail", "Whose path to draw", Target.YOU), "Other players");
	public final BooleanSetting uniqueColors = group(new BooleanSetting("Unique colors", "Give each player their own colour", true), "Other players");
	public final ColorSetting otherColor = group(new ColorSetting("Other color", "Colour of other players' trails", 0xFF8BC8E7), "Other players");
	public final BooleanSetting keepAfterLeaving = group(new BooleanSetting("Keep after leaving", "Keep a trail when its player logs out or goes out of sight", true), "Other players");

	private final ArrayDeque<Vec3> points = new ArrayDeque<>();
	/** Other players' trails and the dimension they were made in. */
	private final Map<UUID, Trail> others = new HashMap<>();

	private record Trail(String dimension, ArrayDeque<Vec3> points) {
	}
	private Object lastLevel;
	private int ticks;

	public Breadcrumbs() {
		super("Breadcrumbs", Category.RENDER, "Leaves a trail of lines, dots or particles (e.g. embers) along your path.");
		color.visibleWhen(() -> style.get() != Style.PARTICLES);
		thickness.visibleWhen(() -> style.get() != Style.PARTICLES);
		fade.visibleWhen(() -> style.get() != Style.PARTICLES);
		particle.visibleWhen(this::usesParticles);
		customParticle.visibleWhen(() -> usesParticles() && particle.get() == Particles.CUSTOM);
		density.visibleWhen(this::usesParticles);
		emitEvery.visibleWhen(this::usesParticles);
		particleLength.visibleWhen(this::usesParticles);
		drift.visibleWhen(this::usesParticles);
		for (Setting<?> s : List.of(uniqueColors, keepAfterLeaving)) s.visibleWhen(() -> target.get() != Target.YOU);
		otherColor.visibleWhen(() -> target.get() != Target.YOU && !uniqueColors.get());
		target.onChange(t -> {
			if (t == Target.YOU) others.clear();
			if (t == Target.OTHERS) points.clear();
		});
	}

	@Override
	protected void init(EventBus bus) {
		listen(Events.GameLeft.class, e -> others.clear());
	}

	/** Only looks, shows or presses keys like the player would: never the cause of a setback. */
	@Override
	public boolean affectsMovement() {
		return false;
	}

	@Override
	public boolean hasConfigScreen() {
		return true;
	}

	@Override
	public IdListSetting embeddedList() {
		return customParticle;
	}

	private boolean usesParticles() {
		return style.get() == Style.PARTICLES || style.get() == Style.LINE_AND_PARTICLES;
	}

	private static List<String> simpleParticles() {
		return BuiltInRegistries.PARTICLE_TYPE.entrySet().stream()
			.filter(e -> e.getValue() instanceof SimpleParticleType)
			.map(e -> e.getKey().identifier().toString()).sorted().toList();
	}

	private ParticleOptions pick() {
		var r = ThreadLocalRandom.current();
		return switch (particle.get()) {
			case EMBERS -> r.nextInt(6) == 0 ? ParticleTypes.LAVA : ParticleTypes.SMALL_FLAME;
			case FLAME -> ParticleTypes.FLAME;
			case SOUL_FIRE -> ParticleTypes.SOUL_FIRE_FLAME;
			case END_ROD -> ParticleTypes.END_ROD;
			case GLOW -> ParticleTypes.GLOW;
			case SPARKS -> ParticleTypes.ELECTRIC_SPARK;
			case CHERRY -> ParticleTypes.CHERRY_LEAVES;
			case ASH -> r.nextBoolean() ? ParticleTypes.ASH : ParticleTypes.WHITE_ASH;
			case HEARTS -> ParticleTypes.HEART;
			case NOTES -> ParticleTypes.NOTE;
			case CUSTOM -> {
				Identifier id = Identifier.tryParse(customParticle.first() == null ? "" : customParticle.first());
				var type = id == null ? null : BuiltInRegistries.PARTICLE_TYPE.getValue(id);
				yield type instanceof SimpleParticleType simple ? simple : ParticleTypes.FLAME;
			}
		};
	}

	@Override
	public void onTick() {
		var mc = mc();
		if (mc.player == null || mc.level == null) return;
		if (mc.level != lastLevel) {
			points.clear();
			lastLevel = mc.level;
		}
		if (!paused.get()) {
			if (target.get() != Target.OTHERS) follow(points, mc.player.position());
			if (target.get() != Target.YOU) trackOthers();
		}
		if (usesParticles() && ++ticks % emitEvery.intValue() == 0) emit();
	}

	/** Adds a point once the player moved a spacing away; a jump over 32 blocks (teleport) starts afresh. */
	private void follow(ArrayDeque<Vec3> trail, Vec3 position) {
		Vec3 pos = position.add(0, 0.05, 0);
		Vec3 last = trail.peekLast();
		if (last == null || last.distanceTo(pos) >= spacing.get()) {
			if (last != null && last.distanceTo(pos) > 32) trail.clear();
			trail.addLast(pos);
			while (trail.size() > length.intValue()) trail.removeFirst();
		}
	}

	private void trackOthers() {
		var mc = mc();
		String dimension = mc.level.dimension().identifier().toString();
		Set<UUID> seen = new HashSet<>();
		for (Player player : mc.level.players()) {
			if (player == mc.player) continue;
			seen.add(player.getUUID());
			Trail trail = others.get(player.getUUID());
			if (trail == null || !trail.dimension().equals(dimension)) {
				trail = new Trail(dimension, new ArrayDeque<>());
				others.put(player.getUUID(), trail);
			}
			follow(trail.points(), player.position());
		}
		if (!keepAfterLeaving.get()) others.keySet().retainAll(seen);
	}

	private int colorOf(UUID id) {
		if (!uniqueColors.get()) return otherColor.argb();
		return 0xFF000000 | java.awt.Color.HSBtoRGB((id.hashCode() & 0xFFFF) / 65535f, 0.6f, 1f);
	}

	private void emit() {
		var level = mc().level;
		var r = ThreadLocalRandom.current();
		List<Vec3> recent = new ArrayList<>(points);
		int from = Math.max(0, recent.size() - particleLength.intValue());
		for (int i = from; i < recent.size(); i++) {
			double count = density.get();
			while (count > 0) {
				if (count < 1 && r.nextDouble() > count) break;
				count -= 1;
				Vec3 p = recent.get(i);
				level.addParticle(pick(), p.x + (r.nextDouble() - 0.5) * 0.4, p.y + 0.05 + r.nextDouble() * 0.15, p.z + (r.nextDouble() - 0.5) * 0.4,
					(r.nextDouble() - 0.5) * 0.01, drift.get(), (r.nextDouble() - 0.5) * 0.01);
			}
		}
	}

	@Override
	public void renderOverlay(Ui ui, WorldProjector projector) {
		if (style.get() == Style.PARTICLES) return;
		draw(ui, projector, points, color.argb());
		if (others.isEmpty() || mc().level == null) return;
		String dimension = mc().level.dimension().identifier().toString();
		for (Map.Entry<UUID, Trail> e : others.entrySet()) {
			if (e.getValue().dimension().equals(dimension)) draw(ui, projector, e.getValue().points(), colorOf(e.getKey()));
		}
	}

	private void draw(Ui ui, WorldProjector projector, ArrayDeque<Vec3> trail, int argb) {
		if (trail.size() < 2) return;
		boolean dots = style.get() == Style.DOTS;
		List<Vec3> list = new ArrayList<>(trail);
		int n = list.size();
		int size = thickness.intValue();
		float[] prev = null;
		for (int i = 0; i < n; i++) {
			Vec3 v = list.get(i);
			float[] p = projector.project(v.x, v.y, v.z);
			float a = fade.get() ? 0.15f + 0.85f * i / (float) n : 1f;
			int c = Colors.fade(Colors.withAlpha(argb, 255), a);
			if (p != null && projector.onScreen(p, 200)) {
				if (dots) Draw.rect(ui.g, Math.round(p[0]) - size, Math.round(p[1]) - size, size * 2 + 1, size * 2 + 1, c);
				else if (prev != null && projector.onScreen(prev, 200)) Draw.line(ui.g, prev[0], prev[1], p[0], p[1], size, c);
			}
			prev = p;
		}
	}
}
