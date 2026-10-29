package dev.coffeeclient.util;

import dev.coffeeclient.util.fakeplayer.FakePlayerEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.tags.EntityTypeTags;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.CombatRules;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.BlocksAttacks;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RespawnAnchorBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Client-side estimate of explosion and melee damage. Explosions follow {@code ServerExplosion} and
 * {@code LivingEntity#hurtServer} in two steps: {@link #crystalRaw}
 * is the damage before armour (difficulty and a raised shield applied), which is what hit immunity
 * compares; {@link #mitigate} then applies armour, Resistance and protection. {@link #upperBound} is the
 * cheap no-cover maximum, for skipping spots that cannot reach a damage bar.
 *
 * <p>Rays treat every solid block as cover, as the server does. A {@link View} can stand in for the
 * world, for example to rate a crystal on obsidian that is not placed yet. Melee damage
 * ({@link #attack}) lets AutoWeapon pick a weapon.
 */
public final class Damage {
	public static final float CRYSTAL_POWER = 6f;
	/** How far an end crystal's blast reaches (its power times two). */
	public static final double CRYSTAL_RANGE = CRYSTAL_POWER * 2;
	/** Beds and respawn anchors blow up with this power where they do not work. */
	public static final float BLOCK_BLAST_POWER = 5;
	private static final EquipmentSlot[] ARMOR = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};

	private Damage() {
	}

	/** Damage {@code target}, where it stands now, would take from a crystal at {@code crystal}. */
	public static float crystal(LivingEntity target, Vec3 crystal) {
		return mitigate(target, crystalRaw(target, target.position(), target.getBoundingBox(), crystal, Minecraft.getInstance().level));
	}

	/**
	 * Damage before armour that {@code target}, with its feet at {@code feet} and box {@code box}, would
	 * take from a crystal at {@code crystal}, with blocks read from {@code view}.
	 */
	public static float crystalRaw(LivingEntity target, Vec3 feet, AABB box, Vec3 crystal, BlockGetter view) {
		return explosionRaw(target, feet, box, crystal, CRYSTAL_POWER, view);
	}

	/** {@link #crystalRaw} for any explosion of {@code power}. */
	public static float explosionRaw(LivingEntity target, Vec3 feet, AABB box, Vec3 center, float power, BlockGetter view) {
		if (isInvulnerable(target)) return 0;
		double radius = power * 2.0;
		double distance = Math.sqrt(feet.distanceToSqr(center)) / radius;
		if (distance > 1) return 0;
		double impact = (1 - distance) * exposure(center, box, view);
		float damage = (float) ((impact * impact + impact) / 2 * 7 * radius + 1);
		return shield(target, feet, center, scale(target, damage));
	}

	/** Most damage a crystal at {@code crystal} could deal to {@code target} at {@code feet}, ignoring cover. */
	public static float upperBound(LivingEntity target, Vec3 feet, Vec3 crystal) {
		if (isInvulnerable(target)) return 0;
		double distance = Math.sqrt(feet.distanceToSqr(crystal)) / CRYSTAL_RANGE;
		if (distance > 1) return 0;
		double impact = 1 - distance;
		float damage = (float) ((impact * impact + impact) / 2 * 7 * CRYSTAL_RANGE + 1);
		return mitigate(target, shield(target, feet, crystal, scale(target, damage)));
	}

	/** Armour, Resistance and protection enchantments, as the server applies them to explosion damage. */
	public static float mitigate(LivingEntity target, float damage) {
		if (damage <= 0) return 0;
		DamageSource source = Minecraft.getInstance().level.damageSources().explosion(null);
		damage = CombatRules.getDamageAfterAbsorb(target, damage, source, (float) Math.floor(target.getAttributeValue(Attributes.ARMOR)),
			(float) target.getAttributeValue(Attributes.ARMOR_TOUGHNESS));
		MobEffectInstance resistance = target.getEffect(MobEffects.RESISTANCE);
		if (resistance != null) {
			damage = Math.max(damage * (25 - (resistance.getAmplifier() + 1) * 5) / 25f, 0);
			if (damage <= 0) return 0;
		}
		int protection = 0;
		for (EquipmentSlot slot : ARMOR) {
			ItemStack stack = target.getItemBySlot(slot);
			protection += EnchantmentIds.level(stack, Enchantments.PROTECTION);
			protection += 2 * EnchantmentIds.level(stack, Enchantments.BLAST_PROTECTION);
		}
		return Math.max(CombatRules.getDamageAfterMagicAbsorb(damage, protection), 0);
	}

	/**
	 * Melee damage {@code attacker} would deal to {@code target} with {@code weapon} in the main hand:
	 * the weapon's attack damage, Sharpness, Smite and Bane of Arthropods, Strength and Weakness, then
	 * the target's armour. A close estimate for comparing weapons, like getAttackDamage.
	 */
	public static float attack(LivingEntity attacker, LivingEntity target, ItemStack weapon) {
		ItemAttributeModifiers modifiers = weapon.getOrDefault(DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.EMPTY);
		float damage = (float) modifiers.compute(Attributes.ATTACK_DAMAGE, 1.0, EquipmentSlot.MAINHAND);
		int sharpness = EnchantmentIds.level(weapon, Enchantments.SHARPNESS);
		if (sharpness > 0) damage += 0.5f * sharpness + 0.5f;
		if (target.getType().builtInRegistryHolder().is(EntityTypeTags.SENSITIVE_TO_SMITE)) damage += 2.5f * EnchantmentIds.level(weapon, Enchantments.SMITE);
		if (target.getType().builtInRegistryHolder().is(EntityTypeTags.SENSITIVE_TO_BANE_OF_ARTHROPODS)) damage += 2.5f * EnchantmentIds.level(weapon, Enchantments.BANE_OF_ARTHROPODS);
		MobEffectInstance strength = attacker.getEffect(MobEffects.STRENGTH);
		if (strength != null) damage += 3 * (strength.getAmplifier() + 1);
		MobEffectInstance weakness = attacker.getEffect(MobEffects.WEAKNESS);
		if (weakness != null) damage -= 4 * (weakness.getAmplifier() + 1);
		if (damage <= 0) return 0;
		DamageSource source = attacker instanceof Player player
			? Minecraft.getInstance().level.damageSources().playerAttack(player)
			: Minecraft.getInstance().level.damageSources().mobAttack(attacker);
		return CombatRules.getDamageAfterAbsorb(target, damage, source, (float) Math.floor(target.getAttributeValue(Attributes.ARMOR)),
			(float) target.getAttributeValue(Attributes.ARMOR_TOUGHNESS));
	}

	/** The most damage one crystal or lit creeper near {@code target} would deal if it went off now. */
	public static float entityBlast(LivingEntity target) {
		double range = CRYSTAL_RANGE * CRYSTAL_RANGE;
		float worst = 0;
		for (Entity entity : Minecraft.getInstance().level.entitiesForRendering()) {
			if (entity.distanceToSqr(target) > range) continue;
			if (entity instanceof EndCrystal crystal) worst = Math.max(worst, crystal(target, crystal.position()));
			else if (entity instanceof Creeper creeper && creeper.getSwelling(0) > 0) worst = Math.max(worst, blast(target, creeper.position(), creeper.isPowered() ? 6 : 3));
		}
		return worst;
	}

	/**
	 * The worst blast from a bed (where beds explode) or a charged anchor (where anchors do not work)
	 * in reach of {@code target}. Scans the blocks around it, so call it at most once a tick.
	 */
	public static float blockBlast(LivingEntity target) {
		var level = Minecraft.getInstance().level;
		BlockPos center = target.blockPosition();
		boolean beds = level.environmentAttributes().getValue(EnvironmentAttributes.BED_RULE, center).explodes();
		boolean anchors = !level.environmentAttributes().getValue(EnvironmentAttributes.RESPAWN_ANCHOR_WORKS, center);
		if (!beds && !anchors) return 0;
		int r = (int) (BLOCK_BLAST_POWER * 2);
		float worst = 0;
		for (BlockPos pos : BlockPos.betweenClosed(center.offset(-r, -r, -r), center.offset(r, r, r))) {
			BlockState state = level.getBlockState(pos);
			boolean live = beds && state.getBlock() instanceof BedBlock
				|| anchors && state.is(Blocks.RESPAWN_ANCHOR) && state.getValue(RespawnAnchorBlock.CHARGE) > 0;
			if (live) worst = Math.max(worst, blast(target, Vec3.atCenterOf(pos), BLOCK_BLAST_POWER));
		}
		return worst;
	}

	/** Damage {@code target}, where it stands, would take from a blast of {@code power} at {@code center}. */
	public static float blast(LivingEntity target, Vec3 center, float power) {
		return mitigate(target, explosionRaw(target, target.position(), target.getBoundingBox(), center, power, Minecraft.getInstance().level));
	}

	/** Fall damage if {@code target} landed now (three blocks are free; armour and Feather Falling are left out). */
	public static float fall(LivingEntity target) {
		return !target.onGround() && target.fallDistance > 3 ? (float) target.fallDistance - 3 : 0;
	}

	/** Health plus absorption. */
	public static float health(LivingEntity entity) {
		return entity.getHealth() + entity.getAbsorptionAmount();
	}

	/** Players in creative or spectator take no explosion damage. */
	public static boolean isInvulnerable(LivingEntity target) {
		if (!(target instanceof Player player) || player instanceof FakePlayerEntity) return false;
		if (player == Minecraft.getInstance().player) return player.getAbilities().invulnerable;
		GameType mode = Targets.gameMode(player);
		return mode == GameType.CREATIVE || mode == GameType.SPECTATOR;
	}

	/** Explosions scale with difficulty for players only (see {@code Player#hurtServer}). */
	private static float scale(LivingEntity target, float damage) {
		if (!(target instanceof Player)) return damage;
		return switch (target.level().getDifficulty()) {
			case PEACEFUL -> 0;
			case EASY -> Math.min(damage / 2 + 1, damage);
			case NORMAL -> damage;
			case HARD -> damage * 1.5f;
		};
	}

	/** Damage left after a raised shield facing the blast (see {@code LivingEntity#applyItemBlocking}). */
	private static float shield(LivingEntity target, Vec3 feet, Vec3 center, float damage) {
		if (damage <= 0) return 0;
		ItemStack blocking = target.getItemBlockingWith();
		if (blocking == null) return damage;
		BlocksAttacks blocks = blocking.get(DataComponents.BLOCKS_ATTACKS);
		if (blocks == null) return damage;
		DamageSource source = Minecraft.getInstance().level.damageSources().explosion(null);
		if (blocks.bypassedBy().map(set -> set.contains(source.typeHolder())).orElse(false)) return damage;
		Vec3 look = Vec3.directionFromRotation(0, target.getYHeadRot());
		Vec3 offset = center.subtract(feet);
		double angle = Math.acos(new Vec3(offset.x, 0, offset.z).normalize().dot(look));
		return damage - blocks.resolveBlockedDamage(source, damage, angle);
	}

	/** Share of rays from points in {@code box} that reach {@code source} unblocked (see {@code ServerExplosion#getSeenPercent}). */
	public static float exposure(Vec3 source, AABB box, BlockGetter view) {
		double xs = 1 / ((box.maxX - box.minX) * 2 + 1);
		double ys = 1 / ((box.maxY - box.minY) * 2 + 1);
		double zs = 1 / ((box.maxZ - box.minZ) * 2 + 1);
		double xOffset = (1 - Math.floor(1 / xs) * xs) / 2;
		double zOffset = (1 - Math.floor(1 / zs) * zs) / 2;
		if (xs < 0 || ys < 0 || zs < 0) return 0;
		int hits = 0, count = 0;
		for (double xx = 0; xx <= 1; xx += xs) {
			for (double yy = 0; yy <= 1; yy += ys) {
				for (double zz = 0; zz <= 1; zz += zs) {
					Vec3 from = new Vec3(Mth.lerp(xx, box.minX, box.maxX) + xOffset, Mth.lerp(yy, box.minY, box.maxY), Mth.lerp(zz, box.minZ, box.maxZ) + zOffset);
					if (!blocked(from, source, view)) hits++;
					count++;
				}
			}
		}
		return (float) hits / count;
	}

	/** True when a block's collision shape lies between the points. */
	private static boolean blocked(Vec3 from, Vec3 to, BlockGetter view) {
		Boolean hit = BlockGetter.traverseBlocks(from, to, view, (v, pos) -> {
			BlockState state = v.getBlockState(pos);
			return state.getCollisionShape(v, pos).clip(from, to, pos) != null ? Boolean.TRUE : null;
		}, v -> Boolean.FALSE);
		return hit;
	}

	/** The world with one block swapped, for rating a crystal on a base that is not placed yet. */
	public static final class View implements BlockGetter {
		private final BlockGetter world;
		private final BlockPos pos;
		private final BlockState state;

		public View(BlockGetter world, BlockPos pos, BlockState state) {
			this.world = world;
			this.pos = pos;
			this.state = state;
		}

		/** {@code world} with obsidian at {@code base}. */
		public static View obsidian(BlockGetter world, BlockPos base) {
			return new View(world, base.immutable(), Blocks.OBSIDIAN.defaultBlockState());
		}

		@Override
		public BlockEntity getBlockEntity(BlockPos at) {
			return at.equals(pos) ? null : world.getBlockEntity(at);
		}

		@Override
		public BlockState getBlockState(BlockPos at) {
			return at.equals(pos) ? state : world.getBlockState(at);
		}

		@Override
		public FluidState getFluidState(BlockPos at) {
			return at.equals(pos) ? state.getFluidState() : world.getFluidState(at);
		}

		@Override
		public int getHeight() {
			return world.getHeight();
		}

		@Override
		public int getMinY() {
			return world.getMinY();
		}
	}
}
