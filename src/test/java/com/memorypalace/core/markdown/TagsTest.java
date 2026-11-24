package com.memorypalace.core.markdown;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TagsTest {
	@Test
	void findsTagsLikeObsidian() {
		assertEquals(List.of("midterm", "exam/week3", "a_b", "数学"),
				MarkdownParser.tags("Revise for the #midterm and #exam/week3 (#a_b), also #数学."));
		assertEquals(List.of("todo"), MarkdownParser.tags("#todo at the start of a line"));
		assertEquals(List.of("x"), MarkdownParser.tags("trailing slash #x/ and dash"));
	}

	@Test
	void notTags() {
		assertEquals(List.of(), MarkdownParser.tags("""
				# Heading is not a tag
				Learning C# and #123 and &#39;
				See https://example.com/page#part and https://example.com/#top
				A [[Note#Heading]] link and a [[#Local heading]] and [text](#anchor)
				Code `#notatag` and math $#x$

				```
				#incode
				```
				"""));
	}

	@Test
	void tagsAreSpansButNotLinksBetweenNotes() {
		String md = "Read [[Week 3]] for #midterm";
		assertEquals(List.of("wiki:Week 3"), MarkdownParser.links(md));
		var spans = ((MdBlock.Paragraph) MarkdownParser.parse(md).getFirst()).spans();
		assertEquals("#midterm", spans.getLast().text());
		assertEquals("tag:midterm", spans.getLast().link());
		assertFalse(MarkdownDetect.isRich("just a #hashtag in a book"), "a tag alone is not Markdown");
	}

	@Test
	void matchingIsCaseInsensitiveAndNested() {
		assertTrue(Tags.matches(List.of("exam/midterm"), "#exam"));
		assertTrue(Tags.matches(List.of("exam/midterm"), "#Exam/Midterm"));
		assertFalse(Tags.matches(List.of("examples"), "#exam"));
		assertTrue(Tags.inTemplates("Templates/Lecture.md"));
		assertTrue(Tags.inTemplates("Library/ENGG1101/templates/Lab.md"));
		assertFalse(Tags.inTemplates("Templates.md"));
		assertFalse(Tags.inTemplates("Library/ENGG1101/Week 3.md"));
	}
}
