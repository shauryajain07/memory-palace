package com.memorypalace.client.book;

import com.memorypalace.core.markdown.MdBlock;
import com.memorypalace.core.markdown.MdSpan;
import net.minecraft.client.StringSplitter;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.List;

/**
 * 排版：把 Markdown 块变成一行一行（BookLine），再按书页高度分页。
 * 一行由若干"段"组成：文字段，或者图块（公式、图片）。图块和文字按基线对齐。
 * 颜色、缩进这些"长什么样"的决定都在这里。
 *
 * 单位：排版单位 = 未放大的字体像素（画的时候整页再乘字体大小）。
 */
public final class BookLayout {
	// Colours come from the theme (BookTheme, which a resource pack can replace); the default is the parchment book
	// 链接地址挂在文字样式的 insertion 上（书页里不会用到它），点击时按位置取回来（见 BookScreen.linkAt）
	private static final String LINK_PREFIX = "memorypalace-link:";

	// to-do check boxes carry their number the same way, so a click can tick the right one (see TaskToggle)
	private static final String TASK_PREFIX = "memorypalace-task:";

	/** The number of the to-do whose check box has this style; null for anything else. */
	public static Integer taskOf(Style style) {
		String insertion = style == null ? null : style.getInsertion();
		if (insertion == null || !insertion.startsWith(TASK_PREFIX)) {
			return null;
		}
		try {
			return Integer.parseInt(insertion.substring(TASK_PREFIX.length()));
		} catch (NumberFormatException e) {
			return null;
		}
	}

	/** 这段文字样式上挂的链接地址（MdSpan.link）；不是链接时为 null。 */
	public static String linkOf(Style style) {
		String insertion = style == null ? null : style.getInsertion();
		return insertion != null && insertion.startsWith(LINK_PREFIX) ? insertion.substring(LINK_PREFIX.length()) : null;
	}
	// The explanation under a broken formula in the editor preview: same red, smaller weight (italic, not bold)
	private static final String ERROR_MARK = "⚠ ";

	private Style errorNote() {
		return Style.EMPTY.withColor(theme.error()).withItalic(true).withBold(false).withUnderlined(false);
	}

	private static final int INDENT = 8;
	private static final int BLOCK_GAP = 4;
	// 原版字体：一行 9 像素，基线在顶部往下 7 像素
	private static final int FONT_ASCENT = 7;
	private static final int FONT_DESCENT = 2;

	/**
	 * 一个图块（公式或图片）对应的贴图。贴图按"屏幕实际像素"生成，pxPerUnit 是 1 个排版单位对应多少贴图像素，
	 * 这样画出来 1 个贴图像素 = 1 个屏幕像素，不会糊。
	 */
	public record Box(Identifier texture, int rasterW, int rasterH, int rasterDepth, float pxPerUnit, String image) {
		float width() { return rasterW / pxPerUnit; }
		float height() { return rasterH / pxPerUnit; }
		float depth() { return rasterDepth / pxPerUnit; }
	}

	/** 排版器向外要图块用的接口（由笔记窗口实现：负责生成贴图、请求图片）。 */
	public interface Boxes {
		/** 公式图块；TeX 写错了返回 null（会显示红色原文）。 */
		Box math(String tex, boolean display);

		/** 图片图块；还没加载好或加载失败返回 null，这时显示 imagePlaceholder 的文字。 */
		Box image(String src);

		String imagePlaceholder(String src, String alt);

		/** 这个链接对应不上笔记（断链），换个颜色显示。 */
		default boolean brokenLink(String link) {
			return false;
		}

		/**
		 * Why math(tex, display) returned null, as a translated one-liner; null when the reason should not be shown
		 * (only the editor preview shows it) or is unknown.
		 */
		default String mathError(String tex, boolean display) {
			return null;
		}
	}

	/** 一行里的一段。x、y 是相对行左上角的位置。 */
	public sealed interface Seg {}

	public record TextSeg(int x, int y, FormattedCharSequence text) implements Seg {}

	/** scale：图块太宽放不下时的缩小比例（1 = 原大小）。 */
	public record BoxSeg(float x, float y, Box box, float scale) implements Seg {}

	/** 一行。kind 决定额外的装饰（代码底色、引用竖线、分隔线）。 */
	/** heading: the number of the heading this line starts (0 = the first heading of the note), -1 for other lines. */
	public record BookLine(Kind kind, int depth, int height, List<Seg> segs, int heading) {
		public BookLine(Kind kind, int depth, int height, List<Seg> segs) {
			this(kind, depth, height, segs, -1);
		}
	}

	/** The page that heading number heading (in note order) starts on, or -1. Pages may shift as pictures load. */
	public static int pageOfHeading(List<List<BookLine>> pages, int heading) {
		for (int p = 0; p < pages.size(); p++) {
			for (BookLine line : pages.get(p)) {
				if (line.heading() == heading) {
					return p;
				}
			}
		}
		return -1;
	}

	public enum Kind { TEXT, CODE, QUOTE, RULE, GAP }

	// 排版时的"零件"：一段同样式的文字，或一个图块
	private sealed interface Piece {}

	// code = set in the typewriter face (inline code, code blocks)
	private record TextPiece(String text, Style style, boolean code) implements Piece {
		TextPiece(String text, Style style) {
			this(text, style, false);
		}
	}

	private record BoxPiece(Box box) implements Piece {}

	private final Font font;
	private final StringSplitter splitter;
	private final int pageWidth;
	private final int pageHeight;
	private final Boxes boxes;
	private final boolean latex;
	private final BookTheme theme = BookTheme.get();
	private final List<BookLine> lines = new ArrayList<>();
	// to-dos seen so far, and the number to put on the next list marker drawn (null: not a to-do)
	private int tasks;
	private String markerInsertion;
	// headings seen so far, and the heading the next finished line starts (-1: none)
	private int headings;
	private int pendingHeading = -1;

	// Computer Modern, the LaTeX typeface (assets/memorypalace/font/latex*.json). Real bold and italic files are used
	// instead of Minecraft's fake bold (drawn twice) and fake italic (slanted)
	private static final FontDescription LATEX = font("latex");
	private static final FontDescription LATEX_BOLD = font("latex_bold");
	private static final FontDescription LATEX_ITALIC = font("latex_italic");
	private static final FontDescription LATEX_BOLD_ITALIC = font("latex_bold_italic");
	private static final FontDescription LATEX_MONO = font("latex_mono");

	private static FontDescription font(String name) {
		return new FontDescription.Resource(Identifier.fromNamespaceAndPath("memorypalace", name));
	}

	/**
	 * Text around the pages (headers, page numbers, the "/" menu) in the book's typeface when LaTeX is on, so the
	 * whole book looks the same; Minecraft's font otherwise.
	 */
	public static Component ui(Component text) {
		return BookSettings.typeface() == BookSettings.Typeface.LATEX ? text.copy().withStyle(s -> s.withFont(LATEX)) : text;
	}

	public static Component ui(String text) {
		return ui(Component.literal(text));
	}

	/** Markdown or LaTeX source shown around the pages (the "/" menu's hints): the typewriter face. */
	public static Component uiCode(String text) {
		Component literal = Component.literal(text);
		return BookSettings.typeface() == BookSettings.Typeface.LATEX ? literal.copy().withStyle(s -> s.withFont(LATEX_MONO)) : literal;
	}

	private static java.lang.reflect.Field fontProvider;

	/**
	 * A copy of base whose plain text is set in typewriter Computer Modern instead of Minecraft's font, for the
	 * editor's typing area: the vanilla text box measures and draws with whatever Font it is given, so cursor,
	 * wrapping and selection all follow. Falls back to base if the game's Font cannot be wrapped.
	 */
	public static Font editorFont(Font base) {
		try {
			if (fontProvider == null) {
				fontProvider = Font.class.getDeclaredField("provider");
				fontProvider.setAccessible(true);
			}
			Font.Provider provider = (Font.Provider) fontProvider.get(base);
			return new Font(new Font.Provider() {
				@Override
				public net.minecraft.client.gui.GlyphSource glyphs(FontDescription description) {
					return provider.glyphs(description.equals(FontDescription.DEFAULT) ? LATEX_MONO : description);
				}

				@Override
				public net.minecraft.client.gui.font.glyphs.EffectGlyph effect() {
					return provider.effect();
				}
			});
		} catch (ReflectiveOperationException | RuntimeException e) {
			return base;
		}
	}

	private BookLayout(Font font, int pageWidth, int pageHeight, Boxes boxes, boolean latex) {
		this.font = font;
		this.splitter = font.getSplitter();
		this.pageWidth = pageWidth;
		this.pageHeight = pageHeight;
		this.boxes = boxes;
		this.latex = latex;
	}

	/**
	 * 排版并分页：文字区宽 pageWidth、高 pageHeight（排版单位）。返回的每一项是一页的行，至少一页。
	 * latex: set the text in Computer Modern instead of Minecraft's pixel font (BookSettings.Typeface).
	 */
	public static List<List<BookLine>> paginate(Font font, List<MdBlock> blocks, int pageWidth, int pageHeight, Boxes boxes,
			boolean latex) {
		BookLayout layout = new BookLayout(font, pageWidth, pageHeight, boxes, latex);
		for (MdBlock block : blocks) {
			layout.add(block);
		}
		return layout.pages();
	}

	private void add(MdBlock block) {
		switch (block) {
			case MdBlock.Heading h -> {
				if (!lines.isEmpty()) {
					gap(BLOCK_GAP);
				}
				Style style = Style.EMPTY.withColor(theme.heading()).withBold(true)
						.withUnderlined(h.level() == 1).withItalic(h.level() >= 3);
				pendingHeading = headings++;
				flow(Kind.TEXT, 0, pieces(h.spans(), style), null, 0);
				pendingHeading = -1;
				gap(BLOCK_GAP);
			}
			case MdBlock.Paragraph p -> {
				flow(Kind.TEXT, 0, pieces(p.spans(), Style.EMPTY.withColor(theme.text())), null, 0);
				gap(BLOCK_GAP);
			}
			case MdBlock.ListItem item -> {
				int x = item.depth() * INDENT;
				String marker = item.checked() == null ? item.marker()
						: (item.checked() ? "☑" : "☐");
				Style base = Style.EMPTY.withColor(item.checked() == Boolean.TRUE ? theme.muted() : theme.text())
						.withStrikethrough(item.checked() == Boolean.TRUE ? true : null);
				if (item.checked() != null) {
					markerInsertion = TASK_PREFIX + tasks++;
				}
				flow(Kind.TEXT, x, pieces(item.spans(), base), marker, 0);
				markerInsertion = null;
				gap(1);
			}
			case MdBlock.Quote q -> {
				flow(Kind.QUOTE, q.depth() * 6, pieces(q.spans(), Style.EMPTY.withColor(theme.muted()).withItalic(true)), null, q.depth());
				gap(BLOCK_GAP);
			}
			case MdBlock.Code c -> {
				Style style = Style.EMPTY.withColor(theme.code());
				for (String line : c.text().split("\n", -1)) {
					flow(Kind.CODE, 3, List.of(new TextPiece(line.isEmpty() ? " " : line, style, true)), null, 0);
				}
				gap(BLOCK_GAP);
			}
			case MdBlock.Math m -> {
				Box box = boxes.math(m.tex(), true);
				if (box == null) {
					flow(Kind.TEXT, 0, List.of(new TextPiece("$$" + m.tex() + "$$", Style.EMPTY.withColor(theme.error()))), null, 0);
					String error = boxes.mathError(m.tex(), true);
					if (error != null) {
						flow(Kind.TEXT, 0, List.of(new TextPiece(ERROR_MARK + error, errorNote())), null, 0);
					}
				} else {
					centeredBox(box, false);
				}
				gap(BLOCK_GAP);
			}
			case MdBlock.Image img -> {
				Box box = boxes.image(img.src());
				if (box == null) {
					flow(Kind.TEXT, 0, List.of(new TextPiece(boxes.imagePlaceholder(img.src(), img.alt()),
							Style.EMPTY.withColor(theme.muted()).withItalic(true))), null, 0);
				} else {
					// 图片铺满页宽（小图也放大），太高就按页高缩
					centeredBox(box, true);
				}
				gap(BLOCK_GAP);
			}
			case MdBlock.Rule r -> {
				lines.add(new BookLine(Kind.RULE, 0, 7, List.of()));
				gap(BLOCK_GAP);
			}
			case MdBlock.Table t -> {
				for (int i = 0; i < t.rows().size(); i++) {
					Style style = Style.EMPTY.withColor(theme.text()).withBold(i == 0 ? true : null);
					List<Piece> row = new ArrayList<>();
					List<List<MdSpan>> cells = t.rows().get(i);
					for (int c = 0; c < cells.size(); c++) {
						if (c > 0) {
							row.add(new TextPiece(" │ ", Style.EMPTY.withColor(theme.muted())));
						}
						row.addAll(pieces(cells.get(c), style));
					}
					flow(Kind.TEXT, 0, row, null, 0);
				}
				gap(BLOCK_GAP);
			}
		}
	}

	/**
	 * 单独一行、居中的图块（块级公式、图片）。太宽就按页宽缩小，太高就按页高缩小。
	 * fillWidth = true（图片）时小的也放大到页宽。
	 */
	private void centeredBox(Box box, boolean fillWidth) {
		float fit = Math.min(pageWidth / box.width(), (pageHeight - 2) / box.height());
		float scale = fillWidth ? fit : Math.min(1.0F, fit);
		float w = box.width() * scale;
		int height = (int) Math.ceil(box.height() * scale);
		lines.add(new BookLine(Kind.TEXT, 0, height, List.of(new BoxSeg((pageWidth - w) / 2, 0, box, scale))));
	}

	// ---- 自动换行 ----

	/** 一行正在拼的内容。 */
	private final class LineBuilder {
		final Kind kind;
		final int depth;
		final List<Object[]> items = new ArrayList<>(); // {x, Piece-或-Box, 文字}
		float x;
		float ascent = FONT_ASCENT;
		float descent = FONT_DESCENT;

		LineBuilder(Kind kind, int depth, float startX) {
			this.kind = kind;
			this.depth = depth;
			this.x = startX;
		}

		void addText(String text, Style style) {
			items.add(new Object[]{x, style, text});
			x += font.width(FormattedText.of(text, style));
		}

		void addBox(Box box, float scale) {
			items.add(new Object[]{x, box, scale});
			x += box.width() * scale;
			ascent = Math.max(ascent, (box.height() - box.depth()) * scale);
			descent = Math.max(descent, box.depth() * scale);
		}

		void finish() {
			int a = (int) Math.ceil(ascent);
			int height = a + (int) Math.ceil(descent);
			List<Seg> segs = new ArrayList<>();
			for (Object[] it : items) {
				float ix = (float) it[0];
				if (it[1] instanceof Style style) {
					FormattedCharSequence seq = Component.literal((String) it[2]).withStyle(style).getVisualOrderText();
					segs.add(new TextSeg(Math.round(ix), a - FONT_ASCENT, seq));
				} else {
					Box box = (Box) it[1];
					float scale = (float) it[2];
					segs.add(new BoxSeg(ix, a - (box.height() - box.depth()) * scale, box, scale));
				}
			}
			lines.add(new BookLine(kind, depth, Math.max(height, font.lineHeight), segs, pendingHeading));
			pendingHeading = -1; // only the heading's first line is its anchor
		}
	}

	/**
	 * 把一串零件按页宽排成若干行。"\n" 强制换行；marker（列表符号）只画在第一行，后面的行和文字对齐（悬挂缩进）。
	 */
	private void flow(Kind kind, int x, List<Piece> pieces, String marker, int depth) {
		float indent = x;
		LineBuilder line = new LineBuilder(kind, depth, x);
		if (marker != null) {
			Style ms = face(Style.EMPTY.withColor(theme.muted()), false);
			if (markerInsertion != null) {
				ms = ms.withInsertion(markerInsertion);
			}
			line.addText(marker + " ", ms);
			indent = line.x;
		}
		for (Piece piece : pieces) {
			switch (piece) {
				case BoxPiece bp -> {
					float available = pageWidth - indent;
					float scale = Math.min(1.0F, available / bp.box().width());
					if (line.x > indent && line.x + bp.box().width() * scale > pageWidth) {
						line.finish();
						line = new LineBuilder(kind, depth, indent);
					}
					line.addBox(bp.box(), scale);
				}
				case TextPiece tp -> {
					// the font is chosen here, so measuring (line breaks) and drawing use the same typeface
					Style style = face(tp.style(), tp.code());
					String[] logical = tp.text().split("\n", -1);
					for (int i = 0; i < logical.length; i++) {
						if (i > 0) {
							line.finish();
							line = new LineBuilder(kind, depth, indent);
						}
						line = flowText(line, logical[i], style, kind, depth, indent);
					}
				}
			}
		}
		line.finish();
	}

	/**
	 * The typeface for a piece of text. With LaTeX on: Computer Modern in the matching weight and slant (the style's
	 * bold / italic flags are cleared, the font itself is bold or italic), and the typewriter face for code.
	 */
	private Style face(Style style, boolean code) {
		if (!latex) {
			return style;
		}
		boolean bold = style.isBold();
		boolean italic = style.isItalic();
		FontDescription typeface = code ? LATEX_MONO
				: bold ? (italic ? LATEX_BOLD_ITALIC : LATEX_BOLD) : italic ? LATEX_ITALIC : LATEX;
		return style.withFont(typeface).withBold(false).withItalic(false);
	}

	/** 把一段文字塞进当前行，放不下就按词换行，返回最后那一行（可能还没满）。 */
	private LineBuilder flowText(LineBuilder line, String text, Style style, Kind kind, int depth, float indent) {
		String rest = text;
		while (!rest.isEmpty()) {
			int available = (int) Math.floor(pageWidth - line.x);
			int cut = available <= 0 ? 0 : splitter.findLineBreak(rest, available, style);
			if (cut < rest.length() && line.x > indent && cut > 0 && splitsWord(rest, cut)) {
				// 行中间放不下一个完整的英文单词：整个单词挪到下一行，别从中间切开
				cut = 0;
			}
			if (cut == 0) {
				if (line.x > indent) {
					line.finish();
					line = new LineBuilder(kind, depth, indent);
					rest = rest.stripLeading();
					continue;
				}
				// 一整行都放不下一个字符（页面极窄）：硬塞一个，避免死循环
				cut = Math.max(1, splitter.plainIndexAtWidth(rest, Math.max(1, available), style));
			}
			line.addText(rest.substring(0, cut), style);
			rest = rest.substring(cut);
			if (!rest.isEmpty()) {
				line.finish();
				line = new LineBuilder(kind, depth, indent);
				rest = rest.stripLeading();
			}
		}
		return line;
	}

	/** 在 cut 处切开是不是把一个英文单词/数字切成了两半。 */
	private static boolean splitsWord(String s, int cut) {
		char before = s.charAt(cut - 1);
		char after = s.charAt(cut);
		return isWordChar(before) && isWordChar(after) && s.substring(0, cut).chars().noneMatch(Character::isWhitespace);
	}

	private static boolean isWordChar(char c) {
		return c < 0x2E80 && Character.isLetterOrDigit(c);
	}

	/** MdSpan → 排版零件。base 是这一块的基础样式（颜色等）。行内公式变成图块，写错了显示红色原文。 */
	private List<Piece> pieces(List<MdSpan> spans, Style base) {
		List<Piece> out = new ArrayList<>();
		for (MdSpan span : spans) {
			if (span.has(MdSpan.MATH)) {
				Box box = boxes.math(span.text(), false);
				if (box != null) {
					out.add(new BoxPiece(box));
				} else {
					out.add(new TextPiece("$" + span.text() + "$", base.withColor(theme.error())));
					String error = boxes.mathError(span.text(), false);
					if (error != null) {
						out.add(new TextPiece(" " + ERROR_MARK + error + " ", errorNote()));
					}
				}
				continue;
			}
			Style style = base;
			boolean code = span.has(MdSpan.CODE);
			if (span.has(MdSpan.BOLD)) style = style.withBold(true);
			if (span.has(MdSpan.ITALIC)) style = style.withItalic(true);
			if (span.has(MdSpan.STRIKE)) style = style.withStrikethrough(true);
			if (code) style = style.withColor(theme.code());
			if (com.memorypalace.core.markdown.Tags.isTagLink(span.link())) {
				// #tags: link colour, no underline; a click searches for the tag
				style = style.withColor(theme.link()).withInsertion(LINK_PREFIX + span.link());
			} else if (span.link() != null) {
				style = style.withColor(boxes.brokenLink(span.link()) ? theme.brokenLink() : theme.link()).withUnderlined(true)
						.withInsertion(LINK_PREFIX + span.link());
			}
			out.add(new TextPiece(span.text(), style, code));
		}
		return out;
	}

	private void gap(int height) {
		lines.add(new BookLine(Kind.GAP, 0, height, List.of()));
	}

	private List<List<BookLine>> pages() {
		List<List<BookLine>> pages = new ArrayList<>();
		List<BookLine> page = new ArrayList<>();
		int used = 0;
		for (BookLine line : lines) {
			// 页首的空隙没有意义，跳过
			if (line.kind() == Kind.GAP && page.isEmpty()) {
				continue;
			}
			if (used + line.height() > pageHeight && !page.isEmpty()) {
				pages.add(page);
				page = new ArrayList<>();
				used = 0;
				if (line.kind() == Kind.GAP) {
					continue;
				}
			}
			page.add(line);
			used += line.height();
		}
		if (!page.isEmpty() || pages.isEmpty()) {
			pages.add(page);
		}
		return pages;
	}
}
