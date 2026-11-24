package com.memorypalace.client;

import com.memorypalace.core.attach.ChunkAssembler;
import com.memorypalace.core.export.QuillPages;
import com.memorypalace.mc.EditPayloads;
import com.memorypalace.mc.LecternPayloads;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.function.Consumer;

/**
 * The Memory Palace editor on a book and quill lying on a lectern (see LecternPayloads): the same "/" menu, live
 * preview with LaTeX and keys, but saving writes the lectern's book. Pictures and PDFs cannot go into a book.
 */
public class LecternQuillScreen extends EditorScreen {
	private static int sessions;

	private final BlockPos pos;
	private int session;
	private Consumer<EditPayloads.SaveResult> pending;

	public LecternQuillScreen(BlockPos pos, String text, String hash) {
		super(null, Component.translatable("screen.memorypalace.lectern_title"), text, false, hash);
		this.pos = pos;
	}

	public static void register() {
		ClientPlayNetworking.registerGlobalReceiver(LecternPayloads.Open.TYPE, (payload, context) ->
				context.client().gui.setScreen(new LecternQuillScreen(payload.pos(), payload.text(), payload.hash())));
		ClientPlayNetworking.registerGlobalReceiver(LecternPayloads.Result.TYPE, (payload, context) -> {
			if (context.client().gui.screen() instanceof LecternQuillScreen screen && screen.pos.equals(payload.pos())
					&& screen.session == payload.session() && screen.pending != null) {
				Consumer<EditPayloads.SaveResult> done = screen.pending;
				screen.pending = null;
				done.accept(new EditPayloads.SaveResult(null, payload.session(), payload.result(), payload.newHash()));
			}
		});
	}

	@Override
	protected void store(String stored, String expectedHash, Consumer<EditPayloads.SaveResult> done) {
		if (!ClientPlayNetworking.canSend(LecternPayloads.SaveChunk.TYPE)) {
			done.accept(new EditPayloads.SaveResult(null, 0, "ERROR", ""));
			return;
		}
		session = ++sessions;
		pending = done;
		byte[] bytes = stored.getBytes(StandardCharsets.UTF_8);
		int total = ChunkAssembler.chunksFor(bytes.length);
		for (int i = 0; i < total; i++) {
			int from = i * ChunkAssembler.CHUNK_BYTES;
			ClientPlayNetworking.send(new LecternPayloads.SaveChunk(pos, session, i, total, bytes.length,
					expectedHash == null ? "" : expectedHash,
					Arrays.copyOfRange(bytes, from, Math.min(bytes.length, from + ChunkAssembler.CHUNK_BYTES))));
		}
	}

	@Override
	protected boolean canOpenExternal() {
		return false;
	}

	@Override
	protected boolean canAttach() {
		attachStatus(Component.translatable("screen.memorypalace.attach_book"));
		return false;
	}

	@Override
	protected int maxChars() {
		return QuillPages.MAX_CHARS;
	}

	@Override
	protected void backToReading() {
		Minecraft.getInstance().gui.setScreen(null);
	}

	BlockPos pos() {
		return pos;
	}
}
