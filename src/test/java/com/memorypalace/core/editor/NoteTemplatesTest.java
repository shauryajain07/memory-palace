package com.memorypalace.core.editor;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class NoteTemplatesTest {
	@Test
	void fillsKnownPlaceholdersInAnyCase() {
		String body = "# {{class}} · {{ Date }}\n\nNotes for {{title}} at {{time}} ({{unknown}})";
		assertEquals("# ENGG1101 · 2026-11-23\n\nNotes for Week 3 at 09:30 ({{unknown}})",
				NoteTemplates.fill(body, Map.of("class", "ENGG1101", "date", "2026-11-23", "title", "Week 3", "time", "09:30")));
		assertEquals("cost $5 \\ path", NoteTemplates.fill("cost {{x}} \\ path", Map.of("x", "$5")), "values are taken literally");
	}

	@Test
	void looksInTheNearestTemplatesFolderFirst() {
		assertEquals(List.of("Library/ENGG1101/Templates", "Library/Templates", "Templates"),
				NoteTemplates.searchFolders("Library/ENGG1101"));
		assertEquals(List.of("Templates"), NoteTemplates.searchFolders(""));
	}

	@Test
	void classNames() {
		assertEquals("ENGG1101", NoteTemplates.className("Library/ENGG1101"));
		assertEquals("ENGG1101", NoteTemplates.className("Library/ENGG1101/Labs"));
		assertEquals("Physics", NoteTemplates.className("School/Physics"));
		assertEquals("", NoteTemplates.className(""));
	}
}
