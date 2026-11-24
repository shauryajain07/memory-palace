package com.memorypalace.client;

import com.memorypalace.client.attach.AttachmentImport;
import com.memorypalace.core.render.NoteImages;
import com.memorypalace.core.render.Raster;
import com.memorypalace.mc.PalaceServer;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.network.chat.Component;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

/**
 * The whole way from a file on this computer to a picture in a note, in a real single-player world: drops a PNG and
 * a 3-page PDF onto the editor (as dragging them onto the window does), waits for the uploads, then checks the
 * Markdown that went in and the files beside the note.
 */
public class AttachClientTest implements FabricClientGameTest {
	@Override
	public void runTest(ClientGameTestContext context) {
		Path dir;
		Path picture;
		Path slides;
		try {
			dir = Files.createTempDirectory("memorypalace-attach");
			picture = dir.resolve("graph.png");
			Files.write(picture, NoteImages.toPng(gradient(2000, 1000)));
			slides = dir.resolve("Lecture 3 slides.pdf");
			writeSlides(slides, 3);
		} catch (IOException e) {
			throw new RuntimeException(e);
		}

		try (TestSingleplayerContext world = context.worldBuilder().create()) {
			UUID id = world.getServer().computeOnServer(server ->
					PalaceServer.vault().createNote("ENGG1330/Week 3.md", "# Week 3\n\n").id());
			Path root = world.getServer().computeOnServer(server -> PalaceServer.vault().root());
			context.setScreen(() -> new EditorScreen(id, Component.literal("Week 3"), "# Week 3\n\n"));
			context.waitTicks(5);
			context.runOnClient(client -> ((EditorScreen) client.gui.screen()).onFilesDrop(List.of(picture, slides)));
			context.waitTicks(2);
			context.waitFor(client -> !AttachmentImport.busy(), 20 * 120);
			context.waitTicks(10);

			String text = context.computeOnClient(client -> ((EditorScreen) client.gui.screen()).currentText());
			System.out.println("MEMORYPALACE_ATTACH_TEXT\n" + text);
			check(text.contains("![graph](attachments/graph.png)"), "picture reference in:\n" + text);
			check(text.contains("**Lecture-3-slides.pdf** · 3 pages · [open PDF](attachments/Lecture-3-slides.pdf)"),
					"PDF title line in:\n" + text);
			for (int page = 1; page <= 3; page++) {
				check(text.matches("(?s).*!\\[Lecture-3-slides p" + page + "]\\(attachments/Lecture-3-slides-p" + page + "\\.(png|jpg)\\).*"),
						"page " + page + " in:\n" + text);
			}
			check(!text.contains("[Adding"), "placeholders were all replaced:\n" + text);

			Path attachments = root.resolve("ENGG1330/attachments");
			Raster stored = NoteImages.fromBytes(Files.readAllBytes(attachments.resolve("graph.png")), 1L << 24, 4096).orElseThrow();
			check(stored.width() == 1600 && stored.height() == 800, "picture shrunk to 1600 wide: " + stored.width() + "x" + stored.height());
			check(Files.readAllBytes(attachments.resolve("Lecture-3-slides.pdf")).length == Files.size(slides), "PDF kept as it was");
			try (var files = Files.list(attachments)) {
				check(files.count() == 5, "picture + PDF + 3 pages");
			}
			Path shot = context.takeScreenshot("attach-editor");
			System.out.println("MEMORYPALACE_SCREENSHOT " + shot.toAbsolutePath());

			// A long lecture deck: import, save, reopen in the reader and look at every page. The reader may hold
			// only some page textures at once and the server serves a few pictures at a time: every page must
			// still show when its spread is open.
			int pages = 30;
			Path deck = dir.resolve("Week 4 deck.pdf");
			writeSlides(deck, pages);
			UUID week4 = world.getServer().computeOnServer(server ->
					PalaceServer.vault().createNote("ENGG1330/Week 4.md", "# Week 4\n\n").id());
			context.setScreen(() -> new EditorScreen(week4, Component.literal("Week 4"), "# Week 4\n\n"));
			context.waitTicks(5);
			context.runOnClient(client -> ((EditorScreen) client.gui.screen()).onFilesDrop(List.of(deck)));
			context.waitTicks(2);
			context.waitFor(client -> !AttachmentImport.busy(), 20 * 180);
			context.clickScreenButton("screen.memorypalace.save");
			Path week4File = root.resolve("ENGG1330/Week 4.md");
			context.waitFor(client -> {
				try {
					return Files.readString(week4File).contains("Week-4-deck-p" + pages + ".");
				} catch (IOException e) {
					return false;
				}
			}, 20 * 30);
			String saved = Files.readString(week4File);
			String body = saved.substring(saved.indexOf("# Week 4"));
			context.setScreen(() -> new NoteScreen(week4, Component.literal("Week 4"), body));
			context.waitFor(client -> ((NoteScreen) client.gui.screen()).allImageSizesKnown(), 20 * 120);
			int spreads = context.computeOnClient(client -> ((NoteScreen) client.gui.screen()).spreadCount());
			var seen = new java.util.TreeSet<String>();
			for (int s = 0; s < spreads; s++) {
				int index = s;
				context.runOnClient(client -> ((NoteScreen) client.gui.screen()).showSpread(index));
				try {
					context.waitFor(client -> {
						NoteScreen reader = (NoteScreen) client.gui.screen();
						return reader.visibleImages(true).size() == reader.visibleImages(false).size();
					}, 20 * 30);
				} catch (AssertionError e) {
					String states = context.computeOnClient(client -> {
						NoteScreen reader = (NoteScreen) client.gui.screen();
						var out = new StringBuilder();
						for (String src : reader.visibleImages(false)) {
							out.append(src).append('=').append(com.memorypalace.client.render.NoteImageStore.state(week4, src))
									.append(" texture=").append(com.memorypalace.client.render.NoteImageStore.texture(week4, src) != null)
									.append("; ");
						}
						return out.toString();
					});
					throw new AssertionError("spread " + index + " of " + spreads + " never finished loading: " + states, e);
				}
				seen.addAll(context.computeOnClient(client -> ((NoteScreen) client.gui.screen()).visibleImages(true)));
				if (s == spreads / 2) {
					Path middle = context.takeScreenshot("attach-reader-middle");
					System.out.println("MEMORYPALACE_SCREENSHOT " + middle.toAbsolutePath());
				}
			}
			System.out.println("MEMORYPALACE_PAGES_SEEN " + seen.size() + " over " + spreads + " spreads");
			check(seen.size() == pages, "every page of the deck was shown: " + seen.size() + " of " + pages);
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	private static void check(boolean ok, String what) {
		if (!ok) {
			throw new AssertionError(what);
		}
	}

	private static Raster gradient(int w, int h) {
		int[] argb = new int[w * h];
		for (int i = 0; i < argb.length; i++) {
			int x = i % w;
			int y = i / w;
			argb[i] = 0xFF000000 | (x * 255 / w) << 16 | (y * 255 / h) << 8 | 0x80;
		}
		return new Raster(w, h, argb);
	}

	/** Slides with a title in Helvetica and a coloured box, like a lecture deck. */
	private static void writeSlides(Path file, int pages) throws IOException {
		try (var document = new PDDocument()) {
			var font = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
			for (int i = 1; i <= pages; i++) {
				var page = new PDPage(new PDRectangle(PDRectangle.A4.getHeight(), PDRectangle.A4.getWidth()));
				document.addPage(page);
				try (var content = new PDPageContentStream(document, page)) {
					content.beginText();
					content.setFont(font, 40);
					content.newLineAtOffset(60, 500);
					content.showText("ENGG1330 - Slide " + i);
					content.endText();
					content.setNonStrokingColor(0.9F, 0.5F, 0.1F);
					content.addRect(60, 100, 300 + 50 * i, 250);
					content.fill();
				}
			}
			document.save(file.toFile());
		}
	}
}
