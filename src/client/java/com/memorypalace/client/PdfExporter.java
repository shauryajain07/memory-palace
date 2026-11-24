package com.memorypalace.client;

import com.memorypalace.MemoryPalace;
import com.memorypalace.core.attach.Attachments;
import com.memorypalace.core.export.PdfExport;
import com.memorypalace.core.render.MathRenderer;
import com.memorypalace.core.render.NoteImages;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The reader's PDF button: the note as a PDF in .minecraft/memory-palace-exports, opened in the computer's PDF
 * viewer. Formulas are drawn here on the render thread first (JLaTeXMath is shared with the game); the page
 * layout runs in the background. Pictures are included when the world is on this computer (the files are read
 * from the vault); on a server they are left out and named instead.
 */
final class PdfExporter {
	private PdfExporter() {}

	/** Tests switch this off so no PDF viewer pops up. */
	static boolean openWhenDone = true;
	static volatile Path lastExport;

	static void export(UUID noteId, String title, String body) {
		Minecraft mc = Minecraft.getInstance();
		Map<String, PdfExport.Formula> formulas = new HashMap<>();
		for (PdfExport.MathRequest request : PdfExport.mathIn(body)) {
			String key = request.display() + "|" + request.tex();
			if (!formulas.containsKey(key)) {
				var result = MathRenderer.render(request.tex(), request.display(), PdfExport.BODY * PdfExport.PX_PER_PT, 0xFF000000);
				formulas.put(key, result.ok() ? new PdfExport.Formula(result.raster(), result.depth()) : null);
			}
		}
		Optional<Path> root = LocalVault.root();
		Optional<Path> note = noteId == null ? Optional.empty() : LocalVault.notePath(noteId);
		message(mc, Component.translatable("message.memorypalace.pdf_working"));
		Path dir = mc.gameDirectory.toPath().resolve("memory-palace-exports");
		Thread.ofVirtual().name("memorypalace-pdf").start(() -> {
			try {
				PdfExport.Images images = src -> {
					if (root.isEmpty() || note.isEmpty()) {
						return Optional.empty();
					}
					String relative = root.get().toAbsolutePath().normalize().relativize(note.get().toAbsolutePath().normalize())
							.toString().replace('\\', '/');
					return NoteImages.resolve(root.get(), relative, src).flatMap(file -> NoteImages.load(file, 2000));
				};
				PdfExport.Result result = PdfExport.export(title, body, images, (tex, display) -> formulas.get(display + "|" + tex));
				Files.createDirectories(dir);
				Path file = write(dir, Attachments.baseName(title, "note"), result.pdf());
				lastExport = file;
				boolean missingPictures = result.notes().stream().anyMatch(n -> n.startsWith("picture "));
				mc.execute(() -> {
					message(mc, Component.translatable("message.memorypalace.pdf_saved", result.pages(),
							Component.literal(file.getFileName().toString()).withStyle(Style.EMPTY.withUnderlined(true)
									.withColor(ChatFormatting.AQUA).withClickEvent(new ClickEvent.OpenFile(file.toString())))));
					if (missingPictures) {
						message(mc, Component.translatable(root.isEmpty() ? "message.memorypalace.pdf_no_pictures_server"
								: "message.memorypalace.pdf_missing_pictures"));
					}
					if (openWhenDone) {
						ExternalEditor.open(file);
					}
				});
			} catch (IOException | RuntimeException e) {
				MemoryPalace.LOGGER.warn("Could not export {} as PDF", title, e);
				mc.execute(() -> message(mc, Component.translatable("message.memorypalace.pdf_failed", String.valueOf(e.getMessage()))));
			}
		});
	}

	/** name.pdf, or name-2.pdf, … when taken. */
	private static Path write(Path dir, String name, byte[] pdf) throws IOException {
		for (int n = 1; ; n++) {
			Path file = dir.resolve((n == 1 ? name : name + "-" + n) + ".pdf");
			try {
				Files.write(file, pdf, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
				return file;
			} catch (java.nio.file.FileAlreadyExistsException taken) {
				// next number
			}
		}
	}

	/** In chat (where the saved file's link can be clicked later) and above the open book. */
	private static void message(Minecraft mc, Component text) {
		if (mc.player != null) {
			mc.player.sendSystemMessage(text);
		}
		if (mc.gui.screen() instanceof com.memorypalace.client.book.BookScreen book) {
			book.notice(text);
		}
	}
}
