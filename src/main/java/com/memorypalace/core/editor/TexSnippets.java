package com.memorypalace.core.editor;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Tab completion for LaTeX in the in-game editor, so formulas are quick to type on a game keyboard:
 * <ul>
 * <li>Type a trigger such as {@code \frac} and press Tab: it becomes {@code \frac{}{}} with the cursor in the
 *     first pair of braces.</li>
 * <li>Press Tab again to jump into the next empty {@code {}} on the same line ({@code \frac{a}{|}}).</li>
 * </ul>
 * A trigger the note defines itself with \newcommand is left alone, so personal macros keep their meaning.
 * When nothing applies, apply returns empty and the key keeps its normal meaning.
 *
 * Note: core/ must not import net.minecraft / net.fabricmc.
 */
public final class TexSnippets {
	/** Text put before and after the cursor in place of the trigger. */
	public record Template(String before, String after) {}

	/** The edited text and where the cursor goes. */
	public record Edit(String text, int cursor) {}

	private static final Map<String, Template> SNIPPETS = new LinkedHashMap<>();

	private static void add(String trigger, String before, String after) {
		SNIPPETS.put(trigger, new Template(before, after));
	}

	static {
		// fractions, roots, big operators
		add("frac", "\\frac{", "}{}");
		add("dfrac", "\\dfrac{", "}{}");
		add("tfrac", "\\tfrac{", "}{}");
		add("sqrt", "\\sqrt{", "}");
		add("nroot", "\\sqrt[", "]{}");
		add("sum", "\\sum_{", "}^{}");
		add("prod", "\\prod_{", "}^{}");
		add("int", "\\int_{", "}^{}");
		add("oint", "\\oint_{", "}");
		add("lim", "\\lim_{", " \\to \\infty}");
		add("binom", "\\binom{", "}{}");
		// derivatives
		add("dd", "\\frac{\\mathrm{d}", "}{\\mathrm{d}x}");
		add("pd", "\\frac{\\partial ", "}{\\partial x}");
		// accents and fonts
		for (String accent : List.of("vec", "hat", "bar", "dot", "ddot", "tilde", "overline", "underline",
				"mathbb", "mathbf", "mathrm", "mathcal", "mathit", "boldsymbol", "text", "operatorname")) {
			add(accent, "\\" + accent + "{", "}");
		}
		// brackets that grow with their content
		add("lr", "\\left( ", " \\right)");
		add("lrb", "\\left[ ", " \\right]");
		add("lrc", "\\left\\{ ", " \\right\\}");
		add("abs", "\\left| ", " \\right|");
		add("norm", "\\left\\lVert ", " \\right\\rVert");
		// environments
		add("pmat", "\\begin{pmatrix} ", " & \\\\ & \\end{pmatrix}");
		add("bmat", "\\begin{bmatrix} ", " & \\\\ & \\end{bmatrix}");
		add("vmat", "\\begin{vmatrix} ", " & \\\\ & \\end{vmatrix}");
		add("cases", "\\begin{cases} ", " & \\text{if } \\\\ & \\text{otherwise} \\end{cases}");
		add("aligned", "\\begin{aligned} ", " &= \\\\ &= \\end{aligned}");
	}

	/** Longest trigger; nothing longer is looked up. */
	private static final int MAX_TRIGGER = SNIPPETS.keySet().stream().mapToInt(String::length).max().orElse(0);

	private TexSnippets() {}

	/** All triggers with their templates, in a stable order (for help text). */
	public static Map<String, Template> all() {
		return Collections.unmodifiableMap(SNIPPETS);
	}

	/**
	 * What pressing Tab at cursor does.
	 * @param userMacros names the note defines itself (without backslash); these triggers are not expanded
	 * @return the edit, or empty when Tab should keep its normal meaning
	 */
	public static Optional<Edit> apply(String text, int cursor, Set<String> userMacros) {
		if (text == null || cursor < 0 || cursor > text.length()) {
			return Optional.empty();
		}
		Optional<Edit> expanded = expand(text, cursor, userMacros == null ? Set.of() : userMacros);
		return expanded.isPresent() ? expanded : jumpToNextEmptyGroup(text, cursor);
	}

	/** "\frac|" → "\frac{|}{}". */
	static Optional<Edit> expand(String text, int cursor, Set<String> userMacros) {
		int start = cursor;
		while (start > 0 && cursor - start <= MAX_TRIGGER && isLetter(text.charAt(start - 1))) {
			start--;
		}
		if (start == cursor || start == 0 || text.charAt(start - 1) != '\\') {
			return Optional.empty();
		}
		// "\\frac" is a line break followed by the word frac: a trigger needs an odd number of backslashes
		int slashes = 0;
		for (int i = start - 1; i >= 0 && text.charAt(i) == '\\'; i--) {
			slashes++;
		}
		String trigger = text.substring(start, cursor);
		Template template = SNIPPETS.get(trigger);
		if (slashes % 2 == 0 || template == null || userMacros.contains(trigger)) {
			return Optional.empty();
		}
		String edited = text.substring(0, start - 1) + template.before() + template.after() + text.substring(cursor);
		return Optional.of(new Edit(edited, start - 1 + template.before().length()));
	}

	/** "\frac{a|}{}" → "\frac{a}{|}": the next "{}" after the cursor on the same line ("\{}" is a literal brace). */
	static Optional<Edit> jumpToNextEmptyGroup(String text, int cursor) {
		int lineEnd = text.indexOf('\n', cursor);
		int end = lineEnd < 0 ? text.length() : lineEnd;
		int at = text.indexOf("{}", cursor);
		while (at >= 0 && at + 2 <= end && at > 0 && text.charAt(at - 1) == '\\') {
			at = text.indexOf("{}", at + 1);
		}
		if (at < 0 || at + 2 > end) {
			return Optional.empty();
		}
		return Optional.of(new Edit(text, at + 1));
	}

	private static boolean isLetter(char c) {
		return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
	}
}
