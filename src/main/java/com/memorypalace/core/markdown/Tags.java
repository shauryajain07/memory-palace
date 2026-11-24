package com.memorypalace.core.markdown;

import java.util.Collection;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * #tags in notes, like Obsidian's: "#" right after a space, the start of a line or an opening bracket, then
 * letters, digits, "_", "-" and "/" (for nested tags such as #exam/midterm), with at least one letter.
 * <ul>
 * <li>Not tags: "C#", "#123", "&amp;#39;", URL fragments (page#part), headings ("# Title"), anything in code or math,
 *     link text and [[note#heading]] links.</li>
 * <li>A tag becomes a span whose link is "tag:" + name, so the reader can draw and click it; MarkdownParser.links
 *     leaves tags out (they are not links between notes), MarkdownParser.tags lists them.</li>
 * </ul>
 * Note: core/ must not import net.minecraft / net.fabricmc.
 */
public final class Tags {
	private Tags() {}

	public static final String LINK_PREFIX = "tag:";

	static final Pattern PATTERN = Pattern.compile("(?<![\\p{L}\\p{N}_/&#\\]`'])#([\\p{L}\\p{N}_][\\p{L}\\p{N}_/-]*)");

	/** The tag without trailing "/" or "-", or null when it has no letter (#123 is not a tag). */
	static String clean(String raw) {
		String tag = raw.replaceAll("[/-]+$", "");
		return tag.isEmpty() || tag.codePoints().allMatch(Character::isDigit) ? null : tag;
	}

	public static boolean isTagLink(String link) {
		return link != null && link.startsWith(LINK_PREFIX);
	}

	/** "tag:exam/midterm" → "exam/midterm". */
	public static String name(String link) {
		return link.substring(LINK_PREFIX.length());
	}

	/**
	 * Whether a note with these tags (lower case, as MarkdownParser.tags gives them) matches the search tag:
	 * the same tag, or one nested under it (#exam finds #exam/midterm). Case does not matter.
	 */
	public static boolean matches(Collection<String> noteTags, String wanted) {
		String w = wanted.startsWith("#") ? wanted.substring(1) : wanted;
		w = w.toLowerCase(Locale.ROOT);
		for (String tag : noteTags) {
			if (tag.equals(w) || tag.startsWith(w + "/")) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Notes in a "Templates" folder (anywhere in the path) are templates for /template, not real notes: their
	 * placeholder tags and cards are left out of tag lists and flashcard decks.
	 */
	public static boolean inTemplates(String relativePath) {
		String[] parts = relativePath.replace('\\', '/').split("/");
		for (int i = 0; i < parts.length - 1; i++) {
			if (parts[i].equalsIgnoreCase("Templates")) {
				return true;
			}
		}
		return false;
	}
}
