package com.memorypalace.core.cards;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Flashcards written in notes as one line each: {@code Question :: Answer} (the "::" with a space on both sides,
 * so C++'s std::vector is not a card). Both sides may use Markdown and LaTeX. A list marker, check box or quote
 * marker in front is not part of the question. Lines in code blocks and $$ blocks, and a "::" inside `inline code`,
 * are never cards.
 * <p>
 * A card's id comes from its note's id and its question, so editing the answer or moving the note keeps its
 * schedule; changing the question makes it a new card. The same question twice in one note is one card.
 * Note: core/ must not import net.minecraft / net.fabricmc.
 */
public final class Flashcards {
	private Flashcards() {}

	public static final String SEPARATOR = " :: ";
	public static final int MAX_SIDE = 500;

	public record Card(String id, String question, String answer) {}

	private static final Pattern PREFIX = Pattern.compile("^\\s*(?:>\\s*)*(?:(?:[-*+]|\\d{1,9}[.)])\\s+(?:\\[[ xX]\\]\\s+)?)?");

	public static List<Card> parse(UUID noteId, String markdown) {
		var out = new ArrayList<Card>();
		Set<String> seen = new LinkedHashSet<>();
		boolean fence = false;
		boolean math = false;
		for (String line : markdown.split("\n", -1)) {
			String trimmed = line.strip();
			if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
				fence = !fence;
				continue;
			}
			if (fence) {
				continue;
			}
			int dollars = 0;
			for (int i = trimmed.indexOf("$$"); i >= 0; i = trimmed.indexOf("$$", i + 2)) {
				dollars++;
			}
			boolean wasMath = math;
			if (dollars % 2 == 1) {
				math = !math;
			}
			if (wasMath || dollars > 0 && trimmed.startsWith("$$")) {
				continue;
			}
			int at = line.indexOf(SEPARATOR);
			// "::" inside `inline code` is an example, not a card
			if (at < 0 || line.substring(0, at).chars().filter(ch -> ch == '`').count() % 2 == 1) {
				continue;
			}
			String question = PREFIX.matcher(line.substring(0, at)).replaceFirst("").strip();
			String answer = line.substring(at + SEPARATOR.length()).strip();
			if (question.isEmpty() || answer.isEmpty() || question.length() > MAX_SIDE || answer.length() > MAX_SIDE) {
				continue;
			}
			String id = id(noteId, question);
			if (seen.add(id)) {
				out.add(new Card(id, question, answer));
			}
		}
		return out;
	}

	/** 16 hex characters from the note's id and the question (whitespace runs count as one space). */
	public static String id(UUID noteId, String question) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(
					(noteId + "\n" + question.strip().replaceAll("\\s+", " ")).getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest, 0, 8);
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}
}
