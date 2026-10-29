package dev.coffeeclient.setting;

import dev.coffeeclient.util.BlockIds;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;

import java.util.List;
import java.util.Set;

/** A selection of blocks, picked from every registered block. */
public class BlockListSetting extends IdListSetting {
	public BlockListSetting(String name, String description, List<String> defaults) {
		super(name, description, defaults, BlockIds::all, false);
		labels(BlockIds::name);
		icons(id -> {
			Block block = BlockIds.of(id);
			return block == null ? ItemStack.EMPTY : new ItemStack(block.asItem());
		});
	}

	public Set<Block> blocks() {
		return resolve(BlockIds::of);
	}

	public boolean has(Block block) {
		return blocks().contains(block);
	}
}
