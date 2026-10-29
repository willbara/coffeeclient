package dev.coffeeclient.macro;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExampleMacroTest {
	@Test
	void exampleUsesEveryKindOfStepAndSurvivesASaveAndLoad() {
		List<Macro> examples = MacroManager.examples();
		Macro example = examples.get(0);
		assertEquals("Example", example.name);
		assertEquals(MacroTrigger.KEY_PRESS, example.trigger);
		assertEquals(-1, example.key(), "unbound, so it never runs until a key is chosen");
		Set<MacroStep.Type> used = EnumSet.noneOf(MacroStep.Type.class);
		for (Macro m : examples) for (MacroStep s : m.steps) used.add(s.type());
		// Stop macro is the one step left out: ending early is shown by Stop if.
		assertEquals(EnumSet.complementOf(EnumSet.of(MacroStep.Type.STOP_MACRO)), used);
		assertEquals(MacroTrigger.MANUAL, examples.get(1).trigger);
		assertTrue(example.steps.stream().anyMatch(s -> s.type() == MacroStep.Type.RUN_MACRO && s.get("macro").equals(examples.get(1).name)));

		JsonObject json = example.toJson();
		Macro back = Macro.fromJson(json);
		assertEquals(example.steps.size(), back.steps.size());
		for (int i = 0; i < example.steps.size(); i++) assertEquals(example.steps.get(i).summary(), back.steps.get(i).summary());
	}

	@Test
	void exampleBlocksAreBalanced() {
		for (Macro m : MacroManager.examples()) {
			int[] depths = MacroBlocks.depths(m.steps);
			assertEquals(0, depths[depths.length - 1], m.name + " ends outside every block");
			int open = 0;
			for (MacroStep s : m.steps) {
				if (MacroBlocks.opens(s.type())) open++;
				if (s.type() == MacroStep.Type.END) open--;
			}
			assertEquals(0, open, m.name);
		}
	}
}
