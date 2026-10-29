package dev.coffeeclient.util;

import dev.coffeeclient.mixin.AbstractArrowAccessor;
import dev.coffeeclient.module.modules.movement.NoSlow;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.monster.breeze.Breeze;
import net.minecraft.world.entity.projectile.LlamaSpit;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.ProjectileDeflection;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.entity.projectile.ThrowableProjectile;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.entity.projectile.arrow.Arrow;
import net.minecraft.world.entity.projectile.arrow.SpectralArrow;
import net.minecraft.world.entity.projectile.arrow.ThrownTrident;
import net.minecraft.world.entity.projectile.hurtingprojectile.AbstractHurtingProjectile;
import net.minecraft.world.entity.projectile.hurtingprojectile.windcharge.AbstractWindCharge;
import net.minecraft.world.entity.projectile.throwableitemprojectile.Snowball;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEgg;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownExperienceBottle;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownLingeringPotion;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownSplashPotion;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.EggItem;
import net.minecraft.world.item.EnderpearlItem;
import net.minecraft.world.item.ExperienceBottleItem;
import net.minecraft.world.item.FishingRodItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.LingeringPotionItem;
import net.minecraft.world.item.SnowballItem;
import net.minecraft.world.item.SplashPotionItem;
import net.minecraft.world.item.TridentItem;
import net.minecraft.world.item.WindChargeItem;
import net.minecraft.world.item.component.ChargedProjectiles;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniond;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Steps a projectile tick by tick with the game's own physics (gravity, drag, water, entity and block
 * hits, piercing, deflection), for drawing where something will land.
 *
 * <p>Collision is checked from the previous to the current position (vanilla goes current to next),
 * so a path starting inside a block still stops on it. A throwaway projectile of each type is kept
 * per level as the "source" vanilla's hit tests need. Client thread only; reuse one instance.
 */
public final class ProjectileSimulator {
	/** Launch speed, extra pitch, gravity and drag of one projectile kind (see minecraft.wiki/w/Projectile). */
	public record MotionData(float power, float roll, double gravity, float airDrag, float waterDrag, EntityType<?> entity) {
		MotionData withPower(float power) {
			return new MotionData(power, roll, gravity, airDrag, waterDrag, entity);
		}
	}

	/** One simulated tick: whether the projectile stopped, and what it hit on the way. */
	public record Step(boolean stop, List<HitResult> hits) {
		static final Step MISS = new Step(true, List.of());
	}

	private static final MotionData EGG = new MotionData(1.5f, 0, 0.03, 0.99f, 0.8f, EntityTypes.EGG);
	private static final MotionData ENDER_PEARL = new MotionData(1.5f, 0, 0.03, 0.99f, 0.8f, EntityTypes.ENDER_PEARL);
	private static final MotionData SNOWBALL = new MotionData(1.5f, 0, 0.03, 0.99f, 0.8f, EntityTypes.SNOWBALL);
	private static final MotionData EXPERIENCE_BOTTLE = new MotionData(0.7f, -20, 0.07, 0.99f, 0.8f, EntityTypes.EXPERIENCE_BOTTLE);
	private static final MotionData LINGERING_POTION = new MotionData(0.5f, -20, 0.05, 0.99f, 0.8f, EntityTypes.LINGERING_POTION);
	private static final MotionData SPLASH_POTION = new MotionData(0.5f, -20, 0.05, 0.99f, 0.8f, EntityTypes.SPLASH_POTION);
	private static final MotionData EXPLOSIVE = new MotionData(0, 0, 0, 1, 1, null);
	private static final MotionData WIND_CHARGE = new MotionData(1.5f, 0, 0, 1, 1, EntityTypes.WIND_CHARGE);
	private static final MotionData ARROW = new MotionData(0, 0, 0.05, 0.99f, 0.6f, EntityTypes.ARROW);
	private static final MotionData TRIDENT = new MotionData(2.5f, 0, 0.05, 0.99f, 0.99f, EntityTypes.TRIDENT);
	private static final MotionData FIREWORK_ROCKET = new MotionData(0, 0, 0, 1, 1, EntityTypes.FIREWORK_ROCKET);
	private static final MotionData FISHING_BOBBER = new MotionData(0, 0, 0.03, 0.92f, 0, EntityTypes.FISHING_BOBBER);
	private static final MotionData LLAMA_SPIT = new MotionData(1.5f, 0, 0.06, 0.99f, 0, EntityTypes.LLAMA_SPIT);

	private final BlockPos.MutableBlockPos blockPos = new BlockPos.MutableBlockPos();
	/** Throwaway projectiles used as hit-test sources, one per type, for {@link #dummyLevel}. */
	private final Map<EntityType<?>, Projectile> dummies = new IdentityHashMap<>();
	private ClientLevel dummyLevel;

	public final Vector3d pos = new Vector3d();
	private final Vector3d velocity = new Vector3d();
	private Vec3 prev = Vec3.ZERO;
	private Projectile entity;
	private EntityDimensions dimensions;
	private int tickCount, pierceLevel;
	private double gravity;
	private float airDrag, waterDrag;
	private boolean inWater;

	// ---- held items ----

	/**
	 * Starts a path for {@code user} firing {@code stack}.
	 *
	 * @param angleOffset sideways turn in radians (crossbow multishot)
	 * @param accurate    add the shooter's own movement, as the game does
	 * @return false when the item is not a projectile launcher (or a bow is not drawn enough)
	 */
	public boolean set(Entity user, ItemStack stack, double angleOffset, boolean accurate, float tickDelta) {
		Item item = stack.getItem();
		Minecraft mc = Minecraft.getInstance();
		switch (item) {
			case BowItem ignored -> {
				if (!(user instanceof LivingEntity living)) return false;
				float charge = BowItem.getPowerForTime(living.getTicksUsingItem());
				if (charge <= 0.1) {
					// Show your own full-power shot before you draw; skip others who are not drawing.
					if (user == mc.player) charge = 1;
					else return false;
				}
				set(user, angleOffset, accurate, tickDelta, ARROW.withPower(charge * 3));
			}
			case CrossbowItem ignored -> {
				ChargedProjectiles charged = stack.get(DataComponents.CHARGED_PROJECTILES);
				if (charged == null || charged.isEmpty()) return false;
				boolean rocket = charged.contains(Items.FIREWORK_ROCKET);
				// CrossbowItem.getShootingPower
				set(user, angleOffset, accurate, tickDelta, (rocket ? FIREWORK_ROCKET : ARROW).withPower(rocket ? 1.6f : 3.15f));
				pierceLevel = rocket ? 0 : EnchantmentIds.level(stack, Enchantments.PIERCING);
			}
			case WindChargeItem ignored -> set(user, angleOffset, accurate, tickDelta, WIND_CHARGE);
			case TridentItem ignored -> set(user, angleOffset, accurate, tickDelta, TRIDENT);
			case SnowballItem ignored -> set(user, angleOffset, accurate, tickDelta, SNOWBALL);
			case EggItem ignored -> set(user, angleOffset, accurate, tickDelta, EGG);
			case EnderpearlItem ignored -> set(user, angleOffset, accurate, tickDelta, ENDER_PEARL);
			case ExperienceBottleItem ignored -> set(user, angleOffset, accurate, tickDelta, EXPERIENCE_BOTTLE);
			case SplashPotionItem ignored -> set(user, angleOffset, accurate, tickDelta, SPLASH_POTION);
			case LingeringPotionItem ignored -> set(user, angleOffset, accurate, tickDelta, LINGERING_POTION);
			case FishingRodItem ignored -> setFishingBobber(user, tickDelta);
			default -> {
				return false;
			}
		}
		return entity != null;
	}

	private void set(Entity user, double angleOffset, boolean accurate, float tickDelta, MotionData data) {
		lerped(user, tickDelta);
		pos.add(0, user.getEyeHeight(pose(user)) - 0.1f, 0);

		float[] look = look(user, tickDelta);
		double yaw = look[0], pitch = look[1];
		double x, y, z;
		if (angleOffset == 0) {
			x = -Math.sin(Math.toRadians(yaw)) * Math.cos(Math.toRadians(pitch));
			y = -Math.sin(Math.toRadians(pitch + data.roll()));
			z = Math.cos(Math.toRadians(yaw)) * Math.cos(Math.toRadians(pitch));
		} else {
			Vec3 up = user.getUpVector(1);
			Vec3 view = user.getViewVector(1);
			Vector3d v = new Vector3d(view.x, view.y, view.z).rotate(new Quaterniond().setAngleAxis(angleOffset, up.x, up.y, up.z));
			x = v.x;
			y = v.y;
			z = v.z;
		}
		velocity.set(x, y, z).normalize().mul(data.power());
		if (accurate) {
			Vec3 moving = user.getKnownMovement();
			velocity.add(moving.x, user.onGround() ? 0 : moving.y, moving.z);
		}
		start(dummy(data.entity()), data);
	}

	private void setFishingBobber(Entity user, float tickDelta) {
		float[] look = look(user, tickDelta);
		double yaw = look[0], pitch = look[1];
		double h = Math.cos(-yaw * Mth.DEG_TO_RAD - Math.PI);
		double i = Math.sin(-yaw * Mth.DEG_TO_RAD - Math.PI);
		double j = -Math.cos(-pitch * Mth.DEG_TO_RAD);
		double k = Math.sin(-pitch * Mth.DEG_TO_RAD);
		lerped(user, tickDelta);
		pos.sub(i * 0.3, 0, h * 0.3).add(0, user.getEyeHeight(pose(user)), 0);
		velocity.set(-i, Mth.clamp(-(k / j), -5, 5), -h);
		double l = velocity.length();
		velocity.mul(0.6 / l + 0.5);
		start(dummy(FISHING_BOBBER.entity()), FISHING_BOBBER);
	}

	/** The look the server uses: the faked one while {@link Rotations} turns the local player. */
	private static float[] look(Entity user, float tickDelta) {
		if (user == Minecraft.getInstance().player && Rotations.rotating()) return new float[] {Rotations.serverYaw(), Rotations.serverPitch()};
		return new float[] {user.getYRot(tickDelta), user.getXRot(tickDelta)};
	}

	/** NoSlow's air-strict sneak lowers the eyes even though the pose has not caught up yet. */
	private static Pose pose(Entity user) {
		return user == Minecraft.getInstance().player && NoSlow.airStrict() ? Pose.CROUCHING : user.getPose();
	}

	private void lerped(Entity user, float tickDelta) {
		pos.set(Mth.lerp(tickDelta, user.xOld, user.getX()), Mth.lerp(tickDelta, user.yOld, user.getY()), Mth.lerp(tickDelta, user.zOld, user.getZ()));
	}

	// ---- projectiles in flight ----

	/** Starts a path for a projectile already flying; false for ones stuck in a block or not simulated. */
	public boolean set(Entity flying) {
		if (flying instanceof AbstractArrow arrow && ((AbstractArrowAccessor) arrow).coffeeclient$isInGround()) return false;
		MotionData data = switch (flying) {
			case Arrow ignored -> ARROW;
			case SpectralArrow ignored -> ARROW;
			case ThrownTrident ignored -> TRIDENT;
			case ThrownEnderpearl ignored -> ENDER_PEARL;
			case Snowball ignored -> SNOWBALL;
			case ThrownEgg ignored -> EGG;
			case ThrownExperienceBottle ignored -> EXPERIENCE_BOTTLE;
			case ThrownSplashPotion ignored -> SPLASH_POTION;
			case ThrownLingeringPotion ignored -> LINGERING_POTION;
			case AbstractWindCharge ignored -> WIND_CHARGE;
			case AbstractHurtingProjectile ignored -> EXPLOSIVE;
			case LlamaSpit ignored -> LLAMA_SPIT;
			default -> null;
		};
		if (data == null) return false;
		Projectile projectile = (Projectile) flying;
		pos.set(projectile.getX(), projectile.getY(), projectile.getZ());
		Vec3 motion = projectile.getDeltaMovement();
		velocity.set(motion.x, motion.y, motion.z);
		start(projectile, data);
		if (flying.isNoGravity()) gravity = 0;
		return true;
	}

	private void start(Projectile source, MotionData data) {
		entity = source;
		if (source == null) return;
		gravity = data.gravity();
		airDrag = data.airDrag();
		waterDrag = data.waterDrag();
		dimensions = source.getDimensions(source.getPose());
		inWater = source.isInWater();
		tickCount = source.tickCount;
		pierceLevel = 0;
	}

	/** A throwaway projectile of {@code type} in the current level, made once and reused. */
	private Projectile dummy(EntityType<?> type) {
		ClientLevel level = Minecraft.getInstance().level;
		if (level != dummyLevel) {
			dummies.clear();
			dummyLevel = level;
		}
		if (type == null || level == null) return null;
		Projectile cached = dummies.get(type);
		if (cached == null && type.create(level, EntitySpawnReason.NATURAL) instanceof Projectile created) {
			cached = created;
			dummies.put(type, created);
		}
		return cached;
	}

	// ---- stepping ----

	/** Advances one game tick. */
	public Step tick() {
		ClientLevel level = Minecraft.getInstance().level;
		if (level == null || entity == null) return Step.MISS;
		tickCount++;
		prev = new Vec3(pos.x, pos.y, pos.z);

		// Each family applies gravity, drag and movement in its own order (matches vanilla's tick).
		if (entity instanceof ThrowableProjectile || entity instanceof AbstractHurtingProjectile) {
			velocity.sub(0, gravity, 0);
			velocity.mul(inWater ? waterDrag : airDrag);
			pos.add(velocity);
			updateInWater(level);
		} else if (entity instanceof AbstractArrow || entity instanceof LlamaSpit) {
			pos.add(velocity);
			velocity.mul(inWater ? waterDrag : airDrag);
			velocity.sub(0, gravity, 0);
			updateInWater(level);
		} else {
			// Fishing bobbers and firework rockets.
			updateInWater(level);
			velocity.sub(0, gravity, 0);
			pos.add(velocity);
			velocity.mul(inWater ? waterDrag : airDrag);
		}

		if (pos.y < level.getMinY()) return Step.MISS;
		if (!level.getChunkSource().hasChunk(SectionPos.posToSectionCoord(pos.x), SectionPos.posToSectionCoord(pos.z))) return Step.MISS;
		Vec3 now = new Vec3(pos.x, pos.y, pos.z);
		if (now.equals(prev)) return Step.MISS;
		return collide(level, now);
	}

	private void updateInWater(ClientLevel level) {
		AABB box = dimensions.makeBoundingBox(pos.x, pos.y, pos.z).deflate(0.001);
		for (int x = Mth.floor(box.minX); x < Mth.ceil(box.maxX); x++) {
			for (int y = Mth.floor(box.minY); y < Mth.ceil(box.maxY); y++) {
				for (int z = Mth.floor(box.minZ); z < Mth.ceil(box.maxZ); z++) {
					blockPos.set(x, y, z);
					FluidState fluid = level.getFluidState(blockPos);
					if (fluid.is(FluidTags.WATER) && y + fluid.getHeight(level, blockPos) >= box.minY) {
						inWater = true;
						return;
					}
				}
			}
		}
		inWater = false;
	}

	private Step collide(ClientLevel level, Vec3 now) {
		Minecraft mc = Minecraft.getInstance();
		BlockHitResult block = level.clip(new ClipContext(prev, now, ClipContext.Block.COLLIDER,
			waterDrag == 0 ? ClipContext.Fluid.ANY : ClipContext.Fluid.NONE, entity));
		Vec3 end = block.getType() != HitResult.Type.MISS ? block.getLocation() : now;
		AABB area = dimensions.makeBoundingBox(prev).expandTowards(velocity.x, velocity.y, velocity.z).inflate(1.0);
		float margin = Math.clamp((tickCount - 2) / 20.0f, 0.0f, 0.3f);

		if (entity instanceof AbstractArrow) {
			Collection<EntityHitResult> hits = ProjectileUtil.getManyEntityHitResult(level, entity, prev, end, area,
				e -> !e.isSpectator() && e.isAlive() && e.isPickable(), margin, ClipContext.Block.COLLIDER, false);
			// The first tick starts inside the shooter.
			hits.removeIf(hit -> tickCount <= 1 && hit.getEntity() == mc.player);
			if (hits.isEmpty()) return new Step(hitOrDeflect(block), List.of(block));
			List<HitResult> taken = new ArrayList<>();
			boolean stop = false;
			for (EntityHitResult hit : hits) {
				if (!hitOrDeflect(hit)) break;
				taken.add(hit);
				if (pierceLevel <= 0) {
					stop = true;
					break;
				}
				pierceLevel--;
			}
			return new Step(stop, taken);
		}

		EntityHitResult hit = ProjectileUtil.getEntityHitResult(level, entity, prev, end, area,
			e -> !e.isSpectator() && e.isAlive() && e.isPickable(), margin);
		if (hit == null || tickCount <= 1 && hit.getEntity() == mc.player) return new Step(hitOrDeflect(block), List.of(block));
		if (hitOrDeflect(hit)) return new Step(true, List.of(hit));
		return new Step(false, List.of());
	}

	/** Projectile#hitTargetOrDeflectSelf: true when the projectile stops here. */
	private boolean hitOrDeflect(HitResult result) {
		if (result instanceof EntityHitResult hit) {
			Entity target = hit.getEntity();
			Vec3 at = hit.getLocation();
			pos.set(at.x, at.y, at.z);
			if (target instanceof Breeze && !(entity instanceof AbstractWindCharge) || target.deflection(entity) == ProjectileDeflection.REVERSE) {
				velocity.mul(-0.5);
				return false;
			}
			// Shields bounce arrows (roughly; the full damage path is not traced).
			if (target instanceof LivingEntity living && living.isBlocking() && entity instanceof AbstractArrow) {
				velocity.mul(-0.1);
				return velocity.lengthSquared() < 1.0E-7;
			}
			return true;
		}
		if (result instanceof BlockHitResult block) {
			Vec3 at = block.getLocation();
			pos.set(at.x, at.y, at.z);
			return block.getType() != HitResult.Type.MISS;
		}
		return false;
	}
}
