package com.memorypalace.core.editor;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Notion-style keys for the in-game editor:
 * <ul>
 * <li>Enter in a list item, to-do or quote starts the next one ({@code - }, {@code 2. }, {@code - [ ] },
 *     {@code > }); Enter on an empty one ends the list.</li>
 * <li>Tab / Shift+Tab in a list item nests it under the item above / moves it back out.</li>
 * <li>Ctrl+B, Ctrl+I, Ctrl+E, Ctrl+Shift+E, Ctrl+K wrap the selection in bold, italic, code, inline math or a
 *     [[link]]; pressing it again on wrapped text unwraps it.</li>
 * </ul>
 * Each method returns empty when the key should keep its normal meaning. Code and math blocks are left alone.
 * Note: core/ must not import net.minecraft / net.fabricmc.
 */
public final class BlockKeys {
	private BlockKeys() {}

	/** The edited text and the new selection (start == end for a plain cursor). */
	public record Edit(String text, int start, int end) {}

	private static final Pattern TODO = Pattern.compile("^(\\s*)([-*+]) \\[[ xX]\\] ");
	private static final Pattern BULLET = Pattern.compile("^(\\s*)([-*+]) ");
	private static final Pattern ORDERED = Pattern.compile("^(\\s*)(\\d{1,9})([.)]) ");
	private static final Pattern QUOTE = Pattern.compile("^((?:> ?)+)");

	/** Enter at cursor: continue the list, to-do or quote on this line, or end it when the item is empty. */
	public static Optional<Edit> enter(String text, int cursor) {
		int lineStart = text.lastIndexOf('\n', cursor - 1) + 1;
		int lineEnd = lineEnd(text, cursor);
		String line = text.substring(lineStart, lineEnd);
		if (SlashMenu.insideBlock(text, lineStart)) {
			return Optional.empty();
		}
		String next;
		int markerEnd;
		Matcher m;
		if ((m = TODO.matcher(line)).find()) {
			next = m.group(1) + m.group(2) + " [ ] ";
			markerEnd = m.end();
		} else if ((m = BULLET.matcher(line)).find()) {
			next = m.group(1) + m.group(2) + " ";
			markerEnd = m.end();
		} else if ((m = ORDERED.matcher(line)).find()) {
			long number = Long.parseLong(m.group(2)) + 1;
			next = m.group(1) + number + m.group(3) + " ";
			markerEnd = m.end();
		} else if ((m = QUOTE.matcher(line)).find()) {
			next = m.group(1).replace(" ", "").replace(">", "> ").stripTrailing() + " ";
			markerEnd = m.end();
		} else {
			return Optional.empty();
		}
		int column = cursor - lineStart;
		if (column < markerEnd) {
			return Optional.empty();
		}
		if (line.substring(markerEnd).isBlank()) {
			// Enter on an empty item: the list ends here, the marker goes away
			String edited = text.substring(0, lineStart) + text.substring(lineEnd);
			return Optional.of(new Edit(edited, lineStart, lineStart));
		}
		String edited = text.substring(0, cursor) + "\n" + next + text.substring(cursor);
		int at = cursor + 1 + next.length();
		return Optional.of(new Edit(edited, at, at));
	}

	/**
	 * Tab (outdent = false) nests the list item at cursor under the item above it; Shift+Tab (outdent = true) moves
	 * it back to the level of its parent. Empty when the line is not a list item or cannot move.
	 */
	public static Optional<Edit> indent(String text, int cursor, boolean outdent) {
		int lineStart = text.lastIndexOf('\n', cursor - 1) + 1;
		String line = text.substring(lineStart, lineEnd(text, cursor));
		Item item = item(line);
		if (item == null || SlashMenu.insideBlock(text, lineStart)) {
			return Optional.empty();
		}
		Item above = null;
		for (int end = lineStart - 1; end > 0; ) {
			int start = text.lastIndexOf('\n', end - 1) + 1;
			String prev = text.substring(start, end);
			Item p = item(prev);
			if (p == null) {
				break;
			}
			if (!outdent || p.indent() < item.indent()) {
				above = p;
				break;
			}
			end = start - 1;
		}
		int target;
		if (outdent) {
			if (item.indent() == 0) {
				return Optional.empty();
			}
			target = above == null ? 0 : above.indent();
		} else {
			if (above == null || item.indent() >= above.contentStart()) {
				return Optional.empty();
			}
			target = above.contentStart();
		}
		String edited = text.substring(0, lineStart) + " ".repeat(target) + line.stripLeading()
				+ text.substring(lineStart + line.length());
		int at = Math.max(lineStart + target, cursor + target - item.indent());
		return Optional.of(new Edit(edited, at, at));
	}

	/** A list line: how far it is indented and where its text starts (where a nested item must start). */
	private record Item(int indent, int contentStart) {}

	private static Item item(String line) {
		Matcher m = BULLET.matcher(line);
		if (!m.find()) {
			m = ORDERED.matcher(line);
			if (!m.find()) {
				return null;
			}
		}
		return new Item(m.group(1).length(), m.end());
	}

	private static int lineEnd(String text, int cursor) {
		int end = text.indexOf('\n', cursor);
		return end < 0 ? text.length() : end;
	}

	/**
	 * Wraps the selection [start, end) in open/close (for example "**", "**"), keeping it selected. With nothing
	 * selected, puts the pair at the cursor with the cursor between. If the selection is already wrapped (just
	 * outside it, or its own first and last characters), the wrapping is removed instead.
	 */
	public static Edit wrap(String text, int start, int end, String open, String close) {
		int a = Math.min(start, end);
		int b = Math.max(start, end);
		String selected = text.substring(a, b);
		if (wrappedOutside(text, a, b, open, close)) {
			String edited = text.substring(0, a - open.length()) + selected + text.substring(b + close.length());
			return new Edit(edited, a - open.length(), b - open.length());
		}
		if (selected.length() >= open.length() + close.length() && selected.startsWith(open) && selected.endsWith(close)
				&& exact(selected, open, close)) {
			String inner = selected.substring(open.length(), selected.length() - close.length());
			return new Edit(text.substring(0, a) + inner + text.substring(b), a, a + inner.length());
		}
		String edited = text.substring(0, a) + open + selected + close + text.substring(b);
		return new Edit(edited, a + open.length(), b + open.length());
	}

	private static boolean wrappedOutside(String text, int a, int b, String open, String close) {
		if (a < open.length() || b + close.length() > text.length()
				|| !text.startsWith(open, a - open.length()) || !text.startsWith(close, b)) {
			return false;
		}
		// "*x*" inside "**x**" is bold, not italic: a one-character marker counts only in a run of 1 or 3 (*** is
		// bold italic)
		if (open.length() == 1 && open.equals(close)) {
			char c = open.charAt(0);
			int left = 0;
			while (a - left - 1 >= 0 && text.charAt(a - left - 1) == c) {
				left++;
			}
			int right = 0;
			while (b + right < text.length() && text.charAt(b + right) == c) {
				right++;
			}
			return left == right && (left == 1 || left == 3);
		}
		return true;
	}

	private static boolean exact(String selected, String open, String close) {
		if (open.length() != 1) {
			return true;
		}
		char c = open.charAt(0);
		return selected.length() > 2 && selected.charAt(1) != c && selected.charAt(selected.length() - 2) != c;
	}
}
