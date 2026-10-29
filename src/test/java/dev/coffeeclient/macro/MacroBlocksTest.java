package dev.coffeeclient.macro;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class MacroBlocksTest {
	private static List<MacroStep> steps(MacroStep.Type... types) {
		List<MacroStep> out = new ArrayList<>();
		for (MacroStep.Type t : types) out.add(new MacroStep(t));
		return out;
	}

	// 0 If, 1 Wait, 2 Else if, 3 Repeat, 4 Wait, 5 End, 6 Else, 7 Wait, 8 End, 9 Wait
	private final List<MacroStep> chain = steps(MacroStep.Type.IF, MacroStep.Type.WAIT, MacroStep.Type.ELSE_IF,
		MacroStep.Type.REPEAT, MacroStep.Type.WAIT, MacroStep.Type.END, MacroStep.Type.ELSE, MacroStep.Type.WAIT,
		MacroStep.Type.END, MacroStep.Type.WAIT);

	@Test
	void endSkipsNestedBlocks() {
		assertEquals(8, MacroBlocks.end(chain, 0));
		assertEquals(8, MacroBlocks.end(chain, 2));
		assertEquals(5, MacroBlocks.end(chain, 3));
	}

	@Test
	void failedIfGoesToTheFirstBranchThatHolds() {
		assertEquals(3, MacroBlocks.branch(chain, 0, s -> true), "inside the Else if");
		assertEquals(7, MacroBlocks.branch(chain, 0, s -> false), "inside the Else");
		List<MacroStep> plain = steps(MacroStep.Type.IF, MacroStep.Type.WAIT, MacroStep.Type.END, MacroStep.Type.WAIT);
		assertEquals(3, MacroBlocks.branch(plain, 0, s -> false), "after the End");
	}

	@Test
	void missingEndRunsToTheEndOfTheMacro() {
		List<MacroStep> open = steps(MacroStep.Type.IF, MacroStep.Type.WAIT);
		assertEquals(2, MacroBlocks.end(open, 0));
		assertEquals(2, MacroBlocks.branch(open, 0, s -> false));
	}

	@Test
	void depthsIndentBlockBodies() {
		assertArrayEquals(new int[] {0, 1, 0, 1, 2, 1, 0, 1, 0, 0}, MacroBlocks.depths(chain));
		assertArrayEquals(new int[] {0, 0}, MacroBlocks.depths(steps(MacroStep.Type.END, MacroStep.Type.WAIT)), "a stray End");
	}
}
