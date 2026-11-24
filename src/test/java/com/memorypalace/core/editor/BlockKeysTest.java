package com.memorypalace.core.editor;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class BlockKeysTest {
	private static final String CURSOR = "‸";

	private static String show(Optional<BlockKeys.Edit> edit) {
		return edit.map(BlockKeysTest::show).orElse(null);
	}

	private static String show(BlockKeys.Edit e) {
		if (e.start() == e.end()) {
			return e.text().substring(0, e.start()) + CURSOR + e.text().substring(e.start());
		}
		return e.text().substring(0, e.start()) + "[" + e.text().substring(e.start(), e.end()) + "]"
				+ e.text().substring(e.end());
	}

	private static String enter(String withCursor) {
		int cursor = withCursor.indexOf(CURSOR);
		return show(BlockKeys.enter(withCursor.replace(CURSOR, ""), cursor));
	}

	private static String tab(String withCursor, boolean outdent) {
		int cursor = withCursor.indexOf(CURSOR);
		return show(BlockKeys.indent(withCursor.replace(CURSOR, ""), cursor, outdent));
	}

	/** "a [sel] b" → wrap the bracketed selection. */
	private static String wrap(String withSelection, String open, String close) {
		int a = withSelection.indexOf('[');
		int b = withSelection.indexOf(']') - 1;
		String text = withSelection.substring(0, a) + withSelection.substring(a + 1, b + 1) + withSelection.substring(b + 2);
		return show(BlockKeys.wrap(text, a, b, open, close));
	}

	@Test
	void enterContinuesLists() {
		assertEquals("- milk\n- ‸", enter("- milk‸"));
		assertEquals("  * a\n  * ‸", enter("  * a‸"));
		assertEquals("1. a\n2. ‸", enter("1. a‸"));
		assertEquals("9) a\n10) ‸", enter("9) a‸"));
		assertEquals("- [x] done\n- [ ] ‸", enter("- [x] done‸"));
		assertEquals("> said\n> ‸", enter("> said‸"));
		assertEquals(">> deep\n> > ‸", enter(">> deep‸"));
		// splits the item at the cursor, like Notion
		assertEquals("- ab\n- ‸cd", enter("- ab‸cd"));
	}

	@Test
	void enterOnEmptyItemEndsTheList() {
		assertEquals("- a\n‸", enter("- a\n- ‸"));
		assertEquals("- a\n‸\nnext", enter("- a\n- [ ] ‸\nnext"));
		assertEquals("‸", enter("> ‸"));
	}

	@Test
	void enterKeepsNormalMeaningElsewhere() {
		assertNull(enter("plain‸"));
		assertNull(enter("# Heading‸"));
		assertNull(enter("‸- before the marker"));
		assertNull(enter("```\n- in code‸"));
		assertNull(enter("$$\n- x‸"));
	}

	@Test
	void tabNestsUnderTheItemAbove() {
		assertEquals("- a\n  - b‸", tab("- a\n- b‸", false));
		assertEquals("1. a\n   - b‸", tab("1. a\n- b‸", false));
		assertNull(tab("- a\n  - b‸", false), "already nested one level deeper");
		assertNull(tab("- first‸", false), "nothing above to nest under");
		assertNull(tab("text\n- b‸", false));
	}

	@Test
	void shiftTabMovesBackOut() {
		assertEquals("- a\n- b‸", tab("- a\n  - b‸", true));
		assertEquals("- a\n  - b\n  - c‸", tab("- a\n  - b\n    - c‸", true));
		assertNull(tab("- a‸", true));
	}

	@Test
	void wrapsAndUnwrapsSelections() {
		assertEquals("a **[bold]** b", wrap("a [bold] b", "**", "**"));
		assertEquals("a [bold] b", wrap("a **[bold]** b", "**", "**"));
		assertEquals("a [bold] b", wrap("a [**bold**] b", "**", "**"));
		assertEquals("a *[it]* b", wrap("a [it] b", "*", "*"));
		assertEquals("a [it] b", wrap("a *[it]* b", "*", "*"));
		// italic on bold text makes it bold italic, and back
		assertEquals("a ***[x]*** b", wrap("a **[x]** b", "*", "*"));
		assertEquals("a **[x]** b", wrap("a ***[x]*** b", "*", "*"));
		assertEquals("see [[[Eigenvalues]]]", wrap("see [Eigenvalues]", "[[", "]]"));
	}

	@Test
	void emptySelectionInsertsThePair() {
		assertEquals("x $‸$", show(BlockKeys.wrap("x ", 2, 2, "$", "$")));
	}
}
