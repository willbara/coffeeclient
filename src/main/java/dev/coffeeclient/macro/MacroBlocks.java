package dev.coffeeclient.macro;

import java.util.List;
import java.util.function.Predicate;

/**
 * Block structure of a flat step list: If and Repeat open a block, Else if and Else split an If, End
 * closes the innermost block. Missing Ends are tolerated: an open block runs to the end of the macro,
 * and a stray End does nothing.
 */
public final class MacroBlocks {
	private MacroBlocks() {
	}

	static boolean opens(MacroStep.Type type) {
		return type == MacroStep.Type.IF || type == MacroStep.Type.REPEAT;
	}

	/** The index of the End that closes the block whose marker (If, Else if, Else or Repeat) is at {@code from}, or the list size. */
	public static int end(List<MacroStep> steps, int from) {
		int depth = 0;
		for (int i = from + 1; i < steps.size(); i++) {
			MacroStep.Type type = steps.get(i).type();
			if (opens(type)) depth++;
			else if (type == MacroStep.Type.END && depth-- == 0) return i;
		}
		return steps.size();
	}

	/**
	 * Where to continue after the If at {@code from} failed: just inside the first Else if that
	 * {@code holds}, or inside the Else, or after the End.
	 */
	static int branch(List<MacroStep> steps, int from, Predicate<MacroStep> holds) {
		int depth = 0;
		for (int i = from + 1; i < steps.size(); i++) {
			MacroStep step = steps.get(i);
			MacroStep.Type type = step.type();
			if (opens(type)) {
				depth++;
			} else if (type == MacroStep.Type.END) {
				if (depth-- == 0) return i + 1;
			} else if (depth == 0 && (type == MacroStep.Type.ELSE || type == MacroStep.Type.ELSE_IF && holds.test(step))) {
				return i + 1;
			}
		}
		return steps.size();
	}

	/** How deep each step is nested, for indenting the editor. Else if, Else and End sit level with their If. */
	public static int[] depths(List<MacroStep> steps) {
		int[] out = new int[steps.size()];
		int depth = 0;
		for (int i = 0; i < steps.size(); i++) {
			MacroStep.Type type = steps.get(i).type();
			boolean splitsOrCloses = type == MacroStep.Type.ELSE_IF || type == MacroStep.Type.ELSE || type == MacroStep.Type.END;
			out[i] = splitsOrCloses ? Math.max(0, depth - 1) : depth;
			if (opens(type)) depth++;
			else if (type == MacroStep.Type.END) depth = Math.max(0, depth - 1);
		}
		return out;
	}
}
