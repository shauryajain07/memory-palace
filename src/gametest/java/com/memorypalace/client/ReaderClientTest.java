package com.memorypalace.client;

import com.memorypalace.core.vault.Vault;
import com.memorypalace.mc.PalaceServer;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.components.Button;
import net.minecraft.core.BlockPos;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/**
 * The reader's own tools in a real world: tick a to-do by clicking its box (the file on disk changes), open the
 * outline with ☰ and click a heading (the book turns to its page).
 */
public class ReaderClientTest implements FabricClientGameTest {
	private static final String FILLER = "A paragraph that takes some room on the page so the headings below end up "
			+ "on later pages of the book, which is what the outline is for.\n\n";

	@Override
	public void runTest(ClientGameTestContext context) {
		String body = "# Week plan\n\nRevise for the #midterm.\n\n- [ ] read chapter 5\n- [ ] finish the lab\n  - [x] part A\n\n"
				+ FILLER.repeat(6) + "## Exam topics\n\n" + FILLER.repeat(6) + "## Later\n\nThe end.\n";
		try (TestSingleplayerContext world = context.worldBuilder().create()) {
			UUID id = world.getServer().computeOnServer(server -> PalaceServer.vault().createNote("Notes/Plan.md", body).id());
			Path file = world.getServer().computeOnServer(server -> PalaceServer.vault().pathOf(id).orElseThrow());
			String stored = world.getServer().computeOnServer(server -> PalaceServer.vault().readNote(id).orElseThrow().body());
			context.setScreen(() -> new NoteScreen(id, Component.literal("Plan"), stored, false, Vault.hash(stored), true));
			context.waitTicks(10);

			// tick "finish the lab" (to-do number 1) by clicking its box
			int[] box = context.computeOnClient(client -> ((NoteScreen) client.gui.screen()).taskScreenPosition(1));
			check(box != null, "the second to-do's box is on the first spread");
			click(context, box[0], box[1]);
			context.waitFor(client -> read(file).contains("- [x] finish the lab"), 20 * 10);
			check(read(file).contains("- [ ] read chapter 5") && read(file).contains("  - [x] part A"), "only that box changed");
			check(context.computeOnClient(client -> ((NoteScreen) client.gui.screen()).currentBody()).contains("- [x] finish the lab"),
					"the page shows the tick");

			// ☰ opens the outline; clicking "Later" turns to its page
			int[] outlineButton = context.computeOnClient(client -> {
				for (var child : client.gui.screen().children()) {
					if (child instanceof Button b && b.getMessage().getString().equals("☰")) {
						return new int[] {b.getX() + b.getWidth() / 2, b.getY() + b.getHeight() / 2};
					}
				}
				return null;
			});
			check(outlineButton != null, "the reader shows a ☰ button for a note with headings");
			click(context, outlineButton[0], outlineButton[1]);
			check(context.computeOnClient(client -> ((NoteScreen) client.gui.screen()).outlineShowing()), "the outline opened");
			Path shot = context.takeScreenshot("reader-outline");
			System.out.println("MEMORYPALACE_SCREENSHOT " + shot.toAbsolutePath());
			int[] later = context.computeOnClient(client -> ((NoteScreen) client.gui.screen()).outlineRowPosition(2));
			check(later != null, "the third heading is listed");
			click(context, later[0], later[1]);
			int spread = context.computeOnClient(client -> ((NoteScreen) client.gui.screen()).currentSpread());
			check(spread > 0, "jumped to a later spread, now on " + spread);
			check(!context.computeOnClient(client -> ((NoteScreen) client.gui.screen()).outlineShowing()), "the outline closed");

			// a #tag is clickable and opens the search with that tag typed in
			context.runOnClient(client -> ((NoteScreen) client.gui.screen()).showSpread(0));
			int[] tag = context.computeOnClient(client -> ((NoteScreen) client.gui.screen()).linkScreenPosition("tag:midterm"));
			check(tag != null, "the #midterm tag is on the first page");
			click(context, tag[0], tag[1]);
			String query = context.computeOnClient(client -> client.gui.screen() instanceof SearchScreen search ? search.currentQuery() : null);
			check("#midterm".equals(query), "clicking the tag searches for it, query was " + query);

			// /template: the class's own template, with its placeholders filled
			world.getServer().runOnServer(server -> PalaceServer.vault().createNote("Library/ENGG1101/Templates/Lecture.md",
					"# {{class}} lecture · {{date}}\n\n## Key ideas\n- \n"));
			UUID week5 = world.getServer().computeOnServer(server ->
					PalaceServer.vault().createNote("Library/ENGG1101/Week 5.md", "").id());
			context.setScreen(() -> new EditorScreen(week5, Component.literal("Week 5"), ""));
			context.waitFor(client -> client.gui.screen() instanceof EditorScreen editor
					&& editor.templateNames().contains("Lecture"), 20 * 10);
			context.getInput().typeChars("/lect");
			context.waitTicks(2);
			context.getInput().pressKey(com.mojang.blaze3d.platform.InputConstants.KEY_RETURN);
			String filled = context.computeOnClient(client -> ((EditorScreen) client.gui.screen()).currentText());
			check(filled.startsWith("# ENGG1101 lecture · " + java.time.LocalDate.now()) && filled.contains("## Key ideas"),
					"template inserted and filled:\n" + filled);

			// a book and quill on a lectern: right-click opens the editor, Save writes the lectern's book
			BlockPos lecternPos = world.getServer().computeOnServer(server -> {
				var player = server.getPlayerList().getPlayers().getFirst();
				BlockPos at = player.blockPosition().relative(player.getDirection(), 2);
				var level = player.level();
				level.setBlockAndUpdate(at, net.minecraft.world.level.block.Blocks.LECTERN.defaultBlockState());
				var quill = new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.WRITABLE_BOOK);
				quill.set(net.minecraft.core.component.DataComponents.WRITABLE_BOOK_CONTENT,
						new net.minecraft.world.item.component.WritableBookContent(java.util.List.of(
								net.minecraft.server.network.Filterable.passThrough("first page"))));
				net.minecraft.world.level.block.LecternBlock.tryPlaceBook(player, level, at, level.getBlockState(at), quill);
				return at;
			});
			context.setScreen(() -> null);
			context.waitTicks(5);
			context.runOnClient(client -> client.gameMode.useItemOn(client.player, net.minecraft.world.InteractionHand.MAIN_HAND,
					new net.minecraft.world.phys.BlockHitResult(net.minecraft.world.phys.Vec3.atCenterOf(lecternPos),
							net.minecraft.core.Direction.UP, lecternPos, false)));
			context.waitForScreen(LecternQuillScreen.class);
			check(context.computeOnClient(client -> ((EditorScreen) client.gui.screen()).currentText()).equals("first page"),
					"the editor shows the book's text");
			context.getInput().pressKey(com.mojang.blaze3d.platform.InputConstants.KEY_RETURN);
			context.getInput().pressKey(com.mojang.blaze3d.platform.InputConstants.KEY_RETURN);
			context.getInput().typeChars("- [ ] new line from the editor");
			context.clickScreenButton("screen.memorypalace.save");
			for (int tick = 0; tick < 200 && !lecternText(world, lecternPos).contains("new line from the editor"); tick++) {
				context.waitTick();
			}
			check(lecternText(world, lecternPos).equals("first page\n\n- [ ] new line from the editor"),
					"the lectern's book was written: " + lecternText(world, lecternPos));

			// flashcards: the reader's Review button, Space to show the answer, 1-4 to grade; the schedule is saved
			String cardsBody = "# Cards\n\nWhat is $\\det A$? :: The product of the eigenvalues\n"
					+ "- Trace :: Sum of the eigenvalues\n- [ ] Rank :: Number of pivots\n";
			UUID deckNote = world.getServer().computeOnServer(server ->
					PalaceServer.vault().createNote("Library/ENGG1101/Cards.md", cardsBody).id());
			String cardsStored = world.getServer().computeOnServer(server -> PalaceServer.vault().readNote(deckNote).orElseThrow().body());
			context.setScreen(() -> new NoteScreen(deckNote, Component.literal("Cards"), cardsStored, false, Vault.hash(cardsStored), true));
			context.waitTicks(5);
			int[] review = context.computeOnClient(client -> {
				for (var child : client.gui.screen().children()) {
					if (child instanceof Button b && b.getMessage().getString().contains("3")) {
						return new int[] {b.getX() + b.getWidth() / 2, b.getY() + b.getHeight() / 2};
					}
				}
				return null;
			});
			check(review != null, "the reader offers to review the 3 cards");
			click(context, review[0], review[1]);
			context.waitForScreen(ReviewScreen.class);
			check(context.computeOnClient(client -> ((ReviewScreen) client.gui.screen()).currentQuestion()).startsWith("What is"),
					"the first new card is asked first");
			context.getInput().pressKey(com.mojang.blaze3d.platform.InputConstants.KEY_SPACE);
			check(context.computeOnClient(client -> ((ReviewScreen) client.gui.screen()).isRevealed()), "Space shows the answer");
			Path reviewShot = context.takeScreenshot("review-card");
			System.out.println("MEMORYPALACE_SCREENSHOT " + reviewShot.toAbsolutePath());
			context.getInput().pressKey(com.mojang.blaze3d.platform.InputConstants.KEY_3); // Good
			context.getInput().pressKey(com.mojang.blaze3d.platform.InputConstants.KEY_SPACE);
			context.getInput().pressKey(com.mojang.blaze3d.platform.InputConstants.KEY_1); // Again: comes back at the end
			context.getInput().pressKey(com.mojang.blaze3d.platform.InputConstants.KEY_SPACE);
			context.getInput().pressKey(com.mojang.blaze3d.platform.InputConstants.KEY_4); // Easy
			String last = context.computeOnClient(client -> ((ReviewScreen) client.gui.screen()).currentQuestion());
			check("Trace".equals(last), "the card answered Again is asked once more, got " + last);
			context.getInput().pressKey(com.mojang.blaze3d.platform.InputConstants.KEY_SPACE);
			context.getInput().pressKey(com.mojang.blaze3d.platform.InputConstants.KEY_3);
			check(context.computeOnClient(client -> ((ReviewScreen) client.gui.screen()).currentQuestion()) == null, "deck finished");
			context.waitTicks(5);
			Path schedule = world.getServer().computeOnServer(server -> PalaceServer.vault().root().resolve(".palace/cards")
					.resolve(server.getPlayerList().getPlayers().getFirst().getUUID() + ".tsv"));
			check(read(schedule).lines().count() == 3, "three cards scheduled:\n" + read(schedule));

			// /palace review ENGG1101: everything was just answered, so nothing is due (only the Again card was shown twice)
			context.setScreen(() -> null);
			context.runOnClient(client -> client.player.connection.sendCommand("palace review Library/ENGG1101"));
			context.waitTicks(10);
			check(!(context.computeOnClient(client -> client.gui.screen()) instanceof ReviewScreen),
					"nothing due right after reviewing: no review screen");

			// with the reader open, "nothing due" shows above the book (chat is hard to see behind it)
			context.setScreen(() -> new NoteScreen(deckNote, Component.literal("Cards"), cardsStored, false, Vault.hash(cardsStored), true));
			context.waitTicks(5);
			click(context, review[0], review[1]);
			context.waitTicks(10);
			String notice = context.computeOnClient(client -> client.gui.screen() instanceof NoteScreen reader
					&& reader.currentNotice() != null ? reader.currentNotice().getString() : null);
			check(notice != null && notice.equals("All caught up! The next card is due tomorrow."), "notice above the book, got " + notice);
			Path noticeShot = context.takeScreenshot("reader-notice");
			System.out.println("MEMORYPALACE_SCREENSHOT " + noticeShot.toAbsolutePath());

			// PDF: the reader's button saves the note to memory-palace-exports (no viewer pops up in the test)
			PdfExporter.openWhenDone = false;
			PdfExporter.lastExport = null;
			String planNow = world.getServer().computeOnServer(server -> PalaceServer.vault().readNote(id).orElseThrow().body());
			context.setScreen(() -> new NoteScreen(id, Component.literal("Plan"), planNow, false, Vault.hash(planNow), true));
			context.waitTicks(5);
			int[] pdfButton = context.computeOnClient(client -> {
				for (var child : client.gui.screen().children()) {
					if (child instanceof Button b && b.getMessage().getString().equals("PDF")) {
						return new int[] {b.getX() + b.getWidth() / 2, b.getY() + b.getHeight() / 2};
					}
				}
				return null;
			});
			check(pdfButton != null, "the reader has a PDF button");
			click(context, pdfButton[0], pdfButton[1]);
			for (int tick = 0; tick < 400 && PdfExporter.lastExport == null; tick++) {
				context.waitTick();
			}
			check(PdfExporter.lastExport != null && Files.exists(PdfExporter.lastExport), "the PDF was written");
			try (var pdf = org.apache.pdfbox.Loader.loadPDF(PdfExporter.lastExport.toFile())) {
				String pdfText = new org.apache.pdfbox.text.PDFTextStripper().getText(pdf);
				check(pdfText.contains("Week plan") && pdfText.contains("Exam topics") && pdf.getNumberOfPages() >= 1,
						"the PDF holds the note:\n" + pdfText.substring(0, Math.min(300, pdfText.length())));
			}
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	private static String lecternText(TestSingleplayerContext world, BlockPos pos) {
		return world.getServer().computeOnServer(server -> {
			var lectern = (net.minecraft.world.level.block.entity.LecternBlockEntity)
					server.overworld().getBlockEntity(pos);
			var content = lectern.getBook().getOrDefault(net.minecraft.core.component.DataComponents.WRITABLE_BOOK_CONTENT,
					net.minecraft.world.item.component.WritableBookContent.EMPTY);
			return String.join("\n\n", content.pages().stream().map(net.minecraft.server.network.Filterable::raw).toList());
		});
	}

	private static void click(ClientGameTestContext context, int x, int y) {
		// left button is 1 since 26.x (SDL)
		context.runOnClient(client -> client.gui.screen().mouseClicked(new MouseButtonEvent(x, y, new MouseButtonInfo(1, 0)), false));
		context.waitTicks(2);
	}

	private static String read(Path file) {
		try {
			return Files.readString(file);
		} catch (IOException e) {
			return "";
		}
	}

	private static void check(boolean ok, String what) {
		if (!ok) {
			throw new AssertionError(what);
		}
	}
}
