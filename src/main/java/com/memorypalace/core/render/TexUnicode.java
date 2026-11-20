package com.memorypalace.core.render;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * LaTeX math → readable plain Unicode, for places that can only show text (vanilla written books):
 * {@code \frac{1}{2} + x^2 \le \sqrt{\alpha}} → {@code ½ + x² ≤ √α}.
 *
 * It is a best-effort reading aid, not a renderer: Greek letters, operators, relations and arrows become their
 * Unicode symbols, ^/_ use superscript/subscript characters when every character has one (otherwise "^(…)"),
 * fractions become a/b, matrices (a b; c d), and font commands keep their content. Anything unknown is kept
 * as written (for example "\ce{H2O}"), so nothing is ever lost. Never throws.
 *
 * Note: core/ must not import net.minecraft / net.fabricmc.
 */
public final class TexUnicode {
	/** Deeper nesting than this is copied as written (the input may come from an untrusted server). */
	private static final int MAX_DEPTH = 48;

	private static final Map<String, String> SYMBOLS = new HashMap<>();
	private static final Map<Integer, String> SUPER = new HashMap<>();
	private static final Map<Integer, String> SUB = new HashMap<>();
	private static final Map<String, String> VULGAR = Map.of("1/2", "½", "1/3", "⅓", "2/3", "⅔", "1/4", "¼",
			"3/4", "¾", "1/5", "⅕", "1/6", "⅙", "1/8", "⅛");
	private static final Map<Character, String> DOUBLE_STRUCK = Map.of('C', "ℂ", 'H', "ℍ", 'N', "ℕ", 'P', "ℙ",
			'Q', "ℚ", 'R', "ℝ", 'Z', "ℤ");
	/** Commands that only change size or spacing rules: dropped. */
	private static final Set<String> IGNORED = Set.of("left", "right", "big", "Big", "bigg", "Bigg", "bigl", "bigr",
			"Bigl", "Bigr", "biggl", "biggr", "Biggl", "Biggr", "displaystyle", "textstyle", "scriptstyle",
			"limits", "nolimits", "mathstrut", "strut", "middle", "nonumber", "notag");
	/** Commands whose argument is shown as is (fonts, text). */
	private static final Set<String> KEEP_ARGUMENT = Set.of("mathrm", "mathbf", "mathit", "mathsf", "mathtt",
			"mathcal", "mathfrak", "mathscr", "boldsymbol", "bm", "text", "textrm", "textbf", "textit", "textsf",
			"texttt", "mbox", "hbox", "operatorname", "emph", "textnormal", "mathnormal");
	/** Accent → combining character placed after the (last) character. */
	private static final Map<String, String> ACCENTS = Map.of("vec", "⃗", "hat", "̂", "widehat", "̂",
			"tilde", "̃", "widetilde", "̃", "dot", "̇", "ddot", "̈", "acute", "́",
			"grave", "̀", "check", "̌");
	/** Accents applied to every character (lines over / under the whole argument). */
	private static final Map<String, String> SPANNING = Map.of("bar", "̄", "overline", "̅",
			"underline", "̲");

	static {
		String[][] greek = {
				{"alpha", "α"}, {"beta", "β"}, {"gamma", "γ"}, {"delta", "δ"}, {"epsilon", "ϵ"}, {"varepsilon", "ε"},
				{"zeta", "ζ"}, {"eta", "η"}, {"theta", "θ"}, {"vartheta", "ϑ"}, {"iota", "ι"}, {"kappa", "κ"},
				{"lambda", "λ"}, {"mu", "μ"}, {"nu", "ν"}, {"xi", "ξ"}, {"pi", "π"}, {"varpi", "ϖ"}, {"rho", "ρ"},
				{"varrho", "ϱ"}, {"sigma", "σ"}, {"varsigma", "ς"}, {"tau", "τ"}, {"upsilon", "υ"}, {"phi", "ϕ"},
				{"varphi", "φ"}, {"chi", "χ"}, {"psi", "ψ"}, {"omega", "ω"},
				{"Gamma", "Γ"}, {"Delta", "Δ"}, {"Theta", "Θ"}, {"Lambda", "Λ"}, {"Xi", "Ξ"}, {"Pi", "Π"},
				{"Sigma", "Σ"}, {"Upsilon", "Υ"}, {"Phi", "Φ"}, {"Psi", "Ψ"}, {"Omega", "Ω"}};
		String[][] symbols = {
				// operators
				{"times", "×"}, {"cdot", "·"}, {"div", "÷"}, {"pm", "±"}, {"mp", "∓"}, {"ast", "∗"}, {"star", "⋆"},
				{"circ", "∘"}, {"bullet", "•"}, {"oplus", "⊕"}, {"ominus", "⊖"}, {"otimes", "⊗"}, {"odot", "⊙"},
				{"cup", "∪"}, {"cap", "∩"}, {"setminus", "∖"}, {"wedge", "∧"}, {"land", "∧"}, {"vee", "∨"},
				{"lor", "∨"}, {"neg", "¬"}, {"lnot", "¬"}, {"dagger", "†"}, {"nabla", "∇"}, {"partial", "∂"},
				// big operators
				{"sum", "∑"}, {"prod", "∏"}, {"coprod", "∐"}, {"int", "∫"}, {"iint", "∬"}, {"iiint", "∭"},
				{"oint", "∮"}, {"bigcup", "⋃"}, {"bigcap", "⋂"}, {"bigoplus", "⨁"}, {"bigotimes", "⨂"},
				// relations
				{"le", "≤"}, {"leq", "≤"}, {"ge", "≥"}, {"geq", "≥"}, {"ne", "≠"}, {"neq", "≠"}, {"approx", "≈"},
				{"equiv", "≡"}, {"sim", "∼"}, {"simeq", "≃"}, {"cong", "≅"}, {"propto", "∝"}, {"ll", "≪"},
				{"gg", "≫"}, {"in", "∈"}, {"notin", "∉"}, {"ni", "∋"}, {"subset", "⊂"}, {"subseteq", "⊆"},
				{"supset", "⊃"}, {"supseteq", "⊇"}, {"perp", "⊥"}, {"parallel", "∥"}, {"mid", "∣"}, {"models", "⊨"},
				{"vdash", "⊢"}, {"prec", "≺"}, {"succ", "≻"}, {"doteq", "≐"},
				// arrows
				{"to", "→"}, {"rightarrow", "→"}, {"leftarrow", "←"}, {"gets", "←"}, {"leftrightarrow", "↔"},
				{"Rightarrow", "⇒"}, {"Leftarrow", "⇐"}, {"Leftrightarrow", "⇔"}, {"implies", "⟹"},
				{"impliedby", "⟸"}, {"iff", "⟺"}, {"mapsto", "↦"}, {"uparrow", "↑"}, {"downarrow", "↓"},
				{"longrightarrow", "⟶"}, {"longleftarrow", "⟵"}, {"hookrightarrow", "↪"}, {"rightleftharpoons", "⇌"},
				// letters and misc
				{"infty", "∞"}, {"forall", "∀"}, {"exists", "∃"}, {"nexists", "∄"}, {"emptyset", "∅"},
				{"varnothing", "∅"}, {"hbar", "ℏ"}, {"ell", "ℓ"}, {"Re", "ℜ"}, {"Im", "ℑ"}, {"aleph", "ℵ"},
				{"wp", "℘"}, {"angle", "∠"}, {"triangle", "△"}, {"square", "□"}, {"top", "⊤"}, {"bot", "⊥"},
				{"prime", "′"}, {"degree", "°"}, {"therefore", "∴"}, {"because", "∵"}, {"checkmark", "✓"},
				{"ldots", "…"}, {"dots", "…"}, {"cdots", "⋯"}, {"vdots", "⋮"}, {"ddots", "⋱"},
				// delimiters
				{"langle", "⟨"}, {"rangle", "⟩"}, {"lfloor", "⌊"}, {"rfloor", "⌋"}, {"lceil", "⌈"}, {"rceil", "⌉"},
				{"lVert", "‖"}, {"rVert", "‖"}, {"Vert", "‖"}, {"lvert", "|"}, {"rvert", "|"}, {"vert", "|"},
				{"lbrace", "{"}, {"rbrace", "}"}, {"backslash", "\\"},
				// spacing
				{"quad", "  "}, {"qquad", "    "},
				// named functions keep their names
				{"sin", "sin"}, {"cos", "cos"}, {"tan", "tan"}, {"cot", "cot"}, {"sec", "sec"}, {"csc", "csc"},
				{"arcsin", "arcsin"}, {"arccos", "arccos"}, {"arctan", "arctan"}, {"sinh", "sinh"}, {"cosh", "cosh"},
				{"tanh", "tanh"}, {"log", "log"}, {"ln", "ln"}, {"lg", "lg"}, {"exp", "exp"}, {"lim", "lim"},
				{"max", "max"}, {"min", "min"}, {"sup", "sup"}, {"inf", "inf"}, {"det", "det"}, {"dim", "dim"},
				{"ker", "ker"}, {"deg", "deg"}, {"gcd", "gcd"}, {"arg", "arg"}, {"Pr", "Pr"}, {"mod", "mod"},
				{"bmod", "mod"}, {"limsup", "lim sup"}, {"liminf", "lim inf"}};
		for (String[] pair : greek) {
			SYMBOLS.put(pair[0], pair[1]);
		}
		for (String[] pair : symbols) {
			SYMBOLS.put(pair[0], pair[1]);
		}
		String digits = "0123456789";
		String superDigits = "⁰¹²³⁴⁵⁶⁷⁸⁹";
		String subDigits = "₀₁₂₃₄₅₆₇₈₉";
		for (int i = 0; i < 10; i++) {
			SUPER.put((int) digits.charAt(i), String.valueOf(superDigits.charAt(i)));
			SUB.put((int) digits.charAt(i), String.valueOf(subDigits.charAt(i)));
		}
		map(SUPER, "+-−=()niabcdefghjklmoprstuvwxyzT", "⁺⁻⁻⁼⁽⁾ⁿⁱᵃᵇᶜᵈᵉᶠᵍʰʲᵏˡᵐᵒᵖʳˢᵗᵘᵛʷˣʸᶻᵀ");
		map(SUB, "+-−=()aehijklmnoprstuvx", "₊₋₋₌₍₎ₐₑₕᵢⱼₖₗₘₙₒₚᵣₛₜᵤᵥₓ");
		SUPER.put((int) ' ', "");
		SUB.put((int) ' ', "");
	}

	private static void map(Map<Integer, String> target, String from, String to) {
		for (int i = 0; i < from.length(); i++) {
			target.put((int) from.charAt(i), String.valueOf(to.charAt(i)));
		}
	}

	private TexUnicode() {}

	/**
	 * @param display true for display formulas ($$…$$): rows of aligned/cases environments go on separate lines;
	 *                inline they are separated by "; "
	 */
	public static String convert(String tex, boolean display) {
		if (tex == null || tex.isEmpty()) {
			return "";
		}
		if (tex.length() > MAX_INPUT) {
			return tex;
		}
		try {
			return tidy(new Converter(tex, display).sequence('\0', 0));
		} catch (RuntimeException | StackOverflowError e) {
			return tex; // never lose the formula (and never take the server down with it)
		}
	}

	/** Longer formulas are copied as written. */
	private static final int MAX_INPUT = 2 * MathRenderer.MAX_TEX_CHARS;

	/** Collapses runs of spaces (TeX ignores them) and trims each line. */
	private static String tidy(String s) {
		var lines = new ArrayList<String>();
		for (String line : s.split("\n", -1)) {
			lines.add(line.replaceAll(" {3,}", "  ").strip());
		}
		return String.join("\n", lines).strip();
	}

	private static final class Converter {
		private final String s;
		private final boolean display;
		private int i;

		Converter(String s, boolean display) {
			this.s = s;
			this.display = display;
		}

		/** Converts until the stop character (consumed) or the end of the input. */
		String sequence(char stop, int depth) {
			var out = new StringBuilder();
			if (depth > MAX_DEPTH) {
				// too deep: copy the rest of this group as written
				int start = i;
				int level = 0;
				while (i < s.length()) {
					char c = s.charAt(i);
					if (c == '\\') {
						i += 2;
						continue;
					}
					if (c == '{') {
						level++;
					} else if (c == '}' && level-- == 0 && stop == '}') {
						i++;
						return s.substring(start, i - 1);
					}
					i++;
				}
				return s.substring(start, Math.min(i, s.length()));
			}
			while (i < s.length()) {
				char c = s.charAt(i);
				if (c == stop) {
					i++;
					return out.toString();
				}
				switch (c) {
					case '{' -> {
						i++;
						out.append(sequence('}', depth + 1));
					}
					case '}' -> i++; // stray closing brace
					case '^' -> {
						i++;
						out.append(script(argument(depth), true));
					}
					case '_' -> {
						i++;
						out.append(script(argument(depth), false));
					}
					case '\\' -> {
						String converted = command(depth);
						// "x \, dx": a spacing command next to a typed space is still one space
						if (!(converted.equals(" ") && !out.isEmpty() && out.charAt(out.length() - 1) == ' ')) {
							out.append(converted);
						}
					}
					case '~' -> {
						i++;
						out.append(' ');
					}
					case '&' -> {
						i++;
						out.append(' ');
					}
					case '\'' -> {
						i++;
						out.append('′');
					}
					case '$' -> i++;
					default -> {
						if (Character.isWhitespace(c)) {
							i++;
							if (out.isEmpty() || out.charAt(out.length() - 1) != ' ') {
								out.append(' ');
							}
						} else {
							int cp = s.codePointAt(i);
							out.appendCodePoint(cp);
							i += Character.charCount(cp);
						}
					}
				}
			}
			return out.toString();
		}

		/** One argument: a {group}, a command with its own arguments, or a single character. */
		String argument(int depth) {
			while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
				i++;
			}
			if (i >= s.length()) {
				return "";
			}
			if (depth > MAX_DEPTH) {
				// "\not\not\not…" nests through commands, not groups: stop here too and keep the rest as written
				String rest = s.substring(i);
				i = s.length();
				return rest;
			}
			char c = s.charAt(i);
			if (c == '{') {
				i++;
				return sequence('}', depth + 1);
			}
			if (c == '\\') {
				return command(depth + 1);
			}
			int cp = s.codePointAt(i);
			i += Character.charCount(cp);
			return c == '\'' ? "′" : new String(Character.toChars(cp));
		}

		/** Raw text of a {group} without converting it (environment names). */
		String rawGroup() {
			while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
				i++;
			}
			if (i >= s.length() || s.charAt(i) != '{') {
				return "";
			}
			int close = s.indexOf('}', i);
			if (close < 0) {
				close = s.length();
			}
			String name = s.substring(i + 1, close);
			i = Math.min(s.length(), close + 1);
			return name.strip();
		}

		String optionalBracket(int depth) {
			int save = i;
			while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
				i++;
			}
			if (i < s.length() && s.charAt(i) == '[') {
				i++;
				return sequence(']', depth + 1);
			}
			i = save;
			return null;
		}

		/** s[i] is a backslash. */
		String command(int depth) {
			int start = i;
			i++;
			if (i >= s.length()) {
				return "\\";
			}
			char first = s.charAt(i);
			if (!Character.isLetter(first) || first > 'z') {
				i++;
				return switch (first) {
					case '\\' -> display ? "\n" : "; ";
					case ',', ':', ';', ' ' -> " ";
					case '!' -> "";
					case '|' -> "‖";
					case '{', '}', '%', '$', '&', '#', '_' -> String.valueOf(first);
					default -> s.substring(start, i);
				};
			}
			while (i < s.length() && s.charAt(i) < 128 && Character.isLetter(s.charAt(i))) {
				i++;
			}
			String name = s.substring(start + 1, i);
			if (i < s.length() && s.charAt(i) == '*' && (name.equals("operatorname") || name.equals("sum"))) {
				i++;
			}
			if (IGNORED.contains(name)) {
				// \left. and \right. are invisible delimiters
				if ((name.equals("left") || name.equals("right")) && i < s.length() && s.charAt(i) == '.') {
					i++;
				}
				return "";
			}
			String symbol = SYMBOLS.get(name);
			if (symbol != null) {
				// "\sin\theta" → "sin θ", not "sinθ"; "\sin(x)" stays "sin(x)"
				return symbol.length() > 1 && Character.isLetter(symbol.charAt(0)) ? symbol + spaceBeforeWord() : symbol;
			}
			if (KEEP_ARGUMENT.contains(name)) {
				String arg = argument(depth);
				return name.equals("operatorname") ? arg + spaceBeforeWord() : arg;
			}
			if (name.equals("mathbb")) {
				var out = new StringBuilder();
				for (char c : argument(depth).toCharArray()) {
					out.append(DOUBLE_STRUCK.getOrDefault(c, String.valueOf(c)));
				}
				return out.toString();
			}
			if (ACCENTS.containsKey(name)) {
				String arg = argument(depth);
				return arg.isEmpty() ? "" : arg + ACCENTS.get(name);
			}
			if (SPANNING.containsKey(name)) {
				var out = new StringBuilder();
				argument(depth).codePoints().forEach(cp -> out.appendCodePoint(cp).append(SPANNING.get(name)));
				return out.toString();
			}
			switch (name) {
				case "frac", "dfrac", "tfrac", "cfrac" -> {
					return fraction(argument(depth), argument(depth));
				}
				case "binom", "dbinom", "tbinom" -> {
					return "C(" + argument(depth) + ", " + argument(depth) + ")";
				}
				case "sqrt" -> {
					String index = optionalBracket(depth);
					String radicand = argument(depth);
					String root = index == null || index.equals("2") ? "√" : index.equals("3") ? "∛"
							: index.equals("4") ? "∜" : script(index, true) + "√";
					return root + group(radicand);
				}
				case "begin" -> {
					return environment(rawGroup(), depth);
				}
				case "end" -> {
					rawGroup();
					return "";
				}
				case "not" -> {
					String next = argument(depth);
					return next.isEmpty() ? "" : next + "̸";
				}
				case "pmod" -> {
					return " (mod " + argument(depth) + ")";
				}
				default -> {
					// unknown (e.g. \ce{H2O}): keep the command and its {arguments} as written so nothing is lost
					int end = i;
					while (true) {
						int p = end;
						while (p < s.length() && s.charAt(p) == ' ') {
							p++;
						}
						int close = p < s.length() && s.charAt(p) == '{' ? matchBrace(s, p) : -1;
						if (close < 0) {
							break;
						}
						end = close + 1;
					}
					i = end;
					return s.substring(start, end);
				}
			}
		}

		/** " " when the next thing in the source is a letter or a command, so words do not run together. */
		private String spaceBeforeWord() {
			return i < s.length() && (Character.isLetter(s.charAt(i)) || s.charAt(i) == '\\') ? " " : "";
		}

		/** \begin{name} … \end{name} with rows (\\) and cells (&). */
		String environment(String name, int depth) {
			String body = environmentBody(name);
			List<List<String>> rows = new ArrayList<>();
			for (String row : split(body, "\\\\")) {
				List<String> cells = new ArrayList<>();
				for (String cell : split(row, "&")) {
					// same depth budget as the surrounding formula: nested environments cannot recurse forever
					cells.add(tidy(new Converter(cell, false).sequence('\0', depth + 1)));
				}
				if (!(cells.size() == 1 && cells.getFirst().isEmpty())) {
					rows.add(cells);
				}
			}
			String bare = name.replace("*", "");
			// "a &= b" → "a = b"; cases put a comma between value and condition
			String cellGap = bare.equals("cases") || bare.equals("dcases") ? ", " : " ";
			List<String> lines = rows.stream().map(cells -> String.join(cellGap, cells).strip()).toList();
			return switch (bare) {
				case "pmatrix" -> "(" + String.join("; ", lines) + ")";
				case "bmatrix" -> "[" + String.join("; ", lines) + "]";
				case "Bmatrix" -> "{" + String.join("; ", lines) + "}";
				case "vmatrix" -> "|" + String.join("; ", lines) + "|";
				case "Vmatrix" -> "‖" + String.join("; ", lines) + "‖";
				case "matrix", "smallmatrix", "array" -> String.join("; ", lines);
				// no-break spaces: tidy() strips ordinary leading spaces from each line
				case "cases", "dcases" -> "{ " + String.join(display ? "\n  " : "; ", lines);
				default -> String.join(display ? "\n" : "; ", lines);
			};
		}

		/** Text up to the matching \end{name} (nested environments of the same name are skipped). */
		String environmentBody(String name) {
			String open = "\\begin{" + name + "}";
			String close = "\\end{" + name + "}";
			int level = 0;
			int start = i;
			int p = i;
			while (p < s.length()) {
				if (s.startsWith(open, p)) {
					level++;
					p += open.length();
				} else if (s.startsWith(close, p)) {
					if (level-- == 0) {
						i = p + close.length();
						return s.substring(start, p);
					}
					p += close.length();
				} else {
					p++;
				}
			}
			i = s.length();
			return s.substring(start);
		}
	}

	/** s[open] is '{': index of the matching '}', or -1. */
	private static int matchBrace(String s, int open) {
		int level = 0;
		for (int p = open; p < s.length(); p++) {
			char c = s.charAt(p);
			if (c == '\\') {
				p++;
			} else if (c == '{') {
				level++;
			} else if (c == '}' && --level == 0) {
				return p;
			}
		}
		return -1;
	}

	/** Splits at a separator that is outside {groups} and not part of a longer backslash run. */
	static List<String> split(String text, String separator) {
		var parts = new ArrayList<String>();
		int level = 0;
		int start = 0;
		for (int p = 0; p < text.length(); p++) {
			char c = text.charAt(p);
			if (level == 0 && text.startsWith(separator, p)) {
				parts.add(text.substring(start, p));
				p += separator.length() - 1;
				start = p + 1;
				continue;
			}
			if (c == '\\') {
				p++;
			} else if (c == '{') {
				level++;
			} else if (c == '}') {
				level--;
			}
		}
		parts.add(text.substring(start));
		return parts;
	}

	/** x^{…} / x_{…}: Unicode super/subscripts when every character has one, otherwise ^(…) / _(…). */
	static String script(String content, boolean up) {
		String c = content.strip();
		if (c.isEmpty()) {
			return "";
		}
		if (up && (c.equals("∘") || c.equals("\\circ"))) {
			return "°";
		}
		if (up && c.chars().allMatch(ch -> ch == '′')) {
			return c;
		}
		Map<Integer, String> table = up ? SUPER : SUB;
		var out = new StringBuilder();
		boolean all = c.codePoints().allMatch(table::containsKey);
		if (all) {
			c.codePoints().forEach(cp -> out.append(table.get(cp)));
			return out.toString();
		}
		String mark = up ? "^" : "_";
		return c.codePointCount(0, c.length()) == 1 ? mark + c : mark + "(" + c + ")";
	}

	static String fraction(String numerator, String denominator) {
		String a = numerator.strip();
		String b = denominator.strip();
		String vulgar = VULGAR.get(a + "/" + b);
		if (vulgar != null) {
			return vulgar;
		}
		return group(a) + "/" + group(b);
	}

	/** Wraps in parentheses when the text is more than one simple term. */
	static String group(String text) {
		String t = text.strip();
		if (t.codePointCount(0, t.length()) <= 1 || t.matches("[\\p{L}\\p{N}′⁰-⁹ⁿ₀-₉.]+")
				|| (t.startsWith("(") && t.endsWith(")"))) {
			return t;
		}
		return "(" + t + ")";
	}
}
