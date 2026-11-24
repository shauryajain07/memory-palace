package com.memorypalace.core.library;

import com.memorypalace.core.export.VanillaBook;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/**
 * The class codes typed after /palace library ("ENGG1101 ENGG1330, MATH1851"): one library room each.
 * A code becomes a folder name (Library/CODE) and the text of a sign, so it is kept short and simple.
 * Note: core/ must not import net.minecraft / net.fabricmc.
 */
public final class CourseCodes {
	/** At most this many rooms in one library. */
	public static final int MAX_ROOMS = 12;
	/** Longest code in characters. */
	public static final int MAX_LENGTH = 24;
	/** A vanilla sign line is 90 font pixels wide (SignBlockEntity.getMaxTextLineWidth). */
	static final int SIGN_LINE_WIDTH = 90;

	/**
	 * @param codes   valid codes in the order typed, upper-cased, without duplicates
	 * @param invalid what could not be used (bad characters, too long for a folder or a sign)
	 */
	public record Parsed(List<String> codes, List<String> invalid) {}

	private CourseCodes() {}

	/** Splits at spaces, commas and semicolons. Never throws. */
	public static Parsed parse(String input) {
		var codes = new LinkedHashSet<String>();
		var invalid = new ArrayList<String>();
		if (input != null) {
			for (String raw : input.strip().split("[\\s,;]+")) {
				if (raw.isEmpty()) {
					continue;
				}
				String code = raw.toUpperCase(Locale.ROOT);
				if (valid(code)) {
					codes.add(code);
				} else {
					invalid.add(raw);
				}
			}
		}
		return new Parsed(List.copyOf(codes), List.copyOf(invalid));
	}

	static boolean valid(String code) {
		// the sign shows the code in bold, which is 1 pixel wider per character
		return code.length() <= MAX_LENGTH && code.matches("[A-Z0-9][A-Z0-9_-]*")
				&& VanillaBook.width(code, true) <= SIGN_LINE_WIDTH;
	}

	/** Folder of a room's notes in the vault. */
	public static String folder(String code) {
		return "Library/" + code;
	}
}
