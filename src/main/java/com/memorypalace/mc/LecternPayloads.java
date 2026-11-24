package com.memorypalace.mc;

import com.memorypalace.MemoryPalace;
import com.memorypalace.core.attach.ChunkAssembler;
import com.memorypalace.core.export.QuillPages;
import com.memorypalace.core.vault.Vault;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.Filterable;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.WritableBookContent;
import net.minecraft.world.level.block.entity.LecternBlockEntity;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Writing in a book and quill that lies on a lectern: right-click opens the Memory Palace editor on its pages
 * (joined with blank lines), saving puts the text back as pages that fit a vanilla book (QuillPages).
 * <ul>
 * <li>Allowed for someone who could take the book (may build there, not blocked by spawn protection), within
 *     reach, with the multiplayer switch on for non-hosts; otherwise the lectern behaves as in vanilla
 *     (read-only, with "Take book"). Sneak + right-click is always vanilla.</li>
 * <li>Every save is checked again on the server: the block must still hold a book and quill whose text has the
 *     fingerprint the editor started from, so a change by someone else is never overwritten silently.</li>
 * </ul>
 */
public final class LecternPayloads {
	private LecternPayloads() {}

	public record Open(BlockPos pos, String text, String hash) implements CustomPacketPayload {
		public static final Type<Open> TYPE = new Type<>(MemoryPalace.id("lectern_open"));
		public static final StreamCodec<RegistryFriendlyByteBuf, Open> CODEC = StreamCodec.composite(
				BlockPos.STREAM_CODEC, Open::pos,
				ByteBufCodecs.stringUtf8(QuillPages.MAX_CHARS + 2 * QuillPages.MAX_PAGES), Open::text,
				ByteBufCodecs.stringUtf8(80), Open::hash,
				Open::new);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/** Client → server: part of the text (UTF-8), chunk index of total; expectedHash "" = overwrite anyway. */
	public record SaveChunk(BlockPos pos, int session, int index, int total, int size, String expectedHash, byte[] data)
			implements CustomPacketPayload {
		public static final Type<SaveChunk> TYPE = new Type<>(MemoryPalace.id("lectern_save"));
		public static final StreamCodec<RegistryFriendlyByteBuf, SaveChunk> CODEC = StreamCodec.composite(
				BlockPos.STREAM_CODEC, SaveChunk::pos,
				ByteBufCodecs.VAR_INT, SaveChunk::session,
				ByteBufCodecs.VAR_INT, SaveChunk::index,
				ByteBufCodecs.VAR_INT, SaveChunk::total,
				ByteBufCodecs.VAR_INT, SaveChunk::size,
				ByteBufCodecs.stringUtf8(80), SaveChunk::expectedHash,
				ByteBufCodecs.byteArray(ChunkAssembler.CHUNK_BYTES), SaveChunk::data,
				SaveChunk::new);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/** Server → client: OK, CONFLICT, BOOK_TOO_LONG, DENIED, MISSING, BUSY or ERROR; newHash after OK. */
	public record Result(BlockPos pos, int session, String result, String newHash) implements CustomPacketPayload {
		public static final Type<Result> TYPE = new Type<>(MemoryPalace.id("lectern_result"));
		public static final StreamCodec<RegistryFriendlyByteBuf, Result> CODEC = StreamCodec.composite(
				BlockPos.STREAM_CODEC, Result::pos,
				ByteBufCodecs.VAR_INT, Result::session,
				ByteBufCodecs.stringUtf8(32), Result::result,
				ByteBufCodecs.stringUtf8(80), Result::newHash,
				Result::new);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/** Where answers go: the player, or a list in the game test. */
	@FunctionalInterface
	interface Reply {
		void send(BlockPos pos, int session, String result, String newHash);
	}

	// UTF-8 of the longest book (4 bytes per character at most)
	private static final long MAX_BYTES = 4L * QuillPages.MAX_CHARS;
	private record Assembly(BlockPos pos, int session, String expectedHash, ChunkAssembler chunks) {}
	private static final Map<UUID, Assembly> PENDING = new ConcurrentHashMap<>();
	private static final RateLimiter RATE = new RateLimiter(1, 5);

	public static void register() {
		PayloadTypeRegistry.clientboundPlay().register(Open.TYPE, Open.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(SaveChunk.TYPE, SaveChunk.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(Result.TYPE, Result.CODEC);
		ServerPlayNetworking.registerGlobalReceiver(SaveChunk.TYPE, (chunk, context) -> {
			ServerPlayer player = context.player();
			receive(player, chunk, (pos, session, result, hash) -> ServerPlayNetworking.send(player, new Result(pos, session, result, hash)));
		});
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			PENDING.remove(handler.getPlayer().getUUID());
			RATE.forget(handler.getPlayer().getUUID());
		});
	}

	/** The text of a book and quill: its pages joined with blank lines. */
	static String text(ItemStack book) {
		WritableBookContent content = book.getOrDefault(DataComponents.WRITABLE_BOOK_CONTENT, WritableBookContent.EMPTY);
		return QuillPages.join(content.pages().stream().map(Filterable::raw).toList());
	}

	/** The lectern at pos if player may write in the book and quill on it now; empty otherwise. */
	static Optional<LecternBlockEntity> writable(ServerPlayer player, BlockPos pos) {
		ServerLevel level = player.level();
		if (!PalaceAccess.enabled(player) || !level.isLoaded(pos)
				|| !(level.getBlockEntity(pos) instanceof LecternBlockEntity lectern)
				|| !lectern.getBook().is(Items.WRITABLE_BOOK)) {
			return Optional.empty();
		}
		if (player.isSpectator() || !player.mayBuild() || !level.mayInteract(player, pos)
				|| !player.isWithinBlockInteractionRange(pos, 1.0)) {
			return Optional.empty();
		}
		return Optional.of(lectern);
	}

	/** Right-click on a lectern holding a book and quill: open the editor if allowed (true), else vanilla (false). */
	static boolean open(ServerPlayer player, BlockPos pos) {
		Optional<LecternBlockEntity> lectern = writable(player, pos);
		if (lectern.isEmpty()) {
			return false;
		}
		String text = text(lectern.get().getBook());
		ServerPlayNetworking.send(player, new Open(pos.immutable(), text, Vault.hash(text)));
		return true;
	}

	static void receive(ServerPlayer player, SaveChunk chunk, Reply reply) {
		UUID id = player.getUUID();
		Assembly a = PENDING.get(id);
		if (a == null || a.session() != chunk.session() || !a.pos().equals(chunk.pos())) {
			PENDING.remove(id);
			if (chunk.index() != 0) {
				return; // the rest of a save that already failed
			}
			if (!RATE.tryTake(id)) {
				reply.send(chunk.pos(), chunk.session(), "BUSY", "");
				return;
			}
			ChunkAssembler assembler = ChunkAssembler.start(chunk.size(), chunk.total(), MAX_BYTES);
			if (chunk.size() == 0) {
				assembler = null;
			}
			if (assembler == null && chunk.size() != 0) {
				reply.send(chunk.pos(), chunk.session(), "BOOK_TOO_LONG", "");
				return;
			}
			if (assembler == null) {
				// an empty book: nothing to assemble
				finish(player, chunk.pos(), chunk.session(), chunk.expectedHash(), "", reply);
				return;
			}
			a = new Assembly(chunk.pos().immutable(), chunk.session(), chunk.expectedHash(), assembler);
			PENDING.put(id, a);
		}
		switch (a.chunks().accept(chunk.index(), chunk.data())) {
			case MORE -> {
			}
			case BAD -> {
				PENDING.remove(id);
				reply.send(chunk.pos(), chunk.session(), "ERROR", "");
			}
			case DONE -> {
				PENDING.remove(id);
				finish(player, a.pos(), a.session(), a.expectedHash(),
						new String(a.chunks().bytes(), StandardCharsets.UTF_8), reply);
			}
		}
	}

	private static void finish(ServerPlayer player, BlockPos pos, int session, String expectedHash, String text, Reply reply) {
		Optional<LecternBlockEntity> lectern = writable(player, pos);
		if (lectern.isEmpty()) {
			boolean there = player.level().isLoaded(pos) && player.level().getBlockEntity(pos) instanceof LecternBlockEntity l
					&& l.getBook().is(Items.WRITABLE_BOOK);
			reply.send(pos, session, there ? "DENIED" : "MISSING", "");
			return;
		}
		String current = text(lectern.get().getBook());
		if (!expectedHash.isEmpty() && !expectedHash.equals(Vault.hash(current))) {
			reply.send(pos, session, "CONFLICT", "");
			return;
		}
		if (text.equals(current)) {
			// nothing changed: leave the pages exactly as they were
			reply.send(pos, session, "OK", Vault.hash(current));
			return;
		}
		Optional<List<String>> pages = text.length() > QuillPages.MAX_CHARS ? Optional.empty() : QuillPages.split(text);
		if (pages.isEmpty()) {
			reply.send(pos, session, "BOOK_TOO_LONG", "");
			return;
		}
		ItemStack book = lectern.get().getBook().copy();
		book.set(DataComponents.WRITABLE_BOOK_CONTENT,
				new WritableBookContent(pages.get().stream().map(Filterable::passThrough).toList()));
		// setBook (not editing in place) so the lectern's page count and comparator output follow the new book
		lectern.get().setBook(book);
		lectern.get().setChanged();
		String saved = text(book);
		reply.send(pos, session, "OK", Vault.hash(saved));
	}
}
