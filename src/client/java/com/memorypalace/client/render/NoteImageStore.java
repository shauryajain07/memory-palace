package com.memorypalace.client.render;

import com.memorypalace.core.render.NoteImages;
import com.memorypalace.core.render.Raster;
import com.memorypalace.mc.ImagePayloads;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 客户端的图片仓库：向服务端要笔记里的图，到了就变成贴图。只在渲染线程使用。
 * <ul>
 * <li>Layout only needs a picture's size (size()), which is kept even after the texture itself is dropped to save
 *     memory. Drawing asks for the texture (texture()) and fetches it again when it was dropped. So a note with
 *     sixty PDF pages keeps only the pages on screen (and the most recent ones) in memory.</li>
 * <li>At most MAX_IN_FLIGHT requests are out at once (the server serves a few per player at a time); the rest
 *     wait in a queue. When the server answers "busy", the picture is asked for again a little later instead of
 *     being shown as missing.</li>
 * </ul>
 */
public final class NoteImageStore {
	/** LOADING: waiting or asked for; READY: size known (the texture may have been dropped); FAILED: not available. */
	public enum State { LOADING, READY, FAILED }

	private record Wanted(UUID noteId, String src) {}

	private static final Map<String, State> STATES = new HashMap<>();
	private static final Map<String, int[]> SIZES = new HashMap<>();
	private static final ArrayDeque<Wanted> QUEUE = new ArrayDeque<>();
	private static final Set<String> ASKED = new HashSet<>();
	// asked for before a save that may have made them available: a "not found" answer is not final for these
	private static final Set<String> RETRY_IF_MISSING = new HashSet<>();
	// 最多记这么多张图的状态（正常远到不了）；断线时清空
	private static final int MAX_STATES = 512;
	// the server handles 4 at once per player: stay under it
	private static final int MAX_IN_FLIGHT = 3;
	private static final long BUSY_RETRY_MS = 400;
	private static long retryAt;
	private static Runnable onArrive = () -> {};

	private NoteImageStore() {}

	private static String key(UUID noteId, String src) {
		return "img|" + noteId + "|" + src;
	}

	/** For layout: the picture's size {width, height} in pixels, or null while unknown (it is then asked for). */
	public static int[] size(UUID noteId, String src) {
		String key = key(noteId, src);
		int[] size = SIZES.get(key);
		if (size == null) {
			want(noteId, src, key);
		}
		return size;
	}

	/** What to tell the reader about a picture without a size yet. Does not ask for anything. */
	public static State state(UUID noteId, String src) {
		String key = key(noteId, src);
		if (SIZES.containsKey(key)) {
			return State.READY;
		}
		State state = STATES.get(key);
		return state != null ? state : STATES.size() >= MAX_STATES ? State.FAILED : State.LOADING;
	}

	/** For drawing: the texture if it is in memory; one dropped to save memory is asked for again (null meanwhile). */
	public static RasterTextures.Tex texture(UUID noteId, String src) {
		String key = key(noteId, src);
		RasterTextures.Tex tex = RasterTextures.get(key);
		if (tex == null) {
			want(noteId, src, key);
		}
		return tex;
	}

	private static void want(UUID noteId, String src, String key) {
		State state = STATES.get(key);
		if (state == State.LOADING || state == State.FAILED) {
			return;
		}
		// 一篇正文里可能塞了成千上万个不同的图片引用（正文可能来自不怀好意的服务端）：状态表满了就不再请求新图
		if (state == null && STATES.size() >= MAX_STATES) {
			return;
		}
		STATES.put(key, State.LOADING);
		QUEUE.add(new Wanted(noteId, src));
		pump();
	}

	private static void pump() {
		long now = System.currentTimeMillis();
		while (ASKED.size() < MAX_IN_FLIGHT && !QUEUE.isEmpty() && now >= retryAt) {
			Wanted w = QUEUE.poll();
			String key = key(w.noteId(), w.src());
			if (STATES.get(key) != State.LOADING || ASKED.contains(key)) {
				continue;
			}
			if (!ClientPlayNetworking.canSend(ImagePayloads.Request.TYPE)) {
				STATES.put(key, State.FAILED);
				continue;
			}
			ASKED.add(key);
			ClientPlayNetworking.send(new ImagePayloads.Request(w.noteId(), w.src()));
		}
	}

	/** 有图片到达时要做的事（笔记窗口设置成"重新排版"）。Only called when a picture's size becomes known. */
	public static void setOnArrive(Runnable callback) {
		onArrive = callback == null ? () -> {} : callback;
	}

	/**
	 * A picture just uploaded from this computer: show it at once from the local copy. The server would refuse to
	 * send it until the note is saved with the new reference in it.
	 */
	public static void seed(UUID noteId, String src, Raster raster) {
		String key = key(noteId, src);
		if (RasterTextures.put(key, raster, 0) != null) {
			STATES.put(key, State.READY);
			SIZES.put(key, new int[] {raster.width(), raster.height()});
			onArrive.run();
		}
	}

	/**
	 * After a save, or when a note is opened: pictures of this note that failed (for example not yet referenced in
	 * the saved text) are asked for again, and an answer still on its way from before counts as out of date.
	 */
	public static void retryFailed(UUID noteId) {
		String prefix = "img|" + noteId + "|";
		STATES.entrySet().removeIf(e -> e.getKey().startsWith(prefix) && e.getValue() == State.FAILED);
		for (String key : ASKED) {
			if (key.startsWith(prefix)) {
				RETRY_IF_MISSING.add(key);
			}
		}
	}

	/** 笔记内容变了（热重载）：忘掉这篇笔记的图片状态，下次重新请求，这样外部换了图也能看到。 */
	public static void forget(UUID noteId) {
		String prefix = "img|" + noteId + "|";
		STATES.keySet().removeIf(k -> k.startsWith(prefix) && !ASKED.contains(k));
		SIZES.keySet().removeIf(k -> k.startsWith(prefix));
		QUEUE.removeIf(w -> w.noteId().equals(noteId));
	}

	public static void register() {
		ClientPlayNetworking.registerGlobalReceiver(ImagePayloads.Data.TYPE, (payload, context) -> {
			String key = key(payload.noteId(), payload.src());
			// 只收自己正在等的图：服务端主动塞过来的一律不解码
			if (!ASKED.remove(key)) {
				return;
			}
			if (payload.busy()) {
				// the server has too much to do right now: try this one again shortly
				STATES.put(key, State.LOADING);
				QUEUE.add(new Wanted(payload.noteId(), payload.src()));
				retryAt = System.currentTimeMillis() + BUSY_RETRY_MS;
				return;
			}
			if (payload.png().length == 0 && RETRY_IF_MISSING.remove(key)) {
				STATES.put(key, State.LOADING);
				QUEUE.add(new Wanted(payload.noteId(), payload.src()));
				pump();
				return;
			}
			RETRY_IF_MISSING.remove(key);
			Raster raster = payload.png().length == 0 ? null : NoteImages.fromBytes(payload.png()).orElse(null);
			boolean newSize = false;
			if (raster == null || RasterTextures.put(key, raster, 0) == null) {
				STATES.put(key, State.FAILED);
				newSize = !SIZES.containsKey(key);
			} else {
				STATES.put(key, State.READY);
				newSize = SIZES.put(key, new int[] {raster.width(), raster.height()}) == null;
			}
			// a picture fetched again after being dropped is simply drawn next frame; a new size needs a new layout
			if (newSize) {
				onArrive.run();
			}
			pump();
		});
		ClientTickEvents.END_CLIENT_TICK.register(client -> pump());
		// 换服务器、退出世界：上一个服务器的图片状态全部作废
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> client.execute(() -> {
			STATES.clear();
			SIZES.clear();
			QUEUE.clear();
			ASKED.clear();
			RETRY_IF_MISSING.clear();
			retryAt = 0;
		}));
	}
}
