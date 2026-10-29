package dev.coffeeclient.module.modules.misc;

import dev.coffeeclient.core.CoffeeClient;
import dev.coffeeclient.module.Category;
import dev.coffeeclient.module.Module;
import dev.coffeeclient.setting.BooleanSetting;
import dev.coffeeclient.setting.EnumSetting;
import dev.coffeeclient.util.Async;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import dev.coffeeclient.util.Open;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.WritableBookContent;
import net.minecraft.world.item.component.WrittenBookContent;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Action: copies the text of the book in your hand (written or book & quill) to the clipboard or a file. */
public final class BookCopier extends Module {
	public enum Target { CLIPBOARD, FILE }
	public enum Separator { BLANK_LINE, PAGE_HEADERS, NONE }

	public final EnumSetting<Target> target = setting(new EnumSetting<>("Target", "Where the text goes", Target.CLIPBOARD));
	public final EnumSetting<Separator> separator = setting(new EnumSetting<>("Page separator", "How pages are separated", Separator.PAGE_HEADERS));
	public final BooleanSetting header = setting(new BooleanSetting("Title and author", "Start with the title and author", true));
	public final BooleanSetting openFile = setting(new BooleanSetting("Open saved file", "Open the file after saving", false));

	public BookCopier() {
		super("BookCopier", Category.MISC, "Copies the text of the book in your hand.");
	}

	@Override
	public boolean isAction() {
		return true;
	}

	@Override
	protected void onAction() {
		var player = mc().player;
		if (player == null) return;
		ItemStack stack = player.getItemInHand(InteractionHand.MAIN_HAND);
		if (!isBook(stack)) stack = player.getItemInHand(InteractionHand.OFF_HAND);
		if (!isBook(stack)) {
			notify("Hold a written book or book & quill");
			return;
		}
		List<String> pages = new ArrayList<>();
		String title = "Untitled", author = "";
		WrittenBookContent written = stack.get(DataComponents.WRITTEN_BOOK_CONTENT);
		if (written != null) {
			title = written.title().raw();
			author = written.author();
			for (var page : written.pages()) pages.add(page.raw().getString());
		} else {
			WritableBookContent writable = stack.get(DataComponents.WRITABLE_BOOK_CONTENT);
			for (var page : writable.pages()) pages.add(page.raw());
		}
		StringBuilder sb = new StringBuilder();
		if (header.get()) sb.append(title).append(author.isEmpty() ? "" : " by " + author).append("\n\n");
		for (int i = 0; i < pages.size(); i++) {
			if (separator.get() == Separator.PAGE_HEADERS) sb.append("--- Page ").append(i + 1).append(" ---\n");
			sb.append(pages.get(i)).append('\n');
			if (separator.get() != Separator.NONE) sb.append('\n');
		}
		String text = sb.toString().strip();
		if (target.get() == Target.CLIPBOARD) {
			mc().keyboardHandler.setClipboard(text);
			notify("Copied " + pages.size() + " pages");
			return;
		}
		String safe = title.replaceAll("[^a-zA-Z0-9._ -]", "_").strip();
		Path file = CoffeeClient.config().directory().resolve("books").resolve((safe.isEmpty() ? "book" : safe) + "-" + System.currentTimeMillis() + ".txt");
		Async.io(() -> {
			try {
				Files.createDirectories(file.getParent());
				Files.writeString(file, text, StandardCharsets.UTF_8);
				mc().execute(() -> {
					notify("Saved " + file.getFileName());
					if (openFile.get()) Open.path(file);
				});
			} catch (Exception e) {
				mc().execute(() -> chat(Component.literal("Could not save book: " + e.getMessage())));
			}
		});
	}

	private static boolean isBook(ItemStack stack) {
		return stack.has(DataComponents.WRITTEN_BOOK_CONTENT) || stack.has(DataComponents.WRITABLE_BOOK_CONTENT);
	}
}
