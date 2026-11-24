package com.memorypalace.client.book;

import com.memorypalace.client.book.BookLayout.BookLine;
import com.memorypalace.client.crypto.ClientKeys;
import com.memorypalace.client.crypto.NoteSealers;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.PreeditEvent;
import com.memorypalace.client.render.NoteImageStore;
import com.memorypalace.client.render.RasterTextures;
import com.memorypalace.core.markdown.MarkdownParser;
import com.memorypalace.core.markdown.MdBlock;
import com.memorypalace.core.render.MathProblem;
import com.memorypalace.core.render.MathRenderer;
import com.memorypalace.core.render.TexMacros;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.BookViewScreen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;

import java.util.List;
import java.util.UUID;

/**
 * "摊开的原版书"界面的公共部分：书页大小、画书页（九宫格）、Markdown 排版、画文字/公式/图片。
 * 阅读界面（NoteScreen）和编辑界面（EditorScreen）都继承它。
 *
 * 坐标约定："页内坐标"从书脊往外量：x=0 是书脊，右页向右为正；左页是右页的镜像。
 */
public abstract class BookScreen extends Screen {
	// ---- 原版 book.png 的尺寸（单位：贴图像素）：书页最小尺寸和文字区位置按它算，不随主题变 ----
	// 不透明部分 x=20..165、y=1..181，书脊在左边。九宫格怎么切见 BookTheme（资源包可以换贴图和切法）
	private static final int U_SPINE = 20, U_EDGE = 166;
	private static final int V_TOP = 1, V_END = 182;
	private static final int VANILLA_W = U_EDGE - U_SPINE;     // 146：原版一页宽
	private static final int VANILLA_H = V_END - V_TOP;        // 181：原版一页高

	// 文字区在页内的位置，和原版一致（原版：贴图 x=36..150，y=30 起）
	protected static final int TEXT_LEFT = BookViewScreen.PAGE_TEXT_X_OFFSET - U_SPINE;  // 16
	private static final int TEXT_RIGHT_MARGIN = 15;
	protected static final int TEXT_TOP = BookViewScreen.PAGE_TEXT_Y_OFFSET - V_TOP;     // 29
	private static final int TEXT_BOTTOM_MARGIN = 24;
	protected static final int INDICATOR_Y = BookViewScreen.PAGE_INDICATOR_TEXT_Y_OFFSET - V_TOP;

	// 渲染失败过的公式和失败原因（只在渲染线程用）
	private static final java.util.Map<String, MathProblem> FAILED_MATH = new java.util.HashMap<>();

	/** 上锁时调用：这里存着公式原文。 */
	public static void clearMathFailures() {
		FAILED_MATH.clear();
	}

	protected final UUID noteId; // 没有对应笔记（M 键说明页）时为 null

	// 这次 init 时算好的尺寸（屏幕像素）
	protected int pageW = VANILLA_W, pageH = VANILLA_H, top;
	protected float fontScale = 1.0F;

	protected final boolean encrypted;

	protected BookScreen(UUID noteId, Component title, boolean encrypted) {
		super(title);
		this.noteId = noteId;
		this.encrypted = encrypted;
	}

	public UUID noteId() {
		return noteId;
	}

	/** 正在显示加密笔记的明文（上锁时要关掉这个界面）。 */
	public boolean isEncrypted() {
		return encrypted;
	}

	// 在加密笔记里有任何操作都算"还在用"，推迟自动上锁

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (encrypted) ClientKeys.touch();
		return super.keyPressed(event);
	}

	@Override
	public boolean charTyped(CharacterEvent event) {
		if (encrypted) ClientKeys.touch();
		return super.charTyped(event);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (encrypted) ClientKeys.touch();
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		if (encrypted) ClientKeys.touch();
		return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
	}

	// 用输入法拼字时（拼音还在候选框里），游戏收不到"打字"，但这也是在写
	@Override
	public boolean preeditUpdated(PreeditEvent event) {
		if (encrypted) ClientKeys.touch();
		return super.preeditUpdated(event);
	}

	// 移动鼠标也算在用（比如正在看书、准备翻页）
	@Override
	public void mouseMoved(double mouseX, double mouseY) {
		if (encrypted) ClientKeys.touch();
		super.mouseMoved(mouseX, mouseY);
	}

	// a short message above the book (PDF saved, nothing due…): chat is hard to see behind an open book
	private Component notice;
	private long noticeUntil;

	/** Shows text above the book for a few seconds. */
	public void notice(Component text) {
		this.notice = text;
		this.noticeUntil = System.currentTimeMillis() + 6_000;
	}

	/** The message shown now, or null (for tests). */
	public Component currentNotice() {
		return notice != null && System.currentTimeMillis() < noticeUntil ? notice : null;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
		Component shown = currentNotice();
		if (shown != null) {
			int w = Math.min(this.width - 8, this.font.width(shown));
			int y = Math.max(3, top - 12);
			graphics.fill(spineX() - w / 2 - 4, y - 3, spineX() + w / 2 + 4, y + 11, 0xC0000000);
			graphics.centeredText(this.font, shown, spineX(), y, 0xFFFFFFFF);
		}
	}

	/**
	 * 快要自动上锁时，在书页顶部画倒计时提醒（"30 秒后自动上锁，按任意键继续"）。
	 * 子类在画完自己的内容后调用。
	 */
	protected void drawLockWarning(GuiGraphicsExtractor graphics) {
		if (!encrypted) {
			return;
		}
		long seconds = ClientKeys.secondsUntilLock();
		if (seconds < 0 || seconds > ClientKeys.WARNING_SECONDS) {
			return;
		}
		Component warning = Component.translatable("screen.memorypalace.lock_warning", seconds);
		int y = Math.max(3, top - 12);
		int w = this.font.width(warning);
		// 深色底：书本贴着屏幕顶部时，文字会压在书框上
		graphics.fill(spineX() - w / 2 - 4, y - 3, spineX() + w / 2 + 4, y + 11, 0xC0000000);
		graphics.centeredText(this.font, warning, spineX(), y, 0xFFFFD040);
	}

	// ---- 尺寸 ----

	protected void computeSize() {
		int maxW = Math.max(VANILLA_W, (this.width - 16) / 2);
		int maxH = Math.max(VANILLA_H, this.height - 12);
		float fill = BookSettings.pageSize().fill;
		if (fill == 0) {
			pageW = VANILLA_W;
			pageH = VANILLA_H;
		} else {
			pageW = Math.max(VANILLA_W, Math.round(maxW * fill));
			pageH = Math.max(VANILLA_H, Math.round(maxH * fill));
		}
		// 窗口比原版书还小时，宁可超出也别算出负数
		pageW = Math.min(pageW, maxW);
		pageH = Math.min(pageH, maxH);
		top = Math.max(2, (this.height - pageH) / 2);
		fontScale = BookSettings.fontSize().scale;
	}

	protected int textWidth() {
		return pageW - TEXT_LEFT - TEXT_RIGHT_MARGIN;
	}

	protected int textHeight() {
		return pageH - TEXT_TOP - TEXT_BOTTOM_MARGIN;
	}

	protected int spineX() {
		return this.width / 2;
	}

	/** 左页文字区左边缘的屏幕 x。 */
	protected int leftTextX() {
		return spineX() - TEXT_LEFT - textWidth();
	}

	/** 右页文字区左边缘的屏幕 x。 */
	protected int rightTextX() {
		return spineX() + TEXT_LEFT;
	}

	// ---- 排版 ----

	/** 把 Markdown 正文排成一页一页（按当前书页大小、字体大小）。 */
	protected List<List<BookLine>> layout(String body) {
		return layout(body, List.of());
	}

	/** 同上，正文后面再接几段（比如阅读界面末尾的"链接到本篇的笔记"）。 */
	protected List<List<BookLine>> layout(String body, List<MdBlock> after) {
		MarkdownParser.imageLabel = net.minecraft.client.resources.language.I18n.get("screen.memorypalace.image_label");
		// \newcommand definitions anywhere in the note apply to all of its formulas (and only to this note)
		TexMacros.Collected collected = TexMacros.collect(MarkdownParser.parse(body));
		List<MdBlock> blocks = collected.blocks();
		if (!after.isEmpty()) {
			blocks = new java.util.ArrayList<>(blocks);
			blocks.addAll(after);
		}
		return BookLayout.paginate(this.font, blocks,
				(int) (textWidth() / fontScale), (int) (textHeight() / fontScale), new ScreenBoxes(collected.macros()),
				BookSettings.typeface() == BookSettings.Typeface.LATEX);
	}

	/** 这个链接是不是断链（对应不上笔记）；默认都不是，阅读界面按服务端的结果覆盖。 */
	protected boolean brokenLink(String link) {
		return false;
	}

	/** Whether a broken formula also shows why it failed (the editor preview does; the reader stays clean). */
	protected boolean showMathErrors() {
		return false;
	}

	/** A translated one-line explanation of a formula problem, e.g. "Unknown command \lam". */
	public static String describe(MathProblem problem) {
		String key = "screen.memorypalace.math_error." + problem.kind().name().toLowerCase(java.util.Locale.ROOT);
		return Component.translatable(key, problem.detail()).getString();
	}

	/**
	 * What to show instead of a picture that is uploaded but cannot be fetched yet (the editor: "save the note to
	 * show it"); null for the usual "image not found".
	 */
	protected String pendingImage(String src) {
		return null;
	}

	/** 图片到了要重新排版：子类在这里重新调用 layout。 */
	protected abstract void relayout();

	/**
	 * 加密笔记界面在已上锁时不能显示：不管是从"确认放弃修改"对话框、设置页还是别的地方返回，
	 * 每次（重新）打开都检查一遍。子类在 init 开头调用，返回 true 时直接 return。
	 */
	protected boolean closeIfLocked() {
		if (encrypted && !NoteSealers.available(noteId)) {
			this.minecraft.execute(() -> this.minecraft.gui.setScreen(null));
			return true;
		}
		return false;
	}

	@Override
	protected void init() {
		// 图片到了就重新排版（排版器会拿到新贴图）
		NoteImageStore.setOnArrive(this::relayout);
		computeSize();
	}

	@Override
	public void removed() {
		NoteImageStore.setOnArrive(null);
	}

	/** 1 个排版单位 = 多少个屏幕实际像素。公式按这个分辨率生成，画出来才清楚。 */
	private float pxPerUnit() {
		return (float) (this.minecraft.getWindow().getGuiScale() * fontScale);
	}

	/** 给排版器提供公式和图片的贴图。 */
	private final class ScreenBoxes implements BookLayout.Boxes {
		private final TexMacros macros;

		ScreenBoxes(TexMacros macros) {
			this.macros = macros;
		}

		@Override
		public boolean brokenLink(String link) {
			return BookScreen.this.brokenLink(link);
		}

		// 公式字号（排版单位）：大写字母高度和原版字体差不多
		private static final float MATH_SIZE = 10.0F;

		@Override
		public BookLayout.Box math(String tex, boolean display) {
			// Expand the note's macros first: textures and failures are keyed by the expanded formula, so editing a
			// \newcommand re-renders every formula that uses it instead of showing a stale picture
			TexMacros.Expansion expansion = macros.expand(tex);
			if (!expansion.ok()) {
				return null;
			}
			tex = expansion.tex();
			float px = pxPerUnit();
			int color = BookTheme.get().math();
			String key = key(tex, display, px, color);
			RasterTextures.Tex t = RasterTextures.get(key);
			if (t == null) {
				// 写错的公式也记住，编辑器实时预览时不用每帧重新试一遍（打到一半的 \lam 之类）
				if (FAILED_MATH.containsKey(key)) {
					return null;
				}
				var result = MathRenderer.render(tex, display, MATH_SIZE * px, color);
				if (!result.ok()) {
					if (FAILED_MATH.size() > 1024) {
						FAILED_MATH.clear();
					}
					FAILED_MATH.put(key, result.problem() == null
							? new MathProblem(MathProblem.Kind.SYNTAX, result.error()) : result.problem());
					return null;
				}
				t = RasterTextures.put(key, result.raster(), result.depth());
				if (t == null) {
					return null;
				}
			}
			return new BookLayout.Box(t.id(), t.rasterW(), t.rasterH(), t.depth(), px, null);
		}

		private static String key(String expandedTex, boolean display, float px, int color) {
			return "math|" + display + "|" + px + "|" + Integer.toHexString(color) + "|" + expandedTex;
		}

		@Override
		public String mathError(String tex, boolean display) {
			if (!showMathErrors()) {
				return null;
			}
			TexMacros.Expansion expansion = macros.expand(tex);
			MathProblem problem = expansion.ok()
					? FAILED_MATH.get(key(expansion.tex(), display, pxPerUnit(), BookTheme.get().math())) : expansion.problem();
			return problem == null ? null : describe(problem);
		}

		@Override
		public BookLayout.Box image(String src) {
			// 加密笔记里的图片路径来自解密后的正文，不能发给服务端；图片文件本身也没加密。暂不支持
			if (encrypted || noteId == null) {
				return null;
			}
			// layout needs only the size; the texture is looked up when the page is drawn (see drawBox)
			int[] size = NoteImageStore.size(noteId, src);
			return size == null ? null : new BookLayout.Box(null, size[0], size[1], 0, pxPerUnit(), src);
		}

		@Override
		public String imagePlaceholder(String src, String alt) {
			if (encrypted) {
				return Component.translatable("screen.memorypalace.image_encrypted").getString();
			}
			if (noteId != null && NoteImageStore.state(noteId, src) == NoteImageStore.State.LOADING) {
				return Component.translatable("screen.memorypalace.image_loading").getString();
			}
			String pending = pendingImage(src);
			if (pending != null) {
				return pending;
			}
			return Component.translatable("screen.memorypalace.image_missing", alt.isEmpty() ? src : alt).getString();
		}
	}

	// ---- 画书页（九宫格平铺，1:1 像素，不拉伸）----

	// 书本贴图画在"背景"这一层，按钮才会盖在书上面
	@Override
	public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		super.extractBackground(graphics, mouseX, mouseY, partialTick);
		drawPageFrame(graphics, false);
		drawPageFrame(graphics, true);
	}

	/**
	 * 画一整页，样子来自 BookTheme（资源包可以换）。贴图页：九宫格，3 列（书脊边 / 纸面 / 外侧边）× 3 行（上边 / 中间 / 下边），
	 * 中间部分重复平铺。Flat page: paper with a thin border, a soft shadow and a line along the spine.
	 */
	private void drawPageFrame(GuiGraphicsExtractor graphics, boolean mirrored) {
		switch (BookTheme.get().page()) {
			case BookTheme.Flat flat -> drawFlatPage(graphics, mirrored, flat);
			case BookTheme.Textured t -> {
				int[] u = t.u();
				int[] v = t.v();
				int spineW = u[1] - u[0];
				int edgeW = u[3] - u[2];
				int topH = v[1] - v[0];
				int bottomH = v[4] - v[3];
				int[][] cols = {
						{0, spineW, u[0], u[1]},
						{spineW, pageW - edgeW, u[1], u[2]},
						{pageW - edgeW, pageW, u[2], u[3]}};
				int[][] rows = {
						{0, topH, v[0], v[1]},
						{topH, pageH - bottomH, v[1], v[2]},
						{pageH - bottomH, pageH, v[3], v[4]}};
				for (int[] c : cols) {
					for (int[] r : rows) {
						tile(graphics, t, mirrored, c[0], c[1], c[2], c[3], r[0], r[1], r[2], r[3]);
					}
				}
			}
		}
	}

	private void drawFlatPage(GuiGraphicsExtractor graphics, boolean mirrored, BookTheme.Flat flat) {
		int x0 = mirrored ? spineX() - pageW : spineX();
		int x1 = mirrored ? spineX() : spineX() + pageW;
		int y0 = top;
		int y1 = top + pageH;
		// shadow below and on the outer side, then border, then paper
		graphics.fill(x0 + (mirrored ? -2 : 2), y0 + 3, x1 + (mirrored ? -2 : 2), y1 + 3, flat.shadow());
		graphics.fill(x0, y0, x1, y1, flat.border());
		graphics.fill(x0 + (mirrored ? 1 : 0), y0 + 1, x1 - (mirrored ? 0 : 1), y1 - 1, flat.paper());
		// the fold: a soft line where the pages meet
		int fx = spineX() + (mirrored ? -3 : 0);
		graphics.fill(fx, y0 + 1, fx + 3, y1 - 1, flat.spine());
	}

	/** 在页内区域 [x0,x1)×[y0,y1) 里重复铺贴图区域 [u0,u1)×[v0,v1)，最后一块按需截短。 */
	private void tile(GuiGraphicsExtractor graphics, BookTheme.Textured t, boolean mirrored,
			int x0, int x1, int u0, int u1, int y0, int y1, int v0, int v1) {
		float texW = t.textureWidth();
		float texH = t.textureHeight();
		for (int x = x0; x < x1; x += u1 - u0) {
			int w = Math.min(u1 - u0, x1 - x);
			for (int y = y0; y < y1; y += v1 - v0) {
				int h = Math.min(v1 - v0, y1 - y);
				float ua = u0 / texW, ub = (u0 + w) / texW, va = v0 / texH, vb = (v0 + h) / texH;
				int sy = top + y;
				if (mirrored) {
					// 左页：屏幕上向左长，贴图 u 反过来（u0 > u1）就是镜像
					int sx = spineX() - x - w;
					graphics.blit(t.texture(), sx, sy, sx + w, sy + h, ub, ua, va, vb);
				} else {
					int sx = spineX() + x;
					graphics.blit(t.texture(), sx, sy, sx + w, sy + h, ua, ub, va, vb);
				}
			}
		}
	}

	// ---- 画排好的一页 ----

	protected void drawPage(GuiGraphicsExtractor graphics, List<BookLine> page, int screenX) {
		graphics.pose().pushMatrix();
		graphics.pose().translate(screenX, top + TEXT_TOP);
		graphics.pose().scale(fontScale, fontScale);
		int width = (int) (textWidth() / fontScale);
		int y = 0;
		BookTheme theme = BookTheme.get();
		for (BookLine line : page) {
			switch (line.kind()) {
				case CODE -> graphics.fill(0, y - 1, width, y + line.height(), theme.codeBackground());
				case QUOTE -> {
					for (int d = 0; d < line.depth(); d++) {
						int bx = d * 6 + 1;
						graphics.fill(bx, y - 1, bx + 2, y + line.height(), theme.quoteBar());
					}
				}
				case RULE -> graphics.fill(0, y + 3, width, y + 4, theme.rule());
				default -> {}
			}
			for (BookLayout.Seg seg : line.segs()) {
				switch (seg) {
					// 原版书里的字没有阴影
					case BookLayout.TextSeg t -> graphics.text(this.font, t.text(), t.x(), y + t.y(), theme.text(), false);
					case BookLayout.BoxSeg b -> drawBox(graphics, b, y);
				}
			}
			y += line.height();
		}
		graphics.pose().popMatrix();
	}

	/**
	 * 鼠标在这一页（画在 screenX 处）的哪个链接上；不在链接上时为 null。和 drawPage 用同样的位置换算。
	 */
	protected String linkAt(List<BookLine> page, int screenX, double mouseX, double mouseY) {
		return BookLayout.linkOf(styleUnder(page, screenX, mouseX, mouseY, s -> BookLayout.linkOf(s) != null));
	}

	/** The to-do whose check box is under the mouse on this page, or null. */
	protected Integer taskAt(List<BookLine> page, int screenX, double mouseX, double mouseY) {
		return BookLayout.taskOf(styleUnder(page, screenX, mouseX, mouseY, s -> BookLayout.taskOf(s) != null));
	}

	/** The style of the character under the mouse on this page (drawn at screenX) that is wanted, or null. */
	private Style styleUnder(List<BookLine> page, int screenX, double mouseX, double mouseY,
			java.util.function.Predicate<Style> wanted) {
		float x = (float) ((mouseX - screenX) / fontScale);
		float y = (float) ((mouseY - (top + TEXT_TOP)) / fontScale);
		if (x < 0 || y < 0) {
			return null;
		}
		int lineY = 0;
		for (BookLine line : page) {
			if (y >= lineY && y < lineY + line.height()) {
				for (BookLayout.Seg seg : line.segs()) {
					if (seg instanceof BookLayout.TextSeg t && y >= lineY + t.y() - 1 && y < lineY + t.y() + this.font.lineHeight) {
						Style style = styleAt(t.text(), x - t.x());
						if (style != null && wanted.test(style)) {
							return style;
						}
					}
				}
				return null;
			}
			lineY += line.height();
		}
		return null;
	}

	/**
	 * Screen position of the first character on this page whose style matches (for tests: a to-do's box, a tag),
	 * or null.
	 */
	protected int[] positionOf(List<BookLine> page, int screenX, java.util.function.Predicate<Style> wanted) {
		int lineY = 0;
		for (BookLine line : page) {
			for (BookLayout.Seg seg : line.segs()) {
				if (seg instanceof BookLayout.TextSeg t) {
					float[] x = {0};
					boolean[] found = {false};
					t.text().accept((index, style, codepoint) -> {
						if (wanted.test(style)) {
							found[0] = true;
							return false;
						}
						x[0] += this.font.getSplitter().stringWidth(net.minecraft.util.FormattedCharSequence.codepoint(codepoint, style));
						return true;
					});
					if (found[0]) {
						return new int[] {screenX + Math.round((t.x() + x[0] + 3) * fontScale),
								top + TEXT_TOP + Math.round((lineY + t.y() + 4) * fontScale)};
					}
				}
			}
			lineY += line.height();
		}
		return null;
	}

	/** The picture under the mouse on this page (drawn at screenX), or null. Same position maths as drawPage. */
	protected BookLayout.Box imageAt(List<BookLine> page, int screenX, double mouseX, double mouseY) {
		float x = (float) ((mouseX - screenX) / fontScale);
		float y = (float) ((mouseY - (top + TEXT_TOP)) / fontScale);
		int lineY = 0;
		for (BookLine line : page) {
			for (BookLayout.Seg seg : line.segs()) {
				if (seg instanceof BookLayout.BoxSeg b && b.box().image() != null) {
					float w = b.box().width() * b.scale();
					float h = b.box().height() * b.scale();
					float by = lineY + b.y();
					if (x >= b.x() && x < b.x() + w && y >= by && y < by + h) {
						return b.box();
					}
				}
			}
			lineY += line.height();
		}
		return null;
	}

	/** Opens the picture full screen over this book (Esc comes back here). */
	protected void viewImage(BookLayout.Box box) {
		UUID id = noteId;
		String src = box.image();
		this.minecraft.gui.setScreen(new ImageViewerScreen(this, () -> NoteImageStore.texture(id, src), src));
	}

	/** 一行字里横坐标 x 处那个字的样式；在字的范围外为 null。 */
	private Style styleAt(net.minecraft.util.FormattedCharSequence text, float x) {
		if (x < 0) {
			return null;
		}
		float[] left = {0};
		Style[] hit = {null};
		text.accept((index, style, codepoint) -> {
			// exact (fractional) advance: rounding each character up drifts to the right in the LaTeX typeface
			float width = this.font.getSplitter().stringWidth(net.minecraft.util.FormattedCharSequence.codepoint(codepoint, style));
			if (x < left[0] + width) {
				hit[0] = style;
				return false;
			}
			left[0] += width;
			return true;
		});
		return hit[0];
	}

	/**
	 * 画公式/图片：缩到"1 贴图像素 = 1 屏幕像素"再画，保证清晰。A picture's texture is looked up now: only pictures
	 * on screen are kept in memory, and one that was dropped is fetched again (a pale box shows meanwhile).
	 */
	private void drawBox(GuiGraphicsExtractor graphics, BookLayout.BoxSeg seg, int lineY) {
		BookLayout.Box box = seg.box();
		Identifier texture = box.texture();
		int texW = box.rasterW();
		int texH = box.rasterH();
		if (box.image() != null) {
			RasterTextures.Tex t = noteId == null ? null : NoteImageStore.texture(noteId, box.image());
			if (t == null) {
				float w = box.width() * seg.scale();
				float h = box.height() * seg.scale();
				graphics.fill(Math.round(seg.x()), lineY + Math.round(seg.y()), Math.round(seg.x() + w),
						lineY + Math.round(seg.y() + h), BookTheme.get().codeBackground());
				return;
			}
			texture = t.id();
			texW = t.rasterW();
			texH = t.rasterH();
		}
		graphics.pose().pushMatrix();
		graphics.pose().translate(seg.x(), lineY + seg.y());
		float s = seg.scale() / box.pxPerUnit();
		graphics.pose().scale(s * box.rasterW() / texW, s * box.rasterH() / texH);
		graphics.blit(RenderPipelines.GUI_TEXTURED, texture, 0, 0, 0.0F, 0.0F, texW, texH, texW, texH);
		graphics.pose().popMatrix();
	}

	// 单人游戏里打开笔记时不暂停游戏
	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
