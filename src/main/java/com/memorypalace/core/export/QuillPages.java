package com.memorypalace.core.export;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The pages of a book and quill written in the Memory Palace editor (on a lectern). The editor shows the pages
 * joined with a blank line; saving splits the text again so that every page fits what vanilla's book screens show
 * (14 lines of 114 pixels, at most 1024 characters), so the book reads in full without the mod too.
 * <ul>
 * <li>Pages break at blank lines whenever possible, so join(split(text)) gives the text back.</li>
 * <li>A paragraph too long for one page is cut at line ends, then between words; there the join adds a blank
 *     line.</li>
 * <li>More than MAX_PAGES pages does not fit a book: split returns empty.</li>
 * </ul>
 * Note: core/ must not import net.minecraft / net.fabricmc.
 */
public final class QuillPages {
	private QuillPages() {}

	public static final String SEPARATOR = "\n\n";
	/** Vanilla limits for a book and quill (WritableBookContent). */
	public static final int MAX_PAGES = 100;
	public static final int PAGE_CHARS = 1024;
	public static final int MAX_CHARS = MAX_PAGES * PAGE_CHARS;

	public static String join(List<String> pages) {
		return String.join(SEPARATOR, pages);
	}

	public static Optional<List<String>> split(String text) {
		var pages = new ArrayList<String>();
		var page = new StringBuilder();
		int lines = 0;
		for (String paragraph : text.split(SEPARATOR, -1)) {
			for (String piece : fitting(paragraph)) {
				int need = lines(piece) + (page.isEmpty() ? 0 : 1);
				if (!page.isEmpty() && (lines + need > VanillaBook.LINES_PER_PAGE
						|| page.length() + SEPARATOR.length() + piece.length() > PAGE_CHARS)) {
					pages.add(page.toString());
					page.setLength(0);
					lines = 0;
					need = lines(piece);
				}
				if (!page.isEmpty()) {
					page.append(SEPARATOR);
				}
				page.append(piece);
				lines += need;
			}
		}
		pages.add(page.toString());
		return pages.size() > MAX_PAGES ? Optional.empty() : Optional.of(pages);
	}

	/** The paragraph, or the pieces it must be cut into when it does not fit on one page by itself. */
	private static List<String> fitting(String paragraph) {
		if (fits(paragraph)) {
			return List.of(paragraph);
		}
		var pieces = new ArrayList<String>();
		var piece = new StringBuilder();
		for (String line : paragraph.split("\n", -1)) {
			for (String part : fits(line) ? List.of(line) : cutLine(line)) {
				String joined = piece.isEmpty() ? part : piece + "\n" + part;
				if (!piece.isEmpty() && !fits(joined)) {
					pieces.add(piece.toString());
					piece.setLength(0);
					piece.append(part);
				} else {
					piece.setLength(0);
					piece.append(joined);
				}
			}
		}
		pieces.add(piece.toString());
		return pieces;
	}

	/** A single line too long for a page, cut between words (inside a word only when it alone is too long). */
	private static List<String> cutLine(String line) {
		var out = new ArrayList<String>();
		var current = new StringBuilder();
		for (String word : line.split("(?<= )")) {
			String joined = current + word;
			if (fits(joined)) {
				current.append(word);
				continue;
			}
			if (!current.isEmpty()) {
				out.add(current.toString().stripTrailing());
				current.setLength(0);
			}
			if (fits(word)) {
				current.append(word);
			} else {
				int start = 0;
				while (start < word.length()) {
					int end = start + 1;
					while (end < word.length() && fits(word.substring(start, end + 1))) {
						end++;
					}
					out.add(word.substring(start, end));
					start = end;
				}
			}
		}
		if (!current.isEmpty()) {
			out.add(current.toString());
		}
		return out;
	}

	private static boolean fits(String text) {
		return lines(text) <= VanillaBook.LINES_PER_PAGE && text.length() <= PAGE_CHARS;
	}

	/** How many lines text takes on a vanilla page: each line wrapped between words at the page width. */
	static int lines(String text) {
		int count = 0;
		for (String line : text.split("\n", -1)) {
			int lineCount = 1;
			int width = 0;
			for (String word : line.split("(?<= )")) {
				int w = VanillaBook.width(word, false);
				if (width > 0 && width + VanillaBook.width(word.stripTrailing(), false) > VanillaBook.PAGE_WIDTH) {
					lineCount++;
					width = 0;
				}
				// a word wider than the page wraps by itself
				while (w > VanillaBook.PAGE_WIDTH) {
					lineCount++;
					w -= VanillaBook.PAGE_WIDTH;
				}
				width += w;
			}
			count += lineCount;
		}
		return count;
	}
}
