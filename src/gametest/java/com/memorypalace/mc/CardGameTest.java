package com.memorypalace.mc;

import com.memorypalace.core.cards.Scheduler;
import com.memorypalace.core.vault.NoteSummary;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;

/**
 * Flashcard scheduling through the real server handler with a controllable clock: a card answered "Good" is not
 * due the same day, comes back the next day, and a grade for a card that was not in the player's deck is ignored.
 */
public class CardGameTest {
	@GameTest(maxTicks = 100)
	public void cardsComeBackWhenDue(GameTestHelper helper) throws Exception {
		Path config = FabricLoader.getInstance().getConfigDir().resolve(PalaceConfig.FILE_NAME);
		String before = Files.exists(config) ? Files.readString(config) : null;
		Files.createDirectories(config.getParent());
		Files.writeString(config, "multiplayer = true\n");
		PalaceConfig.load();
		Clock realClock = CardPayloads.clock;
		try {
			ServerPlayer player = helper.makeMockServerPlayerInLevel();
			String folder = "cardtest-" + UUID.randomUUID().toString().substring(0, 8);
			NoteSummary note = PalaceServer.vault().createNote(folder + "/Deck.md",
					"# Deck\nWhat is $\\det A$? :: The product of the eigenvalues\nTemplates are skipped\n", player.getUUID());
			PalaceServer.vault().createNote(folder + "/Templates/Lecture.md", "Template card :: not reviewed", player.getUUID());

			var deck = CardPayloads.deck(player, "folder:" + folder).orElseThrow();
			helper.assertTrue(deck.cards().size() == 1 && deck.total() == 1, "one new card, template left out: " + deck);
			String id = deck.cards().getFirst().id();

			helper.assertTrue(CardPayloads.grade(player, new CardPayloads.Grade("ffffffffffffffff", 2)).isEmpty(),
					"a card that was not in the deck is ignored");
			Scheduler.Progress after = CardPayloads.grade(player, new CardPayloads.Grade(id, Scheduler.Grade.GOOD.ordinal())).orElseThrow();
			helper.assertTrue(after.interval() == 1, "Good on a new card: back in 1 day, got " + after);

			var sameDay = CardPayloads.deck(player, "note:" + note.id()).orElseThrow();
			helper.assertTrue(sameDay.cards().isEmpty() && sameDay.total() == 1 && sameDay.nextDue() == sameDay.today() + 1,
					"not due again today: " + sameDay);

			CardPayloads.clock = Clock.offset(realClock, Duration.ofDays(1));
			var tomorrow = CardPayloads.deck(player, "note:" + note.id()).orElseThrow();
			helper.assertTrue(tomorrow.cards().size() == 1 && tomorrow.cards().getFirst().id().equals(id)
					&& tomorrow.cards().getFirst().reps() == 1, "due again the next day: " + tomorrow);
			Path schedule = PalaceServer.vault().root().resolve(".palace/cards/" + player.getUUID() + ".tsv");
			helper.assertTrue(Files.readString(schedule).startsWith(id + "\t1\t1\t"), "schedule saved: " + Files.readString(schedule));
			helper.succeed();
		} finally {
			CardPayloads.clock = realClock;
			if (before == null) {
				Files.deleteIfExists(config);
			} else {
				Files.writeString(config, before);
			}
			PalaceConfig.load();
		}
	}
}
