package com.memorypalace.core.export;

import com.memorypalace.core.markdown.MarkdownParser;
import com.memorypalace.core.markdown.MdBlock;
import com.memorypalace.core.markdown.MdSpan;
import com.memorypalace.core.render.Raster;
import com.memorypalace.core.render.TexMacros;
import org.apache.fontbox.ttf.TrueTypeCollection;
import org.apache.fontbox.ttf.TrueTypeFont;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionURI;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDBorderStyleDictionary;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A note as a PDF to hand in or share: A4 pages typeset in Computer Modern (the fonts JLaTeXMath ships), with
 * headings, lists and check boxes, quotes, code, tables, links (web links stay clickable), pictures and formulas
 * (drawn as sharp pictures), and page numbers.
 * <ul>
 * <li>Computer Modern draws a few ASCII characters wrongly ( " &lt; &gt; \ _ { | } ), so those come from the
 *     typewriter face. Other characters it lacks (accents, Greek, Chinese…) come from Helvetica, then from a
 *     Unicode font found on the computer; a character none of them has becomes "?".</li>
 * <li>Formulas are rendered by the caller (Formulas) at PX_PER_PT pixels per point, so this class never touches
 *     JLaTeXMath (whose state is shared with the game). Pictures come from the caller too (Images).</li>
 * <li>What could not be shown (missing pictures, broken formulas, pages past MAX_PAGES) is listed in
 *     Result.notes.</li>
 * </ul>
 * Note: core/ must not import net.minecraft / net.fabricmc.
 */
public final class PdfExport {
	private PdfExport() {}

	public interface Images {
		Optional<Raster> load(String src);
	}

	/** A formula drawn at BODY * PX_PER_PT pixels per em; depth = pixels below the baseline. */
	public record Formula(Raster raster, int depth) {}

	public interface Formulas {
		/** null when the formula cannot be drawn. */
		Formula render(String tex, boolean display);
	}

	public record MathRequest(String tex, boolean display) {}

	public record Result(byte[] pdf, int pages, List<String> notes) {}

	public static final float BODY = 11f;
	public static final float PX_PER_PT = 4f;
	public static final int MAX_PAGES = 500;

	private static final float PAGE_W = PDRectangle.A4.getWidth();
	private static final float PAGE_H = PDRectangle.A4.getHeight();
	private static final float MARGIN = 64;
	private static final float TOP = PAGE_H - MARGIN;
	private static final float BOTTOM = MARGIN;
	private static final float WIDTH = PAGE_W - 2 * MARGIN;

	/** The formulas of a note as export will ask Formulas for them (macros expanded), to render them first. */
	public static List<MathRequest> mathIn(String markdown) {
		TexMacros.Collected collected = TexMacros.collect(MarkdownParser.parse(markdown));
		var out = new ArrayList<MathRequest>();
		for (MdBlock block : collected.blocks()) {
			switch (block) {
				case MdBlock.Math m -> expand(collected.macros(), m.tex(), true, out);
				case MdBlock.Heading h -> inline(collected.macros(), h.spans(), out);
				case MdBlock.Paragraph p -> inline(collected.macros(), p.spans(), out);
				case MdBlock.ListItem i -> inline(collected.macros(), i.spans(), out);
				case MdBlock.Quote q -> inline(collected.macros(), q.spans(), out);
				case MdBlock.Table t -> t.rows().forEach(row -> row.forEach(cell -> inline(collected.macros(), cell, out)));
				default -> {
				}
			}
		}
		return out;
	}

	private static void inline(TexMacros macros, List<MdSpan> spans, List<MathRequest> out) {
		for (MdSpan span : spans) {
			if (span.has(MdSpan.MATH)) {
				expand(macros, span.text(), false, out);
			}
		}
	}

	private static void expand(TexMacros macros, String tex, boolean display, List<MathRequest> out) {
		TexMacros.Expansion e = macros.expand(tex);
		if (e.ok()) {
			out.add(new MathRequest(e.tex(), display));
		}
	}

	public static Result export(String title, String markdown, Images images, Formulas formulas) throws IOException {
		try (var doc = new PDDocument(); var setter = new Typesetter(doc, images, formulas)) {
			PDDocumentInformation info = doc.getDocumentInformation();
			info.setTitle(title);
			info.setCreator("Memory Palace");
			TexMacros.Collected collected = TexMacros.collect(MarkdownParser.parse(markdown));
			setter.macros = collected.macros();
			for (MdBlock block : collected.blocks()) {
				if (setter.full) {
					setter.notes.add("pages after " + MAX_PAGES);
					break;
				}
				setter.block(block);
			}
			setter.finish();
			var bytes = new ByteArrayOutputStream();
			doc.save(bytes);
			return new Result(bytes.toByteArray(), doc.getNumberOfPages(), List.copyOf(setter.notes));
		}
	}

	// ---- typesetting ----

	private enum Face { ROMAN, BOLD, ITALIC, BOLD_ITALIC, MONO }

	/** One thing on a line: a word (with how it looks) or a picture (formula). */
	private sealed interface Piece permits Word, Box {}

	private record Word(String text, Face face, float size, int color, boolean strike, boolean underline, String url,
			boolean code, boolean spaceBefore) implements Piece {}

	private record Box(PDImageXObject image, float width, float height, float depth, boolean spaceBefore) implements Piece {}

	private record Placed(Piece piece, float x, float width) {}

	private static final class Typesetter implements Closeable {
		private final PDDocument doc;
		private final Images images;
		private final Formulas formulas;
		private final List<String> notes = new ArrayList<>();
		private final Map<Face, PDFont> cm = new HashMap<>();
		private final Map<Face, PDFont> helvetica = new HashMap<>();
		private final Map<PDFont, Map<Integer, Boolean>> encodable = new HashMap<>();
		private final Map<String, PDImageXObject> formulaImages = new HashMap<>();
		private PDFont unicode;
		private boolean unicodeTried;
		private Closeable unicodeSource;
		TexMacros macros;

		private PDPage page;
		private PDPageContentStream cs;
		private float y;
		private boolean full;

		Typesetter(PDDocument doc, Images images, Formulas formulas) throws IOException {
			this.doc = doc;
			this.images = images;
			this.formulas = formulas;
			cm.put(Face.ROMAN, load("latin/jlm_cmr10.ttf"));
			cm.put(Face.BOLD, load("latin/optional/jlm_cmbx10.ttf"));
			cm.put(Face.ITALIC, load("latin/optional/jlm_cmti10.ttf"));
			cm.put(Face.BOLD_ITALIC, load("latin/optional/jlm_cmbxti10.ttf"));
			cm.put(Face.MONO, load("latin/optional/jlm_cmtt10.ttf"));
			helvetica.put(Face.ROMAN, new PDType1Font(Standard14Fonts.FontName.HELVETICA));
			helvetica.put(Face.BOLD, new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD));
			helvetica.put(Face.ITALIC, new PDType1Font(Standard14Fonts.FontName.HELVETICA_OBLIQUE));
			helvetica.put(Face.BOLD_ITALIC, new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD_OBLIQUE));
			helvetica.put(Face.MONO, new PDType1Font(Standard14Fonts.FontName.COURIER));
			newPage();
		}

		private PDFont load(String path) throws IOException {
			try (InputStream in = PdfExport.class.getResourceAsStream("/org/scilab/forge/jlatexmath/fonts/" + path)) {
				if (in == null) {
					throw new IOException("font missing: " + path);
				}
				return PDType0Font.load(doc, in, true);
			}
		}

		// ---- fonts per character ----

		private static final String OT1_WRONG = "\"<>\\_{|}";

		private PDFont fontFor(int cp, Face face) {
			if (cp > 32 && cp < 127) {
				if (face == Face.MONO || OT1_WRONG.indexOf(cp) >= 0
						|| cp == '$' && (face == Face.ITALIC || face == Face.BOLD_ITALIC)) {
					return cm.get(Face.MONO);
				}
				return cm.get(face);
			}
			PDFont h = helvetica.get(face);
			if (canEncode(h, cp)) {
				return h;
			}
			PDFont u = unicodeFont();
			return u != null && canEncode(u, cp) ? u : null;
		}

		private boolean canEncode(PDFont font, int cp) {
			return encodable.computeIfAbsent(font, f -> new HashMap<>()).computeIfAbsent(cp, c -> {
				try {
					font.encode(new String(Character.toChars(c)));
					return true;
				} catch (IllegalArgumentException | IOException e) {
					return false;
				}
			});
		}

		private static final List<String> UNICODE_FONTS = List.of(
				"/System/Library/Fonts/Supplemental/Arial Unicode.ttf", "/Library/Fonts/Arial Unicode.ttf",
				"C:\\Windows\\Fonts\\arialuni.ttf", "C:\\Windows\\Fonts\\msyh.ttc", "C:\\Windows\\Fonts\\simsun.ttc",
				"/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc", "/usr/share/fonts/noto-cjk/NotoSansCJK-Regular.ttc",
				"/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf");

		/** A font on this computer with many scripts (for Chinese and the like); null when there is none. */
		private PDFont unicodeFont() {
			if (unicodeTried) {
				return unicode;
			}
			unicodeTried = true;
			for (String path : UNICODE_FONTS) {
				File file = new File(path);
				if (!file.isFile()) {
					continue;
				}
				try {
					if (path.toLowerCase(java.util.Locale.ROOT).endsWith(".ttc")) {
						var collection = new TrueTypeCollection(file);
						TrueTypeFont[] first = {null};
						collection.processAllFonts(font -> {
							if (first[0] == null) {
								first[0] = font;
							}
						});
						if (first[0] != null) {
							unicode = PDType0Font.load(doc, first[0], true);
							unicodeSource = collection;
							return unicode;
						}
						collection.close();
					} else {
						unicode = PDType0Font.load(doc, file);
						return unicode;
					}
				} catch (IOException | RuntimeException ignored) {
					// try the next one
				}
			}
			return null;
		}

		/** Width of text in face at size: each character in the font that draws it. Spaces are not drawn. */
		private float width(String text, Face face, float size) {
			float w = 0;
			for (int i = 0; i < text.length(); ) {
				int cp = text.codePointAt(i);
				i += Character.charCount(cp);
				w += charWidth(cp, face, size);
			}
			return w;
		}

		private float charWidth(int cp, Face face, float size) {
			if (cp == ' ') {
				return spaceWidth(face, size);
			}
			PDFont font = fontFor(cp, face);
			try {
				String s = font == null ? "?" : new String(Character.toChars(cp));
				return (font == null ? cm.get(face) : font).getStringWidth(s) / 1000 * size;
			} catch (IOException | IllegalArgumentException e) {
				return size * 0.5f;
			}
		}

		private static float spaceWidth(Face face, float size) {
			return size * (face == Face.MONO ? 0.525f : 0.333f);
		}

		// ---- pages ----

		private void newPage() throws IOException {
			if (cs != null) {
				cs.close();
			}
			if (doc.getNumberOfPages() >= MAX_PAGES) {
				full = true;
			}
			page = new PDPage(PDRectangle.A4);
			doc.addPage(page);
			cs = new PDPageContentStream(doc, page);
			y = TOP;
			String number = String.valueOf(doc.getNumberOfPages());
			drawText(number, Face.ROMAN, 9, 0x888888, PAGE_W / 2 - width(number, Face.ROMAN, 9) / 2, MARGIN / 2);
		}

		/** Makes room for height on this page, or starts a new one. */
		private void ensure(float height) throws IOException {
			if (y - height < BOTTOM && y < TOP) {
				newPage();
			}
		}

		void finish() throws IOException {
			if (cs != null) {
				cs.close();
				cs = null;
			}
		}

		@Override
		public void close() throws IOException {
			finish();
			if (unicodeSource != null) {
				unicodeSource.close();
			}
		}

		// ---- blocks ----

		void block(MdBlock block) throws IOException {
			switch (block) {
				case MdBlock.Heading h -> {
					float size = switch (h.level()) {
						case 1 -> 20;
						case 2 -> 16;
						case 3 -> 13.5f;
						default -> 12;
					};
					if (y < TOP) {
						y -= size * 0.6f;
					}
					// keep a heading with at least two lines of what follows
					ensure(size * 1.4f + BODY * 3);
					paragraph(pieces(h.spans(), Face.BOLD, size, 0x111111), MARGIN, WIDTH, size, null);
					y -= 3;
				}
				case MdBlock.Paragraph p -> {
					paragraph(pieces(p.spans(), Face.ROMAN, BODY, 0x000000), MARGIN, WIDTH, BODY, null);
					y -= 6;
				}
				case MdBlock.ListItem item -> {
					float indent = 18 * (item.depth() + 1);
					boolean done = Boolean.TRUE.equals(item.checked());
					List<Piece> pieces = pieces(item.spans(), Face.ROMAN, BODY, done ? 0x777777 : 0x000000);
					if (done) {
						pieces = pieces.stream().map(p -> p instanceof Word w ? (Piece) new Word(w.text(), w.face(), w.size(),
								w.color(), true, w.underline(), w.url(), w.code(), w.spaceBefore()) : p).toList();
					}
					paragraph(pieces, MARGIN + indent, WIDTH - indent, BODY,
							firstBaseline -> marker(item, MARGIN + indent - 14, firstBaseline));
					y -= 2;
				}
				case MdBlock.Quote q -> {
					float indent = 12 * q.depth();
					float before = y;
					paragraph(pieces(q.spans(), Face.ITALIC, BODY, 0x555555), MARGIN + indent, WIDTH - indent, BODY, null);
					if (y < before) {
						// the bar beside the part on this page
						float top = Math.min(before, TOP);
						for (int d = 0; d < q.depth(); d++) {
							fill(MARGIN + 2 + 12 * d, y + 2, 2, top - y - 2, 0xBBBBBB);
						}
					}
					y -= 6;
				}
				case MdBlock.Code c -> code(c.text());
				case MdBlock.Math m -> {
					Box box = formula(m.tex(), true);
					if (box == null) {
						paragraph(List.of(new Word("$$" + m.tex() + "$$", Face.MONO, BODY, 0xA02020, false, false, null, false, false)),
								MARGIN, WIDTH, BODY, null);
					} else {
						float scale = Math.min(1, WIDTH / box.width());
						float w = box.width() * scale;
						float h = box.height() * scale;
						ensure(h + 8);
						y -= 4 + h;
						cs.drawImage(box.image(), MARGIN + (WIDTH - w) / 2, y, w, h);
						y -= 6;
					}
				}
				case MdBlock.Image img -> picture(img.src(), img.alt());
				case MdBlock.Rule r -> {
					ensure(14);
					y -= 7;
					fill(MARGIN, y, WIDTH, 0.6f, 0xBBBBBB);
					y -= 7;
				}
				case MdBlock.Table t -> table(t);
			}
		}

		private void marker(MdBlock.ListItem item, float x, float baseline) throws IOException {
			if (item.checked() != null) {
				float s = 7.5f;
				float bx = x + 1;
				float by = baseline - 0.5f;
				cs.setStrokingColor(0.35f, 0.35f, 0.35f);
				cs.setLineWidth(0.7f);
				cs.addRect(bx, by, s, s);
				cs.stroke();
				if (item.checked()) {
					cs.moveTo(bx + 1.5f, by + s * 0.5f);
					cs.lineTo(bx + s * 0.42f, by + 1.5f);
					cs.lineTo(bx + s - 1, by + s - 1);
					cs.stroke();
				}
			} else if (item.marker() != null) {
				String m = item.marker();
				drawText(m, Face.ROMAN, BODY, 0x333333, x + 10 - width(m, Face.ROMAN, BODY), baseline);
			}
		}

		private void code(String text) throws IOException {
			float size = 9.5f;
			float line = size * 1.3f;
			for (String raw : text.split("\n", -1)) {
				// long lines wrap at the box edge, character by character
				List<String> parts = new ArrayList<>();
				String rest = raw.replace("\t", "    ");
				while (!rest.isEmpty() && width(rest, Face.MONO, size) > WIDTH - 12) {
					int n = 1;
					while (n < rest.length() && width(rest.substring(0, n + 1), Face.MONO, size) <= WIDTH - 12) {
						n++;
					}
					parts.add(rest.substring(0, n));
					rest = rest.substring(n);
				}
				parts.add(rest);
				for (String part : parts) {
					ensure(line);
					fill(MARGIN, y - line, WIDTH, line, 0xF3F3F1);
					drawText(part, Face.MONO, size, 0x1F4A2A, MARGIN + 6, y - size);
					y -= line;
				}
			}
			y -= 8;
		}

		private void picture(String src, String alt) throws IOException {
			Optional<Raster> raster = images.load(src);
			if (raster.isEmpty()) {
				notes.add("picture " + src);
				paragraph(List.of(new Word("[" + (alt.isEmpty() ? src : alt) + "]", Face.ITALIC, BODY, 0x777777,
						false, false, null, false, false)), MARGIN, WIDTH, BODY, null);
				y -= 6;
				return;
			}
			PDImageXObject image = LosslessFactory.createFromImage(doc, image(raster.get()));
			float w = Math.min(WIDTH, raster.get().width() * 0.75f);
			float h = w * raster.get().height() / raster.get().width();
			float maxH = (TOP - BOTTOM) * 0.8f;
			if (h > maxH) {
				w *= maxH / h;
				h = maxH;
			}
			ensure(h + 8);
			y -= h + 4;
			cs.drawImage(image, MARGIN + (WIDTH - w) / 2, y, w, h);
			y -= 8;
		}

		private void table(MdBlock.Table t) throws IOException {
			int columns = t.rows().stream().mapToInt(List::size).max().orElse(0);
			if (columns == 0) {
				return;
			}
			float colW = WIDTH / columns;
			float size = BODY - 1;
			for (int r = 0; r < t.rows().size(); r++) {
				List<List<MdSpan>> row = t.rows().get(r);
				List<List<List<Placed>>> cells = new ArrayList<>();
				float rowH = 0;
				for (int c = 0; c < columns; c++) {
					List<MdSpan> cell = c < row.size() ? row.get(c) : List.of();
					List<List<Placed>> lines = lines(pieces(cell, r == 0 ? Face.BOLD : Face.ROMAN, size, 0x000000), colW - 8);
					cells.add(lines);
					rowH = Math.max(rowH, height(lines, size));
				}
				rowH += 6;
				ensure(rowH);
				float top = y;
				if (r == 0) {
					fill(MARGIN, top - rowH, WIDTH, rowH, 0xF0F0EE);
				}
				for (int c = 0; c < columns; c++) {
					float lineTop = top - 3;
					for (List<Placed> line : cells.get(c)) {
						float[] ad = ascentDescent(line, size);
						draw(line, MARGIN + c * colW + 4, lineTop - ad[0]);
						lineTop -= ad[0] + ad[1] + size * 0.2f;
					}
				}
				cs.setStrokingColor(0.78f, 0.78f, 0.78f);
				cs.setLineWidth(0.5f);
				cs.addRect(MARGIN, top - rowH, WIDTH, rowH);
				for (int c = 1; c < columns; c++) {
					cs.moveTo(MARGIN + c * colW, top);
					cs.lineTo(MARGIN + c * colW, top - rowH);
				}
				cs.stroke();
				y = top - rowH;
			}
			y -= 8;
		}

		// ---- inline ----

		private List<Piece> pieces(List<MdSpan> spans, Face base, float size, int color) throws IOException {
			var out = new ArrayList<Piece>();
			boolean space = false;
			for (MdSpan span : spans) {
				if (span.has(MdSpan.MATH)) {
					Box box = formula(span.text(), false);
					if (box != null) {
						float scale = size / BODY;
						out.add(new Box(box.image(), box.width() * scale, box.height() * scale, box.depth() * scale, space));
					} else {
						out.add(new Word("$" + span.text() + "$", Face.MONO, size, 0xA02020, false, false, null, false, space));
					}
					space = false;
					continue;
				}
				boolean bold = span.has(MdSpan.BOLD) || base == Face.BOLD || base == Face.BOLD_ITALIC;
				boolean italic = span.has(MdSpan.ITALIC) || base == Face.ITALIC || base == Face.BOLD_ITALIC;
				boolean code = span.has(MdSpan.CODE);
				Face face = code ? Face.MONO : bold ? (italic ? Face.BOLD_ITALIC : Face.BOLD) : italic ? Face.ITALIC : Face.ROMAN;
				String link = span.link();
				boolean web = link != null && (link.startsWith("http://") || link.startsWith("https://"));
				int c = code ? 0xB03A2E : link != null ? 0x1A3AA0 : color;
				boolean underline = link != null && !com.memorypalace.core.markdown.Tags.isTagLink(link);
				boolean strike = span.has(MdSpan.STRIKE);
				var word = new StringBuilder();
				String text = span.text();
				for (int i = 0; i < text.length(); ) {
					int cp = text.codePointAt(i);
					i += Character.charCount(cp);
					if (Character.isWhitespace(cp)) {
						if (!word.isEmpty()) {
							out.add(new Word(word.toString(), face, size, c, strike, underline, web ? link : null, code, space));
							word.setLength(0);
						}
						space = true;
						continue;
					}
					word.appendCodePoint(cp);
					// Chinese, Japanese and Korean text may break between any two characters
					if (cjk(cp)) {
						out.add(new Word(word.toString(), face, size, c, strike, underline, web ? link : null, code, space));
						word.setLength(0);
						space = false;
					}
				}
				if (!word.isEmpty()) {
					out.add(new Word(word.toString(), face, size, c, strike, underline, web ? link : null, code, space));
					space = false;
				}
			}
			return out;
		}

		private static boolean cjk(int cp) {
			Character.UnicodeScript script = Character.UnicodeScript.of(cp);
			return script == Character.UnicodeScript.HAN || script == Character.UnicodeScript.HIRAGANA
					|| script == Character.UnicodeScript.KATAKANA || script == Character.UnicodeScript.HANGUL;
		}

		private Box formula(String tex, boolean display) throws IOException {
			TexMacros.Expansion e = macros.expand(tex);
			if (!e.ok()) {
				notes.add("formula " + tex);
				return null;
			}
			Formula f = formulas.render(e.tex(), display);
			if (f == null) {
				notes.add("formula " + tex);
				return null;
			}
			String key = display + "|" + e.tex();
			PDImageXObject image = formulaImages.get(key);
			if (image == null) {
				image = LosslessFactory.createFromImage(doc, image(f.raster()));
				formulaImages.put(key, image);
			}
			return new Box(image, f.raster().width() / PX_PER_PT, f.raster().height() / PX_PER_PT, f.depth() / PX_PER_PT, false);
		}

		private static BufferedImage image(Raster raster) {
			var image = new BufferedImage(raster.width(), raster.height(), BufferedImage.TYPE_INT_ARGB);
			image.setRGB(0, 0, raster.width(), raster.height(), raster.argb(), 0, raster.width());
			return image;
		}

		/** Breaks pieces into lines no wider than width. */
		private List<List<Placed>> lines(List<Piece> pieces, float width) {
			var lines = new ArrayList<List<Placed>>();
			var line = new ArrayList<Placed>();
			float x = 0;
			for (Piece piece : pieces) {
				float gap;
				float w;
				if (piece instanceof Word word) {
					gap = word.spaceBefore() && !line.isEmpty() ? spaceWidth(word.face(), word.size()) : 0;
					w = width(word.text(), word.face(), word.size());
					if (w > width) {
						// a word longer than the line: cut it where it overflows
						for (Word part : split(word, width)) {
							float pw = width(part.text(), part.face(), part.size());
							if (!line.isEmpty() && x + pw > width) {
								lines.add(line);
								line = new ArrayList<>();
								x = 0;
							}
							line.add(new Placed(part, x, pw));
							x += pw;
						}
						continue;
					}
				} else {
					Box box = (Box) piece;
					gap = box.spaceBefore() && !line.isEmpty() ? spaceWidth(Face.ROMAN, BODY) : 0;
					w = Math.min(box.width(), width);
				}
				if (!line.isEmpty() && x + gap + w > width) {
					lines.add(line);
					line = new ArrayList<>();
					x = 0;
					gap = 0;
				}
				line.add(new Placed(piece, x + gap, w));
				x += gap + w;
			}
			if (!line.isEmpty() || lines.isEmpty()) {
				lines.add(line);
			}
			return lines;
		}

		private List<Word> split(Word word, float width) {
			var out = new ArrayList<Word>();
			var part = new StringBuilder();
			String text = word.text();
			for (int i = 0; i < text.length(); ) {
				int cp = text.codePointAt(i);
				i += Character.charCount(cp);
				if (!part.isEmpty() && width(part + new String(Character.toChars(cp)), word.face(), word.size()) > width) {
					out.add(new Word(part.toString(), word.face(), word.size(), word.color(), word.strike(), word.underline(),
							word.url(), word.code(), out.isEmpty() && word.spaceBefore()));
					part.setLength(0);
				}
				part.appendCodePoint(cp);
			}
			out.add(new Word(part.toString(), word.face(), word.size(), word.color(), word.strike(), word.underline(),
					word.url(), word.code(), out.isEmpty() && word.spaceBefore()));
			return out;
		}

		private float[] ascentDescent(List<Placed> line, float size) {
			float ascent = size * 0.75f;
			float descent = size * 0.25f;
			for (Placed p : line) {
				if (p.piece() instanceof Box b) {
					float scale = p.width() / b.width();
					ascent = Math.max(ascent, (b.height() - b.depth()) * scale);
					descent = Math.max(descent, b.depth() * scale);
				} else if (p.piece() instanceof Word w) {
					ascent = Math.max(ascent, w.size() * 0.75f);
				}
			}
			return new float[] {ascent, descent};
		}

		private float height(List<List<Placed>> lines, float size) {
			float h = 0;
			for (List<Placed> line : lines) {
				float[] ad = ascentDescent(line, size);
				h += ad[0] + ad[1] + size * 0.2f;
			}
			return h;
		}

		@FunctionalInterface
		private interface FirstLine {
			void at(float baseline) throws IOException;
		}

		/** Lays out and draws pieces as a paragraph at x, width wide; first gets the first line's baseline. */
		private void paragraph(List<Piece> pieces, float x, float width, float size, FirstLine first) throws IOException {
			boolean firstLine = true;
			for (List<Placed> line : lines(pieces, width)) {
				float[] ad = ascentDescent(line, size);
				float h = ad[0] + ad[1] + size * 0.2f;
				ensure(h);
				float baseline = y - ad[0];
				draw(line, x, baseline);
				if (firstLine && first != null) {
					first.at(baseline);
				}
				firstLine = false;
				y -= h;
			}
		}

		private void draw(List<Placed> line, float x, float baseline) throws IOException {
			for (Placed p : line) {
				switch (p.piece()) {
					case Word w -> {
						if (w.code()) {
							fill(x + p.x() - 1, baseline - w.size() * 0.25f, p.width() + 2, w.size() * 1.05f, 0xF1EFEC);
						}
						drawText(w.text(), w.face(), w.size(), w.color(), x + p.x(), baseline);
						if (w.strike()) {
							fill(x + p.x(), baseline + w.size() * 0.28f, p.width(), 0.5f, w.color());
						}
						if (w.underline()) {
							fill(x + p.x(), baseline - 1.5f, p.width(), 0.5f, w.color());
						}
						if (w.url() != null) {
							linkArea(x + p.x(), baseline - w.size() * 0.25f, p.width(), w.size() * 1.05f, w.url());
						}
					}
					case Box b -> {
						float scale = p.width() / b.width();
						cs.drawImage(b.image(), x + p.x(), baseline - b.depth() * scale, p.width(), b.height() * scale);
					}
				}
			}
		}

		/** Draws text, switching fonts character by character as fontFor decides. */
		private void drawText(String text, Face face, float size, int color, float x, float baseline) throws IOException {
			cs.setNonStrokingColor((color >> 16 & 0xFF) / 255f, (color >> 8 & 0xFF) / 255f, (color & 0xFF) / 255f);
			float cx = x;
			var run = new StringBuilder();
			PDFont runFont = null;
			for (int i = 0; i < text.length(); ) {
				int cp = text.codePointAt(i);
				i += Character.charCount(cp);
				if (cp == ' ') {
					cx = flush(run, runFont, size, cx, baseline);
					runFont = null;
					cx += spaceWidth(face, size);
					continue;
				}
				PDFont font = fontFor(cp, face);
				String s = new String(Character.toChars(cp));
				if (font == null) {
					font = cm.get(face == Face.MONO ? Face.MONO : Face.ROMAN);
					s = "?";
				}
				if (font != runFont) {
					cx = flush(run, runFont, size, cx, baseline);
					runFont = font;
				}
				run.append(s);
			}
			flush(run, runFont, size, cx, baseline);
		}

		private float flush(StringBuilder run, PDFont font, float size, float x, float baseline) throws IOException {
			if (run.isEmpty() || font == null) {
				run.setLength(0);
				return x;
			}
			String s = run.toString();
			run.setLength(0);
			cs.beginText();
			cs.setFont(font, size);
			cs.newLineAtOffset(x, baseline);
			cs.showText(s);
			cs.endText();
			return x + font.getStringWidth(s) / 1000 * size;
		}

		private void fill(float x, float y, float w, float h, int color) throws IOException {
			cs.setNonStrokingColor((color >> 16 & 0xFF) / 255f, (color >> 8 & 0xFF) / 255f, (color & 0xFF) / 255f);
			cs.addRect(x, y, w, h);
			cs.fill();
		}

		private void linkArea(float x, float y, float w, float h, String url) throws IOException {
			var link = new PDAnnotationLink();
			link.setRectangle(new PDRectangle(x, y, w, h));
			var border = new PDBorderStyleDictionary();
			border.setWidth(0);
			link.setBorderStyle(border);
			var action = new PDActionURI();
			action.setURI(url);
			link.setAction(action);
			page.getAnnotations().add(link);
		}
	}
}
