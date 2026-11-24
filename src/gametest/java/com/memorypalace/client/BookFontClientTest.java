package com.memorypalace.client;

import com.memorypalace.client.book.BookSettings;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;

import java.nio.file.Path;
import java.util.Locale;

/**
 * Opens a note in the book screen with each text font and takes a screenshot of it
 * (build/run/clientGameTest/screenshots), so the typeset page can be looked at without playing.
 */
public class BookFontClientTest implements FabricClientGameTest {
	private static final String SAMPLE = """
			# Eigenvalues

			Let $A \\in \\mathbb{R}^{n \\times n}$. A number $\\lambda$ is an **eigenvalue** of $A$ if

			$$A\\mathbf{v} = \\lambda \\mathbf{v}$$

			for some *nonzero* vector $\\mathbf{v}$, and ***both*** at once.

			- The trace is `tr(A)` = sum of the diagonal.
			- Symbols: < > { } | _ \\\\ " ' $5 & 100%
			- Accents and more: café, naïve, 中文

			> A quote, set in italic.
			""";

	@Override
	public void runTest(ClientGameTestContext context) {
		BookSettings.Typeface before = context.computeOnClient(client -> BookSettings.typeface());
		try {
			for (BookSettings.Typeface face : BookSettings.Typeface.values()) {
				context.runOnClient(client -> BookSettings.setTypeface(face));
				context.setScreen(() -> new NoteScreen(null, Component.literal("Typeface test"), SAMPLE));
				context.waitTicks(20);
				Path shot = context.takeScreenshot("book-" + face.name().toLowerCase(Locale.ROOT));
				System.out.println("MEMORYPALACE_SCREENSHOT " + shot.toAbsolutePath());
			}
		} finally {
			context.runOnClient(client -> BookSettings.setTypeface(before));
			// client game tests must end where they started
			context.setScreen(TitleScreen::new);
		}
	}
}
