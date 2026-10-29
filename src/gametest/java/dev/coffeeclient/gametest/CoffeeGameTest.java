package dev.coffeeclient.gametest;

import dev.coffeeclient.core.CoffeeClient;
import dev.coffeeclient.core.Events;
import dev.coffeeclient.gui.theme.Theme;
import dev.coffeeclient.gui.theme.ThemePreset;
import dev.coffeeclient.module.Module;
import dev.coffeeclient.module.modules.client.StreamerMode;
import dev.coffeeclient.module.modules.render.EntityCounter;
import dev.coffeeclient.module.modules.exploit.PacketInspector;
import dev.coffeeclient.module.modules.exploit.PacketRate;
import dev.coffeeclient.module.modules.combat.DamageNumbers;
import dev.coffeeclient.module.modules.exploit.DesyncDetector;
import dev.coffeeclient.module.modules.movement.Flight;
import dev.coffeeclient.module.modules.player.AntiAFK;
import dev.coffeeclient.module.modules.player.AutoRespawn;
import dev.coffeeclient.module.modules.player.AutoTool;
import dev.coffeeclient.module.modules.player.FakePlayer;
import dev.coffeeclient.module.modules.player.ChestSwap;
import dev.coffeeclient.module.modules.player.EXPThrower;
import dev.coffeeclient.module.modules.render.Ambience;
import dev.coffeeclient.module.modules.render.CameraTweaks;
import dev.coffeeclient.module.modules.world.AutoShearer;
import dev.coffeeclient.module.modules.world.StashFinder;
import dev.coffeeclient.module.modules.world.stashfinder.Kind;
import dev.coffeeclient.module.modules.combat.AutoTotem;
import dev.coffeeclient.module.modules.combat.CrystalAura;
import dev.coffeeclient.module.modules.combat.KillAura;
import dev.coffeeclient.module.modules.misc.AutoDisable;
import dev.coffeeclient.module.modules.misc.ChatEffects;
import dev.coffeeclient.module.modules.client.Notifier;
import dev.coffeeclient.module.modules.misc.ChatTweaks;
import dev.coffeeclient.module.modules.misc.Panic;
import dev.coffeeclient.module.modules.exploit.Spoofs;
import dev.coffeeclient.module.modules.movement.SafeWalk;
import dev.coffeeclient.module.modules.movement.Scaffold;
import dev.coffeeclient.module.modules.movement.Speed;
import dev.coffeeclient.module.modules.render.NoEffect;
import dev.coffeeclient.module.modules.movement.NoSlowBreak;
import dev.coffeeclient.module.modules.movement.Sprint;
import dev.coffeeclient.module.modules.movement.Step;
import dev.coffeeclient.module.modules.render.BlockESP;
import dev.coffeeclient.module.modules.render.EntityESP;
import dev.coffeeclient.module.modules.render.HoleESP;
import dev.coffeeclient.module.modules.render.StorageESP;
import dev.coffeeclient.module.modules.render.Trajectories;
import dev.coffeeclient.module.modules.render.XRay;
import dev.coffeeclient.module.modules.exploit.NewChunks;
import dev.coffeeclient.render.Tracers;
import dev.coffeeclient.render.WorldOverlays;
import dev.coffeeclient.util.BlockActions;
import dev.coffeeclient.util.BlockSearch;
import dev.coffeeclient.util.Inv;
import dev.coffeeclient.util.PacketBatch;
import dev.coffeeclient.util.Rotations;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.client.multiplayer.chat.GuiMessage;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundSwingPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * End-to-end checks of Coffee's shared systems in a fresh flat creative world: event listeners,
 * silent rotations, held-slot sync, block placing, incremental block search, Panic, and screenshots
 * of the world renderers. Every check runs on the client thread through the context and throws on
 * failure, which fails {@code ./gradlew runClientGameTest}.
 */
public final class CoffeeGameTest implements FabricClientGameTest {
	private static final Logger LOG = LoggerFactory.getLogger("Coffee Test");

	@Override
	public void runTest(ClientGameTestContext context) {
		// The harness must end on the vanilla title screen; Coffee's own menu would replace it.
		context.runOnClient(mc -> dev.coffeeclient.module.modules.client.MainMenu.useVanilla());
		accountsAndProxies(context);
		dashboardFromTitle(context);
		context.worldBuilder().adjustSettings(ui -> ui.setGameMode(WorldCreationUiState.SelectedGameMode.CREATIVE));
		try (TestSingleplayerContext world = context.worldBuilder().create()) {
			TestServerContext server = world.getServer();
			server.runCommand("time set noon");
			server.runCommand("tp @a 0 -60 0 0 0");
			world.getConnection().waitForChunksRender();
			context.waitTicks(20);

			if ("freelook".equals(System.getenv("COFFEE_TEST_ONLY"))) {
				freeLookTurnsOnlyTheCamera(context);
				return;
			}
			if ("autofly-pause".equals(System.getenv("COFFEE_TEST_ONLY"))) {
				autoFlyResumes(context, server);
				return;
			}
			if ("autofly".equals(System.getenv("COFFEE_TEST_ONLY"))) {
				autoFlySpirals(context, server);
				return;
			}
			if ("cape".equals(System.getenv("COFFEE_TEST_ONLY"))) {
				// a blue theme, like ColdBrew, to see the wings follow it
				context.runOnClient(mc -> {
					CoffeeClient.theme().accentColor.set(0xFF4F9FD9);
					CoffeeClient.theme().accent2Color.set(0xFF1E4F8F);
				});
				capeWings(context, server);
				return;
			}
			if ("scaffold".equals(System.getenv("COFFEE_TEST_ONLY"))) {
				scaffoldPlacesUnderYou(context, server);
				return;
			}
			if ("tracers-config".equals(System.getenv("COFFEE_TEST_ONLY"))) {
				tracerConfigCheck(context, server);
				return;
			}
			if ("tracers".equals(System.getenv("COFFEE_TEST_ONLY"))) {
				tracerDiagnosis(context, server);
				return;
			}
			listenersFollowEnabledState(context);
			silentRotation(context);
			swapSyncsHeldSlot(context, server);
			placeBlock(context, server);
			scaffoldPlacesUnderYou(context, server);
			scaffoldExtraParts(context, server);
			blockSearchFollowsUpdates(context, server);
			panicRestores(context);
			macrosRun(context);
			macrosControlFlow(context);
			friendsRules(context);
			windows(context);
			themesRememberTheirSettings(context);
			chatTools(context);
			spoofsAndView(context, server);
			speedMovesFaster(context, server);
			killAuraHits(context, server);
			crystalAuraHits(context, server);
			packetApplyAndLatency(context, server);
			autoTotemRefills(context, server);
			autoDisableOnSetback(context, server);
			noSlowBreakInAir(context);
			friendsMiddleClick(context, server);
			renderers(context, server);
			bigBlockEspCost(context);
			crowdTracerCost(context, server);
			newChunksWindow(context);
			espGroupsAndQuery(context, server);
			entityCounterChunks(context, server);
			stashFinderDetectors(context, server);
			mentionsAndRename(context);
			zoomHoldsKey(context);
			autoShearerShears(context, server);
			flightAntiKick(context);
			chestSwapSkipsBinding(context, server);
			autoChestplateSwap(context, server);
			expThrowerOnHold(context, server);
			clutterAndTextures(context, server);
			autoToolPutsAwayWorn(context, server);
			buriedStorage(context, server);
			whatHitMe(context, server);
			teleportReport(context, server);
			antiAfkWalks(context, server);
			fakeBotsWalkAndPassAsReal(context);
			canaryAlarmsOncePerPlayer(context);
			alarmQueueAndSpiderTimer(context);
			deathScreenButton(context, server);
			packetRateAndInspector(context);
			packetInspectorWindow(context);
			capeWings(context, server);
		}
		LOG.info("All Coffee checks passed");
	}

	/** NewChunks reads saved files line by line (a bad line is skipped) and keeps only the window around you. */
	private static void newChunksWindow(ClientGameTestContext context) {
		check(new ChunkPos(3, -4).equals(NewChunks.parseChunkLine("3,-4")) && new ChunkPos(5, 6).equals(NewChunks.parseChunkLine("[5, 6]"))
			&& NewChunks.parseChunkLine("broken") == null, "NewChunks parses saved lines");
		check(NewChunks.inWindow(new ChunkPos(128, -128), 0, 0, 128) && !NewChunks.inWindow(new ChunkPos(129, 0), 0, 0, 128),
			"NewChunks window is a square of the radius");
		check(NewChunks.reasonsWithin(Set.of(new ChunkPos(0, 0), new ChunkPos(1, 0), new ChunkPos(9000, 0)),
			Map.of(new ChunkPos(0, 0), new BlockPos(1, 64, 1), new ChunkPos(9000, 0), new BlockPos(9001, 64, 1)), new BlockPos(0, 64, 0), 128)
			.equals(List.of(new BlockPos(1, 64, 1))), "NewChunks reasons: only chunks that have one, within the distance");

		Path dir = context.computeOnClient(mc -> CoffeeClient.config().directory().resolve("chunkdata").resolve("singleplayer").resolve("minecraft_overworld"));
		try {
			Files.createDirectories(dir);
			Files.writeString(dir.resolve("OldChunkData.txt"), "1,1\nbroken line\n2,2\n9000,9000\n");
		} catch (java.io.IOException e) {
			throw new AssertionError(e);
		}
		NewChunks newChunks = module(NewChunks.class);
		context.runOnClient(mc -> newChunks.setEnabled(true));
		context.waitTicks(20);
		boolean loaded = context.computeOnClient(mc -> newChunks.isKnown(new ChunkPos(1, 1)) && newChunks.isKnown(new ChunkPos(2, 2))
			&& !newChunks.isKnown(new ChunkPos(9000, 9000)));
		context.runOnClient(mc -> newChunks.setEnabled(false));
		check(loaded, "NewChunks loads saved chunks past a bad line, inside the window only");
	}

	/** StorageESP sorts block entities into groups; BlockESP's query matches ids and names by keyword. */
	private static void espGroupsAndQuery(ClientGameTestContext context, TestServerContext server) {
		server.runCommand("setblock 6 -60 6 minecraft:barrel");
		server.runCommand("setblock 7 -60 6 minecraft:trapped_chest");
		server.runCommand("setblock 8 -60 6 minecraft:dropper");
		context.waitTicks(5);
		boolean groups = context.computeOnClient(mc -> StorageESP.groupOf(mc.level.getBlockEntity(new BlockPos(6, -60, 6))) == StorageESP.Group.BARREL
			&& StorageESP.groupOf(mc.level.getBlockEntity(new BlockPos(7, -60, 6))) == StorageESP.Group.TRAPPED
			&& StorageESP.groupOf(mc.level.getBlockEntity(new BlockPos(8, -60, 6))) == StorageESP.Group.DROPPER);
		server.runCommand("fill 6 -60 6 8 -60 6 minecraft:air");
		check(groups, "StorageESP groups barrels, trapped chests and droppers");
		boolean query = context.computeOnClient(mc -> BlockESP.matchesQuery(Blocks.DIAMOND_ORE, "ore") && BlockESP.matchesQuery(Blocks.RAW_COPPER_BLOCK, "ore, copper")
			&& !BlockESP.matchesQuery(Blocks.STONE, "ore, copper") && BlockESP.matchesQuery(Blocks.OAK_LOG, "oak log"));
		check(query, "BlockESP query matches by keyword");
	}

	/** EntityCounter counts per chunk for the chosen kind. */
	private static void entityCounterChunks(ClientGameTestContext context, TestServerContext server) {
		// Chunk 2,-2: the crowd check leaves pigs in the spawn chunk.
		for (int i = 0; i < 3; i++) server.runCommand("summon minecraft:cow 40 -60 " + (-24 + i) + " {NoAI:1b,Tags:[\"coffee\"]}");
		server.runCommand("summon minecraft:zombie 41 -60 -24 {NoAI:1b,Tags:[\"coffee\"]}");
		EntityCounter counter = module(EntityCounter.class);
		context.runOnClient(mc -> {
			counter.chunkLabels.set(true);
			counter.kind.set(EntityCounter.Kind.FARM);
			counter.setEnabled(true);
		});
		context.waitTicks(25);
		int cows = context.computeOnClient(mc -> counter.chunkCount(new ChunkPos(2, -2)));
		context.runOnClient(mc -> {
			counter.setEnabled(false);
			counter.chunkLabels.reset();
			counter.kind.reset();
		});
		server.runCommand("kill @e[tag=coffee]");
		check(cows == 3, "EntityCounter counts farm animals per chunk (" + cows + ")");
	}

	/** StashFinder's detectors find a dug tunnel and a pocket in the bedrock. */
	private static void stashFinderDetectors(ClientGameTestContext context, TestServerContext server) {
		server.runCommand("fill 20 -60 20 30 -56 24 minecraft:stone");
		server.runCommand("fill 21 -59 22 28 -58 22 minecraft:air");
		server.runCommand("fill 40 -64 40 44 -60 44 minecraft:bedrock");
		server.runCommand("fill 41 -63 41 43 -61 43 minecraft:air");
		context.waitTicks(10);
		StashFinder finder = module(StashFinder.class);
		var found = context.computeOnClient(mc -> {
			finder.tunnels.set(true);
			finder.pockets.set(true);
			var tunnel = finder.hub().scan(mc.level, mc.level.getChunk(1, 1));
			var pocket = finder.hub().scan(mc.level, mc.level.getChunk(2, 2));
			finder.tunnels.reset();
			finder.pockets.reset();
			return new boolean[]{tunnel.containsKey(Kind.TUNNEL), pocket.containsKey(Kind.POCKET)};
		});
		// The live hub: chunks queued around you and scanned within the time budget.
		context.runOnClient(mc -> {
			finder.tunnels.set(true);
			finder.manMadeBlocks.set(true);
			finder.setEnabled(true);
		});
		context.waitTicks(60);
		boolean live = context.computeOnClient(mc -> {
			var chunk = finder.hub().hits().get(new ChunkPos(1, 1));
			return chunk != null && chunk.containsKey(Kind.TUNNEL);
		});
		context.runOnClient(mc -> {
			finder.setEnabled(false);
			finder.tunnels.reset();
			finder.manMadeBlocks.reset();
		});
		server.runCommand("fill 20 -60 20 30 -56 24 minecraft:air");
		server.runCommand("fill 40 -64 40 44 -60 44 minecraft:air");
		check(found[0], "StashFinder finds a dug tunnel");
		check(found[1], "StashFinder finds a pocket in the bedrock");
		check(live, "StashFinder's hub scans loaded chunks while enabled");
	}

	/** ChatTweaks (was ChatFilter) still answers to the old name; mentions match names, parts and nicknames. */
	private static void mentionsAndRename(ClientGameTestContext context) {
		boolean alias = context.computeOnClient(mc -> CoffeeClient.modules().byName("ChatFilter").orElse(null) == module(ChatTweaks.class));
		check(alias, "ChatTweaks is found by its old name ChatFilter");
		var p = ChatTweaks.mentionPattern("Steve_Builder", List.of("stevo"), true);
		check(p.matcher("hey steve, come here").find() && p.matcher("@stevo!").find() && p.matcher("ask Steve_Builder").find()
			&& !p.matcher("steveland is far").find(), "ChatTweaks mention pattern matches name parts and nicknames");
	}

	/** Holding the zoom key divides the FOV by the zoom level. */
	private static void zoomHoldsKey(ClientGameTestContext context) {
		CameraTweaks tweaks = module(CameraTweaks.class);
		context.runOnClient(mc -> {
			tweaks.zoomKey.set(com.mojang.blaze3d.platform.InputConstants.KEY_C);
			tweaks.setEnabled(true);
		});
		context.getInput().holdKey(com.mojang.blaze3d.platform.InputConstants.KEY_C);
		context.waitTicks(2);
		double held = context.computeOnClient(mc -> CameraTweaks.zoomFactor());
		context.getInput().releaseKey(com.mojang.blaze3d.platform.InputConstants.KEY_C);
		context.waitTicks(2);
		double released = context.computeOnClient(mc -> CameraTweaks.zoomFactor());
		context.runOnClient(mc -> {
			tweaks.setEnabled(false);
			tweaks.zoomKey.reset();
		});
		check(held == 3 && released == 1, "Zoom while the key is held (" + held + " -> " + released + ")");
	}

	/** AutoShearer picks shears from the hotbar and shears a sheep in reach. */
	private static void autoShearerShears(ClientGameTestContext context, TestServerContext server) {
		server.runCommand("tp @a 0 -60 0 0 0");
		server.runCommand("item replace entity @a hotbar.3 with minecraft:shears");
		server.runCommand("summon minecraft:sheep 1 -60 2 {NoAI:1b,Tags:[\"coffee\"]}");
		context.waitTicks(10);
		context.runOnClient(mc -> module(AutoShearer.class).setEnabled(true));
		context.waitTicks(20);
		context.runOnClient(mc -> module(AutoShearer.class).setEnabled(false));
		boolean sheared = server.computeOnServer(s -> {
			for (var e : s.overworld().getAllEntities()) {
				if (e instanceof net.minecraft.world.entity.animal.sheep.Sheep sheep && sheep.entityTags().contains("coffee")) return sheep.isSheared();
			}
			return false;
		});
		server.runCommand("kill @e[tag=coffee]");
		server.runCommand("kill @e[type=minecraft:item]");
		server.runCommand("item replace entity @a hotbar.3 with minecraft:air");
		check(sheared, "AutoShearer shears a sheep");
	}

	/** Flight's anti-kick drops a little and climbs back on its interval. */
	private static void flightAntiKick(ClientGameTestContext context) {
		Flight flight = module(Flight.class);
		context.runOnClient(mc -> {
			flight.mode.set(Flight.Mode.VELOCITY);
			flight.antiKick.set(true);
			flight.antiKickInterval.set(5.0);
			flight.setEnabled(true);
		});
		double min = 0, max = 0;
		for (int i = 0; i < 14; i++) {
			context.waitTicks(1);
			double y = context.computeOnClient(mc -> mc.player.getDeltaMovement().y);
			min = Math.min(min, y);
			max = Math.max(max, y);
		}
		context.runOnClient(mc -> {
			flight.setEnabled(false);
			flight.mode.reset();
			flight.antiKick.reset();
			flight.antiKickInterval.reset();
		});
		check(min <= -0.03 && max >= 0.03, "Flight anti-kick drops and climbs back (" + min + ", " + max + ")");
	}

	/** ChestSwap's Best choice never picks a chestplate with Curse of Binding. */
	private static void chestSwapSkipsBinding(ClientGameTestContext context, TestServerContext server) {
		server.runCommand("item replace entity @a inventory.0 with minecraft:netherite_chestplate[enchantments={\"minecraft:binding_curse\":1}]");
		server.runCommand("item replace entity @a inventory.1 with minecraft:iron_chestplate");
		context.waitTicks(5);
		int best = context.computeOnClient(mc -> ChestSwap.bestChestplate(mc.player.getInventory().getNonEquipmentItems()));
		server.runCommand("item replace entity @a inventory.0 with minecraft:air");
		server.runCommand("item replace entity @a inventory.1 with minecraft:air");
		check(best == 10, "ChestSwap picks the iron chestplate over a cursed netherite one (slot " + best + ")");
	}

	/** Smart equips a chestplate over an elytra on enable and puts the elytra back on disable. */
	private static void autoChestplateSwap(ClientGameTestContext context, TestServerContext server) {
		server.runCommand("gamemode survival @a");
		server.runCommand("item replace entity @a armor.chest with minecraft:elytra");
		server.runCommand("item replace entity @a hotbar.8 with minecraft:netherite_chestplate");
		context.waitTicks(3);
		CrystalAura aura = module(CrystalAura.class);
		context.runOnClient(mc -> {
			aura.autoChestplate.set(CrystalAura.AutoChestplate.SMART);
			aura.setEnabled(true);
		});
		context.waitTicks(3);
		boolean swapped = context.computeOnClient(mc -> mc.player.getItemBySlot(EquipmentSlot.CHEST).is(Items.NETHERITE_CHESTPLATE));
		context.runOnClient(mc -> aura.setEnabled(false));
		context.waitTicks(3);
		boolean returned = context.computeOnClient(mc -> mc.player.getItemBySlot(EquipmentSlot.CHEST).is(Items.ELYTRA));
		context.runOnClient(mc -> aura.autoChestplate.reset());
		server.runCommand("item replace entity @a armor.chest with minecraft:air");
		server.runCommand("item replace entity @a hotbar.8 with minecraft:air");
		server.runCommand("gamemode creative @a");
		check(swapped && returned, "Auto Chestplate Smart equips the chestplate and returns the elytra (" + swapped + ", " + returned + ")");
	}

	/** On hold throws only while right-click is held and the bottle is in the main hand. */
	private static void expThrowerOnHold(ClientGameTestContext context, TestServerContext server) {
		EXPThrower thrower = module(EXPThrower.class);
		server.runCommand("gamemode survival @a");
		server.runCommand("item replace entity @a hotbar.0 with minecraft:experience_bottle 16");
		server.runCommand("item replace entity @a hotbar.1 with minecraft:air");
		context.runOnClient(mc -> {
			mc.player.getInventory().setSelectedSlot(0);
			thrower.onHold.set(true);
			thrower.setEnabled(true);
		});
		context.waitTicks(5);
		int idle = bottleCount(context);
		context.runOnClient(mc -> mc.options.keyUse.setDown(true));
		context.waitTicks(10);
		int held = bottleCount(context);
		context.runOnClient(mc -> mc.options.keyUse.setDown(false));
		context.waitTicks(5);
		int released = bottleCount(context);
		context.waitTicks(5);
		int later = bottleCount(context);
		// A bottle elsewhere in the hotbar must not be pulled in while On hold is on.
		context.runOnClient(mc -> {
			mc.player.getInventory().setSelectedSlot(1);
			mc.options.keyUse.setDown(true);
		});
		context.waitTicks(5);
		int other = bottleCount(context);
		context.runOnClient(mc -> {
			mc.options.keyUse.setDown(false);
			thrower.setEnabled(false);
			thrower.onHold.reset();
		});
		server.runCommand("gamemode creative @a");
		server.runCommand("item replace entity @a hotbar.0 with minecraft:air");
		server.runCommand("item replace entity @a hotbar.1 with minecraft:air");
		check(idle == 16 && held < idle && released == later && other == later,
			"EXPThrower On hold waits for use and a held bottle (idle " + idle + ", held " + held + ", released "
				+ released + "->" + later + ", other " + other + ")");
	}

	private static int bottleCount(ClientGameTestContext context) {
		return context.computeOnClient(mc -> {
			int count = 0;
			for (int i = 0; i < 36; i++) {
				ItemStack stack = mc.player.getInventory().getItem(i);
				if (stack.is(Items.EXPERIENCE_BOTTLE)) count += stack.getCount();
			}
			return count;
		});
	}

	/** Ambience hides holograms; StreamerMode's None rotation gives every block the same texture seed. */
	private static void clutterAndTextures(ClientGameTestContext context, TestServerContext server) {
		server.runCommand("summon minecraft:text_display 2 -59 2 {text:\"hi\",Tags:[\"coffee\"]}");
		context.waitTicks(5);
		Ambience ambience = module(Ambience.class);
		boolean hidden = context.computeOnClient(mc -> {
			ambience.hideHolograms.set(true);
			ambience.setEnabled(true);
			boolean any = false;
			for (var e : mc.level.entitiesForRendering()) if (e instanceof net.minecraft.world.entity.Display.TextDisplay) any |= Ambience.hidesEntity(e);
			ambience.setEnabled(false);
			ambience.hideHolograms.reset();
			return any;
		});
		server.runCommand("kill @e[tag=coffee]");
		check(hidden, "Ambience hides holograms");

		StreamerMode streamer = module(StreamerMode.class);
		var seeds = context.computeOnClient(mc -> {
			streamer.textureRotation.set(StreamerMode.Rotation.NONE);
			streamer.setEnabled(true);
			Long none = StreamerMode.textureSeed(Blocks.STONE.defaultBlockState(), new BlockPos(5, 5, 5));
			streamer.textureRotation.set(StreamerMode.Rotation.CUSTOM_SEED);
			Long custom = StreamerMode.textureSeed(Blocks.STONE.defaultBlockState(), new BlockPos(5, 5, 5));
			long vanilla = net.minecraft.util.Mth.getSeed(5, 5, 5);
			streamer.setEnabled(false);
			streamer.textureRotation.reset();
			Long off = StreamerMode.textureSeed(Blocks.STONE.defaultBlockState(), new BlockPos(5, 5, 5));
			return new Object[]{none, custom, vanilla, off};
		});
		check(Long.valueOf(42).equals(seeds[0]) && seeds[1] != null && !seeds[1].equals(seeds[2]) && seeds[3] == null,
			"StreamerMode shuffles texture seeds only while on");
	}

	/** Coffee's title screen opens the dashboard; it and every screen it links to must build and draw with no world loaded. */
	private static void dashboardFromTitle(ClientGameTestContext context) {
		int errorsBefore = dev.coffeeclient.util.ErrorGuard.failureCount();
		var title = context.computeOnClient(mc -> new dev.coffeeclient.gui.screen.CoffeeTitleScreen());
		List<java.util.function.Function<net.minecraft.client.gui.screens.Screen, net.minecraft.client.gui.screens.Screen>> screens = List.of(
			dev.coffeeclient.gui.screen.DashboardScreen::new, dev.coffeeclient.gui.screen.ClickGuiScreen::new,
			dev.coffeeclient.gui.screen.HudEditorScreen::new, dev.coffeeclient.gui.screen.MacrosScreen::new,
			dev.coffeeclient.gui.screen.ProfilesScreen::new, dev.coffeeclient.gui.screen.FriendsScreen::new,
			dev.coffeeclient.gui.screen.SettingsScreen::new);
		boolean open = true;
		for (var make : screens) {
			context.runOnClient(mc -> mc.gui.setScreen(make.apply(title)));
			context.waitTicks(5);
			if (make == screens.getFirst()) {
				context.waitTicks(20);
				context.takeScreenshot("dashboard-from-title");
			}
			open &= context.computeOnClient(mc -> mc.gui.screen() != null && mc.gui.screen() != title);
		}
		context.runOnClient(mc -> mc.gui.setScreen(new net.minecraft.client.gui.screens.TitleScreen()));
		context.waitTicks(2);
		int errors = dev.coffeeclient.util.ErrorGuard.failureCount() - errorsBefore;
		check(open && errors == 0, "Dashboard and its screens open from the title screen without a world (" + errors + " errors)");
	}

	/** Built-in capes paint elytra wings: screenshots from behind for each style. */
	private static void capeWings(ClientGameTestContext context, TestServerContext server) {
		server.runCommand("tp @a 0 -60 0 180 10");
		server.runCommand("item replace entity @a armor.chest with minecraft:elytra");
		var cape = module(dev.coffeeclient.module.modules.cosmetics.Cape.class);
		int[] window = context.computeOnClient(mc -> new int[] {mc.getWindow().getScreenWidth(), mc.getWindow().getScreenHeight()});
		context.getInput().resizeWindow(1600, 900);
		context.runOnClient(mc -> {
			mc.options.setCameraType(net.minecraft.client.CameraType.THIRD_PERSON_BACK);
			mc.player.setYRot(0);
			mc.player.setXRot(25);
			cape.setEnabled(true);
		});
		for (var style : dev.coffeeclient.module.modules.cosmetics.Cape.Style.values()) {
			if (style == dev.coffeeclient.module.modules.cosmetics.Cape.Style.CUSTOM) continue;
			context.runOnClient(mc -> cape.style.set(style));
			context.waitTicks(10);
			context.takeScreenshot("cape-wings-" + style.name().toLowerCase(java.util.Locale.ROOT));
		}
		context.runOnClient(mc -> {
			cape.style.reset();
			cape.setEnabled(false);
			mc.options.setCameraType(net.minecraft.client.CameraType.FIRST_PERSON);
		});
		context.getInput().resizeWindow(window[0], window[1]);
		server.runCommand("item replace entity @a armor.chest with minecraft:air");
	}

	/** The newest {@code n} chat lines, newest first. */
	@SuppressWarnings("unchecked")
	private static List<String> recentChat(net.minecraft.client.Minecraft mc, int n) {
		try {
			var field = ChatComponent.class.getDeclaredField("allMessages");
			field.setAccessible(true);
			List<GuiMessage> messages = (List<GuiMessage>) field.get(mc.gui.hud.getChat());
			return messages.stream().limit(n).map(m -> m.content().getString()).toList();
		} catch (ReflectiveOperationException e) {
			throw new AssertionError(e);
		}
	}

	/** AutoTool swaps a held tool that is about to break for a safe slot. */
	private static void autoToolPutsAwayWorn(ClientGameTestContext context, TestServerContext server) {
		server.runCommand("item replace entity @a hotbar.0 with minecraft:diamond_pickaxe[damage=1558]");
		server.runCommand("item replace entity @a hotbar.1 with minecraft:air");
		context.runOnClient(mc -> mc.player.getInventory().setSelectedSlot(0));
		context.waitTicks(5);
		AutoTool tool = module(AutoTool.class);
		context.runOnClient(mc -> {
			tool.putAwayWorn.set(true);
			tool.setEnabled(true);
		});
		context.waitTicks(5);
		int slot = context.computeOnClient(mc -> mc.player.getInventory().getSelectedSlot());
		context.runOnClient(mc -> {
			tool.setEnabled(false);
			tool.putAwayWorn.reset();
			mc.player.getInventory().setSelectedSlot(0);
		});
		server.runCommand("item replace entity @a hotbar.0 with minecraft:air");
		check(slot != 0, "AutoTool puts away a pickaxe with 3 uses left (now slot " + slot + ")");
	}

	/** StorageESP calls a chest with a block on top buried, and one with another chest on top not. */
	private static void buriedStorage(ClientGameTestContext context, TestServerContext server) {
		server.runCommand("setblock 10 -60 10 minecraft:chest");
		server.runCommand("setblock 10 -59 10 minecraft:dirt");
		server.runCommand("setblock 12 -60 10 minecraft:chest");
		server.runCommand("setblock 12 -59 10 minecraft:barrel");
		context.waitTicks(5);
		boolean ok = context.computeOnClient(mc -> StorageESP.buried(mc.level, mc.level.getBlockEntity(new BlockPos(10, -60, 10)))
			&& !StorageESP.buried(mc.level, mc.level.getBlockEntity(new BlockPos(12, -60, 10))));
		server.runCommand("fill 10 -60 10 12 -59 10 minecraft:air");
		check(ok, "StorageESP spots buried chests");
	}

	/** DamageNumbers' "What hit me" names the cause in chat. */
	private static void whatHitMe(ClientGameTestContext context, TestServerContext server) {
		server.runCommand("gamemode survival @a");
		DamageNumbers numbers = module(DamageNumbers.class);
		context.runOnClient(mc -> {
			numbers.damageAlerts.set(true);
			numbers.setEnabled(true);
		});
		context.waitTicks(3);
		server.runCommand("damage @p 2 minecraft:cactus");
		context.waitTicks(5);
		List<String> chat = context.computeOnClient(mc -> recentChat(mc, 5));
		context.runOnClient(mc -> {
			numbers.setEnabled(false);
			numbers.damageAlerts.reset();
		});
		server.runCommand("gamemode creative @a");
		server.runCommand("effect give @a minecraft:instant_health 1 5");
		check(chat.stream().anyMatch(l -> l.contains("cactus") && l.contains("HP")), "DamageNumbers says what hit you (" + chat.getFirst() + ")");
	}

	/** DesyncDetector reports a real teleport with where from and to. */
	private static void teleportReport(ClientGameTestContext context, TestServerContext server) {
		DesyncDetector desync = module(DesyncDetector.class);
		context.runOnClient(mc -> {
			desync.reportTeleports.set(true);
			desync.setEnabled(true);
		});
		server.runCommand("tp @a 0 -60 100");
		context.waitTicks(5);
		List<String> chat = context.computeOnClient(mc -> recentChat(mc, 5));
		server.runCommand("tp @a 0 -60 0 0 0");
		context.waitTicks(5);
		context.runOnClient(mc -> {
			desync.setEnabled(false);
			desync.reportTeleports.reset();
		});
		check(chat.stream().anyMatch(l -> l.contains("Teleported 100 blocks")), "DesyncDetector reports teleports (" + chat.getFirst() + ")");
	}

	/** AntiAFK's walk wanders off the start point but stays near it. */
	private static void antiAfkWalks(ClientGameTestContext context, TestServerContext server) {
		server.runCommand("tp @a 0 -60 0 0 0");
		context.waitTicks(5);
		AntiAFK afk = module(AntiAFK.class);
		var start = context.computeOnClient(mc -> mc.player.position());
		context.runOnClient(mc -> {
			afk.jump.set(false);
			afk.spin.set(false);
			afk.walk.set(true);
			afk.walkRadius.set(4.0);
			afk.waitMin.set(0.0);
			afk.waitMax.set(0.0);
			afk.setEnabled(true);
		});
		double farthest = 0;
		for (int i = 0; i < 12; i++) {
			context.waitTicks(5);
			farthest = Math.max(farthest, context.computeOnClient(mc -> mc.player.position()).distanceTo(start));
		}
		context.runOnClient(mc -> {
			afk.setEnabled(false);
			for (var s : List.of(afk.jump, afk.spin, afk.walk, afk.walkRadius, afk.waitMin, afk.waitMax)) s.reset();
		});
		server.runCommand("tp @a 0 -60 0 0 0");
		check(farthest > 0.5 && farthest < 6, "AntiAFK walks around the start point (" + String.format(java.util.Locale.ROOT, "%.1f", farthest) + " blocks)");
	}

	/** FakePlayer bots wander, and with "Act as real player" get a tab-list entry that is removed again. */
	private static void fakeBotsWalkAndPassAsReal(ClientGameTestContext context) {
		FakePlayer fakes = module(FakePlayer.class);
		var before = context.computeOnClient(mc -> {
			fakes.walk.set(true);
			fakes.actReal.set(true);
			fakes.setEnabled(true);
			fakes.spawn.run();
			return mc.player.position();
		});
		context.waitTicks(80);
		var state = context.computeOnClient(mc -> {
			var bot = mc.level.players().stream().filter(p -> p instanceof dev.coffeeclient.util.fakeplayer.FakePlayerEntity).findFirst().orElseThrow();
			boolean listed = mc.getConnection().getPlayerInfo(bot.getUUID()) != null;
			boolean real = !dev.coffeeclient.util.fakeplayer.FakePlayerEntity.isFake(bot);
			double moved = bot.position().distanceTo(before);
			fakes.setEnabled(false);
			boolean unlisted = mc.getConnection().getPlayerInfo(bot.getUUID()) == null;
			fakes.walk.reset();
			fakes.actReal.reset();
			return new Object[]{listed, real, moved, unlisted};
		});
		check((boolean) state[0] && (boolean) state[1] && (boolean) state[3], "FakePlayer bot passes as real (tab entry added and removed)");
		check((double) state[2] > 0.5, "FakePlayer bot walks (" + String.format(java.util.Locale.ROOT, "%.1f", (double) state[2]) + " blocks)");
	}

	/** Canary alarms once per player until they leave (or after the repeat time). */
	private static void canaryAlarmsOncePerPlayer(ClientGameTestContext context) {
		var spots = module(dev.coffeeclient.module.modules.render.PlayerSpots.class);
		var a = java.util.UUID.randomUUID();
		var b = java.util.UUID.randomUUID();
		spots.canaryRepeat.set(0.0);
		check(spots.canaryDue(a, 0) && !spots.canaryDue(a, 100_000), "Canary alarms a player once while they stay");
		check(spots.canaryDue(b, 0), "Canary alarms a second player separately");
		spots.canaryForget(a);
		check(spots.canaryDue(a, 200_000), "Canary alarms again after the player left");
		spots.canaryRepeat.set(5.0);
		check(!spots.canaryDue(a, 202_000) && spots.canaryDue(a, 206_000), "Canary repeats after the set time");
		spots.canaryRepeat.reset();
		spots.canaryForget(a);
		spots.canaryForget(b);
	}

	/** The alarm queue stacks for a crowd, is capped and clears on reset; Spider hands the Timer back when Fast climb is off. */
	private static void alarmQueueAndSpiderTimer(ClientGameTestContext context) {
		var alarm = new dev.coffeeclient.util.Alarm(dev.coffeeclient.util.Alarm.soundSetting(),
			dev.coffeeclient.util.Alarm.useJingleSetting(), dev.coffeeclient.util.Alarm.jingleSetting(),
			new dev.coffeeclient.setting.NumberSetting("Count", "", 3, 1, 10, 1), new dev.coffeeclient.setting.NumberSetting("Volume", "", 0.5, 0.05, 2, 0.05));
		alarm.trigger();
		alarm.trigger();
		check(alarm.pending() == 6, "Alarm stacks plays for a second trigger");
		for (int i = 0; i < 5; i++) alarm.trigger();
		check(alarm.pending() == 10, "Alarm queue is capped");
		alarm.reset();
		check(alarm.pending() == 0, "Alarm reset drops pending plays");

		var spider = module(dev.coffeeclient.module.modules.movement.Spider.class);
		double held = context.computeOnClient(mc -> {
			spider.fastClimb.set(false);
			dev.coffeeclient.module.modules.movement.Timer.override(spider, 3.0);
			spider.setEnabled(true);
			spider.onTick();
			double after = dev.coffeeclient.module.modules.movement.Timer.multiplier();
			spider.setEnabled(false);
			return after;
		});
		check(held == 1.0, "Spider releases its Timer override when Fast climb is off");
		context.runOnClient(mc -> spider.fastClimb.reset());
	}

	/** With AutoRespawn off, the death screen offers a button that turns it on and respawns. */
	private static void deathScreenButton(ClientGameTestContext context, TestServerContext server) {
		context.runOnClient(mc -> module(AutoRespawn.class).setEnabled(false));
		server.runCommand("kill @a");
		context.waitTicks(10);
		boolean button = context.computeOnClient(mc -> mc.gui.screen() instanceof net.minecraft.client.gui.screens.DeathScreen death
			&& death.children().stream().anyMatch(c -> c instanceof net.minecraft.client.gui.components.Button b && b.getMessage().getString().equals("AutoRespawn: OFF")));
		context.runOnClient(mc -> AutoRespawn.enableAndRespawn());
		context.waitTicks(10);
		boolean alive = context.computeOnClient(mc -> mc.gui.screen() == null && mc.player.isAlive() && module(AutoRespawn.class).isEnabled());
		context.runOnClient(mc -> module(AutoRespawn.class).setEnabled(false));
		server.runCommand("tp @a 0 -60 0 0 0");
		check(button, "Death screen shows the AutoRespawn button");
		check(alive, "The AutoRespawn button respawns and turns it on");
	}

	/**
	 * PacketRate sends a burst at once, queues the rest and sends them as the rate allows (none lost), and
	 * drops the rest when "Drop excess" is on. PacketInspector sees every packet that left, with its size.
	 */
	private static void packetRateAndInspector(ClientGameTestContext context) {
		PacketRate rate = module(PacketRate.class);
		PacketInspector inspector = module(PacketInspector.class);
		context.runOnClient(mc -> {
			rate.limit.set(200.0);
			rate.burst.set(3.0);
			rate.margin.set(0.0);
			rate.dropExcess.set(false);
			inspector.logToGame.set(false);
			inspector.setEnabled(true);
			rate.setEnabled(true);
		});
		context.waitTicks(20);
		int queued = context.computeOnClient(mc -> {
			for (int i = 0; i < 10; i++) mc.player.connection.send(new ServerboundSwingPacket(InteractionHand.MAIN_HAND));
			return rate.queued();
		});
		context.waitTicks(80);
		int leftover = context.computeOnClient(mc -> rate.queued());
		long[] swings = context.computeOnClient(mc -> {
			long out = 0, bytes = 0;
			for (var e : inspector.recent()) {
				if (e.outbound() && e.id().endsWith("swing")) {
					out++;
					bytes += e.bytes();
				}
			}
			return new long[] {out, bytes};
		});
		// Normal traffic (movement, tick ends) takes tokens too, so the queue holds most of the 10 rather than exactly 7.
		boolean queuedOk = queued >= 5 && queued <= 9 && leftover == 0;
		check(queuedOk && swings[0] == 10 && swings[1] > 0,
			"PacketRate queues over its limit and sends all (queued " + queued + ", left " + leftover + ", inspector saw " + swings[0] + " swings, " + swings[1] + " bytes)");

		context.runOnClient(mc -> rate.dropExcess.set(true));
		context.waitTicks(80);
		long before = swings[0];
		context.computeOnClient(mc -> {
			for (int i = 0; i < 10; i++) mc.player.connection.send(new ServerboundSwingPacket(InteractionHand.MAIN_HAND));
			return 0;
		});
		context.waitTicks(20);
		long sent = context.computeOnClient(mc -> {
			long out = 0;
			for (var e : inspector.recent()) if (e.outbound() && e.id().endsWith("swing")) out++;
			return out;
		}) - before;
		check(sent >= 2 && sent <= 4 && rate.queued() == 0, "PacketRate drops the excess when Drop excess is on (sent " + sent + " of 10)");

		context.runOnClient(mc -> {
			rate.setEnabled(false);
			rate.limit.reset();
			rate.burst.reset();
			rate.margin.reset();
			rate.dropExcess.reset();
			inspector.setEnabled(false);
			inspector.logToGame.reset();
		});
	}

	/** The PacketInspector window opens and draws each of its tabs without a single error. */
	@SuppressWarnings({"unchecked", "rawtypes"})
	private static void packetInspectorWindow(ClientGameTestContext context) {
		int errorsBefore = dev.coffeeclient.util.ErrorGuard.failureCount();
		PacketInspector inspector = module(PacketInspector.class);
		context.runOnClient(mc -> {
			inspector.setEnabled(true);
			mc.gui.setScreen(inspector.configScreen(null));
		});
		context.waitTicks(8);
		context.takeScreenshot("window-packet-inspector");
		for (String tab : List.of("STATS", "SETTINGS", "LIVE")) {
			context.runOnClient(mc -> {
				try {
					Class<?> tabType = Class.forName(dev.coffeeclient.gui.screen.PacketInspectorScreen.class.getName() + "$Tab");
					var show = dev.coffeeclient.gui.screen.PacketInspectorScreen.class.getDeclaredMethod("show", tabType);
					show.setAccessible(true);
					show.invoke(mc.gui.screen(), Enum.valueOf((Class) tabType, tab));
				} catch (ReflectiveOperationException e) {
					throw new IllegalStateException(e);
				}
			});
			context.waitTicks(6);
			if (tab.equals("STATS")) context.takeScreenshot("window-packet-inspector-stats");
			if (tab.equals("SETTINGS")) context.takeScreenshot("window-packet-inspector-settings");
		}
		boolean open = context.computeOnClient(mc -> mc.gui.screen() instanceof dev.coffeeclient.gui.screen.PacketInspectorScreen);
		context.runOnClient(mc -> {
			mc.gui.setScreen(null);
			inspector.setEnabled(false);
		});
		int errors = dev.coffeeclient.util.ErrorGuard.failureCount() - errorsBefore;
		check(open && errors == 0, "PacketInspector window opens and draws its tabs (" + errors + " errors)");
	}

	private static void check(boolean ok, String what) {
		if (!ok) throw new AssertionError("Coffee check failed: " + what);
		LOG.info("PASS {}", what);
	}

	private static <T extends Module> T module(Class<T> type) {
		return CoffeeClient.modules().get(type);
	}

	@SuppressWarnings("unchecked")
	private static dev.coffeeclient.setting.EnumSetting<Tracers.Style> tracerStyle(Class<? extends Module> type) {
		for (var setting : module(type).settings()) {
			if (setting.name().equals("Tracer style")) return (dev.coffeeclient.setting.EnumSetting<Tracers.Style>) setting;
		}
		throw new AssertionError(type.getSimpleName() + " has no tracer style");
	}

	/** A module's handlers are on the bus only while it is enabled. */
	private static void listenersFollowEnabledState(ClientGameTestContext context) {
		context.runOnClient(mc -> {
			var bus = CoffeeClient.events();
			SafeWalk safeWalk = module(SafeWalk.class);
			boolean before = bus.has(Events.ClipAtLedge.class);
			safeWalk.setEnabled(true);
			boolean during = bus.has(Events.ClipAtLedge.class);
			safeWalk.setEnabled(false);
			boolean after = bus.has(Events.ClipAtLedge.class);
			check(!before && during && !after, "listeners attach only while enabled");
		});
	}

	/** The server sees the requested look; the camera does not move. */
	private static void silentRotation(ClientGameTestContext context) {
		AtomicBoolean sentDown = new AtomicBoolean();
		float pitchBefore = context.computeOnClient(mc -> {
			float pitch = mc.player.getXRot();
			Rotations.rotate(mc.player.getYRot(), 90, Rotations.HIGH,
				() -> sentDown.set(mc.player.getXRot() == 90 && Rotations.serverPitch() == 90));
			return pitch;
		});
		context.waitTicks(3);
		boolean cameraKept = context.computeOnClient(mc -> mc.player.getXRot() == pitchBefore);
		check(sentDown.get() && cameraKept, "silent rotation (server pitch 90, camera kept)");
	}

	/** Inv.swap tells the server about the new held slot straight away. */
	private static void swapSyncsHeldSlot(ClientGameTestContext context, TestServerContext server) {
		context.runOnClient(mc -> Inv.swap(4, false));
		context.waitTicks(2);
		int serverSlot = server.computeOnServer(s -> s.getPlayerList().getPlayers().getFirst().getInventory().getSelectedSlot());
		check(serverSlot == 4, "Inv.swap syncs the held slot (server sees " + serverSlot + ")");
		context.runOnClient(mc -> Inv.swap(0, false));
	}

	/** BlockActions.place picks a face, rotates, swaps to the block and places it; the server agrees. */
	private static void placeBlock(ClientGameTestContext context, TestServerContext server) {
		server.runCommand("item replace entity @a hotbar.3 with minecraft:oak_planks 16");
		server.runCommand("setblock 2 -61 0 minecraft:stone");
		context.waitTicks(5);
		BlockPos target = new BlockPos(2, -60, 0);
		boolean queued = context.computeOnClient(mc -> BlockActions.place(target,
			Inv.findInHotbar(stack -> stack.is(Items.OAK_PLANKS)), true, Rotations.NORMAL, true, true, true));
		context.waitTicks(5);
		boolean placed = server.computeOnServer(s -> s.overworld().getBlockState(target).is(Blocks.OAK_PLANKS));
		int held = context.computeOnClient(mc -> mc.player.getInventory().getSelectedSlot());
		check(queued && placed && held == 0, "BlockActions.place (queued " + queued + ", placed " + placed + ", swapped back " + (held == 0) + ")");
		server.runCommand("setblock 2 -60 0 minecraft:air");
	}

	/**
	 * Scaffold places a block under you once you walk off a ledge, aiming at the stone beside it, and the
	 * held slot is put back afterwards.
	 */
	private static void scaffoldPlacesUnderYou(ClientGameTestContext context, TestServerContext server) {
		server.runCommand("item replace entity @a hotbar.3 with minecraft:oak_planks 16");
		server.runCommand("setblock 20 -52 20 minecraft:stone");
		server.runCommand("setblock 21 -52 20 minecraft:air");
		context.waitTicks(5);
		BlockPos spot = new BlockPos(21, -52, 20);
		// Enabled before the teleport, and checked within a few ticks: the player is still above the air
		// block, so the spot under the feet is the one to fill (a longer fall would pick a spot below).
		context.runOnClient(mc -> module(Scaffold.class).setEnabled(true));
		server.runCommand("tp @a 21.5 -51 20.5 0 0");
		context.waitTicks(3);
		context.runOnClient(mc -> module(Scaffold.class).setEnabled(false));
		context.waitTicks(5);
		boolean placed = server.computeOnServer(s -> s.overworld().getBlockState(spot).is(Blocks.OAK_PLANKS));
		int held = context.computeOnClient(mc -> mc.player.getInventory().getSelectedSlot());
		check(placed && held == 0, "Scaffold places under you (placed " + placed + ", held slot back " + (held == 0) + ")");
		server.runCommand("setblock 20 -52 20 minecraft:air");
		server.runCommand("setblock 21 -52 20 minecraft:air");
		server.runCommand("tp @a 0 -60 0 0 0");
		context.waitTicks(10);
	}

	/**
	 * A preset sets motion and window settings as well as colours, every theme remembers its own tweaks,
	 * and reset puts a preset back to how it ships.
	 */
	private static void themesRememberTheirSettings(ClientGameTestContext context) {
		context.runOnClient(mc -> {
			Theme theme = CoffeeClient.theme();
			theme.preset.set(ThemePreset.TERMINAL);
			boolean sets = theme.transition.get() == Theme.Transition.CRT && theme.animationSpeed.get() == 1.5
				&& theme.motionStyle.get() == Theme.MotionStyle.SNAPPY && !theme.blur.get();
			theme.animationSpeed.set(2.0);
			theme.transition.set(Theme.Transition.FADE);
			boolean tweaked = theme.themeChanged();
			theme.preset.set(ThemePreset.NEON_NIGHT);
			boolean other = theme.transition.get() == Theme.Transition.GLITCH && theme.animationSpeed.get() == 1.25;
			theme.preset.set(ThemePreset.TERMINAL);
			boolean remembered = theme.transition.get() == Theme.Transition.FADE && theme.animationSpeed.get() == 2.0;
			theme.revertTheme();
			boolean reset = theme.transition.get() == Theme.Transition.CRT && theme.animationSpeed.get() == 1.5 && !theme.themeChanged();
			theme.preset.set(ThemePreset.ESPRESSO);
			theme.revertTheme();
			theme.themeStates.reset();
			check(sets && tweaked && other && remembered && reset, "themes set and remember motion and window settings (preset sets "
				+ sets + ", tweaked " + tweaked + ", other theme " + other + ", remembered " + remembered + ", reset " + reset + ")");
		});
	}

	/**
	 * Scaffold extra parts: a favourable block beats a bigger stack of ice, Expand builds ahead
	 * of where you stand, and Same Y keeps the height after you rise.
	 */
	private static void scaffoldExtraParts(ClientGameTestContext context, TestServerContext server) {
		Scaffold scaffold = context.computeOnClient(mc -> module(Scaffold.class));
		server.runCommand("item replace entity @a hotbar.3 with minecraft:oak_planks 16");

		// ranking: ice (slippery) is in an earlier slot with the bigger stack, planks are still chosen
		server.runCommand("item replace entity @a hotbar.1 with minecraft:ice 64");
		server.runCommand("setblock 20 -52 20 minecraft:stone");
		server.runCommand("setblock 21 -52 20 minecraft:air");
		context.waitTicks(3);
		context.runOnClient(mc -> scaffold.setEnabled(true));
		server.runCommand("tp @a 21.5 -51 20.5 0 0");
		context.waitTicks(3);
		context.runOnClient(mc -> scaffold.setEnabled(false));
		context.waitTicks(3);
		boolean planks = server.computeOnServer(s -> s.overworld().getBlockState(new BlockPos(21, -52, 20)).is(Blocks.OAK_PLANKS));
		server.runCommand("item replace entity @a hotbar.1 with minecraft:air");
		server.runCommand("setblock 21 -52 20 minecraft:air");

		// expand: standing on the stone facing +Z, a block is placed ahead of the feet
		server.runCommand("tp @a 20.5 -51 20.5 0 0");
		context.waitTicks(8);
		context.runOnClient(mc -> {
			scaffold.expand.set(3.0);
			scaffold.setEnabled(true);
		});
		context.waitTicks(4);
		context.runOnClient(mc -> {
			scaffold.setEnabled(false);
			scaffold.expand.reset();
		});
		context.waitTicks(3);
		boolean ahead = server.computeOnServer(s -> s.overworld().getBlockState(new BlockPos(20, -52, 21)).is(Blocks.OAK_PLANKS));
		server.runCommand("fill 20 -52 21 20 -52 24 minecraft:air");

		// same y: enabled while standing on the stone, then lifted two blocks; the block still goes to the stone's level
		server.runCommand("tp @a 20.5 -51 20.5 0 0");
		context.waitTicks(8);
		context.runOnClient(mc -> {
			scaffold.sameY.set(Scaffold.SameY.On);
			scaffold.setEnabled(true);
		});
		context.waitTicks(2);
		server.runCommand("tp @a 21.5 -49 20.5 0 0");
		context.waitTicks(3);
		context.runOnClient(mc -> {
			scaffold.setEnabled(false);
			scaffold.sameY.reset();
		});
		context.waitTicks(3);
		boolean sameY = server.computeOnServer(s -> s.overworld().getBlockState(new BlockPos(21, -52, 20)).is(Blocks.OAK_PLANKS));
		server.runCommand("fill 18 -53 18 24 -50 24 minecraft:air");

		check(planks && ahead && sameY, "Scaffold extra parts (ranking " + planks + ", expand " + ahead + ", same Y " + sameY + ")");
		server.runCommand("tp @a 0 -60 0 0 0");
		context.waitTicks(10);
	}

	/** A block placed after a search started shows up through block updates, and leaves when removed. */
	private static void blockSearchFollowsUpdates(ClientGameTestContext context, TestServerContext server) {
		Set<Block> key = Set.of(Blocks.LAPIS_BLOCK);
		BlockSearch.Handle search = context.computeOnClient(mc -> BlockSearch.acquire(key, state -> key.contains(state.getBlock())));
		BlockPos pos = new BlockPos(4, -59, 4);
		server.runCommand("setblock 4 -59 4 minecraft:lapis_block");
		context.waitTicks(5);
		boolean found = context.computeOnClient(mc -> search.collect(mc.player.blockPosition(), 32, 100).contains(pos));
		server.runCommand("setblock 4 -59 4 minecraft:air");
		context.waitTicks(5);
		boolean gone = context.computeOnClient(mc -> !search.collect(mc.player.blockPosition(), 32, 100).contains(pos));
		context.runOnClient(mc -> search.release());
		check(found && gone, "block search follows updates (found " + found + ", removed " + gone + ")");
	}

	/** Panic turns modules off and restores exactly those. */
	private static void panicRestores(ClientGameTestContext context) {
		context.runOnClient(mc -> {
			Step step = module(Step.class);
			Sprint sprint = module(Sprint.class);
			step.setEnabled(true);
			sprint.setEnabled(false);
			module(Panic.class).setEnabled(true);
			boolean off = !step.isEnabled();
			module(Panic.class).setEnabled(false);
			boolean restored = step.isEnabled() && !sprint.isEnabled();
			step.setEnabled(false);
			check(off && restored, "Panic switches off and restores");
		});
	}

	/** Holding the FreeLook key turns the camera but not the player, and releasing it restores the view. */
	private static void freeLookTurnsOnlyTheCamera(ClientGameTestContext context) {
		CameraTweaks tweaks = module(CameraTweaks.class);
		int key = com.mojang.blaze3d.platform.InputConstants.KEY_G;
		context.runOnClient(mc -> {
			mc.player.setYRot(0);
			mc.player.setXRot(0);
			mc.options.setCameraType(net.minecraft.client.CameraType.FIRST_PERSON);
			tweaks.freeLookKey.set(key);
			tweaks.setEnabled(true);
		});
		context.getInput().holdKey(key);
		context.waitTicks(3);
		context.runOnClient(mc -> mc.player.turn(400, 0));
		context.waitTicks(2);
		context.takeScreenshot("freelook");
		float[] held = context.computeOnClient(mc -> new float[]{mc.player.getYRot(), mc.gameRenderer.mainCamera().yRot(),
			mc.options.getCameraType() == net.minecraft.client.CameraType.THIRD_PERSON_BACK ? 1 : 0});
		context.getInput().releaseKey(key);
		context.waitTicks(3);
		boolean restored = context.computeOnClient(mc -> mc.options.getCameraType() == net.minecraft.client.CameraType.FIRST_PERSON);
		context.runOnClient(mc -> {
			mc.player.turn(100, 0);
			tweaks.freeLookKey.reset();
			tweaks.setEnabled(false);
		});
		float turned = context.computeOnClient(mc -> mc.player.getYRot());
		LOG.info("FreeLook: player yaw {}, camera yaw {}, third person {}, restored {}, turn after release {}", held[0], held[1], held[2], restored, turned);
		check(held[0] == 0 && Math.abs(held[1] - 60) < 1 && held[2] == 1, "FreeLook turns the camera and leaves the player facing");
		check(restored && turned > 10, "FreeLook releases back to the player's view");
	}

	/** Switching AutoFly off and on again carries on along the same path; "Start a new path" forgets it. */
	private static void autoFlyResumes(ClientGameTestContext context, TestServerContext server) {
		server.runCommand("tp @a 0 -50 0 0 0");
		context.waitTicks(10);
		var fly = module(dev.coffeeclient.module.modules.movement.AutoFly.class);
		context.runOnClient(mc -> {
			mc.player.getAbilities().flying = true;
			mc.player.onUpdateAbilities();
			fly.radius.set(60.0);
			fly.setEnabled(true);
		});
		context.waitTicks(80);
		context.runOnClient(mc -> fly.setEnabled(false));
		context.waitTicks(10);
		context.runOnClient(mc -> fly.setEnabled(true));
		context.waitTicks(2);
		boolean resumed = context.computeOnClient(mc -> recentChat(mc, 5).stream().anyMatch(l -> l.contains("Resuming the paused path")));
		context.runOnClient(mc -> {
			fly.setEnabled(false);
			fly.newPath.run();
			fly.setEnabled(true);
		});
		context.waitTicks(2);
		boolean fresh = context.computeOnClient(mc -> recentChat(mc, 20).stream().filter(l -> l.contains("Resuming the paused path")).count() == 1);
		context.runOnClient(mc -> {
			fly.setEnabled(false);
			mc.player.getAbilities().flying = false;
			fly.radius.reset();
		});
		check(resumed, "AutoFly resumes the paused path when switched back on");
		check(fresh, "AutoFly starts a new path after \"Start a new path\"");
	}

	/** AutoFly steers a creative-flying player in a spiral, in and out, and switches itself off at the end. */
	private static void autoFlySpirals(ClientGameTestContext context, TestServerContext server) {
		var fly = module(dev.coffeeclient.module.modules.movement.AutoFly.class);
		for (var spin : dev.coffeeclient.module.modules.movement.AutoFly.Spin.values()) {
			server.runCommand("tp @a 0 -50 0 0 0");
			context.waitTicks(10);
			context.runOnClient(mc -> {
				mc.player.getAbilities().flying = true;
				mc.player.onUpdateAbilities();
				fly.radius.set(40.0);
				fly.spacing.set(8.0);
				fly.centerRadius.set(10.0);
				fly.spin.set(spin);
				fly.setEnabled(true);
			});
			double[] widest = {0};
			for (int i = 0; i < 90 && context.computeOnClient(mc -> fly.isEnabled()); i++) {
				context.waitTicks(20);
				context.runOnClient(mc -> widest[0] = Math.max(widest[0], Math.hypot(mc.player.getX() + 40, mc.player.getZ())));
			}
			double[] end = context.computeOnClient(mc -> new double[]{mc.player.getX(), mc.player.getZ(), fly.isEnabled() ? 1 : 0});
			LOG.info("AutoFly {} ended at {}, {} (still on: {}); widest {}", spin, end[0], end[1], end[2] == 1, widest[0]);
			check(end[2] == 0, "AutoFly " + spin + " switches itself off when the spiral is done");
			if (spin == dev.coffeeclient.module.modules.movement.AutoFly.Spin.In) {
				check(Math.hypot(end[0] + 40, end[1]) < 25, "AutoFly In ends near the spiral's centre");
			} else {
				check(Math.hypot(end[0] + 10, end[1]) > 30, "AutoFly Out ends on the outer lap");
			}
			context.runOnClient(mc -> mc.player.getAbilities().flying = false);
		}
		context.runOnClient(mc -> {
			fly.radius.reset();
			fly.spacing.reset();
			fly.centerRadius.reset();
			fly.spin.reset();
		});
	}

	/** COFFEE_TEST_ONLY=tracers-config: the config already in the run directory, untouched, with pigs, a zombie, a stash and a tunnel to look at. */
	private static void tracerConfigCheck(ClientGameTestContext context, TestServerContext server) {
		server.runCommand("summon minecraft:pig 6 -60 12 {NoAI:1b}");
		server.runCommand("summon minecraft:cow -4 -60 14 {NoAI:1b}");
		server.runCommand("summon minecraft:zombie 12 -60 6 {NoAI:1b}");
		server.runCommand("summon minecraft:creeper 2 -60 10 {NoAI:1b}");
		for (int i = 0; i < 6; i++) server.runCommand("setblock " + (22 + i) + " -60 28 minecraft:chest");
		server.runCommand("fill 20 -60 20 26 -56 24 minecraft:stone");
		server.runCommand("fill 21 -59 22 25 -58 22 minecraft:air");
		context.runOnClient(mc -> {
			String path = System.getenv("COFFEE_TEST_CONFIG");
			if (path != null) {
				var root = dev.coffeeclient.util.JsonFiles.read(Path.of(path));
				check(root != null, "the config under test loads");
				CoffeeClient.config().applySnapshot(root);
			}
			mc.player.setYRot(-30);
			mc.player.setXRot(0);
			StringBuilder sb = new StringBuilder("CONFIG enabled:");
			for (Module m : CoffeeClient.modules().all()) if (m.isEnabled()) sb.append(' ').append(m.name());
			LOG.info(sb.toString());
			EntityESP e = module(EntityESP.class);
			LOG.info("CONFIG EntityESP tracers={} style={} playersOnly={} filter={} passive={} hostile={}", e.tracers.get(), e.tracerStyle.get(), e.tracersPlayersOnly.get(), e.filter.get(), e.passive.get(), e.hostile.get());
		});
		context.waitTicks(100);
		context.takeScreenshot("tracers-config");
		context.runOnClient(mc -> mc.player.setYRot(-45));
		context.waitTicks(20);
		context.takeScreenshot("tracers-config-stash");
		// off the crosshair line, so a tracer to the stash is a visible line instead of a dot
		context.runOnClient(mc -> mc.player.setYRot(-15));
		context.waitTicks(10);
		context.takeScreenshot("tracers-config-stash-offset");
	}

	/** COFFEE_TEST_ONLY=tracers: smooth tracers for pigs, then StashFinder's stash and detector tracers, one at a time. */
	private static void tracerDiagnosis(ClientGameTestContext context, TestServerContext server) {
		server.runCommand("summon minecraft:pig 6 -60 12 {NoAI:1b}");
		server.runCommand("summon minecraft:pig -4 -60 14 {NoAI:1b}");
		server.runCommand("summon minecraft:pig 12 -60 6 {NoAI:1b}");
		context.runOnClient(mc -> {
			mc.player.setYRot(-30);
			mc.player.setXRot(0);
			EntityESP entities = module(EntityESP.class);
			entities.passive.set(true);
			entities.boxes.set(false);
			entities.tracers.set(true);
			entities.tracersPlayersOnly.set(false);
			entities.tracerStyle.set(Tracers.Style.Smooth);
			entities.setEnabled(true);
		});
		context.waitTicks(20);
		context.takeScreenshot("tracers-1-pigs");
		context.runOnClient(mc -> module(EntityESP.class).setEnabled(false));

		for (int i = 0; i < 5; i++) server.runCommand("setblock " + (10 + i) + " -60 10 minecraft:chest");
		context.runOnClient(mc -> {
			StashFinder finder = module(StashFinder.class);
			finder.containers.set(true);
			finder.minCount.set(4.0);
			finder.hideCloser.set(0.0);
			finder.stashTracers.set(true);
			finder.tracerStyle.set(Tracers.Style.Smooth);
			finder.chunkColumns.set(true);
			finder.setEnabled(true);
		});
		context.waitTicks(60);
		context.takeScreenshot("tracers-2-stash");

		server.runCommand("fill 20 -60 20 30 -56 24 minecraft:stone");
		server.runCommand("fill 21 -59 22 28 -58 22 minecraft:air");
		context.runOnClient(mc -> {
			StashFinder finder = module(StashFinder.class);
			finder.stashTracers.set(false);
			finder.chunkColumns.set(false);
			finder.tunnels.set(true);
			finder.detectorTracers.set(true);
		});
		context.waitTicks(80);
		context.takeScreenshot("tracers-3-detector");
		// everything at once, with fills, like a real session
		server.runCommand("setblock -3 -60 6 minecraft:gold_block");
		context.runOnClient(mc -> {
			StashFinder finder = module(StashFinder.class);
			finder.stashTracers.set(true);
			finder.chunkColumns.set(true);
			StorageESP storage = module(StorageESP.class);
			storage.shape.set(WorldOverlays.Shape.Both);
			storage.tracers.set(true);
			storage.tracerStyle.set(Tracers.Style.Smooth);
			storage.refresh.set(2.0);
			storage.setEnabled(true);
			BlockESP blocks = module(BlockESP.class);
			blocks.blocks.set(List.of("minecraft:gold_block"));
			blocks.refresh.set(2.0);
			blocks.tracers.set(true);
			blocks.tracerStyle.set(Tracers.Style.Smooth);
			blocks.setEnabled(true);
			EntityESP entities = module(EntityESP.class);
			entities.boxes.set(true);
			entities.boxShape.set(WorldOverlays.Shape.Both);
			entities.setEnabled(true);
			module(HoleESP.class).setEnabled(true);
		});
		context.waitTicks(40);
		context.takeScreenshot("tracers-4-everything");
		context.runOnClient(mc -> {
			for (Class<? extends Module> type : List.of(StashFinder.class, StorageESP.class, BlockESP.class, EntityESP.class, HoleESP.class)) module(type).setEnabled(false);
			StashFinder finder = module(StashFinder.class);
			finder.tunnels.reset();
			finder.detectorTracers.reset();
		});
	}

	/** Screenshots of the world renderers, for a look by eye (no reference images yet). */
	private static void renderers(ClientGameTestContext context, TestServerContext server) {
		server.runCommand("setblock 3 -60 6 minecraft:chest");
		server.runCommand("setblock 4 -60 6 minecraft:chest");
		server.runCommand("setblock -3 -60 6 minecraft:gold_block");
		server.runCommand("setblock -3 -59 6 minecraft:gold_block");
		server.runCommand("summon minecraft:pig 0 -60 8 {NoAI:1b}");
		context.runOnClient(mc -> {
			mc.player.setYRot(0);
			mc.player.setXRot(20);
			StorageESP storage = module(StorageESP.class);
			storage.shape.set(WorldOverlays.Shape.Both);
			storage.tracers.set(true);
			storage.refresh.set(2.0);
			storage.setEnabled(true);
			BlockESP blocks = module(BlockESP.class);
			blocks.blocks.set(List.of("minecraft:gold_block"));
			blocks.refresh.set(2.0);
			blocks.tracers.set(true);
			blocks.setEnabled(true);
			EntityESP entities = module(EntityESP.class);
			entities.passive.set(true);
			entities.boxes.set(true);
			entities.tracers.set(true);
			entities.tracersPlayersOnly.set(false);
			entities.setEnabled(true);
			for (Class<? extends Module> type : List.of(StorageESP.class, BlockESP.class, EntityESP.class)) tracerStyle(type).set(Tracers.Style.Classic);
		});
		context.waitTicks(10);
		context.takeScreenshot("esp-classic-tracers");
		context.runOnClient(mc -> {
			module(StorageESP.class).tracerStyle.set(Tracers.Style.Smooth);
			module(BlockESP.class).tracerStyle.set(Tracers.Style.Smooth);
			module(EntityESP.class).tracerStyle.set(Tracers.Style.Smooth);
		});
		context.waitTicks(2);
		context.takeScreenshot("esp-smooth-tracers");
		context.runOnClient(mc -> {
			for (Class<? extends Module> type : List.of(StorageESP.class, BlockESP.class, EntityESP.class)) module(type).setEnabled(false);
			module(XRay.class).setEnabled(true);
		});
		context.waitTicks(10);
		context.takeScreenshot("xray");
		context.runOnClient(mc -> {
			module(XRay.class).setEnabled(false);
			module(Trajectories.class).setEnabled(true);
			mc.player.setXRot(-15);
		});
		server.runCommand("item replace entity @a weapon.mainhand with minecraft:snowball 16");
		context.waitTicks(10);
		context.takeScreenshot("trajectories");
		context.runOnClient(mc -> module(Trajectories.class).colorByOwner.set(true));
		context.waitTicks(2);
		context.takeScreenshot("trajectories-owner-colors");
		context.runOnClient(mc -> module(Trajectories.class).setEnabled(false));
	}

	/**
	 * Cost of drawing a large BlockESP set (every grass block within 64: the per-frame rebuild the
	 * plan wanted measured before adding cached geometry). Logged, not asserted: it depends on the machine.
	 */
	private static void bigBlockEspCost(ClientGameTestContext context) {
		context.runOnClient(mc -> {
			BlockESP blocks = module(BlockESP.class);
			blocks.blocks.set(List.of("minecraft:grass_block"));
			blocks.radius.set(64.0);
			blocks.tracers.set(false);
			blocks.shape.set(WorldOverlays.Shape.Both);
			blocks.setEnabled(true);
		});
		context.waitTicks(40);
		for (int i = 0; i < 100; i++) {
			context.runOnClient(mc -> dev.coffeeclient.module.ModuleProfiler.keepAlive());
			context.waitTick();
		}
		double renderMs = context.computeOnClient(mc -> module(BlockESP.class).profilerStats().ms(dev.coffeeclient.module.ModuleProfiler.Phase.RENDER));
		int fps = context.computeOnClient(mc -> mc.getFps());
		String size = context.computeOnClient(mc -> module(BlockESP.class).infoString());
		LOG.info("PERF BlockESP with {} blocks: {} ms render per second at {} fps ({} ms per frame)", size,
			String.format(java.util.Locale.ROOT, "%.2f", renderMs), fps, String.format(java.util.Locale.ROOT, "%.3f", fps > 0 ? renderMs / fps : 0));
		context.takeScreenshot("blockesp-large");
		context.runOnClient(mc -> module(BlockESP.class).setEnabled(false));
	}

	/** EntityESP tracers to a crowd of 300 mobs, classic vs smooth (logged, machine-dependent). */
	private static void crowdTracerCost(ClientGameTestContext context, TestServerContext server) {
		for (int i = 0; i < 300; i++) {
			server.runCommand("summon minecraft:pig " + (i % 20 - 10) + " -60 " + (12 + i / 20) + " {NoAI:1b}");
		}
		context.runOnClient(mc -> {
			EntityESP esp = module(EntityESP.class);
			esp.passive.set(true);
			esp.boxes.set(false);
			esp.tracers.set(true);
			esp.tracersPlayersOnly.set(false);
			esp.maxTargets.set(1000.0);
			esp.setEnabled(true);
			mc.player.setXRot(10);
		});
		for (Tracers.Style style : Tracers.Style.values()) {
			context.runOnClient(mc -> module(EntityESP.class).tracerStyle.set(style));
			context.waitTicks(20);
			for (int i = 0; i < 100; i++) {
				context.runOnClient(mc -> dev.coffeeclient.module.ModuleProfiler.keepAlive());
				context.waitTick();
			}
			double ms = context.computeOnClient(mc -> module(EntityESP.class).profilerStats().ms(dev.coffeeclient.module.ModuleProfiler.Phase.RENDER));
			int fps = context.computeOnClient(mc -> mc.getFps());
			LOG.info("PERF EntityESP {} tracers to 300 pigs: {} ms render per second at {} fps", style,
				String.format(java.util.Locale.ROOT, "%.2f", ms), fps);
			context.takeScreenshot("crowd-" + style.name().toLowerCase(java.util.Locale.ROOT));
			// Turned away: tracers must still lead off the screen edge towards the crowd.
			context.runOnClient(mc -> mc.player.setYRot(180));
			context.waitTicks(2);
			context.takeScreenshot("crowd-behind-" + style.name().toLowerCase(java.util.Locale.ROOT));
			context.runOnClient(mc -> mc.player.setYRot(0));
		}
		context.runOnClient(mc -> module(EntityESP.class).setEnabled(false));
	}

	/** A macro turns a module on, waits, presses a control, then turns the module off again. */
	private static void macrosRun(ClientGameTestContext context) {
		var macro = context.computeOnClient(mc -> {
			var m = CoffeeClient.macros().add("Test macro");
			m.trigger = dev.coffeeclient.macro.MacroTrigger.MANUAL;
			m.steps.clear();
			var on = new dev.coffeeclient.macro.MacroStep(dev.coffeeclient.macro.MacroStep.Type.MODULE);
			on.set("module", "Step");
			on.set("action", "Enable");
			var hold = new dev.coffeeclient.macro.MacroStep(dev.coffeeclient.macro.MacroStep.Type.KEY);
			hold.set("key", "key.sneak");
			hold.set("mode", "Hold");
			hold.set("ticks", "6");
			var off = new dev.coffeeclient.macro.MacroStep(dev.coffeeclient.macro.MacroStep.Type.MODULE);
			off.set("module", "Step");
			off.set("action", "Disable");
			m.steps.addAll(List.of(on, hold, off));
			CoffeeClient.macros().run(m);
			return m;
		});
		context.waitTicks(3);
		boolean midway = context.computeOnClient(mc -> module(Step.class).isEnabled() && mc.options.keyShift.isDown());
		context.waitTicks(10);
		boolean done = context.computeOnClient(mc -> !module(Step.class).isEnabled() && !mc.options.keyShift.isDown()
			&& !CoffeeClient.macros().isRunning(macro));
		check(midway && done, "macro steps run in order (midway " + midway + ", finished " + done + ")");
	}

	/** If / Else if / Else picks the branch that holds, and Repeat runs its block the set number of times. */
	private static void macrosControlFlow(ClientGameTestContext context) {
		var macro = context.computeOnClient(mc -> {
			module(Step.class).setEnabled(false);
			CoffeeClient.modules().byName("TickMonitor").orElseThrow().setEnabled(false);
			var m = CoffeeClient.macros().add("Flow macro");
			m.trigger = dev.coffeeclient.macro.MacroTrigger.MANUAL;
			m.steps.clear();
			m.steps.add(macroStep("IF", "not", "Not", "condition", "MODULE_OFF", "arg", "TickMonitor"));
			m.steps.add(macroStep("MODULE", "module", "TickMonitor", "action", "Enable"));
			m.steps.add(macroStep("ELSE_IF", "condition", "MODULE_OFF", "arg", "TickMonitor"));
			m.steps.add(macroStep("REPEAT", "mode", "Times", "count", "3"));
			m.steps.add(macroStep("MODULE", "module", "Step", "action", "Toggle"));
			m.steps.add(macroStep("END"));
			m.steps.add(macroStep("ELSE"));
			m.steps.add(macroStep("MODULE", "module", "TickMonitor", "action", "Enable"));
			m.steps.add(macroStep("END"));
			CoffeeClient.macros().run(m);
			return m;
		});
		context.waitTicks(10);
		boolean ok = context.computeOnClient(mc -> module(Step.class).isEnabled()
			&& !CoffeeClient.modules().byName("TickMonitor").orElseThrow().isEnabled() && !CoffeeClient.macros().isRunning(macro));
		check(ok, "macro If / Else if / Else and Repeat take the right branch");
		context.runOnClient(mc -> {
			module(Step.class).setEnabled(false);
			CoffeeClient.macros().remove(macro);
		});
	}

	private static dev.coffeeclient.macro.MacroStep macroStep(String type, String... keyValues) {
		var step = new dev.coffeeclient.macro.MacroStep(dev.coffeeclient.macro.MacroStep.Type.valueOf(type));
		for (int i = 0; i < keyValues.length; i += 2) step.set(keyValues[i], keyValues[i + 1]);
		return step;
	}

	/** Friend rules: per-friend overrides beat the defaults; session friends leave with the session. */
	private static void friendsRules(ClientGameTestContext context) {
		context.runOnClient(mc -> {
			var friends = CoffeeClient.friends();
			var bob = friends.add("TestBob", null, dev.coffeeclient.friend.Friend.PERMANENT);
			friends.add("TestTemp", null, dev.coffeeclient.friend.Friend.SESSION);
			friends.setOverride(bob, dev.coffeeclient.friend.FriendTrait.PROTECTED, false);
			boolean overridden = !dev.coffeeclient.module.modules.misc.Friends.is("testbob", dev.coffeeclient.friend.FriendTrait.PROTECTED)
				&& dev.coffeeclient.module.modules.misc.Friends.is("TestBob", dev.coffeeclient.friend.FriendTrait.HIGHLIGHTED);
			boolean temp = dev.coffeeclient.module.modules.misc.Friends.isFriend("TestTemp");
			check(overridden && temp, "friend rules and temporary friends (override " + overridden + ", temp " + temp + ")");
		});
	}

	/** ChatEffects rewrites outgoing text; ChatTweaks stacks a similar repeat into one line with a counter. */
	@SuppressWarnings("unchecked")
	private static void chatTools(ClientGameTestContext context) {
		context.runOnClient(mc -> {
			ChatEffects fx = module(ChatEffects.class);
			fx.textCase.set(ChatEffects.Case.LOWER);
			fx.leet.set(true);
			fx.setEnabled(true);
			String out = ChatEffects.rewrite("Hello Test");
			fx.setEnabled(false);
			fx.textCase.reset();
			fx.leet.reset();
			check(out.equals("h3ll0 7357"), "ChatEffects rewrites outgoing chat (" + out + ")");

			ChatTweaks filter = module(ChatTweaks.class);
			boolean was = filter.isEnabled();
			filter.setEnabled(true);
			var chat = mc.gui.hud.getChat();
			chat.addClientSystemMessage(Component.literal("Spam test 12"));
			chat.addClientSystemMessage(Component.literal("spam  TEST 345"));
			List<GuiMessage> messages;
			try {
				var field = ChatComponent.class.getDeclaredField("allMessages");
				field.setAccessible(true);
				messages = (List<GuiMessage>) field.get(chat);
			} catch (ReflectiveOperationException e) {
				throw new AssertionError(e);
			}
			String newest = messages.getFirst().content().getString();
			boolean oldGone = messages.size() < 2 || !messages.get(1).content().getString().contains("Spam test 12");
			filter.setEnabled(was);
			check(newest.endsWith("[x2]") && oldGone, "AntiSpam stacks similar repeats (" + newest + ")");
		});
	}

	/** Spoofs swaps the brand; NoEffect's steady FOV ignores Speed. */
	private static void spoofsAndView(ClientGameTestContext context, TestServerContext server) {
		context.runOnClient(mc -> {
			Spoofs spoofs = module(Spoofs.class);
			spoofs.setEnabled(true);
			String brand = Spoofs.brand("fabric");
			check(brand.equals("vanilla"), "Spoofs sends the vanilla brand (" + brand + ")");

			// synthetic packets through the send/receive events, as the connection would post them
			var connection = mc.getConnection().getConnection();
			java.util.function.Function<net.minecraft.network.protocol.Packet<?>, net.minecraft.network.protocol.Packet<?>> send = p -> {
				var e = dev.coffeeclient.core.CoffeeClient.events().post(new dev.coffeeclient.core.Events.PacketSend(p, connection));
				return e.isCancelled() ? null : e.packet;
			};
			java.util.function.Function<java.util.List<net.minecraft.resources.Identifier>, net.minecraft.network.protocol.Packet<?>> register = ids ->
				new net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket(new net.fabricmc.fabric.impl.networking.RegistrationPayload(
					net.fabricmc.fabric.impl.networking.RegistrationPayload.REGISTER, ids));
			var fabricId = net.minecraft.resources.Identifier.parse("fabric:test_channel");
			var otherId = net.minecraft.resources.Identifier.parse("someplugin:hello");
			var sentBrand = send.apply(new net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket(
				new net.minecraft.network.protocol.common.custom.BrandPayload("fabric")));
			check(sentBrand instanceof net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket(
				net.minecraft.network.protocol.common.custom.BrandPayload(String b)) && b.equals("vanilla"), "Spoofs rewrites brand payloads");
			var pruned = send.apply(register.apply(java.util.List.of(fabricId, otherId)));
			check(pruned instanceof net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket(
					net.fabricmc.fabric.impl.networking.RegistrationPayload r) && r.channels().equals(java.util.List.of(otherId)),
				"Spoofs prunes mod channels from registrations (" + pruned + ")");
			check(send.apply(new net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket(
				new net.minecraft.network.protocol.common.custom.DiscardedPayload(fabricId))) == null, "Spoofs drops mod payloads");
			spoofs.channelRules.set(java.util.List.of("allow fabric:test_channel"));
			check(send.apply(register.apply(java.util.List.of(fabricId))) != null, "Spoofs channel rules allow a channel");
			spoofs.channelRules.reset();
			spoofs.modChannels.set(Spoofs.Privacy.VANILLA_ONLY);
			check(send.apply(register.apply(java.util.List.of(otherId))) == null, "Spoofs Vanilla only hides unknown channels");
			spoofs.modChannels.reset();

			dev.coffeeclient.core.CoffeeClient.events().post(new dev.coffeeclient.core.Events.PacketReceive(
				new net.minecraft.network.protocol.login.ClientboundCustomQueryPacket(4242,
					new net.minecraft.network.protocol.login.custom.DiscardedQueryPayload(fabricId)), connection));
			var answer = send.apply(new net.minecraft.network.protocol.login.ServerboundCustomQueryAnswerPacket(4242,
				new net.minecraft.network.protocol.login.custom.DiscardedQueryAnswerPayload()));
			check(answer instanceof net.minecraft.network.protocol.login.ServerboundCustomQueryAnswerPacket a && a.payload() == null,
				"Spoofs answers mod login probes empty");

			var probe = net.minecraft.network.chat.Component.translatable("key.coffeeclient.click_gui");
			String filled = Spoofs.vanillaText(probe, "anvil");
			check(filled.equals("key.coffeeclient.click_gui") && !probe.getString().equals(filled),
				"Spoofs fills mod translations as vanilla (" + probe.getString() + " -> " + filled + ")");
			spoofs.setEnabled(false);
			// mixins apply on class load: make a bad anvil or sign injection fail here, not in game
			for (String c : new String[] {"net.minecraft.client.gui.screens.inventory.AnvilScreen", "net.minecraft.client.gui.screens.inventory.SignEditScreen"}) {
				try {
					Class.forName(c);
				} catch (Throwable t) {
					check(false, "Spoofs text-probe mixin applies to " + c + " (" + t + ")");
				}
			}
		});
		server.runCommand("effect give @a minecraft:speed 30 3");
		context.waitTicks(3);
		float[] fov = context.computeOnClient(mc -> {
			NoEffect noEffect = module(NoEffect.class);
			float vanilla = mc.player.getFieldOfViewModifier(true, 1f);
			noEffect.fov.set(NoEffect.Fov.STEADY);
			noEffect.setEnabled(true);
			float steady = mc.player.getFieldOfViewModifier(true, 1f);
			noEffect.setEnabled(false);
			noEffect.fov.reset();
			return new float[] {vanilla, steady};
		});
		server.runCommand("effect clear @a");
		check(fov[0] > 1.05f && fov[1] == 1f, "NoEffect steady FOV ignores Speed (" + fov[0] + " -> " + fov[1] + ")");
	}

	/** Walking forward with Speed (Vanilla, 12 b/s) covers far more ground than without. */
	private static void speedMovesFaster(ClientGameTestContext context, TestServerContext server) {
		double normal = walk(context, server);
		context.runOnClient(mc -> {
			Speed speed = module(Speed.class);
			speed.vanillaSpeed.set(12.0);
			speed.setEnabled(true);
		});
		double fast = walk(context, server);
		context.runOnClient(mc -> {
			Speed speed = module(Speed.class);
			speed.setEnabled(false);
			speed.vanillaSpeed.reset();
		});
		check(fast > normal * 2 && fast > 8, String.format(java.util.Locale.ROOT, "Speed moves faster (%.1f vs %.1f blocks)", fast, normal));
	}

	/** Blocks walked in one second holding forward, from the spawn point facing +Z. */
	private static double walk(ClientGameTestContext context, TestServerContext server) {
		server.runCommand("tp @a 0 -60 0 0 0");
		// The teleport counts as a setback, and Speed pauses for a second after one.
		context.waitTicks(30);
		double start = context.computeOnClient(mc -> mc.player.getZ());
		context.getInput().holdKey(options -> options.keyUp);
		context.waitTicks(20);
		context.getInput().releaseKey(options -> options.keyUp);
		double end = context.computeOnClient(mc -> mc.player.getZ());
		context.waitTicks(10);
		return end - start;
	}

	/** KillAura hits a nearby zombie with rotations on; the server's death event reaches the client. */
	private static void killAuraHits(ClientGameTestContext context, TestServerContext server) {
		server.runCommand("tp @a 0 -60 0 0 0");
		server.runCommand("summon minecraft:zombie 0 -60 2 {NoAI:1b,Tags:[\"coffee\"]}");
		context.waitTicks(10);
		AtomicBoolean died = new AtomicBoolean();
		java.util.function.Consumer<Events.EntityDeath> onDeath = e -> {
			if (e.entity() instanceof net.minecraft.world.entity.monster.zombie.Zombie) died.set(true);
		};
		context.runOnClient(mc -> {
			CoffeeClient.events().subscribe(Events.EntityDeath.class, onDeath);
			KillAura aura = module(KillAura.class);
			aura.entities.set(List.of("minecraft:zombie"));
			aura.attackWhenHolding.set(KillAura.AttackItems.ALL);
			aura.setEnabled(true);
		});
		context.waitTicks(40);
		float health = server.computeOnServer(s -> {
			var level = s.overworld();
			for (var e : level.getAllEntities()) {
				if (e instanceof net.minecraft.world.entity.monster.zombie.Zombie z && z.entityTags().contains("coffee")) return z.getHealth();
			}
			return 0f;
		});
		context.runOnClient(mc -> {
			KillAura aura = module(KillAura.class);
			aura.setEnabled(false);
			aura.entities.reset();
			aura.attackWhenHolding.reset();
		});
		check(health < 20, "KillAura hits the zombie (health " + health + ")");
		server.runCommand("kill @e[tag=coffee]");
		context.waitTicks(5);
		context.runOnClient(mc -> CoffeeClient.events().unsubscribe(Events.EntityDeath.class, onDeath));
		check(died.get(), "entity death event reaches the client");
	}

	/**
	 * Server packets are posted as they apply, bundled ones included (an entity spawn comes in a
	 * bundle); a barrier's answer gives Latency a round trip; a batch's packets all reach the server.
	 */
	private static void packetApplyAndLatency(ClientGameTestContext context, TestServerContext server) {
		AtomicBoolean spawnSeen = new AtomicBoolean(), ackSeen = new AtomicBoolean();
		java.util.function.Consumer<Events.PacketApply.Pre> onApply = e -> {
			if (e.packet instanceof net.minecraft.network.protocol.game.ClientboundAddEntityPacket) spawnSeen.set(true);
			if (e.packet instanceof net.minecraft.network.protocol.game.ClientboundBlockChangedAckPacket) ackSeen.set(true);
		};
		context.runOnClient(mc -> {
			CoffeeClient.events().subscribe(Events.PacketApply.Pre.class, onApply);
			Inv.barrier();
		});
		server.runCommand("summon minecraft:armor_stand 3 -60 3 {Tags:[\"coffee\"]}");
		context.waitTicks(10);
		int rtt = context.computeOnClient(mc -> {
			CoffeeClient.events().unsubscribe(Events.PacketApply.Pre.class, onApply);
			PacketBatch.run(() -> {
				Inv.swap(3, false);
				Inv.swap(6, false);
			});
			return dev.coffeeclient.util.Latency.rtt();
		});
		context.waitTicks(3);
		int serverSlot = server.computeOnServer(s -> s.getPlayerList().getPlayers().getFirst().getInventory().getSelectedSlot());
		context.runOnClient(mc -> Inv.swap(0, false));
		server.runCommand("kill @e[tag=coffee]");
		check(spawnSeen.get() && ackSeen.get(), "PacketApply sees bundled spawns and block acks (spawn " + spawnSeen.get() + ", ack " + ackSeen.get() + ")");
		check(rtt < 150, "Latency measures the barrier's round trip (" + rtt + " ms)");
		check(serverSlot == 6, "PacketBatch sends every packet of a batch (server slot " + serverSlot + ")");
	}

	/**
	 * Strict AutoTotem moves a totem into the offhand, refills it the moment one pops, and Notifier
	 * reports the pop.
	 */
	private static void autoTotemRefills(ClientGameTestContext context, TestServerContext server) {
		server.runCommand("gamemode survival @a");
		server.runCommand("item replace entity @a inventory.0 with minecraft:totem_of_undying 1");
		server.runCommand("item replace entity @a inventory.1 with minecraft:totem_of_undying 1");
		context.waitTicks(5);
		context.runOnClient(mc -> {
			AutoTotem totem = module(AutoTotem.class);
			totem.mode.set(AutoTotem.Mode.Strict);
			totem.setEnabled(true);
			Notifier notifier = module(Notifier.class);
			notifier.includeSelf.set(true);
			notifier.setEnabled(true);
		});
		context.waitTicks(10);
		boolean equipped = server.computeOnServer(s -> s.getPlayerList().getPlayers().getFirst().getOffhandItem().is(Items.TOTEM_OF_UNDYING));
		server.runCommand("damage @p 100 minecraft:generic");
		context.waitTicks(10);
		int[] state = server.computeOnServer(s -> {
			var player = s.getPlayerList().getPlayers().getFirst();
			int count = 0;
			for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
				if (player.getInventory().getItem(i).is(Items.TOTEM_OF_UNDYING)) count += player.getInventory().getItem(i).getCount();
			}
			return new int[] {player.getOffhandItem().is(Items.TOTEM_OF_UNDYING) ? 1 : 0, count, player.isAlive() ? 1 : 0};
		});
		List<String> chat = context.computeOnClient(mc -> recentChat(mc, 5));
		context.runOnClient(mc -> {
			AutoTotem totem = module(AutoTotem.class);
			totem.setEnabled(false);
			totem.mode.reset();
			Notifier notifier = module(Notifier.class);
			notifier.setEnabled(false);
			notifier.includeSelf.reset();
		});
		server.runCommand("clear @a minecraft:totem_of_undying");
		server.runCommand("effect clear @a");
		server.runCommand("gamemode creative @a");
		server.runCommand("effect give @a minecraft:instant_health 1 5");
		check(equipped, "AutoTotem fills the offhand");
		check(state[2] == 1 && state[0] == 1 && state[1] == 1, "AutoTotem refills after a pop (offhand " + state[0] + ", totems left " + state[1] + ")");
		check(chat.stream().anyMatch(l -> l.contains("popped a totem")), "Notifier reports the pop (" + (chat.isEmpty() ? "" : chat.getFirst()) + ")");
	}

	/** AutoDisable switches its modules off when the server sets you back. */
	private static void autoDisableOnSetback(ClientGameTestContext context, TestServerContext server) {
		context.runOnClient(mc -> {
			AutoDisable auto = module(AutoDisable.class);
			auto.modules.set(List.of("KillAura"));
			auto.setEnabled(true);
			module(KillAura.class).setEnabled(true);
		});
		context.waitTicks(2);
		server.runCommand("tp @a 0 -60 4");
		context.waitTicks(5);
		boolean off = context.computeOnClient(mc -> !module(KillAura.class).isEnabled());
		context.runOnClient(mc -> {
			AutoDisable auto = module(AutoDisable.class);
			auto.setEnabled(false);
			auto.modules.reset();
			module(KillAura.class).setEnabled(false);
		});
		server.runCommand("tp @a 0 -60 0 0 0");
		check(off, "AutoDisable switches KillAura off on a setback");
	}

	/** NoSlowBreak mines as fast in the air as on the ground. */
	private static void noSlowBreakInAir(ClientGameTestContext context) {
		float[] speeds = context.computeOnClient(mc -> {
			var stone = Blocks.STONE.defaultBlockState();
			boolean ground = mc.player.onGround();
			mc.player.setOnGround(false);
			float air = mc.player.getDestroySpeed(stone);
			NoSlowBreak module = module(NoSlowBreak.class);
			module.setEnabled(true);
			float fixed = mc.player.getDestroySpeed(stone);
			module.setEnabled(false);
			mc.player.setOnGround(true);
			float onGround = mc.player.getDestroySpeed(stone);
			mc.player.setOnGround(ground);
			return new float[] {air, fixed, onGround};
		});
		check(speeds[0] < speeds[2] && speeds[1] == speeds[2], "NoSlowBreak mines at ground speed in the air (" + speeds[0] + " -> " + speeds[1] + ")");
	}

	/** Middle-clicking a player adds them as a friend; clicking again removes them. */
	private static void friendsMiddleClick(ClientGameTestContext context, TestServerContext server) {
		FakePlayer fakes = module(FakePlayer.class);
		// The bot spawns where you stand: be at 0,0 first, then step 3 blocks back facing it.
		server.runCommand("tp @a 0 -60 0 0 0");
		context.waitTicks(5);
		context.runOnClient(mc -> {
			fakes.setEnabled(true);
			fakes.spawn.run();
		});
		server.runCommand("tp @a 0 -60 -3 0 0");
		context.waitTicks(5);
		String[] where = {""};
		boolean[] state = context.computeOnClient(mc -> {
			String name = fakes.name.get();
			var bot = mc.level.players().stream().filter(p -> p instanceof dev.coffeeclient.util.fakeplayer.FakePlayerEntity).findFirst();
			where[0] = "me " + mc.player.position() + " yaw " + mc.player.getYRot() + " pitch " + mc.player.getXRot()
				+ ", bot " + bot.map(b -> b.getName().getString() + " " + b.position()).orElse("none");
			CoffeeClient.events().post(new Events.MouseClick(org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_MIDDLE, org.lwjgl.glfw.GLFW.GLFW_PRESS, 0));
			boolean added = dev.coffeeclient.module.modules.misc.Friends.isFriend(name);
			CoffeeClient.events().post(new Events.MouseClick(org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_MIDDLE, org.lwjgl.glfw.GLFW.GLFW_PRESS, 0));
			boolean removed = !dev.coffeeclient.module.modules.misc.Friends.isFriend(name);
			fakes.clear.run();
			fakes.setEnabled(false);
			return new boolean[] {added, removed};
		});
		server.runCommand("tp @a 0 -60 0 0 0");
		check(state[0] && state[1], "Friends: middle click adds and removes (added " + state[0] + ", removed " + state[1] + "; " + where[0] + ")");
	}

	/** CrystalAura places a crystal on obsidian next to a zombie and blows it up. */
	private static void crystalAuraHits(ClientGameTestContext context, TestServerContext server) {
		server.runCommand("tp @a 0 -60 0 0 0");
		// KillAura's zombie dropped loot here; an item on the obsidian would block the crystal, as in vanilla.
		server.runCommand("kill @e[type=minecraft:item]");
		server.runCommand("setblock 1 -61 2 minecraft:obsidian");
		server.runCommand("summon minecraft:zombie 0 -60 3 {NoAI:1b,Tags:[\"coffee\"]}");
		server.runCommand("item replace entity @a hotbar.0 with minecraft:end_crystal 8");
		// Resistance V: the blast this close would otherwise hurt you, and anti suicide would refuse it.
		server.runCommand("effect give @a minecraft:resistance 30 4");
		context.waitTicks(10);
		context.runOnClient(mc -> {
			CrystalAura aura = module(CrystalAura.class);
			aura.entities.set(List.of("minecraft:zombie"));
			aura.minDamage.set(1.0);
			aura.setEnabled(true);
		});
		context.waitTicks(40);
		float health = server.computeOnServer(s -> {
			for (var e : s.overworld().getAllEntities()) {
				if (e instanceof net.minecraft.world.entity.monster.zombie.Zombie z && z.entityTags().contains("coffee")) return z.getHealth();
			}
			return 0f;
		});
		String status = context.computeOnClient(mc -> module(CrystalAura.class).infoString() + ", crystals "
			+ mc.level.getEntitiesOfClass(net.minecraft.world.entity.boss.enderdragon.EndCrystal.class, mc.player.getBoundingBox().inflate(8)).size()
			+ ", held " + mc.player.getMainHandItem().getHoverName().getString());
		context.runOnClient(mc -> {
			CrystalAura aura = module(CrystalAura.class);
			aura.setEnabled(false);
			aura.entities.reset();
			aura.minDamage.reset();
		});
		server.runCommand("kill @e[tag=coffee]");
		server.runCommand("kill @e[type=minecraft:end_crystal]");
		server.runCommand("effect clear @a");
		server.runCommand("setblock 1 -61 2 minecraft:grass_block");
		server.runCommand("item replace entity @a hotbar.0 with minecraft:air");
		// Burning in daylight costs a point or two; a crystal blast this close kills.
		check(health < 10, "CrystalAura places and breaks a crystal (zombie health " + health + "; " + status + ")");
	}

	/**
	 * In the menus: the vault round-trips a secret, an offline account switches the session and the
	 * launch account comes back, the proxy manager hands out a handler only while one is in use. Also
	 * screenshots of the Accounts and Proxies windows and both title screens.
	 */
	private static void accountsAndProxies(ClientGameTestContext context) {
		context.runOnClient(mc -> {
			String sealed = dev.coffeeclient.account.Vault.seal("secret-token");
			check(sealed.startsWith("v1:") && !sealed.contains("secret") && "secret-token".equals(dev.coffeeclient.account.Vault.open(sealed)),
				"vault seals and opens a secret");
		});
		String launchName = context.computeOnClient(mc -> mc.getUser().getName());
		var alt = context.computeOnClient(mc -> CoffeeClient.accounts().addOffline("CoffeeAlt"));
		context.computeOnClient(mc -> CoffeeClient.accounts().login(alt)).join();
		String switched = context.computeOnClient(mc -> mc.getUser().getName());
		context.runOnClient(mc -> CoffeeClient.accounts().restoreOriginal());
		String restored = context.computeOnClient(mc -> mc.getUser().getName());
		check("CoffeeAlt".equals(switched) && launchName.equals(restored),
			"account switch and restore (" + launchName + " -> " + switched + " -> " + restored + ")");

		context.runOnClient(mc -> {
			var proxies = CoffeeClient.proxies();
			var proxy = proxies.add(new dev.coffeeclient.proxy.Proxy("Test proxy", dev.coffeeclient.proxy.Proxy.Type.SOCKS5, "127.0.0.1", 1, "user", "pass"));
			boolean idle = proxies.handler() == null;
			proxies.setActive(proxy);
			boolean active = proxies.handler() instanceof io.netty.handler.proxy.Socks5ProxyHandler;
			proxies.setActive(null);
			check(idle && active && proxies.handler() == null, "proxy handler only while a proxy is in use");
			mc.gui.setScreen(new dev.coffeeclient.gui.screen.ProxiesScreen(null));
		});
		context.waitTicks(8);
		context.takeScreenshot("window-proxies");
		context.runOnClient(mc -> mc.gui.setScreen(new dev.coffeeclient.gui.screen.AccountsScreen(null)));
		context.waitTicks(8);
		context.takeScreenshot("window-accounts");
		context.runOnClient(mc -> mc.gui.setScreen(new dev.coffeeclient.gui.screen.CoffeeTitleScreen()));
		// Past the logo intro and fade-in, so the shot shows the settled screen with its icons.
		context.waitTicks(60);
		context.takeScreenshot("title-coffee");
		context.runOnClient(mc -> mc.gui.setScreen(new net.minecraft.client.gui.screens.TitleScreen()));
		context.waitTicks(20);
		context.takeScreenshot("title-vanilla");
		context.runOnClient(mc -> {
			CoffeeClient.accounts().remove(alt);
			for (var p : CoffeeClient.proxies().all()) if ("Test proxy".equals(p.name)) CoffeeClient.proxies().remove(p);
		});
	}

	/** Screenshots of the Macros, Friends and Dashboard windows with content. */
	private static void windows(ClientGameTestContext context) {
		context.runOnClient(mc -> {
			var m = CoffeeClient.macros().byName("Test macro");
			var wait = new dev.coffeeclient.macro.MacroStep(dev.coffeeclient.macro.MacroStep.Type.WAIT_UNTIL);
			wait.set("condition", "HEALTH_BELOW");
			wait.set("arg", "10");
			m.steps.add(1, wait);
			var say = new dev.coffeeclient.macro.MacroStep(dev.coffeeclient.macro.MacroStep.Type.CHAT);
			say.set("text", "Hello {player}");
			m.steps.add(0, say);
			// A block, so the screenshot shows the indented steps.
			m.steps.add(1, macroStep("IF", "not", "Not", "condition", "HOLDING", "arg", "sword"));
			m.steps.add(macroStep("ELSE"));
			m.steps.add(macroStep("LOCAL", "text", "Sword in hand"));
			m.steps.add(macroStep("END"));
			mc.gui.setScreen(new dev.coffeeclient.gui.screen.MacrosScreen(null, m));
		});
		context.waitTicks(8);
		context.takeScreenshot("window-macros");
		context.runOnClient(mc -> mc.gui.setScreen(new dev.coffeeclient.gui.screen.FriendsScreen(null, CoffeeClient.friends().get("TestBob"))));
		context.waitTicks(8);
		context.takeScreenshot("window-friends");
		context.runOnClient(mc -> mc.gui.setScreen(new dev.coffeeclient.gui.screen.FriendsScreen(null)));
		context.waitTicks(8);
		context.takeScreenshot("window-friend-rules");
		// Coffee's title screen with a button hovered: the highlight must stay inside the button.
		context.runOnClient(mc -> mc.gui.setScreen(new dev.coffeeclient.gui.screen.CoffeeTitleScreen()));
		context.waitTicks(20);
		int[] window = context.computeOnClient(mc -> new int[] {mc.getWindow().getScreenWidth(), mc.getWindow().getScreenHeight()});
		context.getInput().setCursorPos(window[0] / 2.0, window[1] * 0.62);
		context.waitTicks(10);
		context.takeScreenshot("window-title-hover");
		context.runOnClient(mc -> mc.gui.setScreen(new dev.coffeeclient.gui.screen.DashboardScreen(null)));
		context.waitTicks(8);
		context.takeScreenshot("window-dashboard");
		// A module window scrolled to a button row.
		context.runOnClient(mc -> mc.gui.setScreen(new dev.coffeeclient.gui.screen.ModuleScreen(null, module(NewChunks.class))));
		context.waitTicks(8);
		context.getInput().setCursorPos(window[0] * 0.3, window[1] * 0.6);
		context.getInput().scroll(-4);
		context.waitTicks(6);
		context.takeScreenshot("window-module-buttons");
		context.runOnClient(mc -> {
			mc.gui.setScreen(null);
			CoffeeClient.macros().remove(CoffeeClient.macros().byName("Test macro"));
			CoffeeClient.friends().remove("TestBob");
			CoffeeClient.friends().remove("TestTemp");
		});
	}
}
