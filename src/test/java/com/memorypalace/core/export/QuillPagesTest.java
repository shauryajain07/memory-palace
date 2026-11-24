package com.memorypalace.core.export;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class QuillPagesTest {
	private static String paragraphs(int n) {
		var sb = new StringBuilder();
		for (int i = 0; i < n; i++) {
			if (i > 0) {
				sb.append("\n\n");
			}
			sb.append("Paragraph ").append(i).append(" has a few words in it.");
		}
		return sb.toString();
	}

	@Test
	void shortTextIsOnePage() {
		assertEquals(List.of("# Lecture 5\n\nEigenvalues"), QuillPages.split("# Lecture 5\n\nEigenvalues").orElseThrow());
		assertEquals(List.of(""), QuillPages.split("").orElseThrow());
	}

	@Test
	void pagesBreakAtBlankLinesAndJoinBackToTheSameText() {
		String text = paragraphs(40);
		List<String> pages = QuillPages.split(text).orElseThrow();
		assertTrue(pages.size() > 1);
		assertEquals(text, QuillPages.join(pages));
		for (String page : pages) {
			assertTrue(QuillPages.lines(page) <= VanillaBook.LINES_PER_PAGE, "page fits 14 lines: " + page);
			assertTrue(page.length() <= QuillPages.PAGE_CHARS);
		}
		// extra blank lines survive too
		String gaps = "a\n\n\n\nb";
		assertEquals(gaps, QuillPages.join(QuillPages.split(gaps).orElseThrow()));
	}

	@Test
	void longParagraphsAreCutToFit() {
		String paragraph = "word ".repeat(400).strip();
		List<String> pages = QuillPages.split(paragraph).orElseThrow();
		assertTrue(pages.size() > 1);
		for (String page : pages) {
			assertTrue(QuillPages.lines(page) <= VanillaBook.LINES_PER_PAGE);
		}
		assertEquals(paragraph.replace(" ", ""), QuillPages.join(pages).replaceAll("\\s", ""), "no text lost");
		String oneWord = "x".repeat(3000);
		assertEquals(oneWord, String.join("", QuillPages.split(oneWord).orElseThrow()).replaceAll("\\s", ""));
	}

	@Test
	void tooLongForABook() {
		assertTrue(QuillPages.split(paragraphs(2000)).isEmpty());
	}
}
