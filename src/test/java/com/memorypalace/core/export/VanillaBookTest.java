package com.memorypalace.core.export;

import com.memorypalace.core.export.VanillaBook.Kind;
import com.memorypalace.core.export.VanillaBook.Run;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class VanillaBookTest {
	private static String text(List<Run> page) {
		var sb = new StringBuilder();
		page.forEach(r -> sb.append(r.text()));
		return sb.toString();
	}

	/** All pages, one line per book line. */
	private static String allText(VanillaBook.Book book) {
		return String.join("\n", book.pages().stream().map(VanillaBookTest::text).toList());
	}

	/** Splits a page back into its lines (runs between "\n" runs). */
	private static List<List<Run>> lines(List<Run> page) {
		var lines = new ArrayList<List<Run>>();
		var line = new ArrayList<Run>();
		for (Run run : page) {
			if (run.text().equals("\n")) {
				lines.add(line);
				line = new ArrayList<>();
			} else {
				assertFalse(run.text().contains("\n"), "newlines are separate runs: " + run);
				line.add(run);
			}
		}
		lines.add(line);
		return lines;
	}

	private static void assertFitsVanillaPages(VanillaBook.Book book) {
		assertTrue(book.pages().size() <= VanillaBook.MAX_PAGES);
		for (List<Run> page : book.pages()) {
			List<List<Run>> lines = lines(page);
			assertTrue(lines.size() <= VanillaBook.LINES_PER_PAGE, "too many lines: " + lines.size());
			for (List<Run> line : lines) {
				assertTrue(VanillaBook.width(line) <= VanillaBook.PAGE_WIDTH - VanillaBook.SAFETY,
						"too wide (" + VanillaBook.width(line) + "): " + line);
			}
		}
	}

	@Test
	void markdownBecomesStyledText() {
		VanillaBook.Book book = VanillaBook.fromMarkdown("# Title\n\nHello $x^2$ world", "Image");
		assertEquals(1, book.pages().size());
		assertEquals("Title\n\nHello x² world", text(book.pages().getFirst()));
		List<Run> page = book.pages().getFirst();
		assertEquals(new Run("Title", Kind.BOLD), page.getFirst());
		assertTrue(page.contains(new Run("x²", Kind.MATH)), page.toString());
		assertFalse(book.truncated());
	}

	@Test
	void listsCodeQuotesTablesAndImages() {
		String note = """
				- one
				- [x] done

				> quoted

				```
				code line
				```

				| a | b |
				|---|---|
				| 1 | 2 |

				![a cat](cat.png)
				""";
		assertEquals("• one\n☑ done\n\n│ quoted\n\ncode line\n\na | b\n1 | 2\n\n[Image: a cat]",
				allText(VanillaBook.fromMarkdown(note, "Image")));
	}

	@Test
	void macrosAreAppliedAndDefinitionsHidden() {
		String note = "$$\\newcommand{\\R}{\\mathbb{R}}$$\n\nIn $\\R^n$ we have\n\n$$\\R \\times \\R$$";
		assertEquals("In ℝⁿ we have\n\n  ℝ × ℝ", allText(VanillaBook.fromMarkdown(note, "Image")));
	}

	@Test
	void longTextWrapsWithinPageWidthAndLineLimit() {
		String words = "The quick brown fox jumps over the lazy dog, then writes $\\int_0^1 x^2 \\, dx = \\frac{1}{3}$. ";
		VanillaBook.Book book = VanillaBook.fromMarkdown(words.repeat(60) + "\n\n# **Bold heading that is long enough to wrap**\n\n"
				+ "中文笔记也要能正常换行，每个字都比英文宽。".repeat(20), "Image");
		assertTrue(book.pages().size() > 3, "pages: " + book.pages().size());
		assertFitsVanillaPages(book);
		// nothing is lost or duplicated by wrapping (the space at a line break becomes the line break)
		String joined = allText(book).replace("\n", " ");
		assertEquals(60, joined.split("quick brown fox", -1).length - 1);
		assertTrue(joined.contains("∫₀¹ x² dx = ⅓"), joined.substring(0, 200));
	}

	@Test
	void wordLongerThanALineIsBrokenHard() {
		VanillaBook.Book book = VanillaBook.fromMarkdown("x".repeat(500), "Image");
		assertFitsVanillaPages(book);
		assertEquals(500, allText(book).replace("\n", "").length());
	}

	@Test
	void tooLongNotesStopAtOneHundredPages() {
		VanillaBook.Book book = VanillaBook.fromMarkdown("A line of text.\n\n".repeat(3_000), "Image");
		assertTrue(book.truncated());
		assertEquals(VanillaBook.MAX_PAGES, book.pages().size());
		assertTrue(text(book.pages().getLast()).endsWith("…"));
		assertFitsVanillaPages(book);
	}

	@Test
	void emptyNoteGivesOneEmptyPage() {
		VanillaBook.Book book = VanillaBook.fromMarkdown("", "Image");
		assertEquals(1, book.pages().size());
		assertEquals("", text(book.pages().getFirst()));
		assertFalse(book.truncated());
	}

	@Test
	void pagesNeverStartWithBlankLines() {
		VanillaBook.Book book = VanillaBook.fromMarkdown("Paragraph.\n\n".repeat(40), "Image");
		for (List<Run> page : book.pages()) {
			assertFalse(text(page).startsWith("\n"), text(page));
		}
	}

	@Test
	void widthTableMatchesTheVanillaFont() {
		// ASCII values measured from Minecraft 26.3's ascii.png (advance = glyph width + 1)
		String[] byWidth = {"", "", "!',.:;i|", "`l", " \"()*I[]t{}", "<>fk",
				"#$%&+-/0123456789=?ABCDEFGHJKLMNOPQRSTUVWXYZ\\^_abcdeghjmnopqrsuvwxyz", "@~"};
		for (int w = 0; w < byWidth.length; w++) {
			for (char c : byWidth[w].toCharArray()) {
				assertEquals(w, VanillaBook.width(c, false), "width of '" + c + "'");
				assertEquals(w + 1, VanillaBook.width(c, true), "bold width of '" + c + "'");
			}
		}
		// everything else: 10, the widest glyph in the font (☑ ☐ Æ are 10, CJK from unifont 9)
		for (char c : "☑☐Æ中∫α…ℝ".toCharArray()) {
			assertEquals(10, VanillaBook.width(c, false), "width of '" + c + "'");
		}
	}
}
