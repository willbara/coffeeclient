package dev.coffeeclient.module.modules.render;

import dev.coffeeclient.module.Category;
import dev.coffeeclient.module.Module;
import dev.coffeeclient.setting.BlockListSetting;
import dev.coffeeclient.setting.BooleanSetting;
import dev.coffeeclient.setting.EnumSetting;
import dev.coffeeclient.setting.IdListSetting;
import dev.coffeeclient.setting.NumberSetting;
import dev.coffeeclient.setting.StringSetting;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.shapes.Shapes;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Renders only the listed blocks (ores by default); everything else is drawn see-through at a set
 * opacity, or not at all.
 *
 * <p>Chunk meshes are built on worker threads, so the hooks read a snapshot ({@link #state}) that is
 * replaced on the client thread whenever a setting changes, and every change rebuilds the chunks.
 * The hooks live in ModelBlockRendererMixin and FluidRendererMixin (vanilla), the Indigo and Sodium
 * compat mixins, and the lightmap/occlusion/shading mixins (full light, no cave culling).
 */
public final class XRay extends Module {
	public enum FluidOpacity { None, Water, Lava, Both }

	public static final List<String> ORES = List.of(
		"minecraft:coal_ore", "minecraft:deepslate_coal_ore", "minecraft:iron_ore", "minecraft:deepslate_iron_ore",
		"minecraft:gold_ore", "minecraft:deepslate_gold_ore", "minecraft:lapis_ore", "minecraft:deepslate_lapis_ore",
		"minecraft:redstone_ore", "minecraft:deepslate_redstone_ore", "minecraft:diamond_ore", "minecraft:deepslate_diamond_ore",
		"minecraft:emerald_ore", "minecraft:deepslate_emerald_ore", "minecraft:copper_ore", "minecraft:deepslate_copper_ore",
		"minecraft:nether_gold_ore", "minecraft:nether_quartz_ore", "minecraft:ancient_debris");

	public final BlockListSetting blocks = setting(new BlockListSetting("Whitelist", "Blocks shown x-rayed", ORES));
	public final NumberSetting opacity = setting(new NumberSetting("Opacity", "Opacity of every other block (0 = hidden)", 25, 0, 255, 1));
	public final EnumSetting<FluidOpacity> fluidOpacity = setting(new EnumSetting<>("Fluid opacity", "Which fluids use the x-ray opacity", FluidOpacity.Both));
	public final StringSetting query = setting(new StringSetting("Query", "Also show every block whose id or name contains one of these comma-separated words (\"ore, copper\")", "", 200));
	public final NumberSetting area = setting(new NumberSetting("Area (chunks)", "Only x-ray this many chunks around you (0 = no limit); the rest of the world renders normally. The area follows you as you move, in jumps", 0, 0, 32, 1));
	public final BooleanSetting exposedOnly = setting(new BooleanSetting("Exposed only", "Only show listed blocks that touch air or another see-through block", false));

	/** What the render threads read: null while off. */
	private record State(Set<Block> whitelist, int opacity, FluidOpacity fluids, boolean exposedOnly, int area, int centerX, int centerZ) {
		/** True when {@code pos} is outside the chunk area, where x-ray does not apply. */
		boolean outside(BlockPos pos) {
			return area > 0 && pos != null && (Math.abs((pos.getX() >> 4) - centerX) > area || Math.abs((pos.getZ() >> 4) - centerZ) > area);
		}
	}

	private static volatile State state;

	/** Ticks until a typed query is applied, so typing does not rebuild every chunk per letter. */
	private int queryDelay;

	private static final boolean IRIS = FabricLoader.getInstance().isModLoaded("iris");

	public XRay() {
		super("XRay", List.of("X-Ray", "Xray", "Ore finder"), Category.RENDER, "Shows only the listed blocks (ores by default); the rest turns see-through.");
		blocks.onChange(v -> refresh());
		opacity.onChange(v -> refresh());
		fluidOpacity.onChange(v -> refresh());
		exposedOnly.onChange(v -> refresh());
		area.onChange(v -> refresh());
		query.onChange(v -> queryDelay = 10);
	}

	@Override
	public boolean hasConfigScreen() {
		return true;
	}

	@Override
	public IdListSetting embeddedList() {
		return blocks;
	}

	@Override
	protected void onEnable() {
		refresh();
	}

	@Override
	protected void onDisable() {
		refresh();
	}

	/** Switched on by the config at startup (no onEnable then): build the snapshot on the first tick. */
	@Override
	public void onTick() {
		State s = state;
		boolean moved = s != null && s.area() > 0 && mc().player != null
			&& Math.max(Math.abs(mc().player.chunkPosition().x() - s.centerX()), Math.abs(mc().player.chunkPosition().z() - s.centerZ())) >= Math.max(4, s.area() / 2);
		if (s == null || moved || queryDelay > 0 && --queryDelay == 0) refresh();
	}

	/** New snapshot for the render threads, then rebuild every chunk with it. */
	private void refresh() {
		queryDelay = 0;
		var player = mc().player;
		state = isEnabled() ? new State(wanted(), opacity.intValue(), fluidOpacity.get(), exposedOnly.get(), area.intValue(),
			player == null ? 0 : player.chunkPosition().x(), player == null ? 0 : player.chunkPosition().z()) : null;
		Minecraft mc = Minecraft.getInstance();
		if (mc.level != null) mc.execute(mc.levelExtractor::allChanged);
	}

	/** The whitelist plus every block the query matches. */
	private Set<Block> wanted() {
		Set<Block> all = new HashSet<>(blocks.blocks());
		String q = query.get();
		if (!q.isBlank()) for (Block b : BuiltInRegistries.BLOCK) if (BlockESP.matchesQuery(b, q)) all.add(b);
		return Set.copyOf(all);
	}

	public static boolean active() {
		return state != null;
	}

	/** Translucent blocks need sorting, which shader packs break: with shaders on, hidden instead. */
	private static int opacity(State s) {
		return IRIS && ShaderPacks.inUse() ? 0 : s.opacity();
	}

	/**
	 * Opacity for a block: -1 = draw normally (x-ray off, or a listed block), 0 = skip it, else the
	 * alpha to draw it with.
	 *
	 * @param level the region being meshed (thread-safe), or null to read the client level
	 */
	public static int alpha(BlockState blockState, BlockPos pos, BlockGetter level) {
		State s = state;
		if (s == null || !blocked(s, blockState.getBlock(), pos, level)) return -1;
		return opacity(s);
	}

	/** {@link #alpha} for fluids, honouring the fluid-opacity option. */
	public static int fluidAlpha(FluidState fluid, BlockPos pos, BlockGetter level) {
		State s = state;
		if (s == null) return -1;
		boolean applies = switch (s.fluids()) {
			case None -> false;
			case Water -> fluid.is(FluidTags.WATER);
			case Lava -> fluid.is(FluidTags.LAVA);
			case Both -> fluid.is(FluidTags.WATER) || fluid.is(FluidTags.LAVA);
		};
		if (!applies || !blocked(s, fluid.createLegacyBlock().getBlock(), pos, level)) return -1;
		return opacity(s);
	}

	/** True when x-ray is on and {@code block} is not shown (exposure checked when {@code pos} is known). */
	public static boolean blocked(Block block, BlockPos pos, BlockGetter level) {
		State s = state;
		return s != null && blocked(s, block, pos, level);
	}

	private static boolean blocked(State s, Block block, BlockPos pos, BlockGetter level) {
		if (s.outside(pos)) return false;
		if (!s.whitelist().contains(block)) return true;
		return s.exposedOnly() && pos != null && !exposed(pos, level);
	}

	private static boolean exposed(BlockPos pos, BlockGetter level) {
		BlockGetter view = level != null ? level : Minecraft.getInstance().level;
		if (view == null) return true;
		BlockPos.MutableBlockPos next = new BlockPos.MutableBlockPos();
		for (Direction d : Direction.values()) {
			if (!view.getBlockState(next.setWithOffset(pos, d)).isSolidRender()) return true;
		}
		return false;
	}

	/**
	 * Whether a face is drawn. Listed blocks also show faces against hidden neighbours, so a vein seen
	 * through see-through stone is closed; faces between two blocks of the same listed kind stay culled.
	 */
	public static boolean drawSide(BlockState self, BlockGetter level, BlockPos pos, Direction facing, boolean vanilla) {
		State s = state;
		if (s == null || vanilla || blocked(s, self.getBlock(), pos, level)) return vanilla;
		BlockPos adjacent = pos.relative(facing);
		BlockState other = level.getBlockState(adjacent);
		return other.getFaceOcclusionShape(facing.getOpposite()) != Shapes.block() || other.getBlock() != self.getBlock()
			|| !other.isSolidRender() || blocked(s, other.getBlock(), adjacent, level);
	}

	/** Iris API, looked up by reflection so Coffee does not depend on Iris. */
	private static final class ShaderPacks {
		private static final java.lang.invoke.MethodHandle IN_USE = find();

		private static java.lang.invoke.MethodHandle find() {
			try {
				Class<?> api = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
				Object instance = api.getMethod("getInstance").invoke(null);
				return java.lang.invoke.MethodHandles.publicLookup()
					.findVirtual(api, "isShaderPackInUse", java.lang.invoke.MethodType.methodType(boolean.class)).bindTo(instance);
			} catch (Throwable t) {
				return null;
			}
		}

		static boolean inUse() {
			try {
				return IN_USE != null && (boolean) IN_USE.invokeExact();
			} catch (Throwable t) {
				return false;
			}
		}
	}
}
