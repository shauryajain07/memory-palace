package com.memorypalace.core.markdown;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class TaskToggleTest {
	/** The checked flags of the to-dos the book shows, in order. */
	private static List<Boolean> shown(String markdown) {
		return MarkdownParser.parse(markdown).stream()
				.filter(b -> b instanceof MdBlock.ListItem item && item.checked() != null)
				.map(b -> ((MdBlock.ListItem) b).checked()).toList();
	}

	private static void flipsOnlyThatOne(String markdown) {
		List<Boolean> before = shown(markdown);
		assertFalse(before.isEmpty(), "test needs to-dos");
		for (int i = 0; i < before.size(); i++) {
			String toggled = TaskToggle.toggle(markdown, i).orElseThrow();
			List<Boolean> after = shown(toggled);
			assertEquals(before.size(), after.size(), "same number of to-dos after flipping " + i);
			for (int j = 0; j < before.size(); j++) {
				assertEquals(i == j ? !before.get(j) : before.get(j), after.get(j), "flag " + j + " after flipping " + i);
			}
			assertEquals(markdown.length(), toggled.length(), "only one character changes");
			// "[X]" comes back as "[x]"
			assertTrue(markdown.equalsIgnoreCase(TaskToggle.toggle(toggled, i).orElseThrow()), "flipping twice gives the text back");
		}
	}

	@Test
	void flatList() {
		assertEquals(Optional.of("- [x] milk\n- [ ] eggs"), TaskToggle.toggle("- [ ] milk\n- [ ] eggs", 0));
		assertEquals(Optional.of("- [ ] milk\n- [ ] eggs"), TaskToggle.toggle("- [ ] milk\n- [X] eggs", 1));
		flipsOnlyThatOne("- [ ] milk\n- [x] eggs\n* [ ] bread\n1. [ ] first\n2) [X] second");
	}

	@Test
	void nestedQuotedAndMixedLists() {
		flipsOnlyThatOne("""
				# Week 3

				- [ ] read chapter 5
				  - [x] section 5.1
				  - [ ] section 5.2
				- plain item
				- [ ] exercises

				> - [ ] quoted task
				> - [x] done quoted

				Text with - [ ] that is not a task.

				```
				- [ ] in code, not a task
				```
				""");
	}

	@Test
	void tasksAfterMathImagesAndMacros() {
		flipsOnlyThatOne("""
				$$\\newcommand{\\R}{\\mathbb{R}}$$

				- [ ] $x \\in \\R$ first
				- [ ] ![graph](attachments/graph.png)
				- [x] after the image
				- [ ] `code` item
				""");
	}

	@Test
	void outOfRangeAndNoTasks() {
		assertTrue(TaskToggle.toggle("- [ ] a", 1).isEmpty());
		assertTrue(TaskToggle.toggle("- [ ] a", -1).isEmpty());
		assertTrue(TaskToggle.toggle("no tasks", 0).isEmpty());
	}
}
