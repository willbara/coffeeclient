package dev.coffeeclient.macro;

import dev.coffeeclient.core.CoffeeClient;
import dev.coffeeclient.mixin.KeyMappingAccessor;
import dev.coffeeclient.util.History;
import dev.coffeeclient.util.Inv;
import dev.coffeeclient.util.StarscriptUtils;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Runs macros a step at a time on the client tick. Several can run at once; each keeps its own place,
 * timers and held controls. A step list or Repeat block that never waits still yields between loops,
 * and at most {@link #STEPS_PER_TICK} steps run per macro per tick, so a runaway macro cannot freeze
 * the game.
 */
final class MacroRunner {
	private static final int STEPS_PER_TICK = 64;
	private static final int CHAT_KEPT = 32;

	/** A Repeat block being run: where it starts, and the run's wait count when this pass began. */
	private static final class Loop {
		final int start;
		int done;
		int waitsAtPass;

		Loop(int start, int waits) {
			this.start = start;
			this.waitsAtPass = waits;
		}
	}

	/** One running macro. */
	private static final class Run {
		final Macro macro;
		int index;
		int wait;
		int waited;
		int loopsDone;
		/** Steps run this loop that did not wait; a whole loop of them yields a tick before repeating. */
		boolean waitedThisLoop;
		/** Times a step waited; a Repeat pass that did not add to it yields a tick before the next pass. */
		int waits;
		/** Chat lines (lower case) since the previous step finished, for "chat says". */
		final Deque<String> chat = new ArrayDeque<>();
		/** Open Repeat blocks, innermost first. */
		final Deque<Loop> loops = new ArrayDeque<>();
		/** Controls put down by Press steps (until a Release step or the end). */
		final Set<KeyMapping> pressed = new HashSet<>();
		/** A control held by a Hold or Tap step, released when its wait ends. */
		KeyMapping holding;

		Run(Macro macro) {
			this.macro = macro;
		}
	}

	private final List<Run> runs = new ArrayList<>();
	private final MacroManager manager;

	MacroRunner(MacroManager manager) {
		this.manager = manager;
	}

	boolean isRunning(Macro macro) {
		for (Run r : runs) if (r.macro == macro) return true;
		return false;
	}

	void start(Macro macro) {
		if (macro.steps.isEmpty() || isRunning(macro)) return;
		runs.add(new Run(macro));
	}

	void stop(Macro macro) {
		for (Iterator<Run> it = runs.iterator(); it.hasNext(); ) {
			Run r = it.next();
			if (r.macro != macro) continue;
			release(r);
			it.remove();
		}
	}

	void stopAll() {
		for (Run r : runs) release(r);
		runs.clear();
	}

	/** A chat line arrived: every run keeps it until its current step finishes. */
	void onChat(String text) {
		String lower = text.toLowerCase(Locale.ROOT);
		for (Run r : runs) {
			if (r.chat.size() >= CHAT_KEPT) r.chat.removeFirst();
			r.chat.addLast(lower);
		}
	}

	void tick() {
		// Copy: a Run macro step may start another run.
		for (Run r : new ArrayList<>(runs)) {
			if (!runs.contains(r)) continue;
			boolean finished;
			try {
				finished = advance(r);
			} catch (Throwable t) {
				CoffeeClient.LOG.warn("[Macros] {} stopped: {}", r.macro.name, t.toString());
				finished = true;
			}
			if (finished) {
				release(r);
				runs.remove(r);
				manager.finished(r.macro);
			} else {
				// Vanilla re-reads controls from the physical keys on focus and screen changes: re-assert ours.
				for (KeyMapping k : r.pressed) k.setDown(true);
				if (r.holding != null) r.holding.setDown(true);
			}
		}
	}

	/** Runs steps until one waits; true when the macro is done. */
	private boolean advance(Run r) {
		if (r.wait > 0) {
			r.wait--;
			if (r.wait > 0) return false;
			if (r.holding != null) {
				r.holding.setDown(false);
				r.holding = null;
			}
			r.index++;
		}
		for (int executed = 0; executed < STEPS_PER_TICK; executed++) {
			if (r.index >= r.macro.steps.size()) {
				r.loopsDone++;
				int repeat = r.macro.repeat;
				if (repeat > 0 && r.loopsDone >= repeat) return true;
				r.index = 0;
				r.loops.clear();
				boolean yield = !r.waitedThisLoop;
				r.waitedThisLoop = false;
				if (yield) return false;
			}
			MacroStep step = r.macro.steps.get(r.index);
			Outcome outcome = run(r, step);
			switch (outcome) {
				case NEXT -> {
					r.index++;
					r.waited = 0;
					r.chat.clear();
				}
				case JUMP -> {
					r.waited = 0;
					r.chat.clear();
				}
				case WAIT -> {
					r.waitedThisLoop = true;
					r.waits++;
					return false;
				}
				case YIELD -> {
					r.waited = 0;
					r.chat.clear();
					return false;
				}
				case STOP -> {
					return true;
				}
			}
		}
		return false;
	}

	/** NEXT: on to the following step. JUMP: the step set the index. WAIT: stay on it. YIELD: the step set the index; continue next tick. */
	private enum Outcome { NEXT, JUMP, WAIT, YIELD, STOP }

	/** The step's condition, flipped when it is set to "Not". */
	private static boolean holds(Run r, MacroStep step) {
		return MacroCondition.parse(step.get("condition")).test(step.get("arg"), r.chat) != "Not".equals(step.get("not"));
	}

	private Outcome run(Run r, MacroStep step) {
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer player = mc.player;
		List<MacroStep> steps = r.macro.steps;
		switch (step.type()) {
			case CHAT -> {
				ClientPacketListener connection = mc.getConnection();
				String message = StarscriptUtils.format(step.get("text"));
				if (connection != null && message != null && !message.isBlank()) {
					if (message.startsWith("/")) connection.sendCommand(message.substring(1));
					else connection.sendChat(message);
				}
			}
			case MODULE -> CoffeeClient.modules().byName(step.get("module")).ifPresent(m -> {
				switch (step.get("action")) {
					case "Enable" -> m.setEnabled(true);
					case "Disable" -> m.setEnabled(false);
					default -> m.toggle();
				}
			});
			case KEY -> {
				KeyMapping key = KeyMapping.get(step.get("key"));
				if (key == null) return Outcome.NEXT;
				switch (step.get("mode")) {
					case "Hold" -> {
						key.setDown(true);
						r.holding = key;
						r.wait = Math.max(1, step.getInt("ticks"));
						return Outcome.WAIT;
					}
					case "Press" -> {
						key.setDown(true);
						r.pressed.add(key);
					}
					case "Release" -> {
						key.setDown(false);
						r.pressed.remove(key);
					}
					default -> {
						// Tap: one click (attack, drop, inventory...) and the control down for a tick (use, jump...).
						KeyMappingAccessor access = (KeyMappingAccessor) key;
						access.coffeeclient$setClickCount(access.coffeeclient$clickCount() + 1);
						key.setDown(true);
						r.holding = key;
						r.wait = 1;
						return Outcome.WAIT;
					}
				}
			}
			case WAIT -> {
				int ticks = step.getInt("ticks");
				if (ticks <= 0) return Outcome.NEXT;
				// The countdown includes this tick, then advance() moves on.
				r.wait = ticks;
				return Outcome.WAIT;
			}
			case WAIT_UNTIL -> {
				if (holds(r, step)) return Outcome.NEXT;
				int timeout = step.getInt("timeout");
				if (timeout > 0 && ++r.waited >= timeout) return Outcome.NEXT;
				return Outcome.WAIT;
			}
			case STOP_IF -> {
				if (holds(r, step)) return Outcome.STOP;
			}
			case IF -> {
				if (holds(r, step)) return Outcome.NEXT;
				r.index = MacroBlocks.branch(steps, r.index, s -> holds(r, s));
				return Outcome.JUMP;
			}
			case ELSE_IF, ELSE -> {
				// Reached by finishing the branch above: skip the rest of the If.
				r.index = MacroBlocks.end(steps, r.index) + 1;
				return Outcome.JUMP;
			}
			case REPEAT -> {
				if (!("Times".equals(step.get("mode")) ? step.getInt("count") > 0 : holds(r, step))) {
					r.index = MacroBlocks.end(steps, r.index) + 1;
					return Outcome.JUMP;
				}
				r.loops.push(new Loop(r.index, r.waits));
				return Outcome.NEXT;
			}
			case END -> {
				Loop loop = r.loops.peek();
				// An End that closes an If (or matches nothing) just moves on.
				if (loop == null || MacroBlocks.end(steps, loop.start) != r.index) return Outcome.NEXT;
				MacroStep head = steps.get(loop.start);
				loop.done++;
				if (!("Times".equals(head.get("mode")) ? loop.done < head.getInt("count") : holds(r, head))) {
					r.loops.pop();
					return Outcome.NEXT;
				}
				r.index = loop.start + 1;
				if (r.waits == loop.waitsAtPass) return Outcome.YIELD;
				loop.waitsAtPass = r.waits;
				return Outcome.JUMP;
			}
			case SELECT_ITEM -> {
				String item = step.get("item");
				if (item.isBlank()) return Outcome.NEXT;
				Predicate<ItemStack> match = stack -> MacroCondition.isItem(stack, item);
				if ("Hotbar".equals(step.get("from"))) {
					Inv.FindItemResult found = Inv.find(match, 0, 8);
					if (found.found()) Inv.swap(found.slot(), false);
				} else {
					Inv.select(match);
				}
			}
			case STOP_MACRO -> {
				switch (step.get("which")) {
					case "Other macro" -> {
						Macro other = manager.byName(step.get("macro"));
						if (other != null && other != r.macro) stop(other);
					}
					case "All macros" -> {
						for (Run other : new ArrayList<>(runs)) if (other != r) stop(other.macro);
						return Outcome.STOP;
					}
					default -> {
						return Outcome.STOP;
					}
				}
			}
			case SLOT -> {
				int slot = step.getInt("slot");
				if (slot >= 1 && slot <= 9) Inv.swap(slot - 1, false);
			}
			case LOOK -> {
				if (player == null) return Outcome.NEXT;
				float yaw = (float) MacroCondition.number(step.get("yaw")), pitch = (float) MacroCondition.number(step.get("pitch"));
				if ("Set".equals(step.get("mode"))) {
					player.setYRot(yaw);
					player.setXRot(Mth.clamp(pitch, -90, 90));
				} else {
					player.setYRot(player.getYRot() + yaw);
					player.setXRot(Mth.clamp(player.getXRot() + pitch, -90, 90));
				}
			}
			case LOCAL -> {
				String text = StarscriptUtils.format(step.get("text"));
				if (text != null && !text.isBlank() && player != null) {
					mc.gui.hud.getChat().addClientSystemMessage(Component.literal("[Coffee] ").withColor(CoffeeClient.theme().lightAccent() & 0xFFFFFF)
						.append(Component.literal(r.macro.name + " ").withColor(0xFFFFFF))
						.append(Component.literal(text).withColor(0xAAAAAA)));
					History.add(r.macro.name, text);
				}
			}
			case NOTIFY -> {
				String text = StarscriptUtils.format(step.get("text"));
				if (text != null && !text.isBlank()) CoffeeClient.hud().toast(r.macro.name + ": " + text, CoffeeClient.theme().accent);
			}
			case RUN_MACRO -> {
				Macro other = manager.byName(step.get("macro"));
				if (other != null && other != r.macro) start(other);
			}
		}
		return Outcome.NEXT;
	}

	private static void release(Run r) {
		for (KeyMapping k : r.pressed) k.setDown(false);
		r.pressed.clear();
		if (r.holding != null) r.holding.setDown(false);
		r.holding = null;
	}
}
