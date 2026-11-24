package com.memorypalace.mc;

import com.memorypalace.MemoryPalace;
import com.memorypalace.core.cards.Flashcards;
import com.memorypalace.core.cards.ReviewStore;
import com.memorypalace.core.cards.Scheduler;
import com.memorypalace.core.markdown.Tags;
import com.memorypalace.core.vault.NoteSummary;
import com.memorypalace.core.vault.Vault;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;

import java.io.IOException;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Flashcard review (see Flashcards, Scheduler). The client asks for a deck: one note's cards (the reader's
 * Review button) or a folder's (/palace review). The server answers with the cards that are due, oldest first,
 * then up to NEW_PER_DECK new ones, and keeps each player's schedule in .palace/cards/&lt;player&gt;.tsv.
 * <ul>
 * <li>Only notes the player can read; encrypted notes are left out (the server cannot read them), and so are
 *     templates.</li>
 * <li>A grade is accepted only for a card in the deck the player was last sent.</li>
 * </ul>
 */
public final class CardPayloads {
	private CardPayloads() {}

	public static final int MAX_DECK = 100;
	public static final int NEW_PER_DECK = 20;

	/** scope: "note:&lt;id&gt;" or "folder:&lt;path&gt;" ("folder:" = every note). */
	public record Request(String scope) implements CustomPacketPayload {
		public static final Type<Request> TYPE = new Type<>(MemoryPalace.id("cards_request"));
		public static final StreamCodec<RegistryFriendlyByteBuf, Request> CODEC =
				StreamCodec.composite(ByteBufCodecs.stringUtf8(1100), Request::scope, Request::new);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	public record Card(String id, String question, String answer, String source, int reps, int interval, double ease,
			int lapses) {
		public static final StreamCodec<RegistryFriendlyByteBuf, Card> CODEC = new StreamCodec<>() {
			@Override
			public Card decode(RegistryFriendlyByteBuf buf) {
				return new Card(buf.readUtf(32), buf.readUtf(Flashcards.MAX_SIDE * 3), buf.readUtf(Flashcards.MAX_SIDE * 3),
						buf.readUtf(300), buf.readVarInt(), buf.readVarInt(), buf.readDouble(), buf.readVarInt());
			}

			@Override
			public void encode(RegistryFriendlyByteBuf buf, Card c) {
				buf.writeUtf(c.id(), 32);
				buf.writeUtf(c.question(), Flashcards.MAX_SIDE * 3);
				buf.writeUtf(c.answer(), Flashcards.MAX_SIDE * 3);
				buf.writeUtf(c.source(), 300);
				buf.writeVarInt(c.reps());
				buf.writeVarInt(c.interval());
				buf.writeDouble(c.ease());
				buf.writeVarInt(c.lapses());
			}
		};

		public Scheduler.Progress progress() {
			return new Scheduler.Progress(reps, interval, ease, reps == 0 && lapses == 0 ? 0 : 1, lapses);
		}
	}

	/** total: cards in the scope; nextDue: the epoch day the next not-yet-due card comes back (-1: none). */
	public record Deck(String title, List<Card> cards, int total, long nextDue, long today) implements CustomPacketPayload {
		public static final Type<Deck> TYPE = new Type<>(MemoryPalace.id("cards_deck"));
		public static final StreamCodec<RegistryFriendlyByteBuf, Deck> CODEC = StreamCodec.composite(
				ByteBufCodecs.stringUtf8(300), Deck::title,
				Card.CODEC.apply(ByteBufCodecs.list(MAX_DECK)), Deck::cards,
				ByteBufCodecs.VAR_INT, Deck::total,
				ByteBufCodecs.VAR_LONG, Deck::nextDue,
				ByteBufCodecs.VAR_LONG, Deck::today,
				Deck::new);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/** grade: Scheduler.Grade ordinal (0 Again … 3 Easy). */
	public record Grade(String cardId, int grade) implements CustomPacketPayload {
		public static final Type<Grade> TYPE = new Type<>(MemoryPalace.id("cards_grade"));
		public static final StreamCodec<RegistryFriendlyByteBuf, Grade> CODEC = StreamCodec.composite(
				ByteBufCodecs.stringUtf8(32), Grade::cardId,
				ByteBufCodecs.VAR_INT, Grade::grade,
				Grade::new);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/** Today's date for scheduling (a test can set another clock). */
	static Clock clock = Clock.systemDefaultZone();

	private static final Map<UUID, Set<String>> LAST_DECK = new ConcurrentHashMap<>();
	private static final Map<UUID, ReviewStore> STORES = new ConcurrentHashMap<>();
	private static final RateLimiter REQUESTS = new RateLimiter(1, 5);
	private static final RateLimiter GRADES = new RateLimiter(10, 30);

	public static void register() {
		PayloadTypeRegistry.serverboundPlay().register(Request.TYPE, Request.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(Deck.TYPE, Deck.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(Grade.TYPE, Grade.CODEC);
		ServerPlayNetworking.registerGlobalReceiver(Request.TYPE, (request, context) -> {
			if (REQUESTS.tryTake(context.player().getUUID())) {
				deck(context.player(), request.scope()).ifPresent(deck -> ServerPlayNetworking.send(context.player(), deck));
			}
		});
		ServerPlayNetworking.registerGlobalReceiver(Grade.TYPE, (grade, context) -> grade(context.player(), grade));
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			UUID id = handler.getPlayer().getUUID();
			LAST_DECK.remove(id);
			STORES.remove(id);
			REQUESTS.forget(id);
			GRADES.forget(id);
		});
	}

	private static Optional<ReviewStore> store(Vault vault, UUID player) {
		try {
			ReviewStore store = STORES.get(player);
			if (store == null) {
				store = ReviewStore.load(vault.root().resolve(".palace").resolve("cards").resolve(player + ".tsv"));
				STORES.put(player, store);
			}
			return Optional.of(store);
		} catch (IOException e) {
			MemoryPalace.LOGGER.warn("Could not read the flashcard schedule of {}", player, e);
			return Optional.empty();
		}
	}

	/** The deck for scope, or empty when the player may not use notes or the vault is closed. */
	static Optional<Deck> deck(ServerPlayer player, String scope) {
		Vault vault = PalaceServer.vault();
		if (vault == null || !PalaceAccess.enabled(player)) {
			return Optional.empty();
		}
		List<NoteSummary> notes = new ArrayList<>();
		String title;
		if (scope.startsWith("note:")) {
			Optional<NoteSummary> note;
			try {
				note = vault.find(UUID.fromString(scope.substring(5)));
			} catch (IllegalArgumentException e) {
				return Optional.empty();
			}
			if (note.isEmpty() || !PalaceAccess.canRead(player, note.get())) {
				return Optional.empty();
			}
			notes.add(note.get());
			title = note.get().title();
		} else {
			String folder = scope.startsWith("folder:") ? scope.substring(7).strip() : "";
			for (NoteSummary n : vault.list()) {
				if ((folder.isEmpty() || n.relativePath().startsWith(folder + "/")) && PalaceAccess.canRead(player, n)) {
					notes.add(n);
				}
			}
			title = folder.isEmpty() ? "" : folder;
		}
		Optional<ReviewStore> store = store(vault, player.getUUID());
		if (store.isEmpty()) {
			return Optional.empty();
		}
		long today = LocalDate.now(clock).toEpochDay();
		record Entry(Flashcards.Card card, NoteSummary note, Scheduler.Progress progress) {}
		List<Entry> due = new ArrayList<>();
		List<Entry> fresh = new ArrayList<>();
		long nextDue = Long.MAX_VALUE;
		int total = 0;
		for (NoteSummary note : notes) {
			if (note.encrypted() || Tags.inTemplates(note.relativePath())) {
				continue;
			}
			String body;
			try {
				var read = vault.readNote(note.id());
				if (read.isEmpty() || read.get().note().encrypted()) {
					continue;
				}
				body = read.get().body();
			} catch (IOException e) {
				continue;
			}
			for (Flashcards.Card card : Flashcards.parse(note.id(), body)) {
				total++;
				Scheduler.Progress p = store.get().get(card.id());
				if (p.isNew()) {
					fresh.add(new Entry(card, note, p));
				} else if (Scheduler.isDue(p, today)) {
					due.add(new Entry(card, note, p));
				} else {
					nextDue = Math.min(nextDue, p.due());
				}
			}
		}
		due.sort(Comparator.comparingLong(e -> e.progress().due()));
		List<Entry> chosen = new ArrayList<>(due);
		chosen.addAll(fresh.subList(0, Math.min(NEW_PER_DECK, fresh.size())));
		if (chosen.size() > MAX_DECK) {
			chosen = chosen.subList(0, MAX_DECK);
		}
		if (fresh.size() > NEW_PER_DECK && nextDue == Long.MAX_VALUE) {
			nextDue = today + 1; // more new cards tomorrow
		}
		Set<String> ids = new HashSet<>();
		List<Card> cards = new ArrayList<>();
		for (Entry e : chosen) {
			ids.add(e.card().id());
			Scheduler.Progress p = e.progress();
			cards.add(new Card(e.card().id(), e.card().question(), e.card().answer(), e.note().title(),
					p.reps(), p.interval(), p.ease(), p.lapses()));
		}
		LAST_DECK.put(player.getUUID(), ids);
		return Optional.of(new Deck(title, cards, total, nextDue == Long.MAX_VALUE ? -1 : nextDue, today));
	}

	/** Records an answer: only for a card in the player's last deck. Returns the new schedule (for tests). */
	static Optional<Scheduler.Progress> grade(ServerPlayer player, Grade grade) {
		Vault vault = PalaceServer.vault();
		Set<String> deck = LAST_DECK.get(player.getUUID());
		if (vault == null || deck == null || !deck.contains(grade.cardId()) || grade.grade() < 0
				|| grade.grade() >= Scheduler.Grade.values().length || !GRADES.tryTake(player.getUUID())) {
			return Optional.empty();
		}
		Optional<ReviewStore> store = store(vault, player.getUUID());
		if (store.isEmpty()) {
			return Optional.empty();
		}
		long today = LocalDate.now(clock).toEpochDay();
		Scheduler.Progress next = Scheduler.next(store.get().get(grade.cardId()), Scheduler.Grade.values()[grade.grade()], today);
		store.get().put(grade.cardId(), next);
		try {
			store.get().save();
		} catch (IOException e) {
			MemoryPalace.LOGGER.warn("Could not save the flashcard schedule of {}", player.getName().getString(), e);
		}
		return Optional.of(next);
	}

	/** /palace review [folder]. */
	static int command(ServerPlayer player, String folder) {
		Optional<Deck> deck = deck(player, "folder:" + folder);
		deck.ifPresent(d -> ServerPlayNetworking.send(player, d));
		return deck.map(d -> d.cards().size()).orElse(0);
	}
}
