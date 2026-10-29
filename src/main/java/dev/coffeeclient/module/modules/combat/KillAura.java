package dev.coffeeclient.module.modules.combat;

import dev.coffeeclient.core.Events;
import dev.coffeeclient.hud.HudStats;
import dev.coffeeclient.module.Category;
import dev.coffeeclient.module.Module;
import dev.coffeeclient.module.ModuleManager;
import dev.coffeeclient.setting.BooleanSetting;
import dev.coffeeclient.setting.EntityTypeListSetting;
import dev.coffeeclient.setting.EnumSetting;
import dev.coffeeclient.setting.IdListSetting;
import dev.coffeeclient.setting.Labeled;
import dev.coffeeclient.setting.NumberSetting;
import dev.coffeeclient.setting.Setting;
import dev.coffeeclient.util.CombatTick;
import dev.coffeeclient.util.ErrorGuard;
import dev.coffeeclient.util.Inv;
import dev.coffeeclient.util.ItemIds;
import dev.coffeeclient.util.Rotations;
import dev.coffeeclient.util.Targets;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.core.component.DataComponents;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.animal.frog.Frog;
import net.minecraft.world.entity.animal.parrot.Parrot;
import net.minecraft.world.entity.animal.wolf.Wolf;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.entity.monster.Zoglin;
import net.minecraft.world.entity.monster.hoglin.Hoglin;
import net.minecraft.world.entity.monster.piglin.Piglin;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.entity.monster.zombie.ZombifiedPiglin;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.MaceItem;
import net.minecraft.world.item.TridentItem;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.Predicate;

/**
 * Attacks chosen entities around you. Everything runs at the start of the tick, where vanilla attacks: the
 * hit goes out just before the movement packet carrying the turned look. Turns can be smoothed, skipped while the
 * look already rests on the target (lazy), and the hit can go to whatever that look really hits
 * (raycast). Hits can wait for a critical, and a shield in the offhand is raised between hits.
 */
public final class KillAura extends Module {
	public enum AttackItems { WEAPONS, ALL }

	public enum RotationMode implements Labeled {
		ALWAYS("Always"), ON_HIT("On hit"), NONE("None");

		private final String label;

		RotationMode(String label) {
			this.label = label;
		}

		@Override
		public String label() {
			return label;
		}
	}

	public enum ShieldMode { IGNORE, BREAK, NONE }

	public enum Crits implements Labeled {
		OFF("Off"), SMART("Smart"), ALWAYS("Always");

		private final String label;

		Crits(String label) {
			this.label = label;
		}

		@Override
		public String label() {
			return label;
		}
	}

	public enum AgeFilter {
		ADULT, BABY, BOTH;

		boolean test(LivingEntity entity) {
			return this == BOTH || (this == BABY) == entity.isBaby();
		}
	}

	/** One id per weapon kind, as in (the diamond sword stands for every sword). */
	private static final List<String> WEAPON_KINDS = List.of("minecraft:diamond_sword", "minecraft:diamond_axe", "minecraft:diamond_pickaxe",
		"minecraft:diamond_shovel", "minecraft:diamond_hoe", "minecraft:mace", "minecraft:diamond_spear", "minecraft:trident");

	// General
	public final EnumSetting<AttackItems> attackWhenHolding = group(new EnumSetting<>("Attack when holding", "Only attack while holding one of the weapons below", AttackItems.WEAPONS), "General");
	public final IdListSetting weapons = group(new IdListSetting("Weapons", "Weapon kinds to attack with (the diamond one stands for every tier)",
		List.of("minecraft:diamond_sword", "minecraft:diamond_axe", "minecraft:trident"), () -> WEAPON_KINDS, false)
		.labels(KillAura::kindName).icons(ItemIds::stack), "General");
	public final EnumSetting<RotationMode> rotation = group(new EnumSetting<>("Rotate", "When to turn (server side) towards the target", RotationMode.ALWAYS), "General");
	public final EnumSetting<Rotations.AimPoint> aimPoint = group(new EnumSetting<>("Aim point", "Where on the target to aim: Auto takes the first visible of eyes, torso and feet; Least rotation the visible point nearest your current aim", Rotations.AimPoint.Torso), "General");
	public final BooleanSetting smooth = group(new BooleanSetting("Smooth", "Turn towards the target over several ticks instead of at once; hits wait until the look is on it", false), "General");
	public final EnumSetting<Rotations.Smoother> smoother = group(new EnumSetting<>("Smoother", "How each tick's turn is sized", Rotations.Smoother.Sigmoid), "General");
	public final NumberSetting turnSpeed = group(new NumberSetting("Turn speed", "Most degrees turned per tick", 60, 5, 180, 5), "General");
	public final BooleanSetting lazy = group(new BooleanSetting("Lazy rotation", "Don't turn while the look already rests on the target", false), "General");
	public final BooleanSetting raycast = group(new BooleanSetting("Raycast", "Hit whatever the sent look really hits (if it is a valid target), never a target the look misses", false), "General");
	public final BooleanSetting failSwing = group(new BooleanSetting("Fail swing", "Swing at the air when a hit is due but the look misses or the target is just out of range", false), "General");
	public final BooleanSetting autoSwitch = group(new BooleanSetting("Auto switch", "Switch to an allowed weapon when attacking, pulling one from the inventory when the hotbar has none", false), "General");
	public final BooleanSetting swapBack = group(new BooleanSetting("Swap back", "Go back to the previous slot when done", false), "General");
	public final EnumSetting<ShieldMode> shieldMode = group(new EnumSetting<>("Shield mode", "Target blocking with a shield: Ignore skips them, Break swaps to an axe (needs Auto switch), None attacks anyway", ShieldMode.NONE), "General");
	public final BooleanSetting onlyOnClick = group(new BooleanSetting("Only on click", "Only attack while holding the attack button", false), "General");
	public final BooleanSetting autoBlock = group(new BooleanSetting("Auto block", "Raise an offhand shield between hits and lower it for each hit", false), "General");
	public final BooleanSetting onlyOnLook = group(new BooleanSetting("Only on look", "Only attack the entity under your crosshair", false), "General");

	// Targeting
	public final EntityTypeListSetting entities = group(new EntityTypeListSetting("Entities", "Entities to attack", List.of("minecraft:player")), "Targeting");
	public final EnumSetting<Targets.SortPriority> priority = group(new EnumSetting<>("Priority", "Which targets in range come first", Targets.SortPriority.ClosestAngle), "Targeting");
	public final EnumSetting<Targets.SortPriority> thenPriority = group(new EnumSetting<>("Then", "Breaks ties of Priority", Targets.SortPriority.LowestHealth), "Targeting");
	public final NumberSetting fov = group(new NumberSetting("FOV", "Only attack within this many degrees of where you look (360 = all around)", 360, 10, 360, 5), "Targeting");
	public final NumberSetting maxHurtTime = group(new NumberSetting("Max hurt time", "Skip targets still flashing red from a hit for more than this many ticks (10 = never skip)", 10, 0, 10, 1), "Targeting");
	public final BooleanSetting ignoreBots = group(new BooleanSetting("Ignore bots", "Leave players that are not in the tab list alone (server NPCs and anti-cheat bots)", false), "Targeting");
	public final BooleanSetting ignoreTeammates = group(new BooleanSetting("Ignore teammates", "Leave players on your scoreboard team, or wearing your helmet colour, alone", false), "Targeting");
	public final BooleanSetting ignoreSleeping = group(new BooleanSetting("Ignore sleeping", "Leave entities asleep in a bed alone", false), "Targeting");
	public final BooleanSetting ignoreInvisible = group(new BooleanSetting("Ignore invisible", "Leave invisible entities alone", false), "Targeting");
	public final BooleanSetting ignoreFlying = group(new BooleanSetting("Ignore flying", "Leave entities flying with an elytra alone", false), "Targeting");
	public final BooleanSetting sticky = group(new BooleanSetting("Sticky target", "Keep hitting the same target while it stays valid, even if another comes first", false), "Targeting");
	public final NumberSetting maxTargets = group(new NumberSetting("Max targets", "How many entities to hit at once", 1, 1, 5, 1), "Targeting");
	public final NumberSetting range = group(new NumberSetting("Range", "Farthest an entity can be", 4.5, 0, 6, 0.1), "Targeting");
	public final BooleanSetting useReach = group(new BooleanSetting("Use reach", "Range is your entity reach (with Reach's extra) instead of the number above", false), "Targeting");
	public final NumberSetting scanBonus = group(new NumberSetting("Scan bonus", "Pick (and turn to) targets up to this much farther than Range, a random amount each tick; hits still need Range", 0, 0, 3, 0.1), "Targeting");
	public final NumberSetting wallsRange = group(new NumberSetting("Walls range", "Farthest an entity can be through walls", 3.5, 0, 6, 0.1), "Targeting");
	public final EnumSetting<AgeFilter> passiveAge = group(new EnumSetting<>("Passive mob age", "Ages of animals and villagers to attack", AgeFilter.ADULT), "Targeting");
	public final EnumSetting<AgeFilter> hostileAge = group(new EnumSetting<>("Hostile mob age", "Ages of zombies, piglins, hoglins and zoglins to attack", AgeFilter.BOTH), "Targeting");
	public final BooleanSetting ignoreNamed = group(new BooleanSetting("Ignore named", "Leave mobs with a name tag alone", false), "Targeting");
	public final BooleanSetting ignorePassive = group(new BooleanSetting("Ignore passive", "Leave neutral mobs (endermen, piglins, wolves) alone unless they are angry", true), "Targeting");
	public final BooleanSetting ignoreTamed = group(new BooleanSetting("Ignore tamed", "Leave your own pets alone", false), "Targeting");

	// Timing
	public final BooleanSetting pauseOnLag = group(new BooleanSetting("Pause on lag", "Stop while the server is not responding", true), "Timing");
	public final BooleanSetting pauseOnUse = group(new BooleanSetting("Pause on use", "Stop while mining or using an item", false), "Timing");
	public final BooleanSetting pauseInContainer = group(new BooleanSetting("Pause in container", "Stop while an inventory or container screen is open", true), "Timing");
	public final EnumSetting<Crits> crits = group(new EnumSetting<>("Crits", "Smart: in the air, wait until you fall so the hit crits. Always: only hit when it crits. A hit Criticals will make a crit never waits", Crits.OFF), "Timing");
	public final BooleanSetting pauseOnCrystalAura = group(new BooleanSetting("Pause on CrystalAura", "Stop while CrystalAura is placing or breaking", true), "Timing");
	public final BooleanSetting tpsSync = group(new BooleanSetting("TPS sync", "Stretch the delay when the server runs slow", true), "Timing");
	public final BooleanSetting customDelay = group(new BooleanSetting("Custom delay", "Fixed delay instead of the vanilla attack cooldown", false), "Timing");
	public final NumberSetting hitDelay = group(new NumberSetting("Hit delay", "Ticks between hits", 11, 0, 60, 1), "Timing");
	public final NumberSetting switchDelay = group(new NumberSetting("Switch delay", "Ticks to wait after changing hotbar slot", 0, 0, 10, 1), "Timing");


	private final List<Entity> targets = new ArrayList<>();
	/** The main target last tick, kept first by "Sticky target". */
	private Entity locked;
	private volatile int switchTimer;
	private int hitTimer;
	private boolean attacking, swapped, blocking;
	private static volatile boolean critSprintHold;
	private double scanRange;
	private final Random random = new Random();

	public KillAura() {
		super("KillAura", List.of("Killaura", "Kill Aura", "Aura"), Category.COMBAT, "Attacks chosen entities around you.");
		weapons.visibleWhen(() -> attackWhenHolding.get() == AttackItems.WEAPONS);
		swapBack.visibleWhen(autoSwitch::get);
		maxTargets.visibleWhen(() -> !onlyOnLook.get());
		hitDelay.visibleWhen(customDelay::get);
		range.visibleWhen(() -> !useReach.get());
		aimPoint.visibleWhen(() -> rotation.get() != RotationMode.NONE);
		for (Setting<?> s : List.of(smooth, lazy)) s.visibleWhen(() -> rotation.get() != RotationMode.NONE);
		for (Setting<?> s : List.of(smoother, turnSpeed)) s.visibleWhen(() -> rotation.get() != RotationMode.NONE && smooth.get());

		// Network thread: a slot change resets the switch delay.
		listen(Events.PacketSend.class, e -> {
			if (!e.isCancelled() && e.packet instanceof ServerboundSetCarriedItemPacket) switchTimer = switchDelay.intValue();
		});
		ClientTickEvents.START_CLIENT_TICK.register(mc -> {
			if (isEnabled()) ErrorGuard.run("tick:KillAura", this::tickStart);
		});
	}

	@Override
	public boolean hasConfigScreen() {
		return true;
	}

	@Override
	public IdListSetting embeddedList() {
		return entities;
	}

	@Override
	protected void onEnable() {
		swapped = false;
		hitTimer = 0;
	}

	@Override
	protected void onDisable() {
		targets.clear();
		locked = null;
		stopAttacking();
	}

	/** The main target this tick, or null (Criticals' "only KillAura" check). */
	public Entity target() {
		return targets.isEmpty() ? null : targets.getFirst();
	}

	/** Crits: true while sprint is held off in the air (LocalPlayerMixin and Sprint read it). */
	public static boolean critSprintHold() {
		return critSprintHold;
	}

	/**
	 * Start of the client tick, where vanilla attacks: before the movement packet. Grim's Post check
	 * flags hits, slot changes and item uses sent after it, and its attack slowdown is simulated on the
	 * movement that follows the hit.
	 */
	private void tickStart() {
		critSprintHold = false;
		Minecraft mc = mc();
		LocalPlayer player = mc.player;
		if (player == null || mc.level == null || mc.gameMode == null || !player.isAlive() || Targets.gameMode(player) == GameType.SPECTATOR
			|| pauseOnUse.get() && (mc.gameMode.isDestroying() || player.isUsingItem())
			|| onlyOnClick.get() && !mc.options.keyAttack.isDown()
			|| pauseOnLag.get() && CombatTick.lagging()
			|| pauseInContainer.get() && mc.gui.screen() instanceof AbstractContainerScreen<?>
			|| pauseOnCrystalAura.get() && CombatTick.busy(CombatTick.Kind.CRYSTAL, this)) {
			stopAttacking();
			return;
		}

		scanRange = attackRange() + random.nextDouble() * scanBonus.get();
		targets.clear();
		if (onlyOnLook.get()) {
			Entity looked = mc.crosshairPickEntity;
			if (looked != null && entityCheck(looked)) targets.add(looked);
		} else {
			Targets.getList(targets, this::entityCheck, priority.get(), thenPriority.get(), maxTargets.intValue());
			if (sticky.get() && locked != null && locked.isAlive() && entityCheck(locked)) {
				targets.remove(locked);
				targets.addFirst(locked);
				if (targets.size() > maxTargets.intValue()) targets.removeLast();
			}
		}
		locked = targets.isEmpty() ? null : targets.getFirst();
		if (targets.isEmpty()) {
			stopAttacking();
			return;
		}

		if (autoSwitch.get()) {
			int slot = player.getInventory().getSelectedSlot();
			if (attackWhenHolding.get() == AttackItems.WEAPONS) {
				Inv.FindItemResult weapon = Inv.findInHotbar(this::acceptableWeapon);
				if (weapon.found()) slot = weapon.slot();
				else if (!acceptableWeapon(player.getMainHandItem())) Inv.select(this::acceptableWeapon);
			}
			if (shouldShieldBreak()) {
				Inv.FindItemResult axe = Inv.findInHotbar(stack -> stack.is(ItemTags.AXES));
				if (axe.found()) slot = axe.slot();
			}
			Inv.swap(slot, swapBack.get());
			swapped = true;
		}
		if (!acceptableWeapon(player.getMainHandItem())) {
			stopAttacking();
			return;
		}
		attacking = true;
		CombatTick.claim(CombatTick.Kind.MELEE, this, 0, 1);

		boolean ready = delayCheck() && critTiming();
		block(ready);
		Entity primary = targets.getFirst();
		Vec3 point = Rotations.aimPoint(primary, aimPoint.get(), scanRange, wallsRange.get());
		Rotations.Rotation look = point != null ? Rotations.needed(point) : Rotations.needed(primary);
		if (lazy.get() && Rotations.isFacingBox(primary.getBoundingBox(), scanRange)) look = new Rotations.Rotation(Rotations.serverYaw(), Rotations.serverPitch());
		else if (smooth.get() && rotation.get() != RotationMode.NONE) look = Rotations.smoothTowards(look, smoother.get(), turnSpeed.floatValue());
		// The turn is asked for first, so this tick's movement, sprint-jump and packet all use it.
		boolean turned = switch (rotation.get()) {
			case NONE -> true;
			case ALWAYS -> Rotations.rotate(look, Rotations.NORMAL, null);
			case ON_HIT -> ready && Rotations.rotate(look, Rotations.NORMAL, null);
		};

		Criticals criticals = ModuleManager.ifOn(Criticals.class);
		if (wantsCrits(criticals) && !player.onGround() && !player.isInWater() && !player.onClimbable() && !player.isPassenger()) critSprintHold = true;
		if (criticals != null && criticals.jumpsForCrits() && criticals.canJump(player) && !mc.options.keyJump.isDown()) dev.coffeeclient.util.Player.requestJump();

		if (ready && turned) attackAll(rotation.get() == RotationMode.NONE ? null : look);
	}

	/** Hits the targets; {@code look} is the look this tick's packet carries (null: the player's own). */
	private void attackAll(Rotations.Rotation look) {
		Minecraft mc = mc();
		if (mc.player == null || mc.gameMode == null) return;
		List<Entity> hits = targets;
		if (raycast.get() || smooth.get() && rotation.get() != RotationMode.NONE) {
			// Hit only what the sent look reaches.
			Entity looked = lookedAt(look == null ? mc.player.getLookAngle() : look.lookVec());
			hits = looked != null && entityCheck(looked) ? List.of(looked) : List.of();
			if (!raycast.get() && !targets.contains(looked)) hits = List.of();
		}
		boolean hitAny = false;
		for (Entity target : hits) {
			if (!target.isAlive() || !Targets.withinRange(target, attackRange())) continue;
			mc.gameMode.attack(mc.player, target);
			mc.player.swing(InteractionHand.MAIN_HAND);
			hitAny = true;
		}
		if (!hitAny && failSwing.get()) {
			mc.player.swing(InteractionHand.MAIN_HAND);
			mc.player.resetAttackStrengthTicker();
			hitAny = true;
		}
		if (hitAny) hitTimer = 0;
	}

	/** The entity a look along {@code dir} hits within Range, stopping at blocks; null for none. */
	private Entity lookedAt(Vec3 dir) {
		LocalPlayer player = mc().player;
		Vec3 eyes = player.getEyePosition();
		Vec3 end = eyes.add(dir.scale(attackRange()));
		HitResult block = mc().level.clip(new ClipContext(eyes, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
		// Walls range lets the ray through blocks that close.
		double reach = block.getType() == HitResult.Type.MISS ? attackRange() : Math.max(eyes.distanceTo(block.getLocation()), wallsRange.get());
		end = eyes.add(dir.scale(reach));
		AABB area = player.getBoundingBox().expandTowards(end.subtract(eyes)).inflate(1);
		EntityHitResult hit = ProjectileUtil.getEntityHitResult(player, eyes, end, area, e -> !e.isSpectator() && e.isPickable(), reach * reach);
		return hit == null ? null : hit.getEntity();
	}

	private double attackRange() {
		return useReach.get() && mc().player != null ? mc().player.entityInteractionRange() : range.get();
	}

	/** Crits are wanted: the Crits setting, or Criticals' Jump mode (which hops for them). */
	private boolean wantsCrits(Criticals criticals) {
		return crits.get() != Crits.OFF || criticals != null && criticals.jumpsForCrits();
	}

	/** Crits: false while a hit should wait for a critical. */
	private boolean critTiming() {
		Criticals criticals = ModuleManager.ifOn(Criticals.class);
		if (!wantsCrits(criticals)) return true;
		if (criticals != null && criticals.willCrit()) return true;
		LocalPlayer player = mc().player;
		boolean canCrit = Criticals.canCrit(player);
		if (canCrit || crits.get() == Crits.ALWAYS) return canCrit;
		// Criticals is about to hop: wait for the fall.
		if (criticals != null && criticals.jumpsForCrits() && criticals.canJump(player)) return false;
		// Smart: on the ground, in water or climbing no crit is coming; in the air one is, once you fall.
		return player.onGround() || player.isInWater() || player.onClimbable() || player.isPassenger();
	}

	/** Auto block: the offhand shield is up between hits and down for a hit. */
	private void block(boolean ready) {
		LocalPlayer player = mc().player;
		if (!autoBlock.get() || !player.getOffhandItem().has(DataComponents.BLOCKS_ATTACKS)) return;
		if (ready) unblock();
		else if (!player.isUsingItem()) {
			mc().gameMode.useItem(player, InteractionHand.OFF_HAND);
			blocking = true;
		}
	}

	private void unblock() {
		if (!blocking) return;
		blocking = false;
		LocalPlayer player = mc().player;
		if (player != null && player.isUsingItem() && player.getUsedItemHand() == InteractionHand.OFF_HAND) mc().gameMode.releaseUsingItem(player);
	}

	private void stopAttacking() {
		critSprintHold = false;
		unblock();
		if (!attacking) return;
		attacking = false;
		CombatTick.release(this);
		if (swapped) {
			if (swapBack.get()) Inv.swapBack();
			swapped = false;
		}
	}

	private boolean shouldShieldBreak() {
		if (shieldMode.get() != ShieldMode.BREAK) return false;
		for (Entity target : targets) if (target instanceof Player p && p.isBlocking()) return true;
		return false;
	}

	private boolean entityCheck(Entity entity) {
		Minecraft mc = mc();
		LocalPlayer player = mc.player;
		if (entity == player || entity == mc.getCameraEntity() || !entity.isAlive()) return false;
		if (entity instanceof LivingEntity living && living.isDeadOrDying()) return false;
		if (!Targets.withinRange(entity, Math.max(scanRange, attackRange()))) return false;
		if (!entities.has(entity)) return false;
		if (!Targets.inFov(entity, fov.get())) return false;
		if (entity instanceof LivingEntity living && living.hurtTime > maxHurtTime.intValue()) return false;
		if (ignoreTeammates.get() && Targets.isTeammate(entity)) return false;
		if (ignoreBots.get() && entity instanceof Player p && Targets.isBot(p)) return false;
		if (ignoreNamed.get() && entity.hasCustomName()) return false;
		if (Targets.excluded(entity, ignoreSleeping.get(), ignoreInvisible.get(), ignoreFlying.get())) return false;
		if (!canSee(entity) && !Targets.withinRange(entity, wallsRange.get())) return false;
		if (ignoreTamed.get() && entity instanceof OwnableEntity pet && pet.getOwner() == player) return false;
		if (ignorePassive.get()) {
			if (entity instanceof EnderMan enderman && !enderman.isCreepy()) return false;
			if ((entity instanceof Piglin || entity instanceof ZombifiedPiglin || entity instanceof Wolf) && !((Mob) entity).isAggressive()) return false;
		}
		if (entity instanceof Player target) {
			if (!Targets.isAttackablePlayer(target)) return false;
			if (shieldMode.get() == ShieldMode.IGNORE && target.isBlocking()) return false;
		}
		if (entity instanceof LivingEntity living) {
			if (entity instanceof Zombie || entity instanceof Piglin || entity instanceof Hoglin || entity instanceof Zoglin) return hostileAge.get().test(living);
			if (entity instanceof AgeableMob && !(entity instanceof Frog || entity instanceof Parrot)) return passiveAge.get().test(living);
		}
		return true;
	}

	/** Line of sight from the eyes to the target's eyes or feet. */
	private boolean canSee(Entity entity) {
		Minecraft mc = mc();
		Vec3 eyes = mc.player.getEyePosition();
		for (Vec3 point : new Vec3[] {entity.getEyePosition(), entity.position().add(0, 0.1, 0)}) {
			HitResult hit = mc.level.clip(new ClipContext(eyes, point, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mc.player));
			if (hit.getType() == HitResult.Type.MISS) return true;
		}
		return false;
	}

	private boolean delayCheck() {
		if (switchTimer > 0) {
			switchTimer--;
			return false;
		}
		if (dev.coffeeclient.module.modules.movement.Velocity.wantsHit()) return true;
		float delay = customDelay.get() ? hitDelay.floatValue() : 0.5f;
		double tps = HudStats.tps();
		if (tpsSync.get() && !Double.isNaN(tps) && tps > 0) delay /= (float) (tps / 20);
		if (customDelay.get()) {
			if (hitTimer < delay) {
				hitTimer++;
				return false;
			}
			return true;
		}
		return mc().player.getAttackStrengthScale(delay) >= 1;
	}

	private boolean acceptableWeapon(ItemStack stack) {
		if (shouldShieldBreak()) return stack.is(ItemTags.AXES);
		if (attackWhenHolding.get() == AttackItems.ALL) return true;
		return matches(stack, "minecraft:diamond_sword", s -> s.is(ItemTags.SWORDS))
			|| matches(stack, "minecraft:diamond_axe", s -> s.is(ItemTags.AXES))
			|| matches(stack, "minecraft:diamond_pickaxe", s -> s.is(ItemTags.PICKAXES))
			|| matches(stack, "minecraft:diamond_shovel", s -> s.is(ItemTags.SHOVELS))
			|| matches(stack, "minecraft:diamond_hoe", s -> s.is(ItemTags.HOES))
			|| matches(stack, "minecraft:mace", s -> s.getItem() instanceof MaceItem)
			|| matches(stack, "minecraft:diamond_spear", s -> s.is(ItemTags.SPEARS))
			|| matches(stack, "minecraft:trident", s -> s.getItem() instanceof TridentItem);
	}

	private boolean matches(ItemStack stack, String kind, Predicate<ItemStack> test) {
		return weapons.contains(kind) && test.test(stack);
	}

	private static String kindName(String id) {
		return switch (id) {
			case "minecraft:diamond_sword" -> "Swords";
			case "minecraft:diamond_axe" -> "Axes";
			case "minecraft:diamond_pickaxe" -> "Pickaxes";
			case "minecraft:diamond_shovel" -> "Shovels";
			case "minecraft:diamond_hoe" -> "Hoes";
			case "minecraft:mace" -> "Maces";
			case "minecraft:diamond_spear" -> "Spears";
			case "minecraft:trident" -> "Tridents";
			default -> ItemIds.name(id);
		};
	}

	@Override
	public String infoString() {
		Entity target = target();
		return target == null ? null : target.getName().getString();
	}
}
