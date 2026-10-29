package dev.coffeeclient.module.modules.world;

import dev.coffeeclient.module.Category;
import dev.coffeeclient.module.Module;
import dev.coffeeclient.setting.BooleanSetting;
import dev.coffeeclient.setting.NumberSetting;
import dev.coffeeclient.setting.Setting;
import dev.coffeeclient.util.BlockActions;
import dev.coffeeclient.util.BlockSearch;
import dev.coffeeclient.util.CombatTick;
import dev.coffeeclient.util.EnchantmentIds;
import dev.coffeeclient.util.Inv;
import dev.coffeeclient.util.Rotations;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.equine.AbstractHorse;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.BonemealableBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CocoaBlock;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.NetherWartBlock;
import net.minecraft.world.level.block.StemBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Farms what is in reach, one action at a time: harvests ripe plants, replants what it harvested,
 * and can bone meal crops, till soil and feed animals. It never walks; stand in the middle of the farm.
 * Plants come from a shared {@link BlockSearch}, so the farm is found as chunks load and then
 * followed through block updates instead of searched every action.
 */
public final class AutoFarm extends Module {
	/** Ticks a harvested spot stays on the replant list. */
	private static final int REPLANT_TICKS = 100;
	/** Ticks before the same animal is fed again, so a slow server answer is not clicked twice. */
	private static final int FEED_RETRY_TICKS = 10;

	public final NumberSetting range = group(new NumberSetting("Range", "Reach for blocks and animals", 4.5, 1, 6, 0.5), "General");
	public final NumberSetting delay = group(new NumberSetting("Delay", "Ticks between actions", 2, 1, 10, 1), "General");
	public final NumberSetting jitter = group(new NumberSetting("Delay jitter", "Up to this many extra ticks, picked at random each action", 0, 0, 10, 1), "General");
	public final NumberSetting wallRange = group(new NumberSetting("Wall range", "Reach for blocks you can't see", 3, 0, 6, 0.5), "General");
	public final BooleanSetting stopWhenFull = group(new BooleanSetting("Stop when full", "Turn off once your inventory has no free slot", false), "General");
	public final BooleanSetting rotate = group(new BooleanSetting("Rotate", "Face a block before acting on it", false), "General");
	public final BooleanSetting fortune = group(new BooleanSetting("Fortune tool", "Hold your best Fortune item from the hotbar while harvesting crops", false), "Harvest");

	public final BooleanSetting harvest = group(new BooleanSetting("Harvest", "Break ripe plants", true), "Harvest");
	public final BooleanSetting crops = group(new BooleanSetting("Crops", "Wheat, carrots, potatoes and beetroot", true).visibleWhen(() -> harvest.get()), "Harvest");
	public final BooleanSetting netherWart = group(new BooleanSetting("Nether wart", "Nether wart", true).visibleWhen(() -> harvest.get()), "Harvest");
	public final BooleanSetting cocoa = group(new BooleanSetting("Cocoa", "Ripe cocoa pods", true).visibleWhen(() -> harvest.get()), "Harvest");
	public final BooleanSetting gourds = group(new BooleanSetting("Melons and pumpkins", "Only fruit that touches a stem", true).visibleWhen(() -> harvest.get()), "Harvest");
	public final BooleanSetting stalks = group(new BooleanSetting("Cane, cactus and bamboo", "Everything above the bottom block", true).visibleWhen(() -> harvest.get()), "Harvest");
	public final BooleanSetting replant = group(new BooleanSetting("Replant", "Plant the seed again where a crop was harvested, from your hotbar", true), "Replant");

	public final BooleanSetting boneMeal = group(new BooleanSetting("Bone meal", "Use bone meal from your hotbar on growing crops and stems", false), "Bone meal");
	public final NumberSetting boneMealDelay = group(new NumberSetting("Bone meal delay", "Ticks between bone meal uses", 4, 1, 40, 1).visibleWhen(() -> boneMeal.get()), "Bone meal");
	public final BooleanSetting till = group(new BooleanSetting("Till", "Use a hoe from your hotbar on grass, dirt and paths that have air above", false), "Till");
	public final BooleanSetting feed = group(new BooleanSetting("Feed", "Feed animals with food from your hotbar", false), "Feed");
	public final BooleanSetting feedBabies = group(new BooleanSetting("Babies", "Also feed babies, which makes them grow faster", true).visibleWhen(() -> feed.get()), "Feed");

	private record Replant(Item seed, long until) {
	}

	private final Map<BlockPos, Replant> replants = new HashMap<>();
	private final Map<Integer, Long> fedAt = new HashMap<>();
	private BlockPos digging;
	private int wait, mealWait;
	private BlockSearch.Handle plants;

	public AutoFarm() {
		super("AutoFarm", java.util.List.of("FarmTools", "BonemealAura", "Tillaura", "FeedAura"), Category.WORLD,
			"Harvests, replants, bone meals, tills and feeds around you.");
	}

	@Override
	public boolean hasConfigScreen() {
		return true;
	}

	@Override
	protected void onEnable() {
		plants = BlockSearch.acquire(AutoFarm.class, AutoFarm::plant);
	}

	/** Blocks AutoFarm harvests or bone meals, for the search. */
	private static boolean plant(BlockState s) {
		Block b = s.getBlock();
		return b instanceof CropBlock || b instanceof StemBlock || b instanceof NetherWartBlock || b instanceof CocoaBlock
			|| b == Blocks.MELON || b == Blocks.PUMPKIN || b == Blocks.SUGAR_CANE || b == Blocks.CACTUS || b == Blocks.BAMBOO;
	}

	@Override
	protected void onDisable() {
		if (plants != null) plants.release();
		plants = null;
		replants.clear();
		fedAt.clear();
		digging = null;
	}

	@Override
	public void onTick() {
		var mc = mc();
		LocalPlayer p = mc.player;
		ClientLevel level = mc.level;
		if (p == null || level == null || mc.gameMode == null || p.isHandsBusy() || p.isUsingItem() || CombatTick.busy(CombatTick.Kind.USE, this) || mc.gui.screen() != null) return;
		long now = level.getGameTime();
		replants.values().removeIf(r -> r.until() <= now);
		fedAt.values().removeIf(t -> t <= now);

		// A plant that takes more than one tick to break (cactus, bamboo) needs the dig continued every tick.
		if (digging != null) {
			if (ripe(level, digging) && BlockActions.breakBlock(digging, rotate.get(), Rotations.NORMAL, true)) return;
			digging = null;
		}
		if (stopWhenFull.get() && p.getInventory().getFreeSlot() < 0) {
			info("inventory full, turning off");
			setEnabled(false);
			return;
		}
		if (mealWait > 0) mealWait--;
		if (--wait > 0) return;
		wait = delay.intValue() + (jitter.intValue() > 0 ? ThreadLocalRandom.current().nextInt(jitter.intValue() + 1) : 0);

		if (harvest.get() && harvest(level, p, now)) return;
		if (replant.get() && replant(level, p)) return;
		if (boneMeal.get() && boneMeal(level, p)) return;
		if (till.get() && till(level, p)) return;
		if (feed.get()) feed(level, p, now);
	}

	// ---- actions ----

	private boolean harvest(ClientLevel level, LocalPlayer p, long now) {
		BlockPos best = nearestPlant(level, p, pos -> ripe(level, pos));
		if (best == null) return false;
		if (fortune.get() && level.getBlockState(best).getBlock() instanceof CropBlock) holdFortune(p);
		if (!BlockActions.breakBlock(best, rotate.get(), Rotations.NORMAL, true)) return false;
		Item seed = seedOf(level.getBlockState(best).getBlock());
		if (seed != null) replants.put(best.immutable(), new Replant(seed, now + REPLANT_TICKS));
		digging = best.immutable();
		return true;
	}

	private boolean replant(ClientLevel level, LocalPlayer p) {
		for (var entry : replants.entrySet()) {
			BlockPos pos = entry.getKey();
			if (!level.getBlockState(pos).isAir() || !inReach(p, pos)) continue;
			Inv.FindItemResult seed = Inv.findInHotbar(stack -> stack.is(entry.getValue().seed()));
			if (seed.found() && BlockActions.place(pos, seed, rotate.get(), Rotations.NORMAL, true, false, true)) {
				replants.remove(pos);
				return true;
			}
		}
		return false;
	}

	private boolean boneMeal(ClientLevel level, LocalPlayer p) {
		Inv.FindItemResult meal = Inv.findInHotbar(stack -> stack.is(Items.BONE_MEAL));
		if (!meal.found() || mealWait > 0) return false;
		BlockPos target = nearestPlant(level, p, pos -> {
			BlockState state = level.getBlockState(pos);
			return (state.getBlock() instanceof CropBlock || state.getBlock() instanceof StemBlock)
				&& state.getBlock() instanceof BonemealableBlock growable && growable.isValidBonemealTarget(level, pos, state);
		});
		if (target == null || !BlockActions.placeAt(top(target), meal, rotate.get(), Rotations.NORMAL, true, true)) return false;
		mealWait = boneMealDelay.intValue();
		return true;
	}

	/** Selects the hotbar item with the most Fortune, if any has it. */
	private static void holdFortune(LocalPlayer p) {
		int best = -1, level = 0;
		for (int i = 0; i < 9; i++) {
			int l = EnchantmentIds.level(p.getInventory().getItem(i), Enchantments.FORTUNE);
			if (l > level) {
				level = l;
				best = i;
			}
		}
		if (best >= 0 && best != p.getInventory().getSelectedSlot()) Inv.swap(best, false);
	}

	private boolean till(ClientLevel level, LocalPlayer p) {
		Inv.FindItemResult hoe = Inv.findInHotbar(stack -> stack.is(ItemTags.HOES));
		if (!hoe.found()) return false;
		BlockPos target = nearest(level, p, pos -> {
			Block block = level.getBlockState(pos).getBlock();
			return (block == Blocks.GRASS_BLOCK || block == Blocks.DIRT || block == Blocks.DIRT_PATH) && level.getBlockState(pos.above()).isAir();
		});
		return target != null && BlockActions.placeAt(top(target), hoe, rotate.get(), Rotations.NORMAL, true, true);
	}

	private void feed(ClientLevel level, LocalPlayer p, long now) {
		Vec3 eye = p.getEyePosition();
		double reach = range.get() * range.get();
		for (Entity e : level.entitiesForRendering()) {
			if (!(e instanceof Animal animal) || e instanceof TamableAnimal || e instanceof AbstractHorse horse && !horse.isTamed() || fedAt.containsKey(e.getId())) continue;
			if (animal.isBaby() ? !feedBabies.get() : animal.getAge() != 0 || animal.isInLove()) continue;
			if (e.getBoundingBox().distanceToSqr(eye) > reach) continue;
			Inv.FindItemResult food = Inv.findInHotbar(animal::isFood);
			if (!food.found()) continue;

			boolean offhand = food.isOffhand();
			InteractionHand hand = offhand ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
			boolean swapped = !offhand && !food.isMainHand() && Inv.swap(food.slot(), true);
			InteractionResult result = mc().gameMode.interact(p, e, new EntityHitResult(e, e.getBoundingBox().getCenter()), hand);
			if (result instanceof InteractionResult.Success) {
				fedAt.put(e.getId(), now + FEED_RETRY_TICKS);
				p.swing(hand, false);
			}
			if (swapped) Inv.swapBack();
			return;
		}
	}

	// ---- helpers ----

	private boolean ripe(ClientLevel level, BlockPos pos) {
		BlockState state = level.getBlockState(pos);
		Block block = state.getBlock();
		if (block instanceof CropBlock crop) return crops.get() && crop.isMaxAge(state);
		if (block instanceof NetherWartBlock) return netherWart.get() && state.getValue(NetherWartBlock.AGE) >= NetherWartBlock.MAX_AGE;
		if (block instanceof CocoaBlock) return cocoa.get() && state.getValue(CocoaBlock.AGE) >= CocoaBlock.MAX_AGE;
		if (block == Blocks.MELON || block == Blocks.PUMPKIN) return gourds.get() && touchesStem(level, pos);
		if (block == Blocks.SUGAR_CANE || block == Blocks.CACTUS || block == Blocks.BAMBOO) return stalks.get() && level.getBlockState(pos.below()).is(block);
		return false;
	}

	private static boolean touchesStem(ClientLevel level, BlockPos pos) {
		for (Direction side : Direction.Plane.HORIZONTAL) {
			Block next = level.getBlockState(pos.relative(side)).getBlock();
			if (next == Blocks.ATTACHED_MELON_STEM || next == Blocks.ATTACHED_PUMPKIN_STEM) return true;
		}
		return false;
	}

	private static Item seedOf(Block block) {
		if (block == Blocks.WHEAT) return Items.WHEAT_SEEDS;
		if (block == Blocks.CARROTS) return Items.CARROT;
		if (block == Blocks.POTATOES) return Items.POTATO;
		if (block == Blocks.BEETROOTS) return Items.BEETROOT_SEEDS;
		if (block == Blocks.NETHER_WART) return Items.NETHER_WART;
		return null;
	}

	private static BlockHitResult top(BlockPos pos) {
		return new BlockHitResult(Vec3.atCenterOf(pos).add(0, 0.5, 0), Direction.UP, pos, false);
	}

	private boolean inReach(LocalPlayer p, BlockPos pos) {
		return p.getEyePosition().distanceToSqr(Vec3.atCenterOf(pos)) <= range.get() * range.get();
	}

	/** As {@link #nearest}, over the plants the search found instead of every block in reach. */
	private BlockPos nearestPlant(ClientLevel level, LocalPlayer p, java.util.function.Predicate<BlockPos> wanted) {
		if (plants == null) return nearest(level, p, wanted);
		BlockPos best = null;
		double bestDistance = Double.MAX_VALUE;
		for (BlockPos pos : plants.collect(p.blockPosition(), (int) Math.ceil(range.get()) + 2, 4096)) {
			double distance = p.getEyePosition().distanceToSqr(Vec3.atCenterOf(pos));
			if (distance > range.get() * range.get() || distance >= bestDistance || !reachable(level, p, pos, distance) || !wanted.test(pos)) continue;
			best = pos;
			bestDistance = distance;
		}
		return best;
	}

	/** Seen from your eyes, or within Wall range. */
	private boolean reachable(ClientLevel level, LocalPlayer p, BlockPos pos, double distanceSq) {
		if (distanceSq <= wallRange.get() * wallRange.get()) return true;
		BlockHitResult hit = level.clip(new ClipContext(p.getEyePosition(), Vec3.atCenterOf(pos), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p));
		return hit.getType() == HitResult.Type.MISS || hit.getBlockPos().equals(pos);
	}

	/** The closest block in reach that {@code wanted} accepts, or null. */
	private BlockPos nearest(ClientLevel level, LocalPlayer p, java.util.function.Predicate<BlockPos> wanted) {
		int r = (int) Math.ceil(range.get());
		BlockPos origin = p.blockPosition();
		BlockPos best = null;
		double bestDistance = Double.MAX_VALUE;
		for (BlockPos pos : BlockPos.betweenClosed(origin.offset(-r, -r, -r), origin.offset(r, r, r))) {
			double distance = p.getEyePosition().distanceToSqr(Vec3.atCenterOf(pos));
			if (distance > range.get() * range.get() || distance >= bestDistance || !wanted.test(pos) || !reachable(level, p, pos, distance)) continue;
			best = pos.immutable();
			bestDistance = distance;
		}
		return best;
	}
}
