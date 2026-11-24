package com.memorypalace.client;

import com.memorypalace.core.markdown.MarkdownDetect;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.inventory.BookEditScreen;
import net.minecraft.client.gui.screens.inventory.BookViewScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Markdown and LaTeX in ordinary books, not just Memory Palace notes:
 * - Reading a written book whose pages are plain hand-written text with formulas or Markdown opens it in
 *   Memory Palace's reader instead of the vanilla page. Hold Shift while opening to keep the vanilla screen.
 *   Books with colours, click links or other styling (made by commands, /palace export, /palace index) stay vanilla,
 *   so nothing they rely on is lost. Books on lecterns stay vanilla too (their pages are synced with the server).
 * - The book and quill editor gets a Preview button that shows the pages written so far, rendered.
 * Only the display changes: the book itself is untouched, and players without the mod still see the plain text.
 */
public final class VanillaBookReader {
	private VanillaBookReader() {}

	// 26.x is not obfuscated, so the field names are stable (same approach as EditorScreen's textField)
	private static final Field BOOK_ACCESS = field(BookViewScreen.class, "bookAccess");
	private static final Field EDIT_PAGES = field(BookEditScreen.class, "pages");

	private static Field field(Class<?> owner, String name) {
		try {
			Field f = owner.getDeclaredField(name);
			f.setAccessible(true);
			return f;
		} catch (ReflectiveOperationException | RuntimeException e) {
			return null; // a different game version: books just stay vanilla
		}
	}

	public static void register() {
		ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
			// exact class: LecternScreen extends BookViewScreen but must stay vanilla
			if (screen.getClass() == BookViewScreen.class) {
				replaceIfRich(client, (BookViewScreen) screen);
			} else if (screen instanceof BookEditScreen edit) {
				addPreviewButton(client, edit, width);
			}
		});
	}

	private static void replaceIfRich(Minecraft client, BookViewScreen screen) {
		if (client.hasShiftDown()) {
			return;
		}
		List<Component> pages = viewPages(screen);
		if (pages == null || pages.isEmpty() || pages.stream().anyMatch(VanillaBookReader::styled)) {
			return;
		}
		String text = pages.stream().map(Component::getString).collect(Collectors.joining("\n\n"));
		if (!MarkdownDetect.isRich(text)) {
			return;
		}
		Component title = screen.getTitle();
		// next frame: switching screens from inside another screen's init is not safe
		client.execute(() -> {
			if (client.gui.screen() == screen) {
				client.gui.setScreen(new NoteScreen(null, title, text));
			}
		});
	}

	/** Any colour, bold, click or hover anywhere on the page: made by a command or a mod, keep it vanilla. */
	static boolean styled(Component page) {
		boolean[] found = {false};
		page.visit((style, text) -> {
			if (!style.isEmpty() && !text.isEmpty()) {
				found[0] = true;
			}
			return Optional.empty();
		}, Style.EMPTY);
		return found[0];
	}

	private static List<Component> viewPages(BookViewScreen screen) {
		if (BOOK_ACCESS == null) {
			return null;
		}
		try {
			return ((BookViewScreen.BookAccess) BOOK_ACCESS.get(screen)).pages();
		} catch (ReflectiveOperationException | RuntimeException e) {
			return null;
		}
	}

	@SuppressWarnings("unchecked")
	private static void addPreviewButton(Minecraft client, BookEditScreen edit, int width) {
		if (EDIT_PAGES == null) {
			return;
		}
		Button preview = Button.builder(Component.translatable("screen.memorypalace.book_preview"), b -> {
			List<String> pages;
			try {
				pages = List.copyOf((List<String>) EDIT_PAGES.get(edit));
			} catch (ReflectiveOperationException | RuntimeException e) {
				return;
			}
			String text = String.join("\n\n", pages);
			client.gui.setScreen(new NoteScreen(null, Component.translatable("screen.memorypalace.book_preview"), text) {
				// closing the preview goes back to the book and quill, with everything written so far
				@Override
				public void onClose() {
					client.gui.setScreen(edit);
				}
			});
		}).bounds(width - 84, 4, 80, 20)
				.tooltip(Tooltip.create(Component.translatable("screen.memorypalace.book_preview_hint")))
				.build();
		Screens.getWidgets(edit).add(preview);
	}
}
