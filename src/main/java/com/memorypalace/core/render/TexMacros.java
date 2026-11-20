package com.memorypalace.core.render;

import com.memorypalace.core.markdown.MdBlock;
import com.memorypalace.core.markdown.MdSpan;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Per-note LaTeX macros. A definition written in any formula of a note applies to every formula of that note,
 * like in Obsidian (MathJax), and never leaks into other notes:
 * <pre>
 * $$\newcommand{\R}{\mathbb{R}} \newcommand{\norm}[1]{\left\lVert #1 \right\rVert}$$
 * Let $x \in \R^n$, then $\norm{x} \ge 0$.
 * </pre>
 * Supported: \newcommand, \renewcommand, \providecommand (each with an optional *), with [n] parameters and an
 * optional [default] for the first one, plus \DeclareMathOperator and \DeclareMathOperator*.
 *
 * Why not let JLaTeXMath do it: its \newcommand writes into one table shared by the whole game, so a macro from
 * one note would silently work in every other note (and depend on which note was opened first). Here definitions
 * are cut out of the formulas before rendering, and uses are expanded by text substitution.
 *
 * Expansion is iterative (no recursion) and capped, because note bodies may come from an untrusted server:
 * a macro that expands into itself stops with MACRO_LOOP instead of hanging or overflowing the stack.
 *
 * Note: core/ must not import net.minecraft / net.fabricmc.
 */
public final class TexMacros {
	/** At most this many macro uses are expanded in one formula. */
	static final int MAX_EXPANSIONS = 2_000;
	/** At most this many definitions per note; later ones are still removed from the formula but ignored. */
	static final int MAX_DEFINITIONS = 256;

	private static final TexMacros EMPTY = new TexMacros(Map.of(), Set.of());

	// Commands that define something. JLaTeXMath must never see them (see the class comment);
	// MathRenderer refuses any formula that still contains one after extraction.
	private static final Set<String> DEFINING = Set.of("newcommand", "renewcommand", "providecommand",
			"DeclareMathOperator", "newenvironment", "renewenvironment", "def", "gdef", "edef", "let");

	/**
	 * One macro.
	 * @param name            without the backslash, e.g. "R"
	 * @param params          number of arguments, 0..9
	 * @param optionalDefault default value of the first argument when it is optional ([default] in the
	 *                        definition); null when every argument is required
	 * @param body            replacement text; #1..#9 are the arguments, ## is a literal #
	 */
	public record Definition(String name, int params, String optionalDefault, String body) {}

	/** Result of expanding one formula: on success tex is the expanded formula and problem is null. */
	public record Expansion(String tex, MathProblem problem) {
		public boolean ok() {
			return problem == null;
		}
	}

	/** The definitions found in one formula and what is left of the formula without them (may be blank). */
	public record Extraction(List<Definition> definitions, List<Boolean> provideOnly, String rest) {}

	/** The note's macros plus its blocks with every definition removed (see collect). */
	public record Collected(TexMacros macros, List<MdBlock> blocks) {}

	private final Map<String, Definition> definitions;
	// Macros that use themselves, directly or through others (\a → \b → \a). Plain substitution has no way to stop
	// them, so a use fails at once instead of spending MAX_EXPANSIONS in every formula of the note.
	private final Set<String> recursive;

	private TexMacros(Map<String, Definition> definitions, Set<String> recursive) {
		this.definitions = definitions;
		this.recursive = recursive;
	}

	public static TexMacros empty() {
		return EMPTY;
	}

	public boolean isEmpty() {
		return definitions.isEmpty();
	}

	public Map<String, Definition> definitions() {
		return definitions;
	}

	// ---- collecting the definitions of a note ----

	/**
	 * Finds the definitions in every formula of a note (display $$...$$ and inline $...$, including headings,
	 * lists, quotes and tables), and returns them together with the blocks rewritten without the definitions.
	 * Formulas that contained nothing but definitions disappear; a paragraph or quote that becomes empty that way
	 * is dropped. Later definitions replace earlier ones, except \providecommand, which only defines a name that
	 * is still free. Every definition applies to the whole note, also above the place it is written.
	 * Never throws; with no definitions the blocks are returned unchanged.
	 */
	public static Collected collect(List<MdBlock> blocks) {
		var found = new LinkedHashMap<String, Definition>();
		boolean[] changed = {false};
		var out = new ArrayList<MdBlock>(blocks.size());
		for (MdBlock block : blocks) {
			switch (block) {
				case MdBlock.Math m -> {
					Extraction e = extract(m.tex());
					if (e.definitions().isEmpty()) {
						out.add(m);
					} else {
						changed[0] = true;
						register(found, e);
						if (!e.rest().isBlank()) {
							out.add(new MdBlock.Math(e.rest().strip()));
						}
					}
				}
				case MdBlock.Heading h -> out.add(new MdBlock.Heading(h.level(), strip(h.spans(), found, changed)));
				case MdBlock.Paragraph p -> {
					List<MdSpan> spans = strip(p.spans(), found, changed);
					if (!blank(spans) || blank(p.spans())) {
						out.add(spans == p.spans() ? p : new MdBlock.Paragraph(spans));
					}
				}
				case MdBlock.ListItem item -> out.add(new MdBlock.ListItem(item.depth(), item.marker(), item.checked(),
						strip(item.spans(), found, changed)));
				case MdBlock.Quote q -> {
					List<MdSpan> spans = strip(q.spans(), found, changed);
					if (!blank(spans) || blank(q.spans())) {
						out.add(new MdBlock.Quote(q.depth(), spans));
					}
				}
				case MdBlock.Table t -> {
					var rows = new ArrayList<List<List<MdSpan>>>();
					for (List<List<MdSpan>> row : t.rows()) {
						var cells = new ArrayList<List<MdSpan>>();
						for (List<MdSpan> cell : row) {
							cells.add(strip(cell, found, changed));
						}
						rows.add(List.copyOf(cells));
					}
					out.add(new MdBlock.Table(List.copyOf(rows)));
				}
				default -> out.add(block);
			}
		}
		if (!changed[0]) {
			return new Collected(EMPTY, blocks);
		}
		return new Collected(found.isEmpty() ? EMPTY : new TexMacros(Map.copyOf(found), recursiveNames(found)),
				List.copyOf(out));
	}

	/** Names that can reach themselves through the macros their bodies use (at most MAX_DEFINITIONS, so cheap). */
	private static Set<String> recursiveNames(Map<String, Definition> found) {
		var uses = new java.util.HashMap<String, Set<String>>();
		for (Definition d : found.values()) {
			var used = new java.util.HashSet<String>();
			String body = d.body();
			for (int i = body.indexOf('\\'); i >= 0; i = body.indexOf('\\', i)) {
				int end = controlSequenceEnd(body, i);
				String name = body.substring(i + 1, end);
				if (found.containsKey(name)) {
					used.add(name);
				}
				i = end;
			}
			uses.put(d.name(), used);
		}
		var recursive = new java.util.HashSet<String>();
		for (String start : found.keySet()) {
			var seen = new java.util.HashSet<String>();
			var stack = new java.util.ArrayDeque<>(uses.get(start));
			while (!stack.isEmpty()) {
				String name = stack.pop();
				if (name.equals(start)) {
					recursive.add(start);
					break;
				}
				if (seen.add(name)) {
					stack.addAll(uses.getOrDefault(name, Set.of()));
				}
			}
		}
		return Set.copyOf(recursive);
	}

	private static void register(Map<String, Definition> found, Extraction e) {
		for (int i = 0; i < e.definitions().size(); i++) {
			Definition d = e.definitions().get(i);
			if (e.provideOnly().get(i) && found.containsKey(d.name())) {
				continue;
			}
			if (found.size() >= MAX_DEFINITIONS && !found.containsKey(d.name())) {
				continue;
			}
			found.put(d.name(), d);
		}
	}

	/** Removes definitions from the inline formulas of a span list; returns the same list when nothing changed. */
	private static List<MdSpan> strip(List<MdSpan> spans, Map<String, Definition> found, boolean[] changed) {
		List<MdSpan> out = null;
		for (int i = 0; i < spans.size(); i++) {
			MdSpan span = spans.get(i);
			MdSpan replacement = span;
			boolean drop = false;
			if (span.has(MdSpan.MATH)) {
				Extraction e = extract(span.text());
				if (!e.definitions().isEmpty()) {
					register(found, e);
					changed[0] = true;
					drop = e.rest().isBlank();
					replacement = new MdSpan(e.rest().strip(), span.styles(), span.link());
				}
			}
			if (out == null && (drop || replacement != span)) {
				out = new ArrayList<>(spans.subList(0, i));
			}
			if (out != null && !drop) {
				out.add(replacement);
			}
		}
		return out == null ? spans : List.copyOf(out);
	}

	private static boolean blank(List<MdSpan> spans) {
		return spans.stream().allMatch(s -> s.text().isBlank());
	}

	// ---- reading definitions out of one formula ----

	/**
	 * Cuts every well-formed definition out of a formula. A definition that cannot be read (for example
	 * "\newcommand{\R}" without a body) is left in the text, so that MathRenderer reports it.
	 */
	public static Extraction extract(String tex) {
		if (tex == null || tex.indexOf('\\') < 0) {
			return new Extraction(List.of(), List.of(), tex == null ? "" : tex);
		}
		var definitions = new ArrayList<Definition>();
		var provideOnly = new ArrayList<Boolean>();
		var rest = new StringBuilder(tex.length());
		int i = 0;
		while (i < tex.length()) {
			char c = tex.charAt(i);
			if (c != '\\') {
				rest.append(c);
				i++;
				continue;
			}
			int end = controlSequenceEnd(tex, i);
			String command = tex.substring(i + 1, end);
			int parsedEnd = -1;
			switch (command) {
				case "newcommand", "renewcommand", "providecommand" -> {
					parsedEnd = readNewCommand(tex, end, definitions);
					if (parsedEnd >= 0) {
						provideOnly.add(command.equals("providecommand"));
					}
				}
				case "DeclareMathOperator" -> {
					parsedEnd = readOperator(tex, end, definitions);
					if (parsedEnd >= 0) {
						provideOnly.add(false);
					}
				}
				default -> {
				}
			}
			if (parsedEnd >= 0) {
				i = parsedEnd;
			} else {
				rest.append(tex, i, end);
				i = end;
			}
		}
		return new Extraction(List.copyOf(definitions), List.copyOf(provideOnly), rest.toString());
	}

	/** \newcommand[*]{\name}[n][default]{body} or \newcommand\name...; returns the end index, or -1. */
	private static int readNewCommand(String tex, int pos, List<Definition> out) {
		int p = skipSpaces(tex, pos);
		if (p < tex.length() && tex.charAt(p) == '*') {
			p = skipSpaces(tex, p + 1);
		}
		String[] name = new String[1];
		p = readName(tex, p, name);
		if (p < 0) {
			return -1;
		}
		int params = 0;
		String optionalDefault = null;
		p = skipSpaces(tex, p);
		if (p < tex.length() && tex.charAt(p) == '[') {
			int close = matchBracket(tex, p);
			if (close < 0) {
				return -1;
			}
			String count = tex.substring(p + 1, close).strip();
			if (!count.matches("[0-9]")) {
				return -1;
			}
			params = count.charAt(0) - '0';
			p = skipSpaces(tex, close + 1);
			if (p < tex.length() && tex.charAt(p) == '[') {
				close = matchBracket(tex, p);
				if (close < 0 || params == 0) {
					return -1;
				}
				optionalDefault = tex.substring(p + 1, close);
				p = skipSpaces(tex, close + 1);
			}
		}
		if (p >= tex.length() || tex.charAt(p) != '{') {
			return -1;
		}
		int close = matchBrace(tex, p);
		if (close < 0) {
			return -1;
		}
		out.add(new Definition(name[0], params, optionalDefault, tex.substring(p + 1, close)));
		return close + 1;
	}

	/** \DeclareMathOperator[*]{\name}{text}; the macro expands to \operatorname{text} (or \operatorname*). */
	private static int readOperator(String tex, int pos, List<Definition> out) {
		int p = skipSpaces(tex, pos);
		boolean star = p < tex.length() && tex.charAt(p) == '*';
		if (star) {
			p = skipSpaces(tex, p + 1);
		}
		String[] name = new String[1];
		p = readName(tex, p, name);
		if (p < 0) {
			return -1;
		}
		p = skipSpaces(tex, p);
		if (p >= tex.length() || tex.charAt(p) != '{') {
			return -1;
		}
		int close = matchBrace(tex, p);
		if (close < 0) {
			return -1;
		}
		String body = (star ? "\\operatorname*{" : "\\operatorname{") + tex.substring(p + 1, close) + "}";
		out.add(new Definition(name[0], 0, null, body));
		return close + 1;
	}

	/** The macro name, written as {\name} or \name (letters only). Returns the end index, or -1. */
	private static int readName(String tex, int p, String[] name) {
		boolean braced = p < tex.length() && tex.charAt(p) == '{';
		int q = braced ? skipSpaces(tex, p + 1) : p;
		if (q >= tex.length() || tex.charAt(q) != '\\') {
			return -1;
		}
		int end = controlSequenceEnd(tex, q);
		if (end == q + 1 || !isLetter(tex.charAt(q + 1))) {
			return -1;
		}
		name[0] = tex.substring(q + 1, end);
		if (!braced) {
			return end;
		}
		int close = skipSpaces(tex, end);
		return close < tex.length() && tex.charAt(close) == '}' ? close + 1 : -1;
	}

	/** Whether the formula still contains a command that would define something inside JLaTeXMath. */
	public static boolean containsDefinition(String tex) {
		if (tex == null) {
			return false;
		}
		int i = 0;
		while ((i = tex.indexOf('\\', i)) >= 0) {
			int end = controlSequenceEnd(tex, i);
			if (end > i + 1 && DEFINING.contains(tex.substring(i + 1, end))) {
				return true;
			}
			i = end;
		}
		return false;
	}

	// ---- expanding ----

	/**
	 * Replaces every use of a macro in the formula. The result is scanned again, so macros may use other macros.
	 * Arguments are read like TeX does: a {group}, or else a single command or character.
	 * Never throws; a runaway macro gives MACRO_LOOP, a result over MathRenderer.MAX_TEX_CHARS gives MACRO_TOO_LONG.
	 */
	public Expansion expand(String tex) {
		if (tex == null || definitions.isEmpty() || tex.indexOf('\\') < 0) {
			return new Expansion(tex, null);
		}
		var out = new StringBuilder(tex);
		int expansions = 0;
		int i = 0;
		while (i < out.length()) {
			if (out.charAt(i) != '\\') {
				i++;
				continue;
			}
			int nameEnd = controlSequenceEnd(out, i);
			Definition def = nameEnd > i + 1 && isLetter(out.charAt(i + 1))
					? definitions.get(out.substring(i + 1, nameEnd)) : null;
			if (def == null) {
				i = nameEnd;
				continue;
			}
			if (recursive.contains(def.name()) || ++expansions > MAX_EXPANSIONS) {
				return new Expansion(tex, new MathProblem(MathProblem.Kind.MACRO_LOOP, "\\" + def.name()));
			}
			String[] args = new String[def.params()];
			int pos = readArguments(out, nameEnd, def, args);
			String replacement = substitute(def.body(), args);
			// Keep tokens apart where gluing text would merge two commands: "\alpha" + "x" must not become "\alphax"
			if (!replacement.isEmpty() && isLetter(replacement.charAt(0)) && endsWithControlWord(out, i)) {
				replacement = " " + replacement;
			}
			if (pos < out.length() && isLetter(out.charAt(pos))
					&& endsWithControlWord(replacement, replacement.length())) {
				replacement = replacement + " ";
			}
			out.replace(i, pos, replacement);
			if (out.length() > MathRenderer.MAX_TEX_CHARS) {
				return new Expansion(tex, new MathProblem(MathProblem.Kind.MACRO_TOO_LONG,
						String.valueOf(MathRenderer.MAX_TEX_CHARS)));
			}
			// do not advance: the replacement may itself use macros
		}
		return new Expansion(out.toString(), null);
	}

	private static int readArguments(CharSequence s, int pos, Definition def, String[] args) {
		for (int a = 0; a < args.length; a++) {
			int p = skipSpaces(s, pos);
			if (a == 0 && def.optionalDefault() != null) {
				int close = p < s.length() && s.charAt(p) == '[' ? matchBracket(s, p) : -1;
				if (close >= 0) {
					args[0] = s.subSequence(p + 1, close).toString();
					pos = close + 1;
				} else {
					args[0] = def.optionalDefault();
				}
				continue;
			}
			if (p >= s.length()) {
				args[a] = "";
				pos = p;
			} else if (s.charAt(p) == '{') {
				int close = matchBrace(s, p);
				if (close < 0) {
					args[a] = s.subSequence(p + 1, s.length()).toString();
					pos = s.length();
				} else {
					args[a] = s.subSequence(p + 1, close).toString();
					pos = close + 1;
				}
			} else if (s.charAt(p) == '\\') {
				int end = controlSequenceEnd(s, p);
				args[a] = s.subSequence(p, end).toString();
				pos = end;
			} else {
				int end = p + Character.charCount(Character.codePointAt(s, p));
				args[a] = s.subSequence(p, end).toString();
				pos = end;
			}
		}
		return pos;
	}

	/** Puts the arguments into the body: #1..#9 → argument, ## → #. */
	private static String substitute(String body, String[] args) {
		var sb = new StringBuilder(body.length() + 16);
		int i = 0;
		while (i < body.length()) {
			char c = body.charAt(i);
			if (c == '\\') {
				int end = controlSequenceEnd(body, i);
				sb.append(body, i, end);
				i = end;
				continue;
			}
			if (c == '#' && i + 1 < body.length()) {
				char d = body.charAt(i + 1);
				if (d == '#') {
					sb.append('#');
					i += 2;
					continue;
				}
				if (d >= '1' && d <= '9' && d - '1' < args.length) {
					String arg = args[d - '1'];
					if (!arg.isEmpty() && isLetter(arg.charAt(0)) && endsWithControlWord(sb, sb.length())) {
						sb.append(' ');
					}
					sb.append(arg);
					i += 2;
					if (i < body.length() && isLetter(body.charAt(i)) && endsWithControlWord(sb, sb.length())) {
						sb.append(' ');
					}
					continue;
				}
			}
			sb.append(c);
			i++;
		}
		return sb.toString();
	}

	// ---- small TeX scanning helpers ----

	private static boolean isLetter(char c) {
		return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
	}

	/** s[start] is a backslash: end of "\name" (letters) or of a control symbol like "\\" or "\{". */
	static int controlSequenceEnd(CharSequence s, int start) {
		int i = start + 1;
		if (i >= s.length()) {
			return s.length();
		}
		if (!isLetter(s.charAt(i))) {
			return i + 1;
		}
		while (i < s.length() && isLetter(s.charAt(i))) {
			i++;
		}
		return i;
	}

	/** Whether s[0..end) ends with a control word such as "\alpha" (and not with "\\" followed by letters). */
	private static boolean endsWithControlWord(CharSequence s, int end) {
		int j = end;
		while (j > 0 && isLetter(s.charAt(j - 1))) {
			j--;
		}
		if (j == end || j == 0 || s.charAt(j - 1) != '\\') {
			return false;
		}
		int slashes = 0;
		for (int k = j - 1; k >= 0 && s.charAt(k) == '\\'; k--) {
			slashes++;
		}
		return slashes % 2 == 1;
	}

	private static int skipSpaces(CharSequence s, int p) {
		while (p < s.length() && Character.isWhitespace(s.charAt(p))) {
			p++;
		}
		return p;
	}

	/** s[open] is '{': index of the matching '}', or -1. Escaped braces \{ \} do not count. */
	private static int matchBrace(CharSequence s, int open) {
		int depth = 0;
		for (int i = open; i < s.length(); i++) {
			char c = s.charAt(i);
			if (c == '\\') {
				i++;
			} else if (c == '{') {
				depth++;
			} else if (c == '}' && --depth == 0) {
				return i;
			}
		}
		return -1;
	}

	/** s[open] is '[': index of the ']' that closes it outside any {group}, or -1. */
	private static int matchBracket(CharSequence s, int open) {
		int depth = 0;
		for (int i = open + 1; i < s.length(); i++) {
			char c = s.charAt(i);
			if (c == '\\') {
				i++;
			} else if (c == '{') {
				depth++;
			} else if (c == '}') {
				depth--;
			} else if (c == ']' && depth == 0) {
				return i;
			}
		}
		return -1;
	}
}
