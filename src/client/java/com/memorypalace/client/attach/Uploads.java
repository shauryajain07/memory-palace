package com.memorypalace.client.attach;

import com.memorypalace.core.attach.ChunkAssembler;
import com.memorypalace.mc.UploadPayloads;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * Sends files to the server for a note (see UploadPayloads): one file at a time, a few chunks per game tick so a big
 * PDF does not stall the connection, waiting for the server's answer before the next file. Batches queue up.
 * Render thread only; the listener is called on the render thread.
 */
public final class Uploads {
	private Uploads() {}

	public record Outgoing(String fileName, byte[] data) {}

	public interface Listener {
		/** File index (0-based) of files is being sent. */
		void progress(int index, int files);

		/** Every file arrived; srcs are the paths to use in the note, in the same order. */
		void done(List<String> srcs);

		/** The server refused or failed a file (result as in UploadPayloads.Result); later files are not sent. */
		void failed(String result);
	}

	// ~720KB per tick: quick on a local world, still gentle on a real server
	private static final int CHUNKS_PER_TICK = 24;

	private static final class Batch {
		final UUID noteId;
		final List<Outgoing> files;
		final Listener listener;
		final List<String> srcs = new ArrayList<>();
		int file;
		int session;
		int nextChunk;
		boolean waiting;

		Batch(UUID noteId, List<Outgoing> files, Listener listener) {
			this.noteId = noteId;
			this.files = files;
			this.listener = listener;
		}
	}

	private static final ArrayDeque<Batch> QUEUE = new ArrayDeque<>();
	private static Batch active;
	private static int nextSession = (int) (System.nanoTime() & 0x3FFFFFFF);

	public static void enqueue(UUID noteId, List<Outgoing> files, Listener listener) {
		if (files.isEmpty()) {
			listener.done(List.of());
			return;
		}
		QUEUE.add(new Batch(noteId, List.copyOf(files), listener));
	}

	public static void register() {
		ClientTickEvents.END_CLIENT_TICK.register(client -> pump());
		ClientPlayNetworking.registerGlobalReceiver(UploadPayloads.Result.TYPE, (result, context) -> onResult(result));
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> client.execute(() -> {
			if (active != null) {
				active.listener.failed("ERROR");
				active = null;
			}
			while (!QUEUE.isEmpty()) {
				QUEUE.poll().listener.failed("ERROR");
			}
		}));
	}

	private static void pump() {
		if (active == null) {
			active = QUEUE.poll();
			if (active == null) {
				return;
			}
			if (!ClientPlayNetworking.canSend(UploadPayloads.Chunk.TYPE)) {
				Batch b = active;
				active = null;
				b.listener.failed("ERROR");
				return;
			}
			startFile(active);
		}
		if (active.waiting) {
			return;
		}
		Batch b = active;
		Outgoing file = b.files.get(b.file);
		int total = ChunkAssembler.chunksFor(file.data().length);
		for (int sent = 0; sent < CHUNKS_PER_TICK && b.nextChunk < total; sent++) {
			int from = b.nextChunk * ChunkAssembler.CHUNK_BYTES;
			int to = Math.min(file.data().length, from + ChunkAssembler.CHUNK_BYTES);
			ClientPlayNetworking.send(new UploadPayloads.Chunk(b.noteId, b.session, b.nextChunk, total,
					file.data().length, file.fileName(), Arrays.copyOfRange(file.data(), from, to)));
			b.nextChunk++;
		}
		if (b.nextChunk >= total) {
			b.waiting = true;
		}
	}

	private static void startFile(Batch b) {
		b.session = nextSession++;
		b.nextChunk = 0;
		b.waiting = false;
		b.listener.progress(b.file, b.files.size());
	}

	private static void onResult(UploadPayloads.Result result) {
		Batch b = active;
		if (b == null || result.session() != b.session || !result.noteId().equals(b.noteId)) {
			return;
		}
		if (!"OK".equals(result.result())) {
			active = null;
			b.listener.failed(result.result());
			return;
		}
		b.srcs.add(result.src());
		b.file++;
		if (b.file >= b.files.size()) {
			active = null;
			b.listener.done(List.copyOf(b.srcs));
		} else {
			startFile(b);
		}
	}
}
