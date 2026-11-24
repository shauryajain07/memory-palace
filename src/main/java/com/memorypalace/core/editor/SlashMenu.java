package com.memorypalace.core.editor;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The Notion-style "/" menu of the in-game editor. Type {@code /} at the start of a line or after a space and a
 * list of blocks pops up; keep typing to filter it ({@code /h1}, {@code /todo}, {@code /math}) and press Enter.
 * <ul>
 * <li>Line blocks (headings, lists, to-dos, quotes) turn the current line into that block, like Notion's
 *     "Turn into": {@code My title /h1} becomes {@code # My title}.</li>
 * <li>Multi-line blocks (code, math, table, divider) go on their own lines, with the blank lines Markdown
 *     needs around them.</li>
 * <li>Inline items (inline math, link to a note, today's date) are typed in place of {@code /query}.</li>
 * <li>Actions (image, PDF) only remove {@code /query}; the editor then opens the file picker.</li>
 * </ul>
 * Only blocks the book renderer understands are offered. Note: core/ must not import net.minecraft / net.fabricmc.
 */
public final class SlashMenu {
	private SlashMenu() {}

	public enum Kind { LINE, BLOCK, INLINE, ACTION }

	/**
	 * One menu entry. id is also the translation key suffix (screen.memorypalace.slash.&lt;id&gt;);
	 * hint is the Markdown it stands for, shown greyed out next to the name.
	 */
	public record Command(String id, Kind kind, String hint, String before, String after, List<String> aliases) {}

	/** An open menu: start is the index of the "/", query what was typed after it (up to the cursor). */
	public record Trigger(int start, String query) {}

	/** The edited text and where the cursor goes. */
	public record Edit(String text, int cursor) {}

	/** Replaced with today's date by apply. */
	public static final String TODAY = "{today}";
	public static final int MAX_QUERY = 20;

	public static final List<Command> COMMANDS = List.of(
			line("h1", "#", "# ", "heading", "heading1", "title", "big"),
			line("h2", "##", "## ", "heading2", "subheading", "medium"),
			line("h3", "###", "### ", "heading3", "small"),
			line("bullet", "-", "- ", "bulleted", "list", "ul", "unordered", "dot"),
			line("numbered", "1.", "1. ", "number", "ordered", "list", "ol"),
			line("todo", "[ ]", "- [ ] ", "task", "checkbox", "check", "checklist"),
			line("quote", ">", "> ", "blockquote", "citation"),
			new Command("code", Kind.BLOCK, "```", "```\n", "\n```", List.of("codeblock", "snippet", "program")),
			new Command("math", Kind.BLOCK, "$$", "$$\n", "\n$$",
					List.of("equation", "eq", "latex", "tex", "formula", "display", "block")),
			new Command("inline_math", Kind.INLINE, "$", "$", "$", List.of("inline", "math", "latex", "tex", "formula")),
			new Command("table", Kind.BLOCK, "| |", "| ", " |  |\n| --- | --- |\n|  |  |", List.of("grid", "columns")),
			new Command("divider", Kind.BLOCK, "---", "---\n", "", List.of("line", "hr", "rule", "separator")),
			new Command("link", Kind.INLINE, "[[", "[[", "", List.of("page", "note", "wiki", "mention")),
			new Command("image", Kind.ACTION, "", "", "", List.of("img", "picture", "photo", "upload", "screenshot")),
			new Command("pdf", Kind.ACTION, "", "", "", List.of("file", "document", "slides", "upload", "attach")),
			new Command("date", Kind.INLINE, "", TODAY, "", List.of("today", "now", "day")));

	private static Command line(String id, String hint, String prefix, String... aliases) {
		return new Command(id, Kind.LINE, hint, prefix, "", List.of(aliases));
	}

	private static final Pattern QUERY = Pattern.compile("[\\p{L}\\p{N}_]*");

	/**
	 * Whether the cursor is in a "/query" that should open the menu: the "/" is at the start of the line or after
	 * whitespace, only letters and digits follow it (at most MAX_QUERY), and it is not inside a code block, a math
	 * block, inline code or inline math. A URL or a path such as a/b never opens it.
	 */
	public static Optional<Trigger> find(String text, int cursor) {
		if (cursor < 1 || cursor > text.length()) {
			return Optional.empty();
		}
		int lineStart = text.lastIndexOf('\n', cursor - 1) + 1;
		String before = text.substring(lineStart, cursor);
		int slash = before.lastIndexOf('/');
		if (slash < 0) {
			return Optional.empty();
		}
		String query = before.substring(slash + 1);
		if (query.length() > MAX_QUERY || !QUERY.matcher(query).matches()) {
			return Optional.empty();
		}
		if (slash > 0 && !Character.isWhitespace(before.charAt(slash - 1))) {
			return Optional.empty();
		}
		String ahead = before.substring(0, slash);
		if (insideBlock(text, lineStart) || oddCount(ahead, '`') || oddCount(ahead.replace("\\$", ""), '$')) {
			return Optional.empty();
		}
		return Optional.of(new Trigger(lineStart + slash, query));
	}

	/** Lines before lineStart leave a ``` / ~~~ fence or a $$ math block open. */
	static boolean insideBlock(String text, int lineStart) {
		boolean fence = false;
		int dollars = 0;
		for (String line : text.substring(0, lineStart).split("\n", -1)) {
			String trimmed = line.strip();
			if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
				fence = !fence;
			} else if (!fence) {
				for (int i = trimmed.indexOf("$$"); i >= 0; i = trimmed.indexOf("$$", i + 2)) {
					dollars++;
				}
			}
		}
		return fence || dollars % 2 == 1;
	}

	private static boolean oddCount(String s, char c) {
		return s.chars().filter(ch -> ch == c).count() % 2 == 1;
	}

	/**
	 * The entries matching query, best first: the (translated) name starting with it, then an id or alias
	 * starting with it, then the name or an alias containing it. An empty query lists everything in menu order.
	 * English ids and aliases always work, whatever the game language.
	 */
	public static List<Command> filter(String query, Function<Command, String> label) {
		return filter(query, label, List.of());
	}

	/** Same, with extra entries (the note's templates) after the built-in ones. */
	public static List<Command> filter(String query, Function<Command, String> label, List<Command> extra) {
		String q = query.toLowerCase(Locale.ROOT);
		record Scored(Command command, int score, int order) {}
		var scored = new ArrayList<Scored>();
		var all = new ArrayList<Command>(COMMANDS);
		all.addAll(extra);
		for (int i = 0; i < all.size(); i++) {
			Command c = all.get(i);
			int score = score(c, label.apply(c).toLowerCase(Locale.ROOT), q);
			if (score >= 0) {
				scored.add(new Scored(c, score, i));
			}
		}
		scored.sort(Comparator.comparingInt(Scored::score).thenComparingInt(Scored::order));
		return scored.stream().map(Scored::command).toList();
	}

	private static int score(Command c, String label, String q) {
		if (q.isEmpty() || label.startsWith(q)) {
			return 0;
		}
		var names = new ArrayList<String>(c.aliases());
		names.add(0, c.id());
		if (names.stream().anyMatch(n -> n.startsWith(q))) {
			return 1;
		}
		if (label.contains(q) || names.stream().anyMatch(n -> n.contains(q))) {
			return 2;
		}
		return -1;
	}

	// Leading block markers a line can already have: indentation, headings, list markers, check boxes, quotes
	private static final Pattern MARKERS = Pattern.compile(
			"^(\\s*)((?:#{1,6}\\s+|[-*+]\\s+\\[[ xX]\\]\\s+|[-*+]\\s+|\\d{1,9}[.)]\\s+|>\\s?)*)");

	/** Applies command in place of the "/query" at trigger (which ends at cursor). today replaces TODAY. */
	public static Edit apply(String text, Trigger trigger, int cursor, Command command, String today) {
		int slash = trigger.start();
		int end = Math.clamp(cursor, slash, text.length());
		int lineStart = text.lastIndexOf('\n', slash - 1) + 1;
		int lineEnd = text.indexOf('\n', end);
		if (lineEnd < 0) {
			lineEnd = text.length();
		}
		String head = text.substring(0, lineStart);
		String tail = text.substring(lineEnd);
		String before = text.substring(lineStart, slash);
		String after = text.substring(end, lineEnd);
		return switch (command.kind()) {
			case ACTION -> new Edit(text.substring(0, slash) + text.substring(end), slash);
			case INLINE -> {
				String open = command.before().replace(TODAY, today);
				yield new Edit(text.substring(0, slash) + open + command.after() + text.substring(end),
						slash + open.length());
			}
			case LINE -> {
				Matcher m = MARKERS.matcher(before);
				m.find();
				boolean list = command.before().startsWith("-") || Character.isDigit(command.before().charAt(0));
				String prefix = (list ? m.group(1) : "") + command.before();
				// "My title /h1" → "# My title": drop the space that was in front of the slash
				String rest = before.substring(m.end()).stripTrailing();
				String rest2 = rest.isEmpty() ? after.stripLeading() : after;
				yield new Edit(head + prefix + rest + rest2 + tail, lineStart + prefix.length() + rest.length());
			}
			case BLOCK -> {
				String open = command.before();
				String close = command.after();
				String below = tail.isEmpty() || nextLine(tail).isBlank() ? "" : "\n";
				String start;
				if (before.isBlank() && after.isBlank()) {
					// the block takes this line; Markdown needs a blank line between it and text above or below
					String above = head.isEmpty() || previousLine(head).isBlank() ? "" : "\n";
					start = head + above + open;
				} else {
					// the line has text: keep it and put the block on new lines under it
					start = head + (before + after).stripTrailing() + "\n\n" + open;
				}
				yield new Edit(start + close + below + tail, start.length());
			}
		};
	}

	/** The last line of head (head ends with "\n"). */
	private static String previousLine(String head) {
		String h = head.substring(0, head.length() - 1);
		return h.substring(h.lastIndexOf('\n') + 1);
	}

	/** The first line after tail's leading "\n". */
	private static String nextLine(String tail) {
		String t = tail.substring(1);
		int nl = t.indexOf('\n');
		return nl < 0 ? t : t.substring(0, nl);
	}
}
