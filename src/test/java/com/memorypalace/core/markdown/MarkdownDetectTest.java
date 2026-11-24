package com.memorypalace.core.markdown;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MarkdownDetectTest {
	@Test
	void plainProseStaysVanilla() {
		assertFalse(MarkdownDetect.isRich("Dear diary,\nToday I found diamonds.\n\nThe end."));
		assertFalse(MarkdownDetect.isRich("It costs $5 and $10 at the shop."), "money is not a formula");
		assertFalse(MarkdownDetect.isRich(""));
		assertFalse(MarkdownDetect.isRich(null));
	}

	@Test
	void formulasAndMarkdownAreRich() {
		assertTrue(MarkdownDetect.isRich("The area is $\\pi r^2$."));
		assertTrue(MarkdownDetect.isRich("$$E = mc^2$$"));
		assertTrue(MarkdownDetect.isRich("# Chapter 1\nText"));
		assertTrue(MarkdownDetect.isRich("- eggs\n- milk"));
		assertTrue(MarkdownDetect.isRich("This is **important**."));
		assertTrue(MarkdownDetect.isRich("> a quote"));
		assertTrue(MarkdownDetect.isRich("see [[Other note]]"));
		assertTrue(MarkdownDetect.isRich("```\ncode\n```"));
	}
}
