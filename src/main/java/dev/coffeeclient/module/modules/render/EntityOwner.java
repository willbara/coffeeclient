package dev.coffeeclient.module.modules.render;

import dev.coffeeclient.gui.Ui;
import dev.coffeeclient.module.Category;
import dev.coffeeclient.module.Module;
import dev.coffeeclient.setting.BooleanSetting;
import dev.coffeeclient.setting.NumberSetting;
import dev.coffeeclient.util.Colors;
import dev.coffeeclient.util.Coords;
import dev.coffeeclient.util.Nametag;
import dev.coffeeclient.util.WorldProjector;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityReference;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Shows the owner's name above tamed animals and thrown ender pearls, and other projectiles if asked. */
public final class EntityOwner extends Module {
	public final NumberSetting scale = setting(new NumberSetting("Scale", "Label size", 1.0, 0.5, 2.5, 0.05));
	public final NumberSetting maxDistance = setting(new NumberSetting("Max distance", "Hide labels farther than this", 64, 4, 512, 4));
	public final BooleanSetting background = setting(new BooleanSetting("Background", "Draw a panel behind the label", true));
	public final BooleanSetting projectiles = setting(new BooleanSetting("Projectile owners", "Also label arrows, tridents and other projectiles (ender pearls are always labelled)", false));
	public final BooleanSetting showUuid = setting(new BooleanSetting("Show UUID when unknown", "Shortened UUID instead of Unknown when the owner's name is not known", false));
	public final BooleanSetting chatAlerts = setting(new BooleanSetting("Chat alerts", "Chat line with the position and owner of each owned entity found", false));

	private final Map<UUID, String> names = new HashMap<>();
	/** Entities already reported by chat alerts, cleared once it grows large. */
	private final Set<UUID> alerted = new LinkedHashSet<>();
	private static final int MAX_ALERTED = 512;
	private int ticks;

	/** An owner: its UUID, and the entity itself when it is loaded. */
	private record Owner(UUID id, LivingEntity loaded) {
	}

	public EntityOwner() {
		super("EntityOwner", List.of("Entity Owner"), Category.RENDER, "Shows who owns tamed animals and thrown ender pearls.");
	}

	@Override
	protected void onDisable() {
		names.clear();
		alerted.clear();
	}

	@Override
	public void onTick() {
		Minecraft mc = mc();
		if (!chatAlerts.get() || mc.level == null || ++ticks % 20 != 0) return;
		for (Entity entity : mc.level.entitiesForRendering()) {
			Owner owner = ownerOf(entity);
			if (owner == null || !markAlerted(alerted, entity.getUUID())) continue;
			BlockPos pos = entity.blockPosition();
			info(entity.getName().getString() + " (owner: " + ownerName(owner) + ") at " + Coords.format(pos.getX()) + ", " + Coords.format(pos.getY()) + ", " + Coords.format(pos.getZ()));
		}
	}

	/** Adds {@code id} to the reported set, dropping the oldest entry past {@value #MAX_ALERTED}; false if it was already there. */
	static boolean markAlerted(Set<UUID> alerted, UUID id) {
		if (!alerted.add(id)) return false;
		if (alerted.size() > MAX_ALERTED) {
			var oldest = alerted.iterator();
			oldest.next();
			oldest.remove();
		}
		return true;
	}

	/** The owner of a tamed animal, an ender pearl or (with Projectile owners) any projectile; null for none. */
	private Owner ownerOf(Entity entity) {
		return switch (entity) {
			case TamableAnimal tameable -> {
				EntityReference<LivingEntity> ref = tameable.getOwnerReference();
				yield ref == null ? null : new Owner(ref.getUUID(), EntityReference.get(ref, mc().level, LivingEntity.class));
			}
			case ThrownEnderpearl pearl -> livingOwner(pearl.getOwner());
			case Projectile projectile when projectiles.get() -> livingOwner(projectile.getOwner());
			default -> null;
		};
	}

	private static Owner livingOwner(Entity owner) {
		return owner instanceof LivingEntity living ? new Owner(living.getUUID(), living) : null;
	}

	@Override
	public void renderOverlay(Ui ui, WorldProjector projector) {
		Minecraft mc = mc();
		if (mc.level == null || mc.player == null) return;
		float pt = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
		for (Entity entity : mc.level.entitiesForRendering()) {
			Owner owner = ownerOf(entity);
			if (owner == null) continue;
			Vec3 pos = entity.getPosition(pt);
			if (projector.distance(pos.x, pos.y, pos.z) > maxDistance.get()) continue;
			float[] point = projector.project(pos.x, pos.y + entity.getEyeHeight(entity.getPose()) + 0.75, pos.z);
			if (!projector.onScreen(point, 20)) continue;
			String name = ownerName(owner);
			Nametag.drawSegments(ui, point[0], point[1], List.of(new Nametag.Segment(name, Colors.withAlpha(0xFFFFFF, 255))),
				scale.floatValue(), background.get(), ui.theme.panel, null);
		}
	}

	private String ownerName(Owner owner) {
		Minecraft mc = mc();
		if (owner.loaded() instanceof Player player) return player.getName().getString();
		String cached = names.get(owner.id());
		if (cached != null) return cached;
		PlayerInfo info = mc.getConnection() == null ? null : mc.getConnection().getPlayerInfo(owner.id());
		if (info == null) return showUuid.get() ? owner.id().toString().substring(0, 8) : "Unknown";
		String name = info.getProfile().name();
		names.put(owner.id(), name);
		return name;
	}
}
