package dev.coffeeclient.module.modules.player;

import dev.coffeeclient.core.EventBus;
import dev.coffeeclient.core.Events;
import dev.coffeeclient.module.Category;
import dev.coffeeclient.module.Module;
import dev.coffeeclient.setting.ActionSetting;
import dev.coffeeclient.setting.BooleanSetting;
import dev.coffeeclient.setting.KeybindSetting;
import dev.coffeeclient.setting.NumberSetting;
import dev.coffeeclient.setting.StringSetting;
import dev.coffeeclient.util.KeyNames;
import dev.coffeeclient.util.fakeplayer.FakePlayerEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Spawns client-side fake players for testing combat modules and entity rendering.
 * Only the local client sees them; no packets are sent.
 */
public final class FakePlayer extends Module {
	public final StringSetting name = group(new StringSetting("Name", "Name of the fake player", "CoffeeBot", 16), "General");
	public final BooleanSetting randomTabPlayer = group(new BooleanSetting("Random tab player", "Copy a random other player from the tab list (name and skin)", false), "General");
	public final BooleanSetting copyInventory = group(new BooleanSetting("Copy inventory", "Give the fake player your items", true), "General");
	public final BooleanSetting randomGear = group(new BooleanSetting("Random gear", "Give each bot a random enchanted kit: diamond or netherite armor, elytra or turtle helmet, a weapon or tool and an offhand item", false), "General");
	public final BooleanSetting walk = group(new BooleanSetting("Walk around", "Bots wander near where they were spawned (for testing tracers, PlayerSpots, ...)", false), "Bots");
	public final NumberSetting walkRadius = group(new NumberSetting("Walk radius", "How far bots wander from their spawn point", 8, 1, 32, 1), "Bots");
	public final BooleanSetting actReal = group(new BooleanSetting("Act as real player", "Give bots a tab-list entry and let Coffee's modules treat them like real players", false), "Bots");
	public final ActionSetting spawn = group(new ActionSetting("Spawn", "Spawn a fake player", this::spawnFake), "Actions");
	public final KeybindSetting spawnKey = group(new KeybindSetting("Spawn key", "Key or mouse button that spawns a fake player", KeybindSetting.UNBOUND), "Actions");
	public final ActionSetting removeLast = group(new ActionSetting("Remove last", "Remove the most recent fake player", this::removeLast), "Actions");
	public final KeybindSetting removeLastKey = group(new KeybindSetting("Remove last key", "Key or mouse button that removes the most recent fake player", KeybindSetting.UNBOUND), "Actions");
	public final ActionSetting lookAt = group(new ActionSetting("Look at me", "Toggle: bot heads follow you. Bodies stay put", this::toggleLookAt), "Actions");
	public final ActionSetting clear = group(new ActionSetting("Clear all", "Remove every fake player", this::clearFakes), "Actions");

	private final List<FakePlayerEntity> fakes = new ArrayList<>();
	private boolean lookingAtPlayer;

	public FakePlayer() {
		super("FakePlayer", List.of("Fake Player"), Category.PLAYER, "Spawns client-side fake players for testing.");
		name.visibleWhen(() -> !randomTabPlayer.get());
		copyInventory.visibleWhen(() -> !randomGear.get());
		walkRadius.visibleWhen(walk::get);
		walk.onChange(v -> applyBotSettings());
		walkRadius.onChange(v -> applyBotSettings());
		actReal.onChange(v -> applyBotSettings());
	}

	@Override
	public boolean hasConfigScreen() {
		return true;
	}

	private void applyBotSettings() {
		for (FakePlayerEntity fake : fakes) {
			fake.setWalking(walk.get() ? walkRadius.get() : 0);
			fake.setReal(actReal.get());
		}
	}

	@Override
	protected void init(EventBus bus) {
		bus.subscribe(Events.GameLeft.class, event -> fakes.clear());
		listen(Events.KeyPress.class, e -> {
			if (e.action() == 1 && !e.isCancelled() && !e.screenOpen()) pressed(e.key());
		});
		listen(Events.MouseClick.class, e -> {
			if (e.action == 1 && !e.isCancelled()) pressed(KeyNames.mouseKey(e.button));
		});
	}

	private void pressed(int key) {
		if (bindMatches(spawnKey, key)) spawnFake();
		if (bindMatches(removeLastKey, key)) removeLast();
	}

	/** Exact code match, keyboard or mouse. An unbound key matches nothing. */
	private static boolean bindMatches(KeybindSetting bind, int key) {
		return KeyNames.isBound(bind.key()) && bind.key() == key;
	}

	@Override
	protected void onDisable() {
		clearFakes();
	}

	private void spawnFake() {
		Minecraft mc = mc();
		if (mc.level == null || mc.player == null) return;
		PlayerInfo skin = null;
		String fakeName = name.get();
		if (randomTabPlayer.get() && mc.getConnection() != null) {
			List<PlayerInfo> options = new ArrayList<>(mc.getConnection().getOnlinePlayers());
			options.removeIf(info -> info.getProfile().id().equals(mc.player.getUUID()));
			if (!options.isEmpty()) {
				skin = options.get(ThreadLocalRandom.current().nextInt(options.size()));
				fakeName = skin.getProfile().name();
			}
		}
		FakePlayerEntity fake = new FakePlayerEntity(mc.player, fakeName, skin, copyInventory.get() && !randomGear.get());
		if (randomGear.get()) fake.giveRandomGear();
		fakes.add(fake);
		fake.spawn();
		fake.setWalking(walk.get() ? walkRadius.get() : 0);
		fake.setReal(actReal.get());
		fake.setLookAtPlayer(lookingAtPlayer);
	}

	private void toggleLookAt() {
		lookingAtPlayer = !lookingAtPlayer;
		for (FakePlayerEntity fake : fakes) fake.setLookAtPlayer(lookingAtPlayer);
		notify(lookingAtPlayer ? "Bots look at you" : "Bots stopped looking");
	}

	private void removeLast() {
		if (fakes.isEmpty()) return;
		FakePlayerEntity fake = fakes.remove(fakes.size() - 1);
		if (fake.level() != null) fake.despawn();
	}

	private void clearFakes() {
		for (FakePlayerEntity fake : fakes) {
			if (fake.level() != null) fake.despawn();
		}
		fakes.clear();
	}

	@Override
	public String infoString() {
		return String.valueOf(fakes.size());
	}
}
