package dev.coffeeclient.module.modules.render;

import dev.coffeeclient.core.Events;
import dev.coffeeclient.module.Category;
import dev.coffeeclient.module.Module;
import dev.coffeeclient.setting.BooleanSetting;
import dev.coffeeclient.setting.EnumSetting;
import dev.coffeeclient.setting.IdListSetting;
import dev.coffeeclient.setting.Labeled;
import dev.coffeeclient.setting.NumberSetting;
import dev.coffeeclient.setting.Setting;
import dev.coffeeclient.util.Sounds;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/** Client-side effects when someone dies near you: lightning, particles, a sound, a title or a chat line. */
public final class KillEffects extends Module {
	public enum Particles implements Labeled {
		NONE("None", null), TOTEM("Totem", ParticleTypes.TOTEM_OF_UNDYING), FLAME("Flame", ParticleTypes.FLAME),
		SOUL("Soul fire", ParticleTypes.SOUL_FIRE_FLAME), HEARTS("Hearts", ParticleTypes.HEART),
		EXPLOSION("Explosion", ParticleTypes.EXPLOSION), CLOUD("Cloud", ParticleTypes.CLOUD);

		private final String label;
		final ParticleOptions type;

		Particles(String label, ParticleOptions type) {
			this.label = label;
			this.type = type;
		}

		@Override
		public String label() {
			return label;
		}
	}

	public final BooleanSetting players = group(new BooleanSetting("Players", "Effects for player deaths", true), "Who");
	public final BooleanSetting mobs = group(new BooleanSetting("Mobs", "Effects for mob deaths", false), "Who");
	public final BooleanSetting onlyMine = group(new BooleanSetting("Only my kills", "Only targets you hit in the last few seconds", false), "Who");
	public final NumberSetting range = group(new NumberSetting("Range", "Max distance of the death", 32, 4, 128, 4), "Who");

	public final BooleanSetting lightning = group(new BooleanSetting("Lightning", "A harmless client-side lightning strike (with thunder)", true), "Effects");
	public final EnumSetting<Particles> particles = group(new EnumSetting<>("Particles", "Particle burst at the body", Particles.TOTEM), "Effects");
	public final NumberSetting particleCount = group(new NumberSetting("Particle count", "How many particles", 40, 5, 200, 5), "Effects");
	public final BooleanSetting playSound = group(new BooleanSetting("Sound", "Play a sound (heard only by you)", false), "Effects");
	public final IdListSetting sound = group(new IdListSetting("Sound effect", "Sound played on a death (click ▶ to preview)",
		List.of("minecraft:entity.wither.death"), Sounds::allIds, true).labels(Sounds::label).preview(id -> {
		Sounds.play(id, 1f, 1f);
		return id;
	}), "Effects");
	public final NumberSetting volume = group(new NumberSetting("Volume", "Sound volume", 0.6, 0, 1, 0.05), "Effects");
	public final BooleanSetting title = group(new BooleanSetting("Title", "Show the name in the middle of the screen (your kills only)", false), "Effects");
	public final BooleanSetting chatLine = group(new BooleanSetting("Chat line", "Write who died in chat", false), "Effects");

	/** A target counts as yours if it dies this soon after your hit. */
	private static final long CREDIT_MS = 5000;
	/** Client-side entity ids, negative like FakePlayer's but in their own range. */
	private static final AtomicInteger NEXT_ID = new AtomicInteger(-2_000_000);
	private final Map<Integer, Long> hits = new HashMap<>();

	public KillEffects() {
		super("KillEffects", List.of("Kill Effects", "DeathEffects"), Category.RENDER, "Lightning, particles or sounds when someone dies near you.");
		particleCount.visibleWhen(() -> particles.get() != Particles.NONE);
		for (Setting<?> s : List.of(sound, volume)) s.visibleWhen(playSound::get);

		listen(Events.AttackEntity.class, e -> {
			if (e.target() instanceof LivingEntity) hits.put(e.target().getId(), System.currentTimeMillis());
		});
		listen(Events.EntityDeath.class, e -> onDeath(e.entity()));
		listen(Events.GameLeft.class, e -> hits.clear());
	}

	@Override
	public boolean hasConfigScreen() {
		return true;
	}

	@Override
	protected void onDisable() {
		hits.clear();
	}

	@Override
	public void onTick() {
		long now = System.currentTimeMillis();
		hits.values().removeIf(at -> now - at > CREDIT_MS);
	}

	private void onDeath(LivingEntity entity) {
		Minecraft mc = mc();
		ClientLevel level = mc.level;
		if (level == null || mc.player == null || entity == mc.player) return;
		if (!(entity instanceof Player ? players.get() : mobs.get())) return;
		if (entity.distanceToSqr(mc.player) > range.get() * range.get()) return;
		Long hit = hits.remove(entity.getId());
		boolean mine = hit != null && System.currentTimeMillis() - hit <= CREDIT_MS;
		if (onlyMine.get() && !mine) return;

		Vec3 pos = entity.position();
		if (lightning.get()) strike(level, pos);
		if (particles.get().type != null) burst(level, entity, particles.get().type, particleCount.intValue());
		if (playSound.get()) Sounds.play(sound.first(), 1f, volume.floatValue());
		String name = entity.getName().getString();
		if (title.get() && mine) {
			mc.gui.hud.setTimes(5, 30, 10);
			mc.gui.hud.setTitle(Component.literal("☠ " + name).withColor(0xFF5555));
		}
		if (chatLine.get()) chat(Component.literal(name).withColor(0xFFFFFF).append(Component.literal(mine ? " was killed by you" : " died").withColor(0xAAAAAA)));
	}

	private static void strike(ClientLevel level, Vec3 pos) {
		LightningBolt bolt = EntityTypes.LIGHTNING_BOLT.create(level, EntitySpawnReason.TRIGGERED);
		if (bolt == null) return;
		bolt.setId(NEXT_ID.getAndDecrement());
		bolt.setVisualOnly(true);
		bolt.snapTo(pos.x, pos.y, pos.z);
		level.addEntity(bolt);
	}

	private static void burst(ClientLevel level, LivingEntity entity, ParticleOptions type, int count) {
		RandomSource random = level.getRandom();
		double height = entity.getBbHeight();
		for (int i = 0; i < count; i++) {
			level.addParticle(type, entity.getX() + (random.nextDouble() - 0.5) * entity.getBbWidth(), entity.getY() + random.nextDouble() * height,
				entity.getZ() + (random.nextDouble() - 0.5) * entity.getBbWidth(),
				(random.nextDouble() - 0.5) * 0.4, random.nextDouble() * 0.4, (random.nextDouble() - 0.5) * 0.4);
		}
	}
}
