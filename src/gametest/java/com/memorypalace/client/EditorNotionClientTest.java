package com.memorypalace.client;

import com.memorypalace.client.book.BookTheme;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.input.InputQuirks;
import net.minecraft.client.input.KeyEvent;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;

import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Types into the real editor the way a player would: opens the "/" menu, picks Math block, continues a list with
 * Enter. Then switches on the built-in "Clean white" resource pack and takes screenshots of the editor and a book
 * (build/run/clientGameTest/screenshots).
 */
public class EditorNotionClientTest implements FabricClientGameTest {
	private static final String NOTE = """
			# Lecture 5

			Let $A \\in \\mathbb{R}^{n \\times n}$. A number $\\lambda$ is an **eigenvalue** if

			$$A\\mathbf{v} = \\lambda \\mathbf{v}$$

			- [x] read chapter 5
			- [ ] do the `numpy` exercise

			> Check the *trace*: it is the sum of the eigenvalues.

			""";

	@Override
	public void runTest(ClientGameTestContext context) {
		String pack = null;
		try {
			// the "/" menu opens under the cursor and Math block makes a $$ block with the cursor inside
			openEditor(context, NOTE);
			context.getInput().typeChars("/");
			context.waitTicks(3);
			shot(context, "editor-slash-menu");
			context.getInput().typeChars("math");
			context.waitTicks(2);
			context.getInput().pressKey(InputConstants.KEY_RETURN);
			context.getInput().typeChars("\\det(A - \\lambda I) = 0");
			context.waitTicks(10);
			expectText(context, NOTE + "$$\n\\det(A - \\lambda I) = 0\n$$");
			shot(context, "editor-after-math-block");

			// "My title /h1" turns the line into a heading
			openEditor(context, "");
			context.getInput().typeChars("My title /h1");
			context.waitTicks(2);
			context.getInput().pressKey(InputConstants.KEY_RETURN);
			expectText(context, "# My title");

			// Enter continues a list; Enter on an empty item ends it
			openEditor(context, "");
			context.getInput().typeChars("- milk");
			context.getInput().pressKey(InputConstants.KEY_RETURN);
			context.getInput().typeChars("eggs");
			context.getInput().pressKey(InputConstants.KEY_RETURN);
			context.getInput().pressKey(InputConstants.KEY_RETURN);
			expectText(context, "- milk\n- eggs\n");

			// Tab nests a list item under the one above
			openEditor(context, "");
			context.getInput().typeChars("- a");
			context.getInput().pressKey(InputConstants.KEY_RETURN);
			context.getInput().typeChars("b");
			context.getInput().pressKey(InputConstants.KEY_TAB);
			expectText(context, "- a\n  - b");

			// Ctrl+B / Ctrl+Shift+E wrap the selection (Cmd on macOS), and unwrap it when pressed again. This editor opens
			// right after a Tab press: the text must still have the focus
			openEditor(context, "");
			context.getInput().typeChars("word");
			expectText(context, "word");
			// Fabric's test input sends key presses without modifier flags, so these go to the screen directly,
			// as the game would deliver them with Shift / Ctrl (Cmd on macOS) held
			int ctrl = InputQuirks.EDIT_SHORTCUT_KEY_MODIFIER;
			int shift = 1;
			key(context, InputConstants.KEY_HOME, shift);
			key(context, InputConstants.KEY_B, ctrl);
			expectText(context, "**word**");
			key(context, InputConstants.KEY_B, ctrl);
			expectText(context, "word");
			key(context, InputConstants.KEY_E, ctrl | shift);
			expectText(context, "$word$");
			key(context, InputConstants.KEY_E, ctrl | shift);
			key(context, InputConstants.KEY_K, ctrl);
			expectText(context, "[[word]]");

			// the built-in resource pack restyles the books
			pack = context.computeOnClient(client -> client.getResourcePackRepository().getAvailableIds().stream()
					.filter(id -> id.contains("clean_white")).findFirst().orElse(null));
			if (pack == null) {
				throw new AssertionError("the Clean white pack is not listed among the resource packs");
			}
			setPack(context, pack, true);
			if (!(context.computeOnClient(client -> BookTheme.get().page()) instanceof BookTheme.Flat)) {
				throw new AssertionError("Clean white did not switch the book to flat white pages");
			}
			openEditor(context, NOTE);
			context.getInput().typeChars("/");
			context.waitTicks(5);
			shot(context, "clean-white-editor");
			context.setScreen(() -> new NoteScreen(null, Component.literal("Lecture 5"), NOTE));
			context.waitTicks(20);
			shot(context, "clean-white-book");
		} finally {
			if (pack != null) {
				setPack(context, pack, false);
			}
			context.setScreen(TitleScreen::new);
		}
	}

	private static void openEditor(ClientGameTestContext context, String body) {
		context.setScreen(() -> new EditorScreen(UUID.randomUUID(), Component.literal("Lecture 5"), body));
		context.waitTicks(5);
	}

	private static void key(ClientGameTestContext context, int key, int modifiers) {
		// same key code as a real key press (Fabric's TestInput does the same, only without modifiers)
		context.runOnClient(client -> client.gui.screen().keyPressed(
				new KeyEvent(key, org.lwjgl.sdl.SDLKeyboard.SDL_GetKeyFromScancode(key, (short) 0, false), modifiers)));
	}

	private static void expectText(ClientGameTestContext context, String expected) {
		String text = context.computeOnClient(client -> ((EditorScreen) client.gui.screen()).currentText());
		if (!text.equals(expected)) {
			throw new AssertionError("editor text was\n" + text + "\n--- expected ---\n" + expected);
		}
	}

	private static void setPack(ClientGameTestContext context, String id, boolean on) {
		CompletableFuture<Void> reload = context.computeOnClient(client -> {
			var repository = client.getResourcePackRepository();
			if (on) {
				repository.addPack(id);
			} else {
				repository.removePack(id);
			}
			return client.reloadResourcePacks();
		});
		context.waitFor(client -> reload.isDone(), 1200);
		// the loading screen fades out after the reload itself has finished
		context.waitFor(client -> client.gui.overlay() == null, 1200);
		context.waitTicks(5);
	}

	private static void shot(ClientGameTestContext context, String name) {
		Path path = context.takeScreenshot(name);
		System.out.println("MEMORYPALACE_SCREENSHOT " + path.toAbsolutePath());
	}
}
