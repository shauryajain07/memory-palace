package com.memorypalace.client.attach;

import com.memorypalace.MemoryPalace;
import net.minecraft.client.Minecraft;
import org.lwjgl.sdl.SDLClipboard;
import org.lwjgl.sdl.SDLDialog;
import org.lwjgl.sdl.SDLStdinc;
import org.lwjgl.sdl.SDL_DialogFileCallback;
import org.lwjgl.sdl.SDL_DialogFileFilter;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.Pointer;

import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * The computer's own "Open file" dialog and pictures on the clipboard, through SDL (which runs the game window
 * since 26.x). Both are extras: dragging files onto the editor always works.
 */
public final class FilePicker {
	private FilePicker() {}

	public enum Filter {
		PICTURES("png;jpg;jpeg;gif;bmp"), PDF("pdf");

		final String pattern;

		Filter(String pattern) {
			this.pattern = pattern;
		}
	}

	/** Native memory and the callback of an open dialog: kept reachable until SDL has called back. */
	private record Open(SDL_DialogFileCallback callback, SDL_DialogFileFilter.Buffer filters, ByteBuffer name,
			ByteBuffer pattern) {
		void free() {
			callback.free();
			filters.free();
			MemoryUtil.memFree(name);
			MemoryUtil.memFree(pattern);
		}
	}

	private static final Set<Open> OPEN = ConcurrentHashMap.newKeySet();

	/**
	 * Opens the dialog over the game window; onPicked runs on the render thread with the chosen files (empty when
	 * cancelled). Returns false when the dialog could not be opened at all.
	 */
	public static boolean open(Filter filter, String filterName, Consumer<List<Path>> onPicked) {
		Minecraft mc = Minecraft.getInstance();
		ByteBuffer name = null;
		ByteBuffer pattern = null;
		SDL_DialogFileFilter.Buffer filters = null;
		Open[] self = new Open[1];
		try {
			name = MemoryUtil.memUTF8(filterName);
			pattern = MemoryUtil.memUTF8(filter.pattern);
			filters = SDL_DialogFileFilter.calloc(1);
			filters.get(0).name(name).pattern(pattern);
			SDL_DialogFileCallback callback = SDL_DialogFileCallback.create((userdata, fileList, chosenFilter) -> {
				List<Path> paths = new ArrayList<>();
				if (fileList != 0) {
					for (int i = 0; ; i++) {
						long entry = MemoryUtil.memGetAddress(fileList + (long) i * Pointer.POINTER_SIZE);
						if (entry == 0) {
							break;
						}
						String path = MemoryUtil.memUTF8(entry);
						try {
							paths.add(Path.of(path));
						} catch (RuntimeException ignored) {
							// not a path this system understands
						}
					}
				}
				// SDL may call back on another thread, or inside the call below: finish on the render thread
				mc.execute(() -> {
					Open done = self[0];
					if (done != null && OPEN.remove(done)) {
						done.free();
					}
					onPicked.accept(paths);
				});
			});
			self[0] = new Open(callback, filters, name, pattern);
			OPEN.add(self[0]);
			SDLDialog.SDL_ShowOpenFileDialog(callback, 0L, mc.getWindow().handle(), filters, (CharSequence) null, true);
			return true;
		} catch (Throwable t) {
			MemoryPalace.LOGGER.warn("Could not open the file dialog", t);
			if (self[0] != null) {
				if (OPEN.remove(self[0])) {
					self[0].free();
				}
			} else {
				if (filters != null) {
					filters.free();
				}
				MemoryUtil.memFree(name);
				MemoryUtil.memFree(pattern);
			}
			return false;
		}
	}

	/**
	 * The picture on the clipboard (PNG, JPEG, BMP or TIFF bytes), or null when there is none or the clipboard
	 * also holds text (then Ctrl+V pastes the text as usual).
	 */
	public static byte[] clipboardPicture() {
		try {
			if (SDLClipboard.SDL_HasClipboardText()) {
				return null;
			}
			for (String mime : List.of("image/png", "image/jpeg", "image/bmp", "image/tiff")) {
				if (!SDLClipboard.SDL_HasClipboardData(mime)) {
					continue;
				}
				ByteBuffer data = SDLClipboard.SDL_GetClipboardData(mime);
				if (data == null) {
					continue;
				}
				try {
					byte[] bytes = new byte[data.remaining()];
					data.get(bytes);
					return bytes.length == 0 ? null : bytes;
				} finally {
					SDLStdinc.SDL_free(data);
				}
			}
		} catch (Throwable t) {
			MemoryPalace.LOGGER.warn("Could not read a picture from the clipboard", t);
		}
		return null;
	}
}
