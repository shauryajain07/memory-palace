package com.memorypalace.client;

import com.memorypalace.client.book.BookLayout;
import com.memorypalace.client.book.BookLayout.BookLine;
import com.memorypalace.client.book.BookScreen;
import com.memorypalace.client.book.BookSettings;
import com.memorypalace.client.crypto.ClientKeys;
import com.memorypalace.client.crypto.NoteSaver;
import com.memorypalace.client.crypto.NoteSealers;
import com.memorypalace.core.crypto.WrongKeyException;
import com.memorypalace.client.attach.AttachmentImport;
import com.memorypalace.client.attach.FilePicker;
import com.memorypalace.client.book.BookTheme;
import com.memorypalace.client.render.NoteImageStore;
import com.memorypalace.core.editor.BlockKeys;
import com.memorypalace.core.editor.SlashMenu;
import com.memorypalace.core.editor.TexSnippets;
import com.memorypalace.core.editor.UndoHistory;
import com.memorypalace.core.markdown.MarkdownParser;
import com.memorypalace.core.render.TexMacros;
import com.memorypalace.core.vault.Vault;
import com.memorypalace.mc.EditPayloads;
import com.memorypalace.mc.LinkPayloads;
import com.memorypalace.mc.OpenNotePayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.components.MultilineTextField;
import net.minecraft.client.gui.components.Whence;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 游戏内编辑器：还是那本摊开的书，左页直接在纸上写 Markdown，右页实时预览。
 * Ctrl+S 保存，Ctrl+Z 撤销，Ctrl+Y / Ctrl+Shift+Z 重做；有没保存的修改时按 Esc 会先确认。保存时如果文件在外部被改过，会提示冲突而不是悄悄覆盖。
 * 输入 [[ 后在右页上方列出笔记名候选：↑↓ 选择，Tab / Enter 填入，Esc 收起。
 * 加密笔记：保存前在客户端加密，服务端只收到密文。
 * Notion-style writing: "/" opens a menu of blocks; Enter continues lists, to-dos and quotes; Tab / Shift+Tab nest
 * list items; Ctrl+B / I / E / Shift+E / K wrap the selection in bold, italic, code, math or a [[link]]. Pictures and PDFs
 * can be dragged onto the book, pasted with Ctrl+V or picked with /image and /pdf (see AttachmentImport).
 */
public class EditorScreen extends BookScreen implements AttachmentImport.Target {
	// 一次排版超过这么多毫秒就算"慢"，改成停手后再刷新
	private static final long SLOW_LAYOUT_MS = 25;

	private String text;          // 编辑框里的当前内容（重新 init 时用它恢复）
	private String savedBody;     // 最后一次保存成功的内容（明文）
	private String baseHash;      // 文件里存的正文（加密笔记是密文）的指纹，保存时检查外部有没有改过

	private MultiLineEditBox editBox;
	// the typing area's font: typewriter Computer Modern with the LaTeX typeface on (BookLayout.editorFont)
	private net.minecraft.client.gui.Font boxFont;
	private List<List<BookLine>> preview = List.of();
	private int previewPage;
	private boolean previewDirty = true;
	private long lastEdit;
	private long lastLayoutMs;

	private boolean saving;
	private Component status = Component.empty();
	// status 是针对哪份正文说的：正文还是这样就照常显示（保存失败、冲突的提示不被"未保存"盖住），改了字就显示"未保存"
	private String statusFor;
	// 自动保存（见 BookSettings.AutoSave）：从上次保存（或打开编辑器）算起，到时间且有没保存的修改就存一次
	private long lastSaveAt = System.currentTimeMillis();
	private boolean autoSaving;

	// 撤销 / 重做历史；重新 init（改窗口大小）时不丢
	private final UndoHistory history;
	private boolean applyingHistory;
	// 文件夹里的笔记（MPAL2，用文件夹钥匙加密）：上锁后不能在这里补输保险库密码来保存
	private final boolean folderNote;

	// [[ 补全：completeStart 是 [[ 后面第一个字的位置（-1 = 光标不在 [[ 里），completeQuery 是 [[ 到光标之间的字
	private static final long SUGGEST_DELAY_MS = 150;
	private static final int MAX_QUERY = 100;
	private static int suggestRequest;
	private int completeStart = -1;
	private String completeQuery = "";
	private long queryChangedAt;
	private String sentQuery;
	private int dismissedAt = -1; // 按 Esc 收起的那一处：光标离开这个 [[ 之前不再弹出
	private List<LinkPayloads.Candidate> candidates = List.of();
	private int selected;

	// "/" menu: the open trigger (null = closed), what it offers, which row is chosen and which rows are visible
	private static final int MENU_ROWS = 8;
	private static final int MENU_ROW_H = 12;
	private SlashMenu.Trigger slash;
	private List<SlashMenu.Command> slashItems = List.of();
	private int slashSelected;
	private int slashScroll;
	private int slashDismissedAt = -1; // Esc on this "/": stays closed until the cursor leaves it
	private String slashForText;
	private int slashForCursor = -1;
	private int menuX, menuY, menuW, menuH; // where it was drawn last, for the mouse

	// /template entries: the note's templates from the server (or the built-in lecture template), asked for once
	private static final String TEMPLATE_PREFIX = "template:";
	private List<SlashMenu.Command> templates = List.of();
	private String templateClass = "";
	private String templateFolder = "";
	private boolean templatesRequested;

	// pictures and PDFs: progress line, and pictures uploaded but maybe not saved into the note yet
	private Component attachStatus;
	private long attachStatusAt;
	private final Set<String> uploaded = new HashSet<>();

	public EditorScreen(UUID noteId, Component title, String body) {
		this(noteId, title, body, false, Vault.hash(body));
	}

	public EditorScreen(UUID noteId, Component title, String body, boolean encrypted, String storedHash) {
		super(noteId, title, encrypted);
		this.text = body;
		this.savedBody = body;
		this.baseHash = storedHash;
		if (encrypted) {
			restoreDraft();
		}
		this.history = new UndoHistory(this.text);
		this.folderNote = encrypted && NoteSealers.isEnvelope(noteId);
	}

	/** 上次上锁前有没保存成功的草稿：解密后放回编辑框（标记为未保存）。 */
	private void restoreDraft() {
		String draft = ClientKeys.takeDraft(noteId);
		if (draft == null || !NoteSealers.available(noteId)) {
			return;
		}
		try {
			this.text = NoteSealers.open(noteId, draft);
			this.status = Component.translatable("screen.memorypalace.draft_restored");
			this.statusFor = this.text;
		} catch (WrongKeyException e) {
			// 草稿解不开就算了，文件里的内容还在
		}
	}

	/** The template entries the "/" menu offers now (for the client game test). */
	List<String> templateNames() {
		return templates.stream().map(c -> c.id().substring(TEMPLATE_PREFIX.length())).toList();
	}

	/** The text in the editor now (for the client game test). */
	String currentText() {
		return text;
	}

	private boolean dirty() {
		return !text.equals(savedBody);
	}

	// The live preview explains broken formulas ("⚠ Unknown command \lam") while you type
	@Override
	protected boolean showMathErrors() {
		return true;
	}

	@Override
	protected void init() {
		super.init();
		if (closeIfLocked()) {
			return;
		}
		if (encrypted) {
			ClientKeys.setActiveEditor(this);
		}
		int buttonH = 18;
		int boxHeight = textHeight() - buttonH - 4;
		boxFont = BookSettings.typeface() == BookSettings.Typeface.LATEX ? BookLayout.editorFont(this.font) : this.font;
		editBox = MultiLineEditBox.builder()
				.setX(leftTextX()).setY(top + TEXT_TOP)
				.setShowBackground(false)
				.setTextColor(BookTheme.get().text()).setTextShadow(false).setCursorColor(BookTheme.get().text())
				.setPlaceholder(Component.translatable("screen.memorypalace.editor_placeholder"))
				.build(boxFont, textWidth(), boxHeight, this.title);
		editBox.setCharacterLimit(maxChars());
		editBox.setValue(text);
		editBox.setValueListener(value -> {
			text = value;
			previewDirty = true;
			lastEdit = System.currentTimeMillis();
			if (!applyingHistory) {
				history.changed(value, lastEdit);
			}
		});
		addRenderableWidget(editBox);
		setInitialFocus(editBox);
		requestTemplates();

		int buttonY = top + TEXT_TOP + textHeight() - buttonH;
		addRenderableWidget(Button.builder(Component.translatable("screen.memorypalace.save"), b -> save(false))
				.bounds(leftTextX(), buttonY, 56, buttonH).build());
		// 单人游戏才能直接打开本机文件；加密笔记用外部编辑器只能看到乱码，不显示
		if (canOpenExternal()) {
			addRenderableWidget(Button.builder(Component.translatable("screen.memorypalace.open_external"), b -> openExternal())
					.bounds(leftTextX() + 60, buttonY, Math.min(110, textWidth() - 60), buttonH).build());
		}
		relayout();
	}

	/**
	 * After init, Minecraft moves the focus to the first button when the last key was Tab (keyboard navigation).
	 * Here Tab indents lists and expands snippets, so keep typing into the text instead.
	 */
	@Override
	protected void setInitialFocus() {
		if (editBox != null) {
			setInitialFocus(editBox);
		} else {
			super.setInitialFocus();
		}
	}

	@Override
	protected void relayout() {
		if (this.font == null) {
			return;
		}
		preview = layout(text);
		previewPage = Math.clamp(previewPage, 0, preview.size() - 1);
		previewDirty = false;
	}

	/** 内容变了就在下一帧刷新预览；只有排版很慢（超长笔记）时才等停手再刷，免得打字卡。 */
	private void refreshPreviewIfNeeded() {
		if (!previewDirty) {
			return;
		}
		long delay = lastLayoutMs > SLOW_LAYOUT_MS ? lastLayoutMs * 2 : 0;
		if (System.currentTimeMillis() - lastEdit < delay) {
			return;
		}
		long start = System.nanoTime();
		relayout();
		lastLayoutMs = (System.nanoTime() - start) / 1_000_000;
	}

	// ---- 保存 ----

	private void save(boolean force) {
		if (saving) {
			return;
		}
		autoSaving = false;
		if (encrypted && !NoteSealers.available(noteId)) {
			if (folderNote) {
				// 文件夹笔记的内容钥匙上锁时已经清零：没法在这里补输文件夹密码，草稿已暂存，重新打开这篇即可恢复
				status = Component.translatable("screen.memorypalace.save_locked");
				return;
			}
			// 编辑时自动上锁了：先解锁再保存
			ClientKeys.requireUnlocked(this, () -> {
				this.minecraft.gui.setScreen(this);
				save(force);
			});
			return;
		}
		String body = text;
		String stored = encrypted ? NoteSealers.seal(noteId, body) : body;
		send(body, stored, force);
	}

	private void send(String body, String stored, boolean force) {
		saving = true;
		lastSaveAt = System.currentTimeMillis();
		status = Component.translatable("screen.memorypalace.saving");
		store(stored, force ? null : baseHash, result -> {
			if ("OK".equals(result.result()) && encrypted) {
				NoteSealers.saved(noteId, stored);
			}
			onSaveResult(result, body);
		});
	}

	/**
	 * 每刻检查要不要自动保存：设置里开着、有没保存的修改、离上次保存够久了、手停了 2 秒（不在打字中途存）。
	 * 加密笔记的钥匙已经清零（自动上锁了）就不存，也不弹解锁框——草稿上锁时已经暂存了。
	 * 自动保存不覆盖外部的修改：冲突时只提示，不弹框打断。
	 */
	@Override
	public void tick() {
		super.tick();
		updateCompletion();
		updateSlash();
		int minutes = BookSettings.autoSave().minutes;
		long now = System.currentTimeMillis();
		if (minutes <= 0 || saving || !dirty() || now - lastSaveAt < minutes * 60_000L || now - lastEdit < 2_000
				|| encrypted && !NoteSealers.available(noteId)) {
			return;
		}
		String body = text;
		String stored = encrypted ? NoteSealers.seal(noteId, body) : body;
		autoSaving = true;
		send(body, stored, false);
	}

	/**
	 * 上锁前调用（钥匙马上要清零了）：有没保存的内容就先加密暂存一份草稿，再尝试保存。
	 * 保存成功会删掉草稿；失败的话下次解锁后打开编辑器会恢复它。
	 */
	public void stashDraftAndSave() {
		if (!encrypted || !dirty() || !NoteSealers.available(noteId)) {
			return;
		}
		String body = text;
		String cipher = NoteSealers.seal(noteId, body);
		ClientKeys.stashDraft(noteId, cipher);
		if (!saving) {
			send(body, cipher, false);
		}
	}

	private void onSaveResult(EditPayloads.SaveResult result, String body) {
		saving = false;
		boolean auto = autoSaving;
		autoSaving = false;
		statusFor = text;
		if (auto && "CONFLICT".equals(result.result())) {
			status = Component.translatable("screen.memorypalace.autosave_conflict");
			return;
		}
		switch (result.result()) {
			case "OK" -> {
				savedBody = body;
				baseHash = result.newHash();
				statusFor = body; // 保存途中又打了字：显示"未保存"
				status = Component.translatable(auto ? "screen.memorypalace.autosaved" : "screen.memorypalace.saved");
				// pictures uploaded before this save can now be fetched from the server
				if (noteId != null) {
					NoteImageStore.retryFailed(noteId);
				}
				uploaded.removeIf(body::contains);
				previewDirty = true;
				// 保存的就是暂存的那份（或更新的）：草稿不需要了
				if (body.equals(text) && noteId != null) {
					ClientKeys.dropDraft(noteId);
				}
			}
			case "REFUSED" -> status = Component.translatable("screen.memorypalace.save_refused");
			case "DENIED" -> status = Component.translatable("screen.memorypalace.save_denied");
			case "CONFLICT" -> this.minecraft.gui.setScreen(new ConfirmScreen(overwrite -> {
						this.minecraft.gui.setScreen(this);
						if (overwrite) {
							save(true);
						} else {
							status = Component.translatable("screen.memorypalace.conflict_kept");
						}
					},
					Component.translatable("screen.memorypalace.conflict_title"),
					Component.translatable("screen.memorypalace.conflict_message"),
					Component.translatable("screen.memorypalace.conflict_overwrite"),
					Component.translatable("screen.memorypalace.conflict_cancel")));
			case "MISSING" -> status = Component.translatable("screen.memorypalace.save_missing");
			case "BUSY" -> status = Component.translatable("screen.memorypalace.save_busy");
			case "TOO_LONG" -> status = Component.translatable("screen.memorypalace.save_too_long");
			case "BOOK_TOO_LONG" -> status = Component.translatable("screen.memorypalace.book_too_long");
			default -> status = Component.translatable("screen.memorypalace.save_failed");
		}
	}

	private void openExternal() {
		LocalVault.notePath(noteId).ifPresent(ExternalEditor::open);
	}

	// ---- 关闭 ----

	@Override
	public void onClose() {
		if (!dirty()) {
			backToReading();
			return;
		}
		this.minecraft.gui.setScreen(new ConfirmScreen(discard -> {
					if (discard) {
						backToReading();
					} else {
						this.minecraft.gui.setScreen(this);
					}
				},
				Component.translatable("screen.memorypalace.unsaved_title"),
				Component.translatable("screen.memorypalace.unsaved_message"),
				Component.translatable("screen.memorypalace.unsaved_discard"),
				Component.translatable("screen.memorypalace.unsaved_keep")));
	}

	// ---- hooks for editors of something other than a vault note (a book and quill on a lectern) ----

	/** Saves stored (expectedHash null = overwrite); done gets the result on the render thread. */
	protected void store(String stored, String expectedHash, java.util.function.Consumer<EditPayloads.SaveResult> done) {
		NoteSaver.save(noteId, stored, expectedHash, done);
	}

	/** The "open in external editor" button: only for a plain note in a world on this computer. */
	protected boolean canOpenExternal() {
		return noteId != null && !encrypted && this.minecraft.hasSingleplayerServer();
	}

	/** The most text the editor takes. */
	protected int maxChars() {
		return OpenNotePayload.MAX_BODY;
	}

	protected void backToReading() {
		ClientKeys.clearActiveEditor(this);
		this.minecraft.gui.setScreen(new NoteScreen(noteId, this.title, savedBody, encrypted, baseHash, true));
	}

	// ---- [[ 补全 ----

	private MultilineTextField textField() {
		if (TEXT_FIELD == null || editBox == null) {
			return null;
		}
		try {
			return (MultilineTextField) TEXT_FIELD.get(editBox);
		} catch (ReflectiveOperationException | RuntimeException e) {
			return null;
		}
	}

	/**
	 * 光标是不是在一个还没写完的 [[ 里（同一行、中间没有 ] | # [、不是 ![[ 图片）：是的话停手 150 毫秒后向服务端要候选。
	 * 每刻检查一次，挪光标、打字都能跟上。
	 */
	private void updateCompletion() {
		MultilineTextField field = textField();
		int start = -1;
		String query = "";
		if (field != null && editBox.isFocused()) {
			int cursor = Math.clamp(field.cursor(), 0, text.length());
			int lineStart = text.lastIndexOf('\n', cursor - 1) + 1;
			String before = text.substring(lineStart, cursor);
			int open = before.lastIndexOf("[[");
			if (open >= 0 && (open == 0 || before.charAt(open - 1) != '!')) {
				String q = before.substring(open + 2);
				if (q.length() <= MAX_QUERY && q.chars().noneMatch(c -> c == ']' || c == '[' || c == '|' || c == '#')) {
					start = lineStart + open + 2;
					query = q;
				}
			}
		}
		if (start < 0 || start == dismissedAt) {
			if (start != dismissedAt) {
				dismissedAt = -1;
			}
			closeCompletion();
			return;
		}
		long now = System.currentTimeMillis();
		if (start != completeStart || !query.equals(completeQuery)) {
			if (start != completeStart) {
				candidates = List.of();
				sentQuery = null;
			}
			completeStart = start;
			completeQuery = query;
			queryChangedAt = now;
		}
		if (!query.equals(sentQuery) && now - queryChangedAt >= SUGGEST_DELAY_MS
				&& ClientPlayNetworking.canSend(LinkPayloads.Suggest.TYPE)) {
			sentQuery = query;
			ClientPlayNetworking.send(new LinkPayloads.Suggest(++suggestRequest, query));
		}
	}

	private void closeCompletion() {
		completeStart = -1;
		completeQuery = "";
		sentQuery = null;
		candidates = List.of();
	}

	private boolean completing() {
		return completeStart >= 0 && !candidates.isEmpty();
	}

	/** 服务端发来的候选：只收最新一次请求的，而且编辑器还开着、光标还在 [[ 里。 */
	public static void onSuggestions(LinkPayloads.Suggestions payload) {
		if (net.minecraft.client.Minecraft.getInstance().gui.screen() instanceof EditorScreen screen
				&& payload.requestId() == suggestRequest && screen.completeStart >= 0) {
			screen.candidates = payload.candidates();
			screen.selected = 0;
		}
	}

	/** 把选中的候选填进去：替换 [[ 到光标之间的字，后面没有 ]] 就补上，光标停在 ]] 后面。 */
	private void acceptCompletion() {
		MultilineTextField field = textField();
		if (field == null || !completing()) {
			return;
		}
		String insert = candidates.get(Math.clamp(selected, 0, candidates.size() - 1)).insert();
		int cursor = Math.clamp(field.cursor(), completeStart, text.length());
		String rest = text.substring(cursor);
		String closing = rest.startsWith("]]") ? "" : "]]";
		String value = text.substring(0, completeStart) + insert + closing + rest;
		if (value.length() > maxChars()) {
			return;
		}
		int newCursor = completeStart + insert.length() + 2;
		history.breakMerge();
		editBox.setValue(value, true);
		history.breakMerge();
		try {
			field.seekCursor(Whence.ABSOLUTE, Math.clamp(newCursor, 0, text.length()));
		} catch (RuntimeException e) {
			// 挪不了光标就留在原处
		}
		closeCompletion();
	}

	// ---- LaTeX snippets (Tab) ----

	/** Applies TexSnippets at the cursor; false when Tab has nothing to do here (then it keeps its normal meaning). */
	private boolean applySnippet() {
		MultilineTextField field = textField();
		if (field == null) {
			return false;
		}
		int cursor = Math.clamp(field.cursor(), 0, text.length());
		// macros the note defines itself win over snippets with the same name
		Set<String> ownMacros = TexMacros.collect(MarkdownParser.parse(text)).macros().definitions().keySet();
		Optional<TexSnippets.Edit> edit = TexSnippets.apply(text, cursor, ownMacros);
		if (edit.isEmpty() || edit.get().text().length() > maxChars()) {
			return false;
		}
		if (!edit.get().text().equals(text)) {
			history.breakMerge();
			editBox.setValue(edit.get().text(), true);
			history.breakMerge();
		}
		try {
			field.seekCursor(Whence.ABSOLUTE, Math.clamp(edit.get().cursor(), 0, text.length()));
		} catch (RuntimeException e) {
			// 挪不了光标就留在原处
		}
		return true;
	}

	/** 右页上方的候选框：每行"填进去的名字  路径"，选中的一行加底色。 */
	private void drawCompletion(GuiGraphicsExtractor graphics) {
		if (!completing()) {
			return;
		}
		int rowH = 11;
		int x = rightTextX() - 3;
		int y = top + TEXT_TOP - 2;
		int w = textWidth() + 6;
		int h = candidates.size() * rowH + 4;
		BookTheme theme = BookTheme.get();
		graphics.fill(x - 1, y - 1, x + w + 1, y + h + 1, theme.menuBorder());
		graphics.fill(x, y, x + w, y + h, theme.menuBackground());
		for (int i = 0; i < candidates.size(); i++) {
			LinkPayloads.Candidate c = candidates.get(i);
			int rowY = y + 2 + i * rowH;
			if (i == selected) {
				graphics.fill(x + 1, rowY - 1, x + w - 1, rowY + rowH - 1, theme.menuSelected());
			}
			String name = this.font.plainSubstrByWidth(c.insert(), textWidth() * 3 / 5);
			graphics.text(this.font, name, x + 3, rowY + 1, theme.menuText(), false);
			int pathX = x + 3 + this.font.width(name) + 6;
			String path = this.font.plainSubstrByWidth(c.path(), Math.max(0, x + w - 3 - pathX));
			graphics.text(this.font, path, pathX, rowY + 1, theme.menuHint(), false);
		}
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		updateSlash();
		if (slashOpen()) {
			switch (event.key()) {
				case InputConstants.KEY_UP -> {
					slashSelected = Math.floorMod(slashSelected - 1, slashItems.size());
					return true;
				}
				case InputConstants.KEY_DOWN -> {
					slashSelected = Math.floorMod(slashSelected + 1, slashItems.size());
					return true;
				}
				case InputConstants.KEY_TAB, InputConstants.KEY_RETURN, InputConstants.KEY_NUMPADENTER -> {
					acceptSlash(slashItems.get(Math.clamp(slashSelected, 0, slashItems.size() - 1)));
					return true;
				}
				case InputConstants.KEY_ESCAPE -> {
					slashDismissedAt = slash.start();
					closeSlash();
					return true;
				}
				default -> {
				}
			}
		}
		if (completing()) {
			switch (event.key()) {
				case InputConstants.KEY_UP -> {
					selected = Math.floorMod(selected - 1, candidates.size());
					return true;
				}
				case InputConstants.KEY_DOWN -> {
					selected = Math.floorMod(selected + 1, candidates.size());
					return true;
				}
				case InputConstants.KEY_TAB, InputConstants.KEY_RETURN, InputConstants.KEY_NUMPADENTER -> {
					acceptCompletion();
					return true;
				}
				case InputConstants.KEY_ESCAPE -> {
					dismissedAt = completeStart;
					closeCompletion();
					return true;
				}
				default -> {
				}
			}
		}
		// Tab: expand a LaTeX snippet (\frac → \frac{}{}) or jump into the next empty {}; otherwise Tab works as usual
		if (event.key() == InputConstants.KEY_TAB && !event.hasShiftDown() && !event.hasControlDownWithQuirk()
				&& editBox != null && editBox.isFocused() && applySnippet()) {
			return true;
		}
		if (editBox != null && editBox.isFocused() && notionKey(event)) {
			return true;
		}
		// 移动光标、改选区（方向键、Home/End、翻页、Ctrl+A）之后的修改另算一步，不和之前的打字合并
		switch (event.key()) {
			case InputConstants.KEY_LEFT, InputConstants.KEY_RIGHT, InputConstants.KEY_UP, InputConstants.KEY_DOWN,
					InputConstants.KEY_HOME, InputConstants.KEY_END, InputConstants.KEY_PAGEUP, InputConstants.KEY_PAGEDOWN ->
					history.breakMerge();
			case InputConstants.KEY_A -> {
				if (event.hasControlDownWithQuirk()) {
					history.breakMerge();
				}
			}
			default -> {
			}
		}
		if (event.hasControlDownWithQuirk()) {
			// Ctrl+S（Mac 上是 Cmd+S）保存；Ctrl+Z 撤销；Ctrl+Y 或 Ctrl+Shift+Z 重做
			switch (event.key()) {
				case InputConstants.KEY_S -> {
					save(false);
					return true;
				}
				case InputConstants.KEY_Z -> {
					apply(event.hasShiftDown() ? history.redo() : history.undo());
					return true;
				}
				case InputConstants.KEY_Y -> {
					apply(history.redo());
					return true;
				}
				default -> {
				}
			}
		}
		return super.keyPressed(event);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (slashOpen() && event.x() >= menuX && event.x() < menuX + menuW && event.y() >= menuY && event.y() < menuY + menuH) {
			int row = slashScroll + (int) ((event.y() - menuY - 3) / MENU_ROW_H);
			if (row >= 0 && row < slashItems.size()) {
				acceptSlash(slashItems.get(row));
			}
			return true;
		}
		// click a picture in the preview to see it full screen (left button is 1 since 26.x)
		if (event.button() == 1 && previewPage < preview.size()) {
			BookLayout.Box image = imageAt(preview.get(previewPage), rightTextX(), event.x(), event.y());
			if (image != null) {
				viewImage(image);
				return true;
			}
		}
		// 在编辑框里点一下（挪光标、双击选词、开始拖选）之后的修改另算一步
		if (editBox != null && editBox.isMouseOver(event.x(), event.y())) {
			history.breakMerge();
		}
		return super.mouseClicked(event, doubleClick);
	}

	// ---- "/" menu ----

	private static String slashLabel(SlashMenu.Command command) {
		if (command.id().startsWith(TEMPLATE_PREFIX)) {
			return net.minecraft.client.resources.language.I18n.get("screen.memorypalace.slash.template",
					command.id().substring(TEMPLATE_PREFIX.length()));
		}
		return net.minecraft.client.resources.language.I18n.get("screen.memorypalace.slash." + command.id());
	}

	private static SlashMenu.Command template(String name, String body) {
		return new SlashMenu.Command(TEMPLATE_PREFIX + name, SlashMenu.Kind.BLOCK, "", body, "",
				List.of("template", "tpl", name.toLowerCase(java.util.Locale.ROOT)));
	}

	/** The built-in template, used when the vault has no Templates folder for this note. */
	private static List<SlashMenu.Command> builtInTemplates() {
		return List.of(template(net.minecraft.client.resources.language.I18n.get("screen.memorypalace.template_lecture"),
				net.minecraft.client.resources.language.I18n.get("screen.memorypalace.template_lecture_body")));
	}

	/** The server's answer to the template request sent when this editor opened. */
	public static void onTemplates(com.memorypalace.mc.TemplatePayloads.Templates payload) {
		if (net.minecraft.client.Minecraft.getInstance().gui.screen() instanceof EditorScreen screen
				&& payload.noteId().equals(screen.noteId)) {
			screen.templateClass = payload.className();
			screen.templateFolder = payload.folder();
			screen.templates = payload.entries().isEmpty() ? builtInTemplates()
					: payload.entries().stream().map(e -> template(e.name(), e.body())).toList();
			screen.slashForText = null; // filter again with the new entries
		}
	}

	private void requestTemplates() {
		if (templatesRequested) {
			return;
		}
		templatesRequested = true;
		templates = builtInTemplates();
		if (noteId != null && ClientPlayNetworking.canSend(com.memorypalace.mc.TemplatePayloads.Request.TYPE)) {
			ClientPlayNetworking.send(new com.memorypalace.mc.TemplatePayloads.Request(noteId));
		}
	}

	/** A template with its placeholders filled for this note and moment. */
	private SlashMenu.Command filled(SlashMenu.Command template) {
		var now = java.time.LocalDateTime.now();
		String body = com.memorypalace.core.editor.NoteTemplates.fill(template.before(), java.util.Map.of(
				"date", now.toLocalDate().toString(),
				"time", String.format(java.util.Locale.ROOT, "%02d:%02d", now.getHour(), now.getMinute()),
				"title", this.title.getString(),
				"class", templateClass,
				"folder", templateFolder));
		return new SlashMenu.Command(template.id(), SlashMenu.Kind.BLOCK, "", body, "", template.aliases());
	}

	private boolean slashOpen() {
		return slash != null && !slashItems.isEmpty();
	}

	private void closeSlash() {
		slash = null;
		slashItems = List.of();
	}

	/** Opens, filters or closes the menu for the text and cursor as they are now (cheap when nothing changed). */
	private void updateSlash() {
		MultilineTextField field = textField();
		if (field == null || !editBox.isFocused() || completeStart >= 0 || field.hasSelection()) {
			closeSlash();
			slashForText = null;
			return;
		}
		int cursor = Math.clamp(field.cursor(), 0, text.length());
		if (text.equals(slashForText) && cursor == slashForCursor) {
			return;
		}
		slashForText = text;
		slashForCursor = cursor;
		Optional<SlashMenu.Trigger> found = SlashMenu.find(text, cursor);
		if (found.isEmpty() || found.get().start() != slashDismissedAt) {
			slashDismissedAt = -1;
		}
		if (found.isEmpty() || found.get().start() == slashDismissedAt) {
			closeSlash();
			return;
		}
		List<SlashMenu.Command> items = SlashMenu.filter(found.get().query(), EditorScreen::slashLabel, templates);
		if (slash == null || slash.start() != found.get().start() || !items.equals(slashItems)) {
			slashSelected = 0;
			slashScroll = 0;
		}
		slash = found.get();
		slashItems = items;
	}

	private void acceptSlash(SlashMenu.Command command) {
		MultilineTextField field = textField();
		if (field == null || slash == null) {
			return;
		}
		int cursor = Math.clamp(field.cursor(), slash.start(), text.length());
		SlashMenu.Command chosen = command.id().startsWith(TEMPLATE_PREFIX) ? filled(command) : command;
		SlashMenu.Edit edit = SlashMenu.apply(text, slash, cursor, chosen, LocalDate.now().toString());
		closeSlash();
		if (edit.text().length() <= maxChars()) {
			setText(edit.text(), edit.cursor(), edit.cursor());
		}
		switch (command.id()) {
			case "image" -> pickFile(FilePicker.Filter.PICTURES);
			case "pdf" -> pickFile(FilePicker.Filter.PDF);
			default -> {
			}
		}
	}

	/** The menu just under the "/" (above it when there is no room below), Notion-style. */
	private void drawSlashMenu(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		MultilineTextField field = textField();
		if (!slashOpen() || field == null) {
			return;
		}
		BookTheme theme = BookTheme.get();
		int rows = Math.min(MENU_ROWS, slashItems.size());
		slashSelected = Math.clamp(slashSelected, 0, slashItems.size() - 1);
		if (slashSelected < slashScroll) {
			slashScroll = slashSelected;
		} else if (slashSelected >= slashScroll + rows) {
			slashScroll = slashSelected - rows + 1;
		}
		int width = 0;
		for (SlashMenu.Command c : slashItems) {
			width = Math.max(width, this.font.width(BookLayout.ui(slashLabel(c))) + 14 + this.font.width(BookLayout.uiCode(c.hint())));
		}
		menuW = Math.max(110, width + 12);
		menuH = rows * MENU_ROW_H + 6;
		// where the "/" is on screen: the wrapped line the cursor is on, minus how far the box is scrolled
		int line = field.getLineAtCursor();
		int lineY = editBox.getY() + 4 + line * boxFont.lineHeight - (int) editBox.scrollAmount();
		int[] bounds = lineBounds(field, line);
		int from = Math.clamp(slash.start(), bounds[0], bounds[1]);
		int slashX = editBox.getX() + 4 + boxFont.width(text.substring(bounds[0], from));
		menuX = Math.clamp(slashX - 4, 2, Math.max(2, this.width - menuW - 2));
		menuY = lineY + this.font.lineHeight + 2;
		if (menuY + menuH > this.height - 2) {
			menuY = Math.max(2, lineY - menuH - 2);
		}
		graphics.fill(menuX + 2, menuY + 2, menuX + menuW + 2, menuY + menuH + 2, 0x30000000);
		graphics.fill(menuX - 1, menuY - 1, menuX + menuW + 1, menuY + menuH + 1, theme.menuBorder());
		graphics.fill(menuX, menuY, menuX + menuW, menuY + menuH, theme.menuBackground());
		for (int i = 0; i < rows; i++) {
			int index = slashScroll + i;
			SlashMenu.Command c = slashItems.get(index);
			int rowY = menuY + 3 + i * MENU_ROW_H;
			boolean hover = mouseX >= menuX && mouseX < menuX + menuW && mouseY >= rowY && mouseY < rowY + MENU_ROW_H;
			if (index == slashSelected || hover) {
				graphics.fill(menuX + 2, rowY, menuX + menuW - 2, rowY + MENU_ROW_H, theme.menuSelected());
			}
			Component label = BookLayout.ui(slashLabel(c));
			Component hint = BookLayout.uiCode(c.hint());
			graphics.text(this.font, label, menuX + 6, rowY + 2, theme.menuText(), false);
			graphics.text(this.font, hint, menuX + menuW - 6 - this.font.width(hint), rowY + 2, theme.menuHint(), false);
		}
		// more entries than fit: small marks at the edge
		if (slashScroll > 0) {
			graphics.fill(menuX + menuW / 2 - 3, menuY + 1, menuX + menuW / 2 + 3, menuY + 2, theme.menuHint());
		}
		if (slashScroll + rows < slashItems.size()) {
			graphics.fill(menuX + menuW / 2 - 3, menuY + menuH - 2, menuX + menuW / 2 + 3, menuY + menuH - 1, theme.menuHint());
		}
	}

	// ---- Notion-style keys ----

	/**
	 * Enter continues lists, Tab / Shift+Tab nest them, Ctrl+B / I / E / Shift+E / K wrap the selection, Ctrl+V pastes
	 * pictures. (Inline math is Shift+E like Notion's inline equation, not M: Cmd+M minimizes the window on macOS.)
	 */
	private boolean notionKey(KeyEvent event) {
		MultilineTextField field = textField();
		if (field == null) {
			return false;
		}
		int cursor = Math.clamp(field.cursor(), 0, text.length());
		boolean ctrl = event.hasControlDownWithQuirk();
		switch (event.key()) {
			case InputConstants.KEY_RETURN, InputConstants.KEY_NUMPADENTER -> {
				if (ctrl || event.hasShiftDown() || field.hasSelection()) {
					return false;
				}
				return apply(BlockKeys.enter(text, cursor));
			}
			case InputConstants.KEY_TAB -> {
				return !ctrl && apply(BlockKeys.indent(text, cursor, event.hasShiftDown()));
			}
			case InputConstants.KEY_V -> {
				if (!ctrl || event.hasShiftDown()) {
					return false;
				}
				byte[] picture = FilePicker.clipboardPicture();
				if (picture == null) {
					return false;
				}
				if (canAttach()) {
					AttachmentImport.importPasted(this, picture);
				}
				return true;
			}
			default -> {
			}
		}
		if (!ctrl) {
			return false;
		}
		String[] pair = switch (event.key()) {
			case InputConstants.KEY_B -> new String[] {"**", "**"};
			case InputConstants.KEY_I -> new String[] {"*", "*"};
			case InputConstants.KEY_E -> event.hasShiftDown() ? new String[] {"$", "$"} : new String[] {"`", "`"};
			case InputConstants.KEY_K -> new String[] {"[[", "]]"};
			default -> null;
		};
		if (pair == null) {
			return false;
		}
		int[] selection = selection(field);
		BlockKeys.Edit edit = BlockKeys.wrap(text, selection[0], selection[1], pair[0], pair[1]);
		return apply(Optional.of(edit));
	}

	private boolean apply(Optional<BlockKeys.Edit> edit) {
		if (edit.isEmpty() || edit.get().text().length() > maxChars()) {
			return false;
		}
		setText(edit.get().text(), edit.get().start(), edit.get().end());
		return true;
	}

	/** Replaces the whole text as one undo step and selects [start, end) (a plain cursor when they are equal). */
	private void setText(String value, int start, int end) {
		MultilineTextField field = textField();
		if (!value.equals(text)) {
			history.breakMerge();
			editBox.setValue(value, true);
			history.breakMerge();
		}
		if (field == null) {
			return;
		}
		try {
			field.setSelecting(false);
			field.seekCursor(Whence.ABSOLUTE, Math.clamp(start, 0, text.length()));
			if (end != start) {
				field.setSelecting(true);
				field.seekCursor(Whence.ABSOLUTE, Math.clamp(end, 0, text.length()));
				field.setSelecting(false);
			}
		} catch (RuntimeException e) {
			// 挪不了光标就留在原处
		}
	}

	// MultilineTextField.StringView is not public: read its (public record) accessors and the selection anchor by
	// reflection (26.x is not obfuscated, the names are stable). Fallbacks keep the editor working without them
	private static final java.lang.reflect.Method VIEW_BEGIN = viewAccessor("beginIndex");
	private static final java.lang.reflect.Method VIEW_END = viewAccessor("endIndex");
	private static final Field SELECT_CURSOR = textFieldField("selectCursor");

	private static java.lang.reflect.Method viewAccessor(String name) {
		try {
			var method = Class.forName("net.minecraft.client.gui.components.MultilineTextField$StringView").getDeclaredMethod(name);
			method.setAccessible(true);
			return method;
		} catch (ReflectiveOperationException | RuntimeException e) {
			return null;
		}
	}

	private static Field textFieldField(String name) {
		try {
			Field field = MultilineTextField.class.getDeclaredField(name);
			field.setAccessible(true);
			return field;
		} catch (ReflectiveOperationException | RuntimeException e) {
			return null;
		}
	}

	/** Start and end (in the text) of wrapped line number line; the whole text's line around the cursor if unknown. */
	private int[] lineBounds(MultilineTextField field, int line) {
		try {
			Object view = field.getLineView(line);
			return new int[] {(int) VIEW_BEGIN.invoke(view), (int) VIEW_END.invoke(view)};
		} catch (ReflectiveOperationException | RuntimeException e) {
			int c = cursor();
			int start = text.lastIndexOf('\n', c - 1) + 1;
			return new int[] {start, Math.max(start, c)};
		}
	}

	/** The selected range [start, end) (start == end when nothing is selected). */
	private int[] selection(MultilineTextField field) {
		int cursor = cursor();
		try {
			int anchor = Math.clamp((int) SELECT_CURSOR.get(field), 0, text.length());
			return new int[] {Math.min(anchor, cursor), Math.max(anchor, cursor)};
		} catch (ReflectiveOperationException | RuntimeException e) {
			return new int[] {cursor, cursor};
		}
	}

	private int cursor() {
		MultilineTextField field = textField();
		return field == null ? text.length() : Math.clamp(field.cursor(), 0, text.length());
	}

	// ---- pictures and PDFs ----

	/** Encrypted notes keep their files out: the picture would sit unencrypted beside the note. */
	protected boolean canAttach() {
		if (encrypted) {
			attachStatus(Component.translatable("screen.memorypalace.attach_encrypted"));
			return false;
		}
		return true;
	}

	private void pickFile(FilePicker.Filter filter) {
		if (!canAttach()) {
			return;
		}
		String name = Component.translatable(filter == FilePicker.Filter.PDF
				? "screen.memorypalace.attach_filter_pdf" : "screen.memorypalace.attach_filter_pictures").getString();
		boolean opened = FilePicker.open(filter, name, paths -> {
			if (!paths.isEmpty()) {
				AttachmentImport.importFiles(this, paths);
			}
		});
		if (!opened) {
			attachStatus(Component.translatable("screen.memorypalace.attach_no_dialog"));
		}
	}

	/** Files dragged from the desktop onto the game window while the editor is open. */
	@Override
	public void onFilesDrop(List<Path> paths) {
		if (!paths.isEmpty() && canAttach()) {
			AttachmentImport.importFiles(this, paths);
		}
	}

	@Override
	public void insertBlock(String markdown) {
		int cursor = cursor();
		int lineStart = text.lastIndexOf('\n', cursor - 1) + 1;
		int lineEnd = text.indexOf('\n', cursor);
		if (lineEnd < 0) {
			lineEnd = text.length();
		}
		boolean blank = text.substring(lineStart, lineEnd).isBlank();
		String before = blank ? text.substring(0, lineStart) : text.substring(0, lineEnd) + "\n";
		String after = text.substring(lineEnd);
		if (!before.isEmpty() && !before.endsWith("\n\n")) {
			before += "\n";
		}
		String tail = after.isEmpty() ? "\n" : after.startsWith("\n\n") ? after : "\n" + after;
		String value = before + markdown + tail;
		if (value.length() <= maxChars()) {
			int at = before.length() + markdown.length() + 1;
			setText(value, at, at);
		}
	}

	@Override
	public void replaceBlock(String placeholder, String markdown) {
		int at = text.indexOf(placeholder);
		if (at < 0) {
			if (!markdown.isEmpty()) {
				insertBlock(markdown);
			}
			return;
		}
		int end = at + placeholder.length();
		if (markdown.isEmpty() && end < text.length() && text.charAt(end) == '\n') {
			end++;
		}
		String value = text.substring(0, at) + markdown + text.substring(end);
		if (value.length() > maxChars()) {
			return;
		}
		int cursor = cursor();
		int moved = cursor >= end ? cursor + value.length() - text.length() : Math.min(cursor, at + markdown.length());
		setText(value, moved, moved);
	}

	@Override
	public void attachStatus(Component status) {
		this.attachStatus = status;
		this.attachStatusAt = System.currentTimeMillis();
	}

	@Override
	public void uploaded(String src) {
		uploaded.add(src);
	}

	@Override
	protected String pendingImage(String src) {
		return uploaded.contains(src) ? Component.translatable("screen.memorypalace.image_pending").getString() : null;
	}

	// ---- 撤销 / 重做 ----

	// 原版多行输入框没有公开光标位置：用反射拿到里面的 MultilineTextField 来挪光标（26.x 起不混淆，字段名就是 textField）。
	// 拿不到就算了，光标留在末尾，撤销本身不受影响
	private static final Field TEXT_FIELD = findTextField();

	private static Field findTextField() {
		try {
			Field field = MultiLineEditBox.class.getDeclaredField("textField");
			field.setAccessible(true);
			return field;
		} catch (ReflectiveOperationException | RuntimeException e) {
			return null;
		}
	}

	/** 把编辑框恢复成历史里的某个版本；state 为 null（没得撤销/重做）时什么都不做。 */
	private void apply(UndoHistory.State state) {
		if (state == null || editBox == null) {
			return;
		}
		applyingHistory = true;
		try {
			editBox.setValue(state.text(), true);
		} finally {
			applyingHistory = false;
		}
		if (TEXT_FIELD != null) {
			try {
				((MultilineTextField) TEXT_FIELD.get(editBox)).seekCursor(Whence.ABSOLUTE, Math.clamp(state.cursor(), 0, text.length()));
			} catch (ReflectiveOperationException | RuntimeException e) {
				// 挪不了光标就留在末尾
			}
		}
	}

	// ---- 绘制 ----

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		if (encrypted && !NoteSealers.available(noteId)) {
			return; // 已上锁：一帧都不画明文（界面马上会被关掉）
		}
		refreshPreviewIfNeeded();
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
		if (previewPage < preview.size()) {
			drawPage(graphics, preview.get(previewPage), rightTextX());
		}
		int indicatorY = top + INDICATOR_Y;
		// 左页顶部：编辑状态；右页顶部：预览页码
		Component state = dirty() && !saving && !text.equals(statusFor) ? Component.translatable("screen.memorypalace.unsaved") : status;
		if (attachStatus != null && (AttachmentImport.busy() || System.currentTimeMillis() - attachStatusAt < 6_000)) {
			state = attachStatus;
		}
		Component left = Component.translatable(encrypted ? "screen.memorypalace.editing_secret" : "screen.memorypalace.editing")
				.append(state);
		graphics.text(this.font, BookLayout.ui(left), leftTextX(), indicatorY, BookTheme.get().muted(), false);
		String n = Component.translatable("screen.memorypalace.preview").getString() + " " + (previewPage + 1) + " / " + preview.size();
		Component counter = BookLayout.ui(n);
		graphics.text(this.font, counter, rightTextX() + textWidth() - this.font.width(counter), indicatorY, BookTheme.get().muted(), false);
		drawCompletion(graphics);
		updateSlash();
		drawSlashMenu(graphics, mouseX, mouseY);
		drawLockWarning(graphics);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		// 右半边滚轮翻预览页，左半边交给编辑框自己滚动
		if (mouseX >= spineX() && scrollY != 0) {
			if (encrypted) {
				ClientKeys.touch();
			}
			previewPage = Math.clamp(previewPage + (scrollY < 0 ? 1 : -1), 0, preview.size() - 1);
			return true;
		}
		return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
	}
}
