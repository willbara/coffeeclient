package dev.coffeeclient.module.modules.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.coffeeclient.core.CoffeeClient;
import dev.coffeeclient.core.EventBus;
import dev.coffeeclient.core.Events;
import dev.coffeeclient.gui.Ui;
import dev.coffeeclient.render.EspRender;
import dev.coffeeclient.render.Tracers;
import dev.coffeeclient.util.WorldProjector;
import dev.coffeeclient.render.OverlayRenderer;
import dev.coffeeclient.render.Skeletons;
import dev.coffeeclient.render.WorldOverlays;
import dev.coffeeclient.module.Category;
import dev.coffeeclient.module.Module;
import dev.coffeeclient.module.ModuleManager;
import dev.coffeeclient.setting.BooleanSetting;
import dev.coffeeclient.setting.ColorSetting;
import dev.coffeeclient.setting.EnumSetting;
import dev.coffeeclient.setting.EntityTypeListSetting;
import dev.coffeeclient.setting.IdListSetting;
import dev.coffeeclient.setting.NumberSetting;
import dev.coffeeclient.setting.Setting;
import dev.coffeeclient.util.Colors;
import dev.coffeeclient.util.Nametag;
import dev.coffeeclient.util.Players;
import dev.coffeeclient.util.fakeplayer.FakePlayerEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.ShulkerBullet;
import net.minecraft.world.entity.projectile.hurtingprojectile.WitherSkull;
import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Glow outlines, boxes and tracers for selected entities. The anti-vanish options (after
 * AntiVanish and StaffMonitor) flag players missing from the tab list and report tab-list
 * removals without a leave message.
 */
public final class EntityESP extends Module {
	public enum Filter { CATEGORIES, ONLY_SELECTED, CATEGORIES_PLUS_SELECTED, CATEGORIES_EXCEPT_SELECTED }

	public final BooleanSetting players = group(new BooleanSetting("Players", "Other players", true), "Targets");
	public final BooleanSetting hostile = group(new BooleanSetting("Hostile mobs", "Monsters", true), "Targets");
	public final BooleanSetting passive = group(new BooleanSetting("Passive mobs", "Animals, villagers and other creatures", false), "Targets");
	public final BooleanSetting other = group(new BooleanSetting("Other entities", "Boats, minecarts, armor stands, projectiles...", false), "Targets");
	public final BooleanSetting projectiles = group(new BooleanSetting("Shulker and wither shots", "Shulker bullets and wither skulls, in the Other colour", false), "Targets");
	public final BooleanSetting invisible = group(new BooleanSetting("Show invisible", "Include invisible entities", true), "Targets");
	public final NumberSetting range = group(new NumberSetting("Range", "Max distance in blocks", 64, 8, 256, 8), "Targets");
	public final BooleanSetting useMinY = group(new BooleanSetting("Y limit", "Only targets at or above a height", false), "Targets");
	public final NumberSetting minY = group(new NumberSetting("Min Y", "Lowest Y targeted", 0, -64, 320, 1), "Targets");
	public final EnumSetting<Filter> filter = group(new EnumSetting<>("Filter", "How the entity list combines with the selected types", Filter.CATEGORIES), "Targets");
	public final EntityTypeListSetting entities = group(new EntityTypeListSetting("Entities", "Entity types used by the filter",
		List.of("minecraft:creeper", "minecraft:enderman")), "Targets");

	public final BooleanSetting outline = group(new BooleanSetting("Outline", "Glow outline", true), "Outline");
	public final ColorSetting playerColor = group(new ColorSetting("Player color", "Players", 0xFF4FD9C4), "Outline");
	public final BooleanSetting uniquePlayers = group(new BooleanSetting("Unique player colours", "Give each player a colour of their own, from their UUID", false), "Outline");
	public final BooleanSetting teamColors = group(new BooleanSetting("Team colours", "Players on a scoreboard team use the team's colour", false), "Outline");
	public final ColorSetting friendColor = group(new ColorSetting("Friend color", "Friends", 0xFF5CD65C), "Outline");
	public final ColorSetting hostileColor = group(new ColorSetting("Hostile color", "Monsters", 0xFFE0584F), "Outline");
	public final ColorSetting passiveColor = group(new ColorSetting("Passive color", "Passive mobs", 0xFF5CB85C), "Outline");
	public final ColorSetting otherColor = group(new ColorSetting("Other color", "Everything else", 0xFFB0B0B0), "Outline");
	public final ColorSetting selectedColor = group(new ColorSetting("Selected color", "Types picked in the list", 0xFFB86FD9), "Outline");
	public final BooleanSetting rainbow = group(new BooleanSetting("Rainbow colors", "Cycle every target colour through the rainbow", false), "Outline");
	public final BooleanSetting skeletons = group(new BooleanSetting("Skeletons", "Stick figures along players' limbs (sneak, swim and elytra aware)", false), "Skeletons");
	public final BooleanSetting skeletonDistance = group(new BooleanSetting("Distance colours", "Red up close to green far away, instead of the target colour", false), "Skeletons");
	public final NumberSetting skeletonWidth = group(new NumberSetting("Skeleton width", "Line thickness", 1.5, 0.5, 5, 0.5), "Skeletons");
	public final BooleanSetting skeletonSeeThrough = group(new BooleanSetting("Skeleton through walls", "Draw skeletons through terrain", true), "Skeletons");
	public final BooleanSetting boxes = group(new BooleanSetting("Boxes", "Draw a box around each target's hitbox", false), "Boxes");
	public final EnumSetting<WorldOverlays.Shape> boxShape = group(new EnumSetting<>("Box shape", "How each box is drawn", WorldOverlays.Shape.Outline), "Boxes");
	public final NumberSetting boxOpacity = group(new NumberSetting("Box fill opacity", "Alpha of filled faces", 50, 0, 255, 5), "Boxes");
	public final NumberSetting boxWidth = group(new NumberSetting("Box width", "Outline thickness", 1.0, 0.5, 5, 0.5), "Boxes");
	public final BooleanSetting boxSeeThrough = group(new BooleanSetting("Box through walls", "Draw boxes through terrain", true), "Boxes");
	public final NumberSetting maxTargets = group(new NumberSetting("Max boxes/tracers", "Nearest targets that get a box or tracer (keeps crowds cheap)", 200, 10, 1000, 10), "Boxes");
	public final BooleanSetting tracers = group(new BooleanSetting("Tracers", "Draw tracers to targets", false), "Tracers");
	public final EnumSetting<Tracers.Style> tracerStyle = group(new EnumSetting<>("Tracer style", "Smooth: thin lines drawn in the world. Classic: Coffee's original pixel lines on the screen", Tracers.Style.Smooth), "Tracers");
	public final BooleanSetting tracersPlayersOnly = group(new BooleanSetting("Players only", "Tracers only to players", true), "Tracers");
	public final BooleanSetting nearestTracer = group(new BooleanSetting("Nearest tracer only", "Only the closest tracer is drawn", false), "Tracers");
	public final BooleanSetting tracerFlash = group(new BooleanSetting("Tracer flash", "Pulse tracers with a smooth fade", false), "Tracers");
	public final BooleanSetting lastAttackerTracer = group(new BooleanSetting("Last attacker tracer", "Tracer to the mob that last hurt you", true), "Tracers");
	public final BooleanSetting chargingCreeperTracer = group(new BooleanSetting("Charging creeper tracer", "Flashing tracer to creepers about to explode", true), "Tracers");
	public final NumberSetting tracerWidth = group(new NumberSetting("Thickness", "Tracer line thickness", 1, 1, 3, 1), "Tracers");
	public final NumberSetting tracerAlpha = group(new NumberSetting("Opacity", "Tracer opacity", 0.7, 0.1, 1, 0.05), "Tracers");
	public final BooleanSetting villagerLabels = group(new BooleanSetting("Villager professions", "Profession above each villager in range", false), "Labels");
	public final BooleanSetting bookLabels = group(new BooleanSetting("Librarian books", "Enchanted books a librarian sells, under its profession. Learned when you open a trade with the villager under your crosshair", false), "Labels");
	public final BooleanSetting flagUnlisted = group(new BooleanSetting("Flag unlisted players", "Own colour for players you can see who are not in the tab list (vanished staff)", true), "Anti-vanish");
	public final ColorSetting unlistedColor = group(new ColorSetting("Unlisted color", "Players missing from the tab list", 0xFFFF55FF), "Anti-vanish");
	public final BooleanSetting vanishAlerts = group(new BooleanSetting("Vanish alerts", "Chat line when someone leaves the tab list without a leave message", true), "Anti-vanish");
	public final BooleanSetting gameModeAlerts = group(new BooleanSetting("Game mode alerts", "Chat line when a player switches game mode (spectator, creative)", true), "Anti-vanish");
	public final BooleanSetting ignoreNpcs = group(new BooleanSetting("Ignore NPCs", "Skip server NPCs (version-2 UUIDs), which are never in the tab list", true), "Anti-vanish");
	public final BooleanSetting looking = group(new BooleanSetting("Looking at you", "Recolour players who are looking at you with a clear line of sight", false), "Looking at you");
	public final NumberSetting lookFov = group(new NumberSetting("Their view (degrees)", "How wide a cone counts as looking at you", 140, 30, 180, 5), "Looking at you");
	public final NumberSetting lookRange = group(new NumberSetting("Look range", "Only players this close are checked", 64, 8, 128, 4), "Looking at you");
	public final ColorSetting lookColor = group(new ColorSetting("Looking color", "Colour of a player looking at you", 0xFFFF2020), "Looking at you");

	/** How long a "looking" verdict lasts, so a staggered check doesn't flicker. */
	private static final long LOOK_HOLD_MS = 400;
	/** Players seen looking at you, until this time (client thread). */
	private final Map<UUID, Long> lookingUntil = new HashMap<>();
	private int lookTicks;

	/** Librarian trade offers by villager, learned from the trade screen (the client only gets them while trading). */
	private final Map<UUID, List<String>> librarianOffers = new HashMap<>();

	/** A leave message this soon before or after a tab-list removal means a normal disconnect. */
	private static final long LEAVE_WINDOW_MS = 2000;
	/** Recent chat lines, lower-cased, for matching leave messages (client thread). */
	private final ArrayDeque<TimedText> recentChat = new ArrayDeque<>();
	/** Tab-list removals waiting for a leave message (client thread). */
	private final List<TimedText> pendingRemovals = new ArrayList<>();

	private record TimedText(String text, long at) {
	}

	public EntityESP() {
		super("EntityESP", Category.RENDER, "Glow outlines and tracers for selected entities.");
		for (Setting<?> s : List.of(skeletonDistance, skeletonWidth, skeletonSeeThrough)) s.visibleWhen(skeletons::get);
		entities.visibleWhen(() -> filter.get() != Filter.CATEGORIES);
		for (Setting<?> setting : List.of(tracerStyle, tracersPlayersOnly, nearestTracer, tracerFlash, lastAttackerTracer, chargingCreeperTracer, tracerWidth, tracerAlpha)) setting.visibleWhen(tracers::get);
		for (Setting<?> setting : List.of(boxShape, boxWidth, boxSeeThrough)) setting.visibleWhen(boxes::get);
		boxOpacity.visibleWhen(() -> boxes.get() && boxShape.get() != WorldOverlays.Shape.Outline);
		maxTargets.visibleWhen(() -> boxes.get() || tracers.get());
		minY.visibleWhen(useMinY::get);
		unlistedColor.visibleWhen(flagUnlisted::get);
		for (Setting<?> setting : List.of(lookFov, lookRange, lookColor)) setting.visibleWhen(looking::get);

		// Network thread: hop to the client thread before the packet is applied, while the tab entry still exists.
		// Before the tab-list packet applies: the leaving player's entry and the old game mode are still there.
		listen(Events.PacketApply.Pre.class, EventBus.LOWEST, e -> {
			if (e.isCancelled()) return;
			if (e.packet instanceof ClientboundPlayerInfoRemovePacket remove && vanishAlerts.get()) {
				onTabRemove(remove);
			} else if (e.packet instanceof ClientboundPlayerInfoUpdatePacket update && gameModeAlerts.get()
				&& update.actions().contains(ClientboundPlayerInfoUpdatePacket.Action.UPDATE_GAME_MODE)) {
				onGameModes(update);
			}
		});
		listen(Events.ChatReceived.class, e -> {
			if (!vanishAlerts.get()) return;
			long now = System.currentTimeMillis();
			recentChat.addLast(new TimedText(e.message().getString().toLowerCase(Locale.ROOT), now));
			// Kept twice the window: a removal is checked a window after it, against chat up to a window before it.
			while (recentChat.size() > 100 || !recentChat.isEmpty() && now - recentChat.peekFirst().at() > 2 * LEAVE_WINDOW_MS) recentChat.removeFirst();
		});
		listen(Events.GameLeft.class, e -> {
			recentChat.clear();
			pendingRemovals.clear();
			librarianOffers.clear();
		});
	}

	@Override
	protected void onDisable() {
		recentChat.clear();
		pendingRemovals.clear();
		lookingUntil.clear();
		librarianOffers.clear();
	}

	/** Vanish alerts: a removal with no chat line naming the player around it. */
	@Override
	public void onTick() {
		if (looking.get()) checkLooking();
		else lookingUntil.clear();
		if (bookLabels.get()) captureOffers();
		if (pendingRemovals.isEmpty()) return;
		long now = System.currentTimeMillis();
		pendingRemovals.removeIf(removal -> {
			if (now - removal.at() < LEAVE_WINDOW_MS) return false;
			String name = removal.text().toLowerCase(Locale.ROOT);
			boolean announced = recentChat.stream().anyMatch(line -> line.text().contains(name) && Math.abs(line.at() - removal.at()) <= LEAVE_WINDOW_MS);
			if (!announced) chat(Component.literal(removal.text()).withColor(unlistedColor.argb() & 0xFFFFFF)
				.append(Component.literal(" left the tab list without a leave message (vanished?)").withColor(0xAAAAAA)));
			return true;
		});
	}

	/** Each player is checked every fourth tick, staggered by id, as PlayerESP does. */
	private void checkLooking() {
		Minecraft mc = mc();
		if (mc.level == null || mc.player == null) return;
		long now = System.currentTimeMillis();
		lookTicks++;
		for (Player other : mc.level.players()) {
			if (other == mc.player || ((lookTicks + other.getId()) & 3) != 0) continue;
			if (lookingAt(other, mc.player)) lookingUntil.put(other.getUUID(), now + LOOK_HOLD_MS);
		}
		lookingUntil.values().removeIf(until -> until < now);
	}

	/** Their eyes see a point of your hitbox inside their view cone, with no block in between. */
	private boolean lookingAt(Player other, Player me) {
		if (other.distanceTo(me) > lookRange.get()) return false;
		Vec3 eye = other.getEyePosition();
		AABB box = me.getBoundingBox();
		Vec3 closest = new Vec3(Mth.clamp(eye.x, box.minX, box.maxX), Mth.clamp(eye.y, box.minY, box.maxY), Mth.clamp(eye.z, box.minZ, box.maxZ));
		Vec3 toYou = closest.subtract(eye);
		double length = toYou.length();
		if (length < 1.0E-4) return true;
		if (other.getViewVector(1f).dot(toYou.scale(1 / length)) < Math.cos(Math.toRadians(lookFov.get() / 2))) return false;
		BlockHitResult hit = other.level().clip(new ClipContext(eye, closest, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, other));
		return hit.getType() == HitResult.Type.MISS || hit.getLocation().distanceToSqr(eye) >= length * length - 0.01;
	}

	/** Learns a librarian's offers from the trade screen open on the villager you are looking at. */
	private void captureOffers() {
		Minecraft mc = mc();
		// shortcut: learns only from the villager you are looking at, upgrade to the nearest librarian if that misses trades
		if (mc.player == null || !(mc.player.containerMenu instanceof MerchantMenu menu) || menu.getOffers().isEmpty()) return;
		if (mc.hitResult instanceof EntityHitResult hit && hit.getEntity() instanceof Villager villager && isLibrarian(villager)) {
			librarianOffers.put(villager.getUUID(), bookNames(menu.getOffers()));
		}
	}

	private void onTabRemove(ClientboundPlayerInfoRemovePacket remove) {
		ClientPacketListener connection = mc().getConnection();
		if (connection == null) return;
		long now = System.currentTimeMillis();
		for (UUID id : remove.profileIds()) {
			PlayerInfo info = connection.getPlayerInfo(id);
			if (info == null || ignoreNpcs.get() && npc(id)) continue;
			pendingRemovals.add(new TimedText(info.getProfile().name(), now));
		}
	}

	private void onGameModes(ClientboundPlayerInfoUpdatePacket update) {
		ClientPacketListener connection = mc().getConnection();
		if (connection == null) return;
		for (ClientboundPlayerInfoUpdatePacket.Entry entry : update.entries()) {
			PlayerInfo info = connection.getPlayerInfo(entry.profileId());
			if (info == null || info.getGameMode() == entry.gameMode() || entry.gameMode() == null) continue;
			if (mc().player != null && entry.profileId().equals(mc().player.getUUID())) continue;
			chat(Component.literal(info.getProfile().name()).withColor(0xFFFFFF)
				.append(Component.literal(" switched to " + entry.gameMode().getShortDisplayName().getString().toLowerCase(Locale.ROOT)
					+ (entry.gameMode() == GameType.SPECTATOR ? " (possible staff)" : "")).withColor(0xAAAAAA)));
		}
	}

	/** Citizens-style player NPCs use a version-2 UUID. */
	private static boolean npc(UUID id) {
		return id.version() == 2;
	}

	/** A player you can see who has no tab-list entry: hidden from the list (usually vanished staff). */
	private boolean unlisted(Player player) {
		if (!flagUnlisted.get() || FakePlayerEntity.isFake(player)) return false;
		if (ignoreNpcs.get() && npc(player.getUUID())) return false;
		if (mc().getConnection() == null) return false;
		return !Players.isListed(player.getUUID());
	}

	@Override
	public boolean hasConfigScreen() {
		return true;
	}

	@Override
	public IdListSetting embeddedList() {
		return entities;
	}

	private int targetColor(Entity entity) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || entity == mc.player || entity instanceof ItemEntity) return 0;
		if (entity.isInvisible() && !invisible.get()) return 0;
		if (entity.distanceTo(mc.player) > range.get()) return 0;
		if (useMinY.get() && entity.getY() < minY.intValue()) return 0;
		// "Ignore NPCs" must drop them from every target path (outline, boxes, tracers), not recolour them.
		if (entity instanceof Player player && ignoreNpcs.get() && npc(player.getUUID())) return 0;
		if (entity instanceof Player player && unlisted(player)) return unlistedColor.argb();
		if (entity instanceof Player player && dev.coffeeclient.module.modules.misc.Friends.is(player.getName().getString(), dev.coffeeclient.friend.FriendTrait.HIGHLIGHTED)) {
			return dev.coffeeclient.module.modules.misc.Friends.color(player.getName().getString(), friendColor.argb());
		}
		if (entity instanceof Player && looking.get() && lookingUntil.containsKey(entity.getUUID())) return lookColor.argb();
		boolean listed = entities.has(entity);
		int category = categoryColor(entity);
		int color = switch (filter.get()) {
			case CATEGORIES -> category;
			case ONLY_SELECTED -> listed ? selectedColor.argb() : 0;
			case CATEGORIES_PLUS_SELECTED -> listed ? selectedColor.argb() : category;
			case CATEGORIES_EXCEPT_SELECTED -> listed ? 0 : category;
		};
		return color != 0 && rainbow.get() ? rainbowColor() : color;
	}

	/** The rainbow colour right now: the hue turns a full circle about every six seconds. */
	private static int rainbowColor() {
		return Colors.fromHsv((float) (System.currentTimeMillis() / 17 % 360), 1f, 1f, 0xFF);
	}

	private int categoryColor(Entity entity) {
		if (entity instanceof Player player) return players.get() ? playerTint(player) : 0;
		if (projectiles.get() && (entity instanceof ShulkerBullet || entity instanceof WitherSkull)) return otherColor.argb();
		MobCategory category = entity.getType().getCategory();
		if (category == MobCategory.MONSTER) return hostile.get() ? hostileColor.argb() : 0;
		if (category != MobCategory.MISC) return passive.get() ? passiveColor.argb() : 0;
		return other.get() ? otherColor.argb() : 0;
	}

	/** A player's colour: their team's colour, a colour of their own, or the Players colour. */
	private int playerTint(Player player) {
		if (teamColors.get() && player.getTeam() != null && player.getTeam().getColor().isPresent()) {
			return 0xFF000000 | player.getTeam().getColor().get().rgb();
		}
		if (uniquePlayers.get()) return Colors.fromHsv(uniqueHue(player.getUUID()), 0.75f, 1f, 0xFF);
		return playerColor.argb();
	}

	/** A hue in degrees from the UUID: the same for the same player every time. */
	static float uniqueHue(UUID id) {
		return (id.hashCode() & 0x7FFFFFFF) % 360;
	}

	/** Outline colour (RGB) for an entity, or -1. */
	public static int outlineColor(Entity entity) {
		if (entity == Minecraft.getInstance().player || !ModuleManager.on(EntityESP.class)) return -1;
		EntityESP esp = CoffeeClient.modules().get(EntityESP.class);
		if (!esp.outline.get()) return -1;
		int color = esp.targetColor(entity);
		return color == 0 ? -1 : color & 0xFFFFFF;
	}

	private record Target(Entity entity, int color, double distanceSq) {
	}

	/** Targets with a colour, nearest {@code maxTargets} only (so a mob farm cannot flood the frame). */
	private List<Target> targets(Vec3 cam) {
		Minecraft mc = mc();
		List<Target> targets = new ArrayList<>();
		for (Entity entity : mc.level.entitiesForRendering()) {
			int color = targetColor(entity);
			if (color != 0) targets.add(new Target(entity, color, entity.distanceToSqr(cam)));
		}
		if (targets.size() > maxTargets.intValue()) {
			targets.sort(Comparator.comparingDouble(Target::distanceSq));
			targets = targets.subList(0, maxTargets.intValue());
		}
		return targets;
	}

	/**
	 * Tracer candidates: the targets (players only, if set), then the alert tracers that show whatever the
	 * targets are: the mob that last hurt you, and creepers about to explode. Nearest only, if set.
	 */
	private List<Target> tracerTargets(List<Target> targets, Vec3 cam) {
		Minecraft mc = mc();
		List<Target> out = new ArrayList<>();
		for (Target t : targets) {
			if (!tracersPlayersOnly.get() || t.entity() instanceof Player) out.add(t);
		}
		if (lastAttackerTracer.get() && mc.player.getLastHurtByMob() != null) {
			out.add(new Target(mc.player.getLastHurtByMob(), hostileColor.argb(), 0));
		}
		if (chargingCreeperTracer.get()) {
			for (Entity entity : mc.level.entitiesForRendering()) {
				if (entity instanceof Creeper creeper && creeper.isIgnited()) out.add(new Target(creeper, hostileColor.argb(), 0));
			}
		}
		return Tracers.nearestOnly(out, nearestTracer.get(), t -> t.entity().distanceToSqr(cam));
	}

	/** Tracer opacity: the Opacity setting, pulsed by Tracer flash and always for a charging creeper. */
	private float tracerOpacity(Target t) {
		boolean flash = tracerFlash.get() || t.entity() instanceof Creeper creeper && creeper.isIgnited();
		return flash ? tracerAlpha.floatValue() * Tracers.flash(255) / 255f : tracerAlpha.floatValue();
	}

	@Override
	public void renderOverlay(Ui ui, WorldProjector projector) {
		if (villagerLabels.get() || bookLabels.get()) drawVillagerLabels(ui, projector);
		if (tracers.get() && tracerStyle.get() == Tracers.Style.Classic) classicTracers(ui, projector);
	}

	/** Classic tracers: the original on-screen pixel lines from the crosshair. */
	private void classicTracers(Ui ui, WorldProjector projector) {
		Minecraft mc = mc();
		if (mc.level == null || mc.player == null) return;
		Vec3 camera = projector.camera();
		float partialTick = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
		for (Target t : tracerTargets(targets(camera), camera)) {
			Vec3 position = t.entity().getPosition(partialTick);
			Tracers.pixel(ui, projector, position.x, position.y + t.entity().getBbHeight() / 2, position.z,
				Colors.fade(Colors.withAlpha(t.color(), 255), tracerOpacity(t)), tracerWidth.intValue());
		}
	}

	/** Profession and librarian book lines above each villager in range. */
	private void drawVillagerLabels(Ui ui, WorldProjector projector) {
		Minecraft mc = mc();
		if (mc.level == null || mc.player == null) return;
		float partialTick = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
		for (Entity entity : mc.level.entitiesForRendering()) {
			if (!(entity instanceof Villager villager) || villager.distanceTo(mc.player) > range.get()) continue;
			List<Nametag.Line> lines = new ArrayList<>();
			if (villagerLabels.get()) lines.add(new Nametag.Line(professionName(villager), 0xFFFFFFFF));
			if (bookLabels.get() && isLibrarian(villager)) {
				for (String book : librarianOffers.getOrDefault(villager.getUUID(), List.of())) lines.add(new Nametag.Line(book, 0xFFFFFFFF));
			}
			if (lines.isEmpty()) continue;
			Vec3 p = villager.getPosition(partialTick);
			float[] point = projector.project(p.x, p.y + villager.getBbHeight() + 0.5, p.z);
			if (!projector.onScreen(point, 30)) continue;
			Nametag.draw(ui, point[0], point[1], lines, 1f, true, ui.theme.panel, -1, 0, null);
		}
	}

	private static boolean isLibrarian(Villager villager) {
		return villager.getVillagerData().profession().unwrapKey().orElse(null) == VillagerProfession.LIBRARIAN;
	}

	/** "Librarian", "Farmer" and so on, from the profession's id. */
	private static String professionName(Villager villager) {
		String path = BuiltInRegistries.VILLAGER_PROFESSION.getKey(villager.getVillagerData().profession().value()).getPath();
		if (path.equals("none")) return "Unemployed";
		return Character.toUpperCase(path.charAt(0)) + path.substring(1);
	}

	/** The enchanted books in a set of offers, by name (worked out once, when the trade screen is open). */
	private static List<String> bookNames(MerchantOffers offers) {
		Set<String> names = new LinkedHashSet<>();
		for (MerchantOffer offer : offers) {
			if (offer.getResult().getItem() != Items.ENCHANTED_BOOK) continue;
			for (var entry : EnchantmentHelper.getEnchantmentsForCrafting(offer.getResult()).entrySet()) {
				names.add(Enchantment.getFullname(entry.getKey(), entry.getIntValue()).getString());
			}
		}
		return List.copyOf(names);
	}

	/**
	 * Boxes and tracers in world space. The nearest {@code maxTargets} targets are kept, and boxes outside
	 * the view frustum are skipped.
	 */
	@Override
	public void renderWorld(PoseStack poseStack, Vec3 cameraPos) {
		Minecraft mc = mc();
		boolean smoothTracers = tracers.get() && tracerStyle.get() == Tracers.Style.Smooth;
		if (mc.level == null || mc.player == null || !boxes.get() && !smoothTracers && !skeletons.get()) return;
		List<Target> targets = targets(cameraPos);
		float partialTick = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);

		if (skeletons.get()) {
			VertexConsumer lines = OverlayRenderer.buffer(WorldOverlays.outlineType(skeletonSeeThrough.get()));
			for (Target t : targets) {
				if (!(t.entity() instanceof Player player) || player == mc.player && mc.options.getCameraType().isFirstPerson()) continue;
				int argb = skeletonDistance.get() ? distanceColor(player.distanceTo(mc.player)) : Colors.withAlpha(t.color(), 255);
				Skeletons.draw(lines, player, partialTick, cameraPos, argb, skeletonWidth.floatValue());
			}
		}

		if (boxes.get()) {
			Frustum frustum = EspRender.frustum();
			List<AABB> shown = new ArrayList<>(targets.size());
			int[] colors = new int[targets.size()];
			for (Target t : targets) {
				AABB box = EspRender.entityBox(t.entity(), partialTick);
				if (!frustum.isVisible(box)) continue;
				colors[shown.size()] = t.color() & 0xFFFFFF;
				shown.add(box);
			}
			EspRender.boxes(shown, colors, boxShape.get(), boxSeeThrough.get(), boxWidth.floatValue(), boxOpacity.intValue(), cameraPos);
		}

		if (smoothTracers) {
			VertexConsumer lines = EspRender.tracerBuffer();
			for (Target t : tracerTargets(targets, cameraPos)) {
				Vec3 position = t.entity().getPosition(partialTick);
				int argb = Colors.fade(Colors.withAlpha(t.color(), 255), tracerOpacity(t));
				EspRender.tracer(lines, position.x, position.y + t.entity().getBbHeight() / 2, position.z, argb, tracerWidth.floatValue(), cameraPos);
			}
		}
	}

	/** Red at 0 blocks through yellow to green at 60 and beyond. */
	private static int distanceColor(float distance) {
		float f = Math.min(1, distance / 60);
		int r = f < 0.5f ? 255 : Math.round(255 * (1 - f) * 2), g = f < 0.5f ? Math.round(255 * f * 2) : 255;
		return 0xFF000000 | r << 16 | g << 8;
	}
}
