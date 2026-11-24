package com.memorypalace.mc;

import com.memorypalace.MemoryPalace;
import com.memorypalace.core.attach.Attachments;
import com.memorypalace.core.attach.ChunkAssembler;
import com.memorypalace.core.vault.FolderLocks;
import com.memorypalace.core.vault.Inbox;
import com.memorypalace.core.vault.NoteSummary;
import com.memorypalace.core.vault.Vault;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;

import java.io.IOException;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Pictures and PDFs added in the in-game editor. The client sends one file at a time in chunks (client → server
 * packets hold only about 32KB); the server checks every chunk, puts the file together, verifies it and writes it
 * into the "attachments" folder beside the note (see Attachments), then answers with the path to put in the note.
 * <ul>
 * <li>Only someone who may edit the note can add files to it, checked on every chunk.</li>
 * <li>Never for encrypted notes, notes in locked folders or the inbox: the file would sit unencrypted on disk.</li>
 * <li>The size is declared up front and capped per type; all uploads in progress together hold at most
 *     MAX_HELD bytes; each player gets a limited number of uploads per second.</li>
 * <li>The server chooses the file name and never overwrites a file; pictures are decoded and re-encoded.</li>
 * </ul>
 */
public final class UploadPayloads {
	public static final int MAX_NAME = 120;
	// everything being uploaded at once, all players together
	private static final long MAX_HELD = 128L << 20;

	private UploadPayloads() {}

	/** Client → server: chunk index of total of the file fileName (size bytes in all). */
	public record Chunk(UUID noteId, int session, int index, int total, int size, String fileName, byte[] data)
			implements CustomPacketPayload {
		public static final Type<Chunk> TYPE = new Type<>(MemoryPalace.id("upload_chunk"));
		public static final StreamCodec<RegistryFriendlyByteBuf, Chunk> CODEC = StreamCodec.composite(
				UUIDUtil.STREAM_CODEC, Chunk::noteId,
				ByteBufCodecs.VAR_INT, Chunk::session,
				ByteBufCodecs.VAR_INT, Chunk::index,
				ByteBufCodecs.VAR_INT, Chunk::total,
				ByteBufCodecs.VAR_INT, Chunk::size,
				ByteBufCodecs.stringUtf8(MAX_NAME), Chunk::fileName,
				ByteBufCodecs.byteArray(ChunkAssembler.CHUNK_BYTES), Chunk::data,
				Chunk::new);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/**
	 * Server → client: how the upload of session went. result is OK (src is the path to use in the note, e.g.
	 * "attachments/graph.png"), DENIED, LOCKED, BAD_TYPE, TOO_BIG, BAD_FILE, BUSY or ERROR.
	 */
	public record Result(UUID noteId, int session, String result, String src) implements CustomPacketPayload {
		public static final Type<Result> TYPE = new Type<>(MemoryPalace.id("upload_result"));
		public static final StreamCodec<RegistryFriendlyByteBuf, Result> CODEC = StreamCodec.composite(
				UUIDUtil.STREAM_CODEC, Result::noteId,
				ByteBufCodecs.VAR_INT, Result::session,
				ByteBufCodecs.stringUtf8(32), Result::result,
				ByteBufCodecs.stringUtf8(512), Result::src,
				Result::new);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/** Where answers go: the player's connection, or a list in the game test. */
	@FunctionalInterface
	interface Reply {
		void send(UUID noteId, int session, String result, String src);
	}

	private record Assembly(UUID noteId, int session, String fileName, Attachments.Type type, ChunkAssembler chunks) {}

	private static final Map<UUID, Assembly> PENDING = new ConcurrentHashMap<>();
	// the last session that failed, per player: its remaining chunks are dropped without answering each one
	private static final Map<UUID, Integer> DEAD = new ConcurrentHashMap<>();
	private static final AtomicLong HELD = new AtomicLong();
	// a PDF of 60 pages is 61 files: let a burst like that through, not a steady flood
	private static final RateLimiter RATE = new RateLimiter(2, 120);

	public static void register() {
		PayloadTypeRegistry.serverboundPlay().register(Chunk.TYPE, Chunk.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(Result.TYPE, Result.CODEC);
		ServerPlayNetworking.registerGlobalReceiver(Chunk.TYPE, (chunk, context) -> {
			ServerPlayer player = context.player();
			receive(player, chunk, (noteId, session, result, src) -> reply(player, noteId, session, result, src));
		});
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			UUID id = handler.getPlayer().getUUID();
			drop(id);
			DEAD.remove(id);
			RATE.forget(id);
		});
	}

	/** Forgets the player's upload in progress and gives back the memory it held. */
	private static void drop(UUID player) {
		Assembly a = PENDING.remove(player);
		if (a != null) {
			HELD.addAndGet(-a.chunks().size());
		}
	}

	/** Handles one chunk on the server thread (package-private for the game test). */
	static void receive(ServerPlayer player, Chunk chunk, Reply reply) {
		UUID id = player.getUUID();
		Integer dead = DEAD.get(id);
		if (dead != null && dead == chunk.session()) {
			return;
		}
		Vault vault = PalaceServer.vault();
		Optional<NoteSummary> found = vault == null ? Optional.empty() : vault.find(chunk.noteId());
		if (found.isEmpty() || !PalaceAccess.canEdit(player, found.get())) {
			fail(player, chunk, reply, "DENIED");
			return;
		}
		NoteSummary note = found.get();
		if (note.encrypted() || FolderLocks.lockedFolderOf(vault, note).isPresent() || Inbox.isInboxFolder(note.folder())) {
			fail(player, chunk, reply, "LOCKED");
			return;
		}
		Optional<Attachments.Type> type = Attachments.typeOf(chunk.fileName());
		if (type.isEmpty()) {
			fail(player, chunk, reply, "BAD_TYPE");
			return;
		}
		Assembly a = PENDING.get(id);
		if (a == null || a.session() != chunk.session() || !a.noteId().equals(chunk.noteId())) {
			drop(id);
			if (chunk.index() != 0) {
				fail(player, chunk, reply, "ERROR");
				return;
			}
			if (chunk.size() > type.get().maxBytes) {
				fail(player, chunk, reply, "TOO_BIG");
				return;
			}
			ChunkAssembler assembler = ChunkAssembler.start(chunk.size(), chunk.total(), type.get().maxBytes);
			if (assembler == null) {
				fail(player, chunk, reply, "ERROR");
				return;
			}
			if (!RATE.tryTake(id)) {
				fail(player, chunk, reply, "BUSY");
				return;
			}
			if (HELD.addAndGet(assembler.size()) > MAX_HELD) {
				HELD.addAndGet(-assembler.size());
				fail(player, chunk, reply, "BUSY");
				return;
			}
			a = new Assembly(chunk.noteId(), chunk.session(), chunk.fileName(), type.get(), assembler);
			PENDING.put(id, a);
		}
		switch (a.chunks().accept(chunk.index(), chunk.data())) {
			case MORE -> {
			}
			case BAD -> fail(player, chunk, reply, "ERROR");
			case DONE -> {
				// leave PENDING now but keep the memory counted until the file is written
				PENDING.remove(id);
				finish(player, vault, note, a, reply);
			}
		}
	}

	/** Verifies and writes the file off the server thread, then answers on it. */
	private static void finish(ServerPlayer player, Vault vault, NoteSummary note, Assembly a, Reply reply) {
		var server = player.level().getServer();
		Thread.ofVirtual().name("memorypalace-upload").start(() -> {
			String result;
			String src = "";
			try {
				Optional<byte[]> checked = Attachments.verify(a.type(), a.chunks().bytes());
				if (checked.isEmpty()) {
					result = "BAD_FILE";
				} else {
					src = Attachments.store(vault.root(), note.relativePath(), a.fileName(), a.type(), checked.get());
					result = "OK";
					MemoryPalace.LOGGER.info("{} added {} to {}", player.getName().getString(), src, note.relativePath());
				}
			} catch (IOException | RuntimeException e) {
				MemoryPalace.LOGGER.warn("Could not store upload {} for {}", a.fileName(), note.relativePath(), e);
				result = "ERROR";
			} finally {
				HELD.addAndGet(-a.chunks().size());
			}
			String r = result;
			String s = src;
			server.execute(() -> reply.send(a.noteId(), a.session(), r, s));
		});
	}

	private static void fail(ServerPlayer player, Chunk chunk, Reply reply, String result) {
		drop(player.getUUID());
		DEAD.put(player.getUUID(), chunk.session());
		reply.send(chunk.noteId(), chunk.session(), result, "");
	}

	private static void reply(ServerPlayer player, UUID noteId, int session, String result, String src) {
		if (!player.hasDisconnected()) {
			ServerPlayNetworking.send(player, new Result(noteId, session, result, src));
		}
	}
}
