package com.memorypalace.core.editor;

import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class TexSnippetsTest {
	/** Applies Tab at the "|" in the input and returns the result with "|" at the new cursor (or null). */
	private static String tab(String withCursor, Set<String> userMacros) {
		int cursor = withCursor.indexOf('|');
		String text = withCursor.substring(0, cursor) + withCursor.substring(cursor + 1);
		Optional<TexSnippets.Edit> edit = TexSnippets.apply(text, cursor, userMacros);
		return edit.map(e -> e.text().substring(0, e.cursor()) + "|" + e.text().substring(e.cursor())).orElse(null);
	}

	private static String tab(String withCursor) {
		return tab(withCursor, Set.of());
	}

	@Test
	void expandsTriggerWithCursorInFirstGroup() {
		assertEquals("$\\frac{|}{}$", tab("$\\frac|$"));
		assertEquals("$x = \\sqrt{|} + 1$", tab("$x = \\sqrt| + 1$"));
		assertEquals("\\sum_{|}^{}", tab("\\sum|"));
		assertEquals("\\begin{pmatrix} | & \\\\ & \\end{pmatrix}", tab("\\pmat|"));
		assertEquals("\\mathbb{|}", tab("\\mathbb|"));
	}

	@Test
	void tabAgainJumpsToNextEmptyGroup() {
		assertEquals("\\frac{a}{|}", tab("\\frac{a|}{}"));
		assertEquals("\\sum_{i=1}^{|}", tab("\\sum_{i=1|}^{}"));
		// only on the same line
		assertNull(tab("\\frac{a}{b}|\n\\frac{}{}"));
		// "\{}" is a literal brace, not a group to jump into
		assertNull(tab("x| \\{}"));
	}

	@Test
	void unknownTriggersAndPlainWordsKeepNormalTab() {
		assertNull(tab("\\alpha|"));
		assertNull(tab("frac|"));
		assertNull(tab("|"));
		assertNull(tab("plain text|"));
	}

	@Test
	void lineBreakFollowedByWordIsNotATrigger() {
		// "\\frac" = line break + the word "frac"
		assertNull(tab("a \\\\frac|"));
		// three backslashes: line break, then a real \frac
		assertEquals("a \\\\\\frac{|}{}", tab("a \\\\\\frac|"));
	}

	@Test
	void triggerMustEndAtCursorAndBeTheWholeWord() {
		// "\fracx" is not "\frac"
		assertNull(tab("\\fracx|"));
		// cursor in the middle of a word: the part before the cursor counts
		assertEquals("\\frac{|}{}x", tab("\\frac|x"));
	}

	@Test
	void notesOwnMacrosWin() {
		assertNull(tab("\\norm|", Set.of("norm")));
		assertEquals("\\left\\lVert | \\right\\rVert", tab("\\norm|", Set.of("R")));
	}

	@Test
	void outOfRangeCursorIsIgnored() {
		assertTrue(TexSnippets.apply("abc", -1, Set.of()).isEmpty());
		assertTrue(TexSnippets.apply("abc", 4, Set.of()).isEmpty());
		assertTrue(TexSnippets.apply(null, 0, Set.of()).isEmpty());
	}

	@Test
	void everyTemplateIsBalanced() {
		for (var entry : TexSnippets.all().entrySet()) {
			String whole = entry.getValue().before() + entry.getValue().after();
			int depth = 0;
			for (int i = 0; i < whole.length(); i++) {
				char c = whole.charAt(i);
				if (c == '\\') {
					i++;
				} else if (c == '{') {
					depth++;
				} else if (c == '}') {
					depth--;
				}
				assertTrue(depth >= 0, entry.getKey());
			}
			assertEquals(0, depth, entry.getKey());
		}
	}
}
