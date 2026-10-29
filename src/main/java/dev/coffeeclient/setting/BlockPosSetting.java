package dev.coffeeclient.setting;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import dev.coffeeclient.util.Coords;
import net.minecraft.core.BlockPos;

/** An exact block position, edited as three bounded integer sliders. */
public class BlockPosSetting extends Setting<BlockPos> {
	private final int min;
	private final int max;

	public BlockPosSetting(String name, String description, int x, int y, int z, int min, int max) {
		super(name, description, new BlockPos(x, y, z));
		this.min = min;
		this.max = max;
	}

	public int min() {
		return min;
	}

	public int max() {
		return max;
	}

	public int x() {
		return value.getX();
	}

	public int y() {
		return value.getY();
	}

	public int z() {
		return value.getZ();
	}

	public void setX(int x) {
		set(new BlockPos(clamp(x), value.getY(), value.getZ()));
	}

	public void setY(int y) {
		set(new BlockPos(value.getX(), clamp(y), value.getZ()));
	}

	public void setZ(int z) {
		set(new BlockPos(value.getX(), value.getY(), clamp(z)));
	}

	private int clamp(int v) {
		return Math.max(min, Math.min(max, v));
	}

	@Override
	protected BlockPos sanitize(BlockPos v) {
		if (v == null) return defaultValue();
		return new BlockPos(clamp(v.getX()), clamp(v.getY()), clamp(v.getZ()));
	}

	@Override
	public JsonElement toJson() {
		JsonArray arr = new JsonArray();
		arr.add(value.getX());
		arr.add(value.getY());
		arr.add(value.getZ());
		return arr;
	}

	@Override
	public void fromJson(JsonElement json) {
		try {
			JsonArray arr = json.getAsJsonArray();
			set(new BlockPos(arr.get(0).getAsInt(), arr.get(1).getAsInt(), arr.get(2).getAsInt()));
		} catch (RuntimeException e) {
			set(defaultValue());
		}
	}

	@Override
	public String display() {
		return Coords.format(value.getX()) + ", " + Coords.format(value.getY()) + ", " + Coords.format(value.getZ());
	}
}
