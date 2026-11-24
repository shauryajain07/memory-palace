package com.memorypalace.client;

import com.memorypalace.client.book.BookLayout;
import com.memorypalace.client.book.BookLayout.BookLine;
import com.memorypalace.client.book.BookScreen;
import com.memorypalace.client.book.BookTheme;
import com.memorypalace.core.cards.Scheduler;
import com.memorypalace.mc.CardPayloads;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Flashcard review as an open book: the question on the left page; Space (or "Show answer") shows the answer on
 * the right; then Again / Hard / Good / Easy (keys 1–4), each with when the card comes back. "Again" shows the card
 * once more at the end. Questions and answers are Markdown with LaTeX, laid out like the notes.
 */
public class ReviewScreen extends BookScreen {
	private final Screen parent;
	private final String deckTitle;
	private final ArrayDeque<CardPayloads.Card> queue;
	private final Set<String> repeated = new HashSet<>();
	private final int total;
	private CardPayloads.Card card;
	private boolean revealed;
	private int reviewed;
	private List<List<BookLine>> question = List.of();
	private List<List<BookLine>> answer = List.of();

	public ReviewScreen(Screen parent, CardPayloads.Deck deck) {
		super(null, Component.translatable("screen.memorypalace.review"), false);
		this.parent = parent;
		this.deckTitle = deck.title();
		this.queue = new ArrayDeque<>(deck.cards());
		this.total = deck.cards().size();
		this.card = queue.poll();
	}

	/** Opens the deck the server sent, or says in chat why there is nothing to review. */
	public static void register() {
		ClientPlayNetworking.registerGlobalReceiver(CardPayloads.Deck.TYPE, (deck, context) -> {
			Minecraft mc = context.client();
			if (deck.cards().isEmpty()) {
				Component message = deck.total() == 0 ? Component.translatable("message.memorypalace.cards_none")
						: deck.nextDue() < 0 ? Component.translatable("message.memorypalace.cards_done")
						: deck.nextDue() - deck.today() <= 1 ? Component.translatable("message.memorypalace.cards_done_tomorrow")
						: Component.translatable("message.memorypalace.cards_done_next", deck.nextDue() - deck.today());
				if (mc.gui.screen() instanceof BookScreen book) {
					book.notice(message);
				} else if (mc.player != null) {
					mc.player.sendSystemMessage(message);
				}
				return;
			}
			Screen current = mc.gui.screen();
			mc.gui.setScreen(new ReviewScreen(current instanceof NoteScreen ? current : null, deck));
		});
	}

	@Override
	protected void init() {
		super.init();
		relayout();
		int y = Math.min(this.height - 22, top + pageH + 2);
		if (card == null) {
			addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose())
					.bounds(spineX() - 40, y, 80, 20).build());
			return;
		}
		if (!revealed) {
			addRenderableWidget(Button.builder(Component.translatable("screen.memorypalace.review_show"), b -> reveal())
					.bounds(spineX() - 60, y, 120, 20).build());
			return;
		}
		Scheduler.Progress progress = card.progress();
		Scheduler.Grade[] grades = Scheduler.Grade.values();
		int w = 70;
		int x = spineX() - (grades.length * (w + 4) - 4) / 2;
		for (Scheduler.Grade g : grades) {
			int days = Scheduler.next(progress, g, 0).interval();
			Component label = Component.translatable("screen.memorypalace.review_" + g.name().toLowerCase(java.util.Locale.ROOT))
					.append(" · ").append(days == 0 ? Component.translatable("screen.memorypalace.review_now")
							: Component.translatable("screen.memorypalace.review_days", days));
			addRenderableWidget(Button.builder(label, b -> grade(g)).bounds(x, y, w, 20).build());
			x += w + 4;
		}
	}

	@Override
	protected void relayout() {
		if (this.font == null || card == null) {
			return;
		}
		question = layout(card.question());
		answer = layout(card.answer());
	}

	private void reveal() {
		revealed = true;
		rebuildWidgets();
	}

	private void grade(Scheduler.Grade grade) {
		if (card == null || !revealed) {
			return;
		}
		if (ClientPlayNetworking.canSend(CardPayloads.Grade.TYPE)) {
			ClientPlayNetworking.send(new CardPayloads.Grade(card.id(), grade.ordinal()));
		}
		// "Again": once more at the end of this session
		if (grade == Scheduler.Grade.AGAIN && repeated.add(card.id())) {
			// with its schedule after "Again", so the buttons show what the server will now do
			Scheduler.Progress p = Scheduler.next(card.progress(), grade, 0);
			queue.addLast(new CardPayloads.Card(card.id(), card.question(), card.answer(), card.source(),
					p.reps(), p.interval(), p.ease(), p.lapses()));
		}
		reviewed++;
		card = queue.poll();
		revealed = false;
		rebuildWidgets();
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (card != null && !revealed && (event.key() == InputConstants.KEY_SPACE || event.key() == InputConstants.KEY_RETURN)) {
			reveal();
			return true;
		}
		if (card != null && revealed && event.key() >= InputConstants.KEY_1 && event.key() <= InputConstants.KEY_4) {
			grade(Scheduler.Grade.values()[event.key() - InputConstants.KEY_1]);
			return true;
		}
		return super.keyPressed(event);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
		BookTheme theme = BookTheme.get();
		int indicatorY = top + INDICATOR_Y;
		if (card == null) {
			graphics.text(this.font, BookLayout.ui(Component.translatable("screen.memorypalace.review_finished", reviewed)),
					leftTextX(), top + TEXT_TOP, theme.text(), false);
			return;
		}
		int position = Math.min(total, total - queue.size());
		Component header = BookLayout.ui(Component.translatable("screen.memorypalace.review_header", position, total, card.source()));
		graphics.text(this.font, header, leftTextX(), indicatorY, theme.muted(), false);
		if (!question.isEmpty()) {
			drawPage(graphics, question.getFirst(), leftTextX());
		}
		if (revealed) {
			if (!answer.isEmpty()) {
				drawPage(graphics, answer.getFirst(), rightTextX());
			}
		} else {
			Component hint = BookLayout.ui(Component.translatable("screen.memorypalace.review_hint"));
			graphics.text(this.font, hint, rightTextX() + (textWidth() - this.font.width(hint)) / 2, top + pageH / 2 - 4,
					theme.muted(), false);
		}
	}

	@Override
	public void onClose() {
		this.minecraft.gui.setScreen(parent);
	}

	// ---- for the client game test ----

	String currentQuestion() {
		return card == null ? null : card.question();
	}

	boolean isRevealed() {
		return revealed;
	}

	int reviewedCount() {
		return reviewed;
	}
}
