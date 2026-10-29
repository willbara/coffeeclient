package dev.coffeeclient.module.modules.render;

import dev.coffeeclient.hud.HudElement;
import dev.coffeeclient.hud.ModuleHudElement;
import dev.coffeeclient.module.Category;
import dev.coffeeclient.module.Module;
import dev.coffeeclient.setting.ActionSetting;
import dev.coffeeclient.setting.BooleanSetting;
import dev.coffeeclient.setting.NumberSetting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.ArrayList;
import java.util.List;

/** Reads the sign you're looking at (both sides) from further away than you can normally read it. */
public final class SignPreview extends Module {
	public final NumberSetting range = setting(new NumberSetting("Range", "How far away signs are read", 32, 4, 128, 4));
	public final BooleanSetting showBack = setting(new BooleanSetting("Back side", "Also show the back of the sign", true));
	public final BooleanSetting hideEmpty = setting(new BooleanSetting("Hide empty side", "Skip a side with no text", true));
	public final ActionSetting copy = setting(new ActionSetting("Copy text", "Copy the current sign's text", () -> {
		List<String> lines = lines();
		if (!lines.isEmpty()) {
			mc().keyboardHandler.setClipboard(String.join("\n", lines));
			notify("Sign text copied");
		}
	}));

	public SignPreview() {
		super("SignPreview", Category.RENDER, "Shows both sides of the sign you're looking at, from far away.");
	}

	private SignBlockEntity lookedAt() {
		var mc = mc();
		if (mc.player == null || mc.level == null) return null;
		HitResult hit = mc.player.pick(range.get(), 0, false);
		if (!(hit instanceof BlockHitResult bhr) || hit.getType() != HitResult.Type.BLOCK) return null;
		return mc.level.getBlockEntity(bhr.getBlockPos()) instanceof SignBlockEntity sign ? sign : null;
	}

	private List<String> lines() {
		SignBlockEntity sign = lookedAt();
		List<String> out = new ArrayList<>();
		if (sign == null) return out;
		addSide(out, sign.getFrontText(), showBack.get() ? "Front: " : "");
		if (showBack.get()) addSide(out, sign.getBackText(), "Back: ");
		return out;
	}

	private void addSide(List<String> out, SignText text, String prefix) {
		List<String> side = new ArrayList<>();
		Component[] messages = text.getMessages(false);
		for (int i = 0; i < 4; i++) side.add(i < messages.length ? messages[i].getString() : "");
		if (hideEmpty.get() && side.stream().allMatch(String::isBlank)) return;
		for (int i = 0; i < side.size(); i++) out.add((i == 0 ? prefix : "") + side.get(i));
	}

	@Override
	public List<HudElement> hudElements() {
		return List.of(new ModuleHudElement(this, "sign", "Sign reader", () -> {
			List<String[]> rows = new ArrayList<>();
			for (String line : lines()) {
				int colon = line.indexOf(": ");
				rows.add(colon > 0 && colon < 7 ? ModuleHudElement.row(line.substring(0, colon), line.substring(colon + 2)) : ModuleHudElement.row("", line));
			}
			return rows;
		}, (w, h) -> new int[] {w / 2 + 16, h / 2 - 60}).when(() -> lookedAt() != null).fixed());
	}
}
