package com.memorypalace.core.markdown;

import java.util.List;
import java.util.Optional;

/**
 * Ticking a to-do while reading: the n-th check box shown in the book is the n-th one MarkdownParser emits, so
 * its "[ ]" / "[x]" is found through MarkdownParser.taskMarkers and flipped in place. Nothing else in the text
 * changes. Note: core/ must not import net.minecraft / net.fabricmc.
 */
public final class TaskToggle {
	private TaskToggle() {}

	/** The text with to-do number index flipped ("[ ]" ↔ "[x]"); empty when there is no such to-do. */
	public static Optional<String> toggle(String markdown, int index) {
		List<Integer> boxes = MarkdownParser.taskMarkers(markdown);
		if (index < 0 || index >= boxes.size() || boxes.get(index) < 0) {
			return Optional.empty();
		}
		int at = boxes.get(index) + 1;
		char now = markdown.charAt(at);
		char next = now == ' ' ? 'x' : ' ';
		return Optional.of(markdown.substring(0, at) + next + markdown.substring(at + 1));
	}
}
