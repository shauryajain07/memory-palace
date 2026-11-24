package com.memorypalace.core.editor;

import com.memorypalace.core.markdown.MarkdownParser;
import com.memorypalace.core.markdown.MdBlock;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class SlashMenuTest {
	private static final char CURSOR = '‸';

	private static Optional<SlashMenu.Trigger> find(String withCursor) {
		int cursor = withCursor.indexOf(CURSOR);
		return SlashMenu.find(withCursor.substring(0, cursor) + withCursor.substring(cursor + 1), cursor);
	}

	/** Picks the command with this id for the "/query" at the cursor and returns the result with the cursor. */
	private static String pick(String withCursor, String id) {
		int cursor = withCursor.indexOf(CURSOR);
		String text = withCursor.substring(0, cursor) + withCursor.substring(cursor + 1);
		SlashMenu.Trigger trigger = SlashMenu.find(text, cursor).orElseThrow();
		SlashMenu.Command command = SlashMenu.COMMANDS.stream().filter(c -> c.id().equals(id)).findFirst().orElseThrow();
		SlashMenu.Edit edit = SlashMenu.apply(text, trigger, cursor, command, "2026-11-23");
		return edit.text().substring(0, edit.cursor()) + CURSOR + edit.text().substring(edit.cursor());
	}

	private static String label(SlashMenu.Command c) {
		return switch (c.id()) {
			case "h1" -> "Heading 1";
			case "h2" -> "Heading 2";
			case "h3" -> "Heading 3";
			case "bullet" -> "Bulleted list";
			case "numbered" -> "Numbered list";
			case "todo" -> "To-do list";
			case "math" -> "Math block";
			case "inline_math" -> "Inline math";
			default -> c.id();
		};
	}

	@Test
	void opensAtLineStartOrAfterSpace() {
		assertEquals(Optional.of(new SlashMenu.Trigger(0, "")), find("/‸"));
		assertEquals(Optional.of(new SlashMenu.Trigger(0, "h1")), find("/h1‸"));
		assertEquals(Optional.of(new SlashMenu.Trigger(6, "to")), find("Notes /to‸"));
		assertEquals(Optional.of(new SlashMenu.Trigger(4, "")), find("a\nb\n/‸ rest"));
	}

	@Test
	void staysClosedInPathsUrlsCodeAndMath() {
		assertTrue(find("a/b‸").isEmpty());
		assertTrue(find("see https://x.org/‸").isEmpty());
		assertTrue(find("/to do‸").isEmpty(), "a space closes the menu");
		assertTrue(find("`a /‸").isEmpty());
		assertTrue(find("$x /‸").isEmpty());
		assertTrue(find("```\ncode\n/‸").isEmpty());
		assertTrue(find("$$\nx\n/‸").isEmpty());
		assertTrue(find("/" + "a".repeat(SlashMenu.MAX_QUERY + 1) + "‸").isEmpty());
		// after a closed code block or formula it opens again
		assertTrue(find("```\ncode\n```\n/‸").isPresent());
		assertTrue(find("$a$ and /‸").isPresent());
		assertTrue(find("costs \\$5 /‸").isPresent());
	}

	@Test
	void filtersByNameThenAlias() {
		List<String> all = SlashMenu.filter("", SlashMenuTest::label).stream().map(SlashMenu.Command::id).toList();
		assertEquals(SlashMenu.COMMANDS.size(), all.size());
		assertEquals("h1", all.getFirst());
		assertEquals(List.of("h1", "h2", "h3"),
				SlashMenu.filter("head", SlashMenuTest::label).stream().map(SlashMenu.Command::id).toList());
		assertEquals("todo", SlashMenu.filter("todo", SlashMenuTest::label).getFirst().id());
		assertEquals("math", SlashMenu.filter("math", SlashMenuTest::label).getFirst().id());
		assertEquals("math", SlashMenu.filter("eq", SlashMenuTest::label).getFirst().id());
		assertEquals("pdf", SlashMenu.filter("PDF", SlashMenuTest::label).getFirst().id());
		assertTrue(SlashMenu.filter("zzz", SlashMenuTest::label).isEmpty());
	}

	@Test
	void englishAliasesWorkWithTranslatedNames() {
		assertEquals("h1", SlashMenu.filter("h1", c -> c.id().equals("h1") ? "标题 1" : "其他").getFirst().id());
		assertEquals("h1", SlashMenu.filter("标题", c -> c.id().equals("h1") ? "标题 1" : "其他").getFirst().id());
	}

	@Test
	void lineBlocksTurnTheLineInto() {
		assertEquals("# ‸", pick("/h1‸", "h1"));
		assertEquals("# My title‸", pick("My title /h1‸", "h1"));
		assertEquals("## My title‸", pick("# My title /h2‸", "h2"));
		assertEquals("- [ ] buy milk‸", pick("- buy milk /todo‸", "todo"));
		assertEquals("  - ‸", pick("  /‸", "bullet"));
		assertEquals("> quoted‸ rest", pick("quoted /q‸ rest", "quote"));
		assertEquals("a\n1. ‸\nb", pick("a\n/num‸\nb", "numbered"));
	}

	@Test
	void blocksGetTheirOwnLinesWithBlankLinesAround() {
		assertEquals("$$\n‸\n$$", pick("/math‸", "math"));
		assertEquals("text\n\n$$\n‸\n$$", pick("text\n/math‸", "math"));
		assertEquals("text\n\n```\n‸\n```\n\nafter", pick("text\n/code‸\nafter", "code"));
		assertEquals("text\n\n---\n‸", pick("text /div‸", "divider"));
		assertEquals("| ‸ |  |\n| --- | --- |\n|  |  |", pick("/table‸", "table"));
	}

	@Test
	void inlineItemsAndActions() {
		assertEquals("so $‸$ holds", pick("so /inl‸ holds", "inline_math"));
		assertEquals("see [[‸", pick("see /link‸", "link"));
		assertEquals("Due 2026-11-23‸", pick("Due /date‸", "date"));
		assertEquals("a ‸b", pick("a /img‸b", "image"));
	}

	@Test
	void insertedBlocksParseAsWhatTheMenuSays() {
		assertInstanceOf(MdBlock.Math.class, MarkdownParser.parse(fill(pick("text\n/math‸", "math"), "x^2")).get(1));
		assertInstanceOf(MdBlock.Code.class, MarkdownParser.parse(fill(pick("text\n/code‸", "code"), "x")).get(1));
		assertInstanceOf(MdBlock.Rule.class, MarkdownParser.parse(fill(pick("text /div‸", "divider"), "")).get(1));
		assertInstanceOf(MdBlock.Table.class, MarkdownParser.parse(fill(pick("text\n/table‸", "table"), "A")).get(1));
		var todo = MarkdownParser.parse(fill(pick("/todo‸", "todo"), "milk")).getFirst();
		assertEquals(Boolean.FALSE, assertInstanceOf(MdBlock.ListItem.class, todo).checked());
		assertEquals(1, assertInstanceOf(MdBlock.Heading.class,
				MarkdownParser.parse(fill(pick("Title /h1‸", "h1"), "")).getFirst()).level());
	}

	private static String fill(String withCursor, String typed) {
		return withCursor.replace(String.valueOf(CURSOR), typed);
	}
}
