package com.memorypalace.client;

import com.memorypalace.client.book.BookLayout;
import com.memorypalace.client.book.BookLayout.BookLine;
import com.memorypalace.client.book.BookScreen;
import com.memorypalace.client.crypto.ClientKeys;
import com.memorypalace.client.crypto.CryptoClient;
import com.memorypalace.client.crypto.NoteSealers;
import com.memorypalace.client.render.NoteImageStore;
import com.memorypalace.core.markdown.MdBlock;
import com.memorypalace.core.markdown.MdSpan;
import com.memorypalace.core.vault.NoteLinks;
import com.memorypalace.core.vault.Vault;
import com.memorypalace.mc.LinkPayloads;
import com.memorypalace.mc.OpenNotePayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.inventory.PageButton;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 阅读笔记：摊开的原版书（左右两页），翻页按钮 / 滚轮翻页。书脊下方的 ✎ 进入游戏内编辑器。
 * 书页大小、字体大小在设置里调（SettingsScreen）。
 */
public class NoteScreen extends BookScreen {
	private String body;          // 明文（加密笔记已经解密）
	private String storedHash;    // 文件里存的那份正文（加密笔记是密文）的指纹，编辑器保存时检查冲突用
	private final boolean editable; // 服务端说这个人能改这篇：只决定显不显示编辑按钮，保存时服务端还会再检查
	private List<List<BookLine>> pages = List.of();
	private int spread; // 第几个"对开页"，0 = 第 1、2 页

	private PageButton backButton;
	private PageButton forwardButton;

	// 服务端整理好的链接情况（见 LinkPayloads.Info）：断链换颜色，反向链接列在正文后面。打开后要一次，正文变了再要一次
	private Set<String> brokenLinks = Set.of();
	private LinkPayloads.Info linkInfo;
	private boolean linkInfoRequested;

	/** 按 M 打开的窗口：还没有选笔记时显示说明。 */
	public NoteScreen() {
		this(null, Component.translatable("screen.memorypalace.note"),
				Component.translatable("screen.memorypalace.empty_hint").getString());
	}

	public NoteScreen(UUID noteId, Component title, String body) {
		this(noteId, title, body, false, Vault.hash(body), false);
	}

	public NoteScreen(UUID noteId, Component title, String body, boolean encrypted, String storedHash, boolean editable) {
		super(noteId, title, encrypted);
		this.body = body;
		this.storedHash = storedHash;
		this.editable = editable;
		// opening a note is a fresh start for pictures that failed before (the note may have been saved since)
		if (noteId != null) {
			NoteImageStore.retryFailed(noteId);
		}
	}

	/** 文件在外部被改了：换上新内容，尽量停在原来那一页。 */
	public void updateBody(String newBody, String newStoredHash) {
		this.body = newBody;
		this.storedHash = newStoredHash;
		// 外部可能换了图片：重新请求这篇笔记的图
		NoteImageStore.forget(noteId);
		requestLinkInfo();
		relayout();
	}

	private void requestLinkInfo() {
		if (noteId != null && ClientPlayNetworking.canSend(LinkPayloads.InfoRequest.TYPE)) {
			linkInfoRequested = true;
			ClientPlayNetworking.send(new LinkPayloads.InfoRequest(noteId));
		}
	}

	/** 服务端发来的链接情况：正打开着的是这篇就重新排版。 */
	public static void onLinkInfo(LinkPayloads.Info info) {
		if (net.minecraft.client.Minecraft.getInstance().gui.screen() instanceof NoteScreen screen && info.noteId().equals(screen.noteId)) {
			screen.linkInfo = info;
			screen.brokenLinks = Set.copyOf(info.broken());
			screen.relayout();
		}
	}

	@Override
	protected boolean brokenLink(String link) {
		// a PDF beside the note is a file, not a missing note
		return brokenLinks.contains(link) && !isAttachedFile(link);
	}

	/** 正文后面接的"链接到本篇的笔记"：分隔线、小标题、每篇一行（点了就打开）。没有反向链接时不加。 */
	private List<MdBlock> backlinkBlocks() {
		if (linkInfo == null || linkInfo.backlinks().isEmpty()) {
			return List.of();
		}
		List<MdBlock> blocks = new ArrayList<>();
		blocks.add(new MdBlock.Rule());
		blocks.add(new MdBlock.Heading(3, List.of(MdSpan.plain(
				Component.translatable("screen.memorypalace.backlinks", linkInfo.backlinks().size() + linkInfo.moreBacklinks()).getString()))));
		for (LinkPayloads.Backlink link : linkInfo.backlinks()) {
			String path = link.path();
			String target = path.toLowerCase(java.util.Locale.ROOT).endsWith(".md") ? path.substring(0, path.length() - 3) : path;
			String shown = link.title().isBlank() ? target : link.title();
			blocks.add(new MdBlock.ListItem(0, "•", null, List.of(new MdSpan(shown, 0, NoteLinks.WIKI + target))));
		}
		if (linkInfo.moreBacklinks() > 0) {
			blocks.add(new MdBlock.Paragraph(List.of(MdSpan.plain(
					Component.translatable("screen.memorypalace.backlinks_more", linkInfo.moreBacklinks()).getString()))));
		}
		return blocks;
	}

	@Override
	protected void init() {
		super.init();
		if (closeIfLocked()) {
			return;
		}
		if (!linkInfoRequested) {
			requestLinkInfo();
		}
		relayout();
		int buttonY = top + pageH - 25;
		// 原版翻页按钮（自带翻书声），放在左页左下角和右页右下角
		backButton = addRenderableWidget(new PageButton(leftTextX(), buttonY, false, b -> flip(-1), true));
		forwardButton = addRenderableWidget(new PageButton(rightTextX() + textWidth() - 23, buttonY, true, b -> flip(1), true));

		// 书脊下方一排小按钮，不占阅读空间：☰ 目录、✎ 进入游戏内编辑器、解除加密
		List<Button> row = new ArrayList<>();
		if (!outline().isEmpty()) {
			row.add(Button.builder(Component.literal("☰"), b -> toggleOutline()).size(20, 20)
					.tooltip(Tooltip.create(Component.translatable("screen.memorypalace.outline"))).build());
		}
		// the note as a PDF (not encrypted notes, like /palace export)
		if (!encrypted) {
			row.add(Button.builder(Component.literal("PDF"), b -> PdfExporter.export(noteId, this.title.getString(), body)).size(28, 20)
					.tooltip(Tooltip.create(Component.translatable("screen.memorypalace.pdf_export"))).build());
		}
		// flashcards in this note: review them
		int cards = noteId == null || encrypted ? 0 : com.memorypalace.core.cards.Flashcards.parse(noteId, body).size();
		if (cards > 0) {
			Component label = Component.translatable("screen.memorypalace.review_button", cards);
			row.add(Button.builder(label, b -> requestReview()).size(this.font.width(label) + 12, 20)
					.tooltip(Tooltip.create(Component.translatable("screen.memorypalace.review_tooltip"))).build());
		}
		if (noteId != null && editable) {
			row.add(Button.builder(Component.literal("✎"), b -> openEditor()).size(20, 20)
					.tooltip(Tooltip.create(Component.translatable("screen.memorypalace.edit"))).build());
			// 解除加密只能从"正打开着的这篇"发起：客户端记下这份密文的指纹，服务端发回的必须是同一份
			if (encrypted) {
				row.add(Button.builder(Component.translatable("screen.memorypalace.decrypt_short"),
								b -> CryptoClient.requestDecrypt(noteId, storedHash)).size(40, 20)
						.tooltip(Tooltip.create(Component.translatable("screen.memorypalace.decrypt_tooltip"))).build());
			}
		}
		int rowWidth = row.stream().mapToInt(Button::getWidth).sum() + 4 * Math.max(0, row.size() - 1);
		int x = spineX() - rowWidth / 2;
		int y = Math.min(this.height - 21, top + pageH + 1);
		for (Button button : row) {
			button.setPosition(x, y);
			addRenderableWidget(button);
			x += button.getWidth() + 4;
		}
		updateButtons();
	}

	@Override
	protected void relayout() {
		if (this.font == null) {
			return; // 还没 init，init 时会再排一次
		}
		pages = layout(body, backlinkBlocks());
		outlineEntries = buildOutline();
		spread = Math.clamp(spread, 0, maxSpread());
		updateButtons();
	}

	private void openEditor() {
		// 游戏内输入框处理不了太长的文本，超过上限会被截断——截断后一保存就丢了后半段，所以干脆不打开
		if (body.length() > OpenNotePayload.MAX_BODY) {
			this.minecraft.player.sendSystemMessage(Component.translatable("message.memorypalace.too_long_to_edit", OpenNotePayload.MAX_BODY));
			return;
		}
		this.minecraft.gui.setScreen(new EditorScreen(noteId, this.title, body, encrypted, storedHash));
	}

	/**
	 * 点书页上的链接：笔记链接交给服务端找到那篇打开（读不了的和不存在的一样说找不到）；
	 * 断链先问要不要新建；网址先弹原版的确认框，确认后用浏览器打开。
	 */
	@Override
	public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean doubleClick) {
		if (outlineOpen && event.button() == LEFT_BUTTON) {
			// buttons (☰ again) still work; anything else goes to the outline
			if (!super.mouseClicked(event, doubleClick)) {
				outlineClick(event.x(), event.y());
			}
			return true;
		}
		if (event.button() == LEFT_BUTTON && noteId != null) {
			int leftIndex = spread * 2;
			// click a to-do's check box to tick it, without opening the editor
			Integer task = leftIndex < pages.size() ? taskAt(pages.get(leftIndex), leftTextX(), event.x(), event.y()) : null;
			if (task == null && leftIndex + 1 < pages.size()) {
				task = taskAt(pages.get(leftIndex + 1), rightTextX(), event.x(), event.y());
			}
			if (task != null && editable) {
				toggleTask(task);
				return true;
			}
			// click a picture (or a PDF page) to see it full screen
			com.memorypalace.client.book.BookLayout.Box image = leftIndex < pages.size()
					? imageAt(pages.get(leftIndex), leftTextX(), event.x(), event.y()) : null;
			if (image == null && leftIndex + 1 < pages.size()) {
				image = imageAt(pages.get(leftIndex + 1), rightTextX(), event.x(), event.y());
			}
			if (image != null) {
				viewImage(image);
				return true;
			}
			String link = leftIndex < pages.size() ? linkAt(pages.get(leftIndex), leftTextX(), event.x(), event.y()) : null;
			if (link == null && leftIndex + 1 < pages.size()) {
				link = linkAt(pages.get(leftIndex + 1), rightTextX(), event.x(), event.y());
			}
			if (link != null) {
				openLink(link);
				return true;
			}
		}
		return super.mouseClicked(event, doubleClick);
	}

	// 26.x 底层换成了 SDL：左键是 1（不是 GLFW 的 0），原版按钮也按 1 判断
	private static final int LEFT_BUTTON = 1;

	private void openLink(String link) {
		if (com.memorypalace.core.markdown.Tags.isTagLink(link)) {
			this.minecraft.gui.setScreen(new SearchScreen("#" + com.memorypalace.core.markdown.Tags.name(link)));
			return;
		}
		if (com.memorypalace.core.vault.NoteLinks.isExternal(link)) {
			java.net.URI uri;
			try {
				uri = new java.net.URI(link);
			} catch (java.net.URISyntaxException e) {
				return;
			}
			String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(java.util.Locale.ROOT);
			if (scheme.equals("http") || scheme.equals("https")) {
				net.minecraft.client.gui.screens.ConfirmLinkScreen.confirmLinkNow(this, uri);
			}
			return;
		}
		if (link.length() > LinkPayloads.MAX_LINK) {
			return;
		}
		if (isAttachedFile(link)) {
			openAttachedFile(link);
			return;
		}
		if (brokenLinks.contains(link) && ClientPlayNetworking.canSend(LinkPayloads.CreateFromLink.TYPE)) {
			String name = link.startsWith(NoteLinks.WIKI) ? link.substring(NoteLinks.WIKI.length()) : link;
			this.minecraft.gui.setScreen(new ConfirmScreen(create -> {
						this.minecraft.gui.setScreen(this);
						if (create) {
							ClientPlayNetworking.send(new LinkPayloads.CreateFromLink(noteId, link));
						}
					},
					Component.translatable("screen.memorypalace.broken_link_title"),
					Component.translatable("screen.memorypalace.broken_link_message", name),
					Component.translatable("screen.memorypalace.broken_link_create"),
					Component.translatable("screen.memorypalace.broken_link_cancel")));
			return;
		}
		ClientPlayNetworking.send(new LinkPayloads.OpenLink(noteId, link));
	}

	/** A link to a PDF stored beside the note (what a PDF upload writes: "attachments/slides.pdf"). */
	private static boolean isAttachedFile(String link) {
		String path = link.split("#", 2)[0];
		return !com.memorypalace.core.vault.NoteLinks.isExternal(link) && !link.startsWith(NoteLinks.WIKI)
				&& path.toLowerCase(java.util.Locale.ROOT).endsWith(".pdf");
	}

	/**
	 * Opens the PDF with the computer's PDF viewer. Only in a world on this computer: on a server the file is on
	 * the server's disk.
	 */
	private void openAttachedFile(String link) {
		String path = link.split("#", 2)[0];
		try {
			path = java.net.URLDecoder.decode(path.replace("+", "%2B"), java.nio.charset.StandardCharsets.UTF_8);
		} catch (IllegalArgumentException ignored) {
			// use it as written
		}
		java.util.Optional<java.nio.file.Path> note = LocalVault.notePath(noteId);
		java.util.Optional<java.nio.file.Path> root = LocalVault.root();
		if (note.isEmpty() || root.isEmpty()) {
			this.minecraft.player.sendSystemMessage(Component.translatable("screen.memorypalace.pdf_on_server"));
			return;
		}
		java.nio.file.Path base = root.get().toAbsolutePath().normalize();
		java.nio.file.Path file = note.get().toAbsolutePath().normalize().getParent().resolve(path).normalize();
		if (!file.startsWith(base) || !java.nio.file.Files.isRegularFile(file)) {
			this.minecraft.player.sendSystemMessage(Component.translatable("screen.memorypalace.pdf_missing", path));
			return;
		}
		ExternalEditor.open(file);
	}

	// ---- outline (☰): the note's headings, click one to turn to its page ----

	private record OutlineEntry(int level, String text, int heading) {}

	private static final int OUTLINE_ROW = 12;
	private boolean outlineOpen;
	private int outlineSelected;
	private int outlineScroll;
	private int outX, outY, outW, outH, outRows;

	// built with the layout (not every frame)
	private List<OutlineEntry> outlineEntries = List.of();

	private List<OutlineEntry> outline() {
		return outlineEntries;
	}

	/** The note's own headings (not the "linked from" list added after it), in order. */
	private List<OutlineEntry> buildOutline() {
		var out = new ArrayList<OutlineEntry>();
		for (MdBlock block : com.memorypalace.core.markdown.MarkdownParser.parse(body)) {
			if (block instanceof MdBlock.Heading h) {
				var text = new StringBuilder();
				for (MdSpan span : h.spans()) {
					text.append(span.has(MdSpan.MATH) ? "$" + span.text() + "$" : span.text());
				}
				out.add(new OutlineEntry(h.level(), text.toString().strip(), out.size()));
			}
		}
		return out;
	}

	private void toggleOutline() {
		outlineOpen = !outlineOpen;
		outlineSelected = 0;
		outlineScroll = 0;
	}

	/** Turns to the spread with heading number heading (looked up now: pages move as pictures arrive). */
	void jumpToHeading(int heading) {
		int page = BookLayout.pageOfHeading(pages, heading);
		if (page >= 0) {
			spread = Math.clamp(page / 2, 0, maxSpread());
			updateButtons();
		}
		outlineOpen = false;
	}

	private void drawOutline(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		if (!outlineOpen) {
			return;
		}
		List<OutlineEntry> entries = outline();
		if (entries.isEmpty()) {
			outlineOpen = false;
			return;
		}
		var theme = com.memorypalace.client.book.BookTheme.get();
		outW = Math.min(Math.max(160, pageW), this.width - 20);
		outRows = Math.max(1, Math.min(entries.size(), (pageH - 30) / OUTLINE_ROW));
		outH = outRows * OUTLINE_ROW + 20;
		outX = spineX() - outW / 2;
		outY = top + (pageH - outH) / 2;
		outlineSelected = Math.clamp(outlineSelected, 0, entries.size() - 1);
		if (outlineSelected < outlineScroll) {
			outlineScroll = outlineSelected;
		} else if (outlineSelected >= outlineScroll + outRows) {
			outlineScroll = outlineSelected - outRows + 1;
		}
		graphics.fill(outX + 2, outY + 2, outX + outW + 2, outY + outH + 2, 0x30000000);
		graphics.fill(outX - 1, outY - 1, outX + outW + 1, outY + outH + 1, theme.menuBorder());
		graphics.fill(outX, outY, outX + outW, outY + outH, theme.menuBackground());
		graphics.text(this.font, BookLayout.ui(Component.translatable("screen.memorypalace.outline")), outX + 6, outY + 5,
				theme.menuHint(), false);
		for (int i = 0; i < outRows && outlineScroll + i < entries.size(); i++) {
			OutlineEntry e = entries.get(outlineScroll + i);
			int rowY = outY + 17 + i * OUTLINE_ROW;
			boolean hover = mouseX >= outX && mouseX < outX + outW && mouseY >= rowY && mouseY < rowY + OUTLINE_ROW;
			if (outlineScroll + i == outlineSelected || hover) {
				graphics.fill(outX + 2, rowY, outX + outW - 2, rowY + OUTLINE_ROW, theme.menuSelected());
			}
			int page = BookLayout.pageOfHeading(pages, e.heading());
			Component number = BookLayout.ui(page < 0 ? "" : String.valueOf(page + 1));
			int indent = 6 + (Math.min(e.level(), 4) - 1) * 8;
			int textWidth = outW - indent - 12 - this.font.width(number);
			String shown = this.font.plainSubstrByWidth(e.text(), Math.max(10, textWidth));
			graphics.text(this.font, BookLayout.ui(shown), outX + indent, rowY + 2, theme.menuText(), false);
			graphics.text(this.font, number, outX + outW - 6 - this.font.width(number), rowY + 2, theme.menuHint(), false);
		}
	}

	private boolean outlineClick(double mouseX, double mouseY) {
		if (mouseX < outX || mouseX >= outX + outW || mouseY < outY || mouseY >= outY + outH) {
			outlineOpen = false; // a click outside closes it
			return true;
		}
		int row = outlineScroll + (int) ((mouseY - outY - 17) / OUTLINE_ROW);
		List<OutlineEntry> entries = outline();
		if (mouseY >= outY + 17 && row >= 0 && row < entries.size()) {
			jumpToHeading(entries.get(row).heading());
		}
		return true;
	}

	@Override
	public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
		if (outlineOpen) {
			int size = outline().size();
			switch (event.key()) {
				case com.mojang.blaze3d.platform.InputConstants.KEY_ESCAPE -> {
					outlineOpen = false;
					return true;
				}
				case com.mojang.blaze3d.platform.InputConstants.KEY_UP -> {
					outlineSelected = Math.floorMod(outlineSelected - 1, Math.max(1, size));
					return true;
				}
				case com.mojang.blaze3d.platform.InputConstants.KEY_DOWN -> {
					outlineSelected = Math.floorMod(outlineSelected + 1, Math.max(1, size));
					return true;
				}
				case com.mojang.blaze3d.platform.InputConstants.KEY_RETURN, com.mojang.blaze3d.platform.InputConstants.KEY_NUMPADENTER -> {
					if (size > 0) {
						jumpToHeading(outline().get(Math.clamp(outlineSelected, 0, size - 1)).heading());
					}
					return true;
				}
				default -> {
				}
			}
		}
		return super.keyPressed(event);
	}

	private void requestReview() {
		if (ClientPlayNetworking.canSend(com.memorypalace.mc.CardPayloads.Request.TYPE)) {
			ClientPlayNetworking.send(new com.memorypalace.mc.CardPayloads.Request("note:" + noteId));
		}
	}

	// ---- ticking to-dos ----

	// a tick is being saved: further clicks wait, so two quick clicks cannot race each other
	private boolean savingTask;

	/**
	 * Flips to-do number index in the note and saves it right away, guarded by the stored text's fingerprint so a
	 * change made elsewhere is never overwritten. The page shows the tick at once and goes back if saving fails.
	 */
	private void toggleTask(int index) {
		if (savingTask || storedHash == null) {
			return;
		}
		java.util.Optional<String> toggled = com.memorypalace.core.markdown.TaskToggle.toggle(body, index);
		if (toggled.isEmpty() || toggled.get().length() > OpenNotePayload.MAX_BODY) {
			return;
		}
		String next = toggled.get();
		String stored;
		if (encrypted) {
			if (!NoteSealers.available(noteId)) {
				return;
			}
			stored = NoteSealers.seal(noteId, next);
		} else {
			stored = next;
		}
		String before = body;
		savingTask = true;
		body = next;
		relayout();
		this.minecraft.getSoundManager().play(net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(
				net.minecraft.sounds.SoundEvents.UI_BUTTON_CLICK, 1.0F));
		com.memorypalace.client.crypto.NoteSaver.save(noteId, stored, storedHash, result -> {
			savingTask = false;
			if ("OK".equals(result.result())) {
				storedHash = result.newHash();
				if (encrypted) {
					NoteSealers.saved(noteId, stored);
				}
				return;
			}
			body = before;
			relayout();
			String key = "CONFLICT".equals(result.result()) ? "message.memorypalace.task_conflict"
					: "DENIED".equals(result.result()) ? "screen.memorypalace.save_denied" : "screen.memorypalace.save_failed";
			notice(Component.translatable(key));
		});
	}

	// ---- for the client game test ----

	/** Screen position of to-do number task's check box on the open spread, or null. */
	int[] taskScreenPosition(int task) {
		return screenPosition(style -> {
			Integer n = BookLayout.taskOf(style);
			return n != null && n == task;
		});
	}

	/** Screen position of the link (or #tag, as "tag:name") on the open spread, or null. */
	int[] linkScreenPosition(String link) {
		return screenPosition(style -> link.equals(BookLayout.linkOf(style)));
	}

	private int[] screenPosition(java.util.function.Predicate<net.minecraft.network.chat.Style> wanted) {
		int leftIndex = spread * 2;
		int[] at = leftIndex < pages.size() ? positionOf(pages.get(leftIndex), leftTextX(), wanted) : null;
		if (at == null && leftIndex + 1 < pages.size()) {
			at = positionOf(pages.get(leftIndex + 1), rightTextX(), wanted);
		}
		return at;
	}

	String currentBody() {
		return body;
	}

	int currentSpread() {
		return spread;
	}

	boolean outlineShowing() {
		return outlineOpen;
	}

	/** Screen position of outline row number row (0 = first heading), or null when not visible. */
	int[] outlineRowPosition(int row) {
		int visible = row - outlineScroll;
		if (!outlineOpen || visible < 0 || visible >= outRows) {
			return null;
		}
		return new int[] {outX + outW / 2, outY + 17 + visible * OUTLINE_ROW + OUTLINE_ROW / 2};
	}

	int spreadCount() {
		return maxSpread() + 1;
	}

	void showSpread(int index) {
		spread = Math.clamp(index, 0, maxSpread());
		updateButtons();
	}

	/** Every picture in the note has a known size, so the pages are laid out for good. */
	boolean allImageSizesKnown() {
		for (MdBlock block : com.memorypalace.core.markdown.MarkdownParser.parse(body)) {
			if (block instanceof MdBlock.Image image
					&& NoteImageStore.state(noteId, image.src()) != NoteImageStore.State.READY) {
				return false;
			}
		}
		return true;
	}

	/** The pictures on the open spread whose textures are in memory now; the others are being fetched. */
	List<String> visibleImages(boolean onlyLoaded) {
		var out = new ArrayList<String>();
		for (int i = spread * 2; i < Math.min(pages.size(), spread * 2 + 2); i++) {
			for (BookLine line : pages.get(i)) {
				for (BookLayout.Seg seg : line.segs()) {
					if (seg instanceof BookLayout.BoxSeg box && box.box().image() != null
							&& (!onlyLoaded || NoteImageStore.texture(noteId, box.box().image()) != null)) {
						out.add(box.box().image());
					}
				}
			}
		}
		return out;
	}

	private int maxSpread() {
		return Math.max(0, (pages.size() - 1) / 2);
	}

	private void flip(int delta) {
		spread = Math.clamp(spread + delta, 0, maxSpread());
		updateButtons();
	}

	private void updateButtons() {
		if (backButton != null) {
			backButton.visible = spread > 0;
			forwardButton.visible = spread < maxSpread();
		}
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
		if (encrypted && !NoteSealers.available(noteId)) {
			return; // 已上锁：一帧都不画明文（界面马上会被关掉）
		}
		int leftIndex = spread * 2;
		if (leftIndex < pages.size()) {
			drawPage(graphics, pages.get(leftIndex), leftTextX());
		}
		if (leftIndex + 1 < pages.size()) {
			drawPage(graphics, pages.get(leftIndex + 1), rightTextX());
		}

		// 页码，位置和原版的"第 x 页"一样在页面上方（不跟字体一起放大）
		int indicatorY = top + INDICATOR_Y;
		if (leftIndex < pages.size()) {
			graphics.text(this.font, BookLayout.ui(String.valueOf(leftIndex + 1)), leftTextX(), indicatorY, com.memorypalace.client.book.BookTheme.get().muted(), false);
		}
		if (leftIndex + 1 < pages.size()) {
			String n = (leftIndex + 2) + " / " + pages.size();
			Component number = BookLayout.ui(n);
			graphics.text(this.font, number, rightTextX() + textWidth() - this.font.width(number), indicatorY, com.memorypalace.client.book.BookTheme.get().muted(), false);
		}
		drawOutline(graphics, mouseX, mouseY);
		drawLockWarning(graphics);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		if (outlineOpen && scrollY != 0) {
			outlineSelected = Math.max(0, outlineSelected + (scrollY < 0 ? 1 : -1));
			return true;
		}
		super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
		if (scrollY != 0) {
			flip(scrollY < 0 ? 1 : -1);
		}
		return true;
	}
}
