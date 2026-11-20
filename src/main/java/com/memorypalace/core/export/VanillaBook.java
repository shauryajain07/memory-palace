package com.memorypalace.core.export;

import com.memorypalace.core.markdown.MarkdownParser;
import com.memorypalace.core.markdown.MdBlock;
import com.memorypalace.core.markdown.MdSpan;
import com.memorypalace.core.render.TexMacros;
import com.memorypalace.core.render.TexUnicode;

import java.util.ArrayList;
import java.util.List;

/**
 * A note → the pages of an ordinary written book that anyone can read, with or without the mod
 * (/palace export). Markdown becomes simple styled text and LaTeX becomes Unicode (see TexUnicode).
 *
 * The server has no font, so lines are wrapped here with a conservative table of the vanilla font's character
 * widths and then joined with "\n". Every line is at most as wide as the vanilla book page, so the book screen
 * never has to wrap again, and a page never has more lines than the book screen shows (text past line 14 would
 * be cut off silently).
 *
 * Note: core/ must not import net.minecraft / net.fabricmc.
 */
public final class VanillaBook {
	/** Width of the text area of a vanilla book page in font pixels (BookViewScreen wraps at 114). */
	static final int PAGE_WIDTH = 114;
	/** Our lines stay this much narrower than the page (the width table is exact for ASCII, an upper bound otherwise). */
	static final int SAFETY = 2;
	/** Lines the vanilla book screen shows on one page (128 / 9). */
	static final int LINES_PER_PAGE = 14;
	/** Vanilla written books hold at most 100 pages. */
	public static final int MAX_PAGES = 100;

	public enum Kind { TEXT, BOLD, ITALIC, MATH, CODE, QUOTE, MUTED }

	/** A piece of text with one look; "\n" only appears as its own run between lines. */
	public record Run(String text, Kind kind) {}

	/** @param truncated the note did not fit in MAX_PAGES pages; the last page ends with "…" */
	public record Book(List<List<Run>> pages, boolean truncated) {}

	private VanillaBook() {}

	/**
	 * @param imageLabel word shown for images, which a written book cannot hold ("[Image: alt]")
	 */
	public static Book fromMarkdown(String markdown, String imageLabel) {
		TexMacros.Collected collected = TexMacros.collect(MarkdownParser.parse(markdown));
		return paginate(lines(collected.blocks(), collected.macros(), imageLabel));
	}

	// ---- blocks → logical lines ----

	static List<List<Run>> lines(List<MdBlock> blocks, TexMacros macros, String imageLabel) {
		var out = new ArrayList<List<Run>>();
		for (int b = 0; b < blocks.size(); b++) {
			MdBlock block = blocks.get(b);
			MdBlock next = b + 1 < blocks.size() ? blocks.get(b + 1) : null;
			boolean gapAfter = true;
			switch (block) {
				case MdBlock.Heading h -> addLines(out, List.of(), runs(h.spans(), Kind.BOLD, macros));
				case MdBlock.Paragraph p -> addLines(out, List.of(), runs(p.spans(), Kind.TEXT, macros));
				case MdBlock.ListItem item -> {
					String indent = "  ".repeat(item.depth());
					String marker = item.checked() != null ? (item.checked() ? "☑ " : "☐ ")
							: item.marker() != null ? item.marker() + " " : "  ";
					addLines(out, List.of(new Run(indent + marker, Kind.MUTED)), runs(item.spans(), Kind.TEXT, macros));
					gapAfter = !(next instanceof MdBlock.ListItem);
				}
				case MdBlock.Quote q -> {
					addLines(out, List.of(new Run("│ ".repeat(q.depth()), Kind.MUTED)), runs(q.spans(), Kind.QUOTE, macros));
					gapAfter = !(next instanceof MdBlock.Quote);
				}
				case MdBlock.Code c -> {
					for (String line : c.text().split("\n", -1)) {
						out.add(List.of(new Run(line, Kind.CODE)));
					}
				}
				case MdBlock.Math m -> {
					for (String line : math(m.tex(), true, macros).split("\n", -1)) {
						out.add(List.of(new Run("  " + line, Kind.MATH)));
					}
				}
				case MdBlock.Rule r -> out.add(List.of(new Run("- - - - - - - - -", Kind.MUTED)));
				case MdBlock.Image img -> out.add(List.of(new Run("[" + imageLabel
						+ (img.alt().isBlank() ? "" : ": " + img.alt()) + "]", Kind.MUTED)));
				case MdBlock.Table t -> {
					for (int r = 0; r < t.rows().size(); r++) {
						var row = new ArrayList<Run>();
						List<List<MdSpan>> cells = t.rows().get(r);
						for (int c = 0; c < cells.size(); c++) {
							if (c > 0) {
								row.add(new Run(" | ", Kind.MUTED));
							}
							row.addAll(runs(cells.get(c), r == 0 ? Kind.BOLD : Kind.TEXT, macros));
						}
						addLines(out, List.of(), row);
					}
				}
			}
			if (gapAfter) {
				out.add(List.of());
			}
		}
		while (!out.isEmpty() && out.getLast().isEmpty()) {
			out.removeLast();
		}
		return out;
	}

	/** Splits runs at "\n" into separate logical lines; the prefix goes before the first one only. */
	private static void addLines(List<List<Run>> out, List<Run> prefix, List<Run> runs) {
		var line = new ArrayList<Run>(prefix);
		String pad = " ".repeat(prefix.stream().mapToInt(r -> r.text().length()).sum());
		for (Run run : runs) {
			String[] parts = run.text().split("\n", -1);
			for (int i = 0; i < parts.length; i++) {
				if (i > 0) {
					out.add(line);
					line = new ArrayList<>();
					if (!pad.isEmpty()) {
						line.add(new Run(pad, Kind.TEXT));
					}
				}
				if (!parts[i].isEmpty()) {
					line.add(new Run(parts[i], run.kind()));
				}
			}
		}
		out.add(line);
	}

	private static List<Run> runs(List<MdSpan> spans, Kind base, TexMacros macros) {
		var out = new ArrayList<Run>();
		for (MdSpan span : spans) {
			Kind kind = base;
			String text = span.text();
			if (span.has(MdSpan.MATH)) {
				kind = Kind.MATH;
				text = math(text, false, macros);
			} else if (span.has(MdSpan.CODE)) {
				kind = Kind.CODE;
			} else if (span.has(MdSpan.BOLD)) {
				kind = Kind.BOLD;
			} else if (span.has(MdSpan.ITALIC) && base == Kind.TEXT) {
				kind = Kind.ITALIC;
			}
			out.add(new Run(text, kind));
		}
		return out;
	}

	private static String math(String tex, boolean display, TexMacros macros) {
		TexMacros.Expansion expansion = macros.expand(tex);
		return TexUnicode.convert(expansion.ok() ? expansion.tex() : tex, display);
	}

	// ---- wrapping and pages ----

	/** One character with its look, for wrapping. */
	private record Glyph(int codePoint, Kind kind) {}

	static Book paginate(List<List<Run>> logicalLines) {
		var visual = new ArrayList<List<Run>>();
		for (List<Run> line : logicalLines) {
			visual.addAll(wrap(line));
		}
		var pages = new ArrayList<List<Run>>();
		var page = new ArrayList<List<Run>>();
		boolean truncated = false;
		for (List<Run> line : visual) {
			if (page.isEmpty() && line.isEmpty()) {
				continue; // no blank line at the top of a page
			}
			if (page.size() == LINES_PER_PAGE) {
				if (pages.size() == MAX_PAGES - 1) {
					truncated = true;
					break;
				}
				pages.add(join(page));
				page = new ArrayList<>();
				if (line.isEmpty()) {
					continue;
				}
			}
			page.add(line);
		}
		if (truncated) {
			page.set(page.size() - 1, List.of(new Run("…", Kind.MUTED)));
		}
		if (!page.isEmpty() || pages.isEmpty()) {
			pages.add(join(page));
		}
		return new Book(List.copyOf(pages), truncated);
	}

	/** Lines of one page → runs separated by "\n" runs, neighbouring runs of the same kind merged. */
	private static List<Run> join(List<List<Run>> lines) {
		var out = new ArrayList<Run>();
		for (int l = 0; l < lines.size(); l++) {
			if (l > 0) {
				out.add(new Run("\n", Kind.TEXT));
			}
			for (Run run : lines.get(l)) {
				if (run.text().isEmpty()) {
					continue;
				}
				Run last = out.isEmpty() ? null : out.getLast();
				if (last != null && last.kind() == run.kind() && !last.text().equals("\n")) {
					out.set(out.size() - 1, new Run(last.text() + run.text(), run.kind()));
				} else {
					out.add(run);
				}
			}
		}
		return List.copyOf(out);
	}

	/** Greedy word wrap of one logical line into lines no wider than the page. */
	static List<List<Run>> wrap(List<Run> line) {
		var glyphs = new ArrayList<Glyph>();
		for (Run run : line) {
			run.text().codePoints().forEach(cp -> glyphs.add(new Glyph(cp, run.kind())));
		}
		var out = new ArrayList<List<Run>>();
		if (glyphs.isEmpty()) {
			out.add(List.of());
			return out;
		}
		int max = PAGE_WIDTH - SAFETY;
		int start = 0;
		while (start < glyphs.size()) {
			int width = 0;
			int lastSpace = -1;
			int end = start;
			while (end < glyphs.size()) {
				Glyph g = glyphs.get(end);
				int w = width(g.codePoint(), g.kind() == Kind.BOLD);
				if (width + w > max && end > start) {
					break;
				}
				if (g.codePoint() == ' ') {
					lastSpace = end;
				}
				width += w;
				end++;
			}
			int next = end;
			if (end < glyphs.size() && lastSpace > start) {
				// break at the last space that fit; the space itself is dropped
				end = lastSpace;
				next = lastSpace + 1;
			}
			out.add(toRuns(glyphs.subList(start, end)));
			start = next;
			// a wrapped line does not start with spaces
			while (start < glyphs.size() && glyphs.get(start).codePoint() == ' ') {
				start++;
			}
		}
		return out;
	}

	private static List<Run> toRuns(List<Glyph> glyphs) {
		var out = new ArrayList<Run>();
		var text = new StringBuilder();
		Kind kind = null;
		for (Glyph g : glyphs) {
			if (kind != null && g.kind() != kind) {
				out.add(new Run(text.toString(), kind));
				text.setLength(0);
			}
			kind = g.kind();
			text.appendCodePoint(g.codePoint());
		}
		if (kind != null) {
			out.add(new Run(text.toString(), kind));
		}
		return out;
	}

	/** Visual width of a line in font pixels (as estimated by width()). */
	static int width(List<Run> line) {
		int total = 0;
		for (Run run : line) {
			boolean bold = run.kind() == Kind.BOLD;
			total += run.text().codePoints().map(cp -> width(cp, bold)).sum();
		}
		return total;
	}

	// Advance widths of the vanilla font for ASCII, in font pixels including the 1-pixel gap (bold adds 1);
	// checked against ascii.png of Minecraft 26.3 (BookViewScreen.TEXT_WIDTH = 114, TEXT_HEIGHT = 128 = 14 lines).
	private static final int[] ASCII = new int[128];

	static {
		java.util.Arrays.fill(ASCII, 6);
		for (int c = 0; c < 32; c++) {
			ASCII[c] = 0;
		}
		set(2, "!',.:;|i");
		set(3, "l`");
		set(4, " \"()*[]{}It");
		set(5, "<>fk");
		set(7, "@~");
	}

	private static void set(int width, String chars) {
		for (char c : chars.toCharArray()) {
			ASCII[c] = width;
		}
	}

	/**
	 * Advance width: exact for ASCII, and 10 for everything else, which is the widest glyph in the 26.3 default font.
	 * Measured from the game's own assets with the game's rules (bitmap: rightmost opaque column × height / cell
	 * height, rounded, + 1; unifont: trimmed width / 2 + 1): the bitmap pages (accented, nonlatin_european) reach
	 * 10 (Æ, Œ, ☑, ☐ …) and unifont reaches 9 (CJK). Too wide only wastes a little room; too narrow would make the
	 * book screen wrap a line again and push the page's last line out of view.
	 */
	static int width(int codePoint, boolean bold) {
		int w = codePoint < 128 ? ASCII[codePoint] : 10;
		return w == 0 ? 0 : w + (bold ? 1 : 0);
	}
}
