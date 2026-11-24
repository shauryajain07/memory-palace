package com.memorypalace.core.cards;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class FlashcardsTest {
	private static final UUID NOTE = UUID.fromString("00000000-0000-0000-0000-000000000001");

	@Test
	void parsesCardLines() {
		List<Flashcards.Card> cards = Flashcards.parse(NOTE, """
				# Eigenvalues
				What is an eigenvalue? :: A $\\lambda$ with $A\\mathbf{v} = \\lambda\\mathbf{v}$
				- det(A) :: the product of the eigenvalues
				- [ ] tr(A) :: the sum of the eigenvalues
				> Quoted :: card
				std::vector is not a card
				Write cards like `Question :: Answer`
				 :: no question
				no answer ::\s
				```
				in code :: not a card
				```
				$$
				a :: b
				$$
				What is an eigenvalue? :: duplicate question
				""");
		assertEquals(List.of("What is an eigenvalue?", "det(A)", "tr(A)", "Quoted"),
				cards.stream().map(Flashcards.Card::question).toList());
		assertEquals("the product of the eigenvalues", cards.get(1).answer());
	}

	@Test
	void idsFollowTheQuestionNotTheAnswer() {
		String a = Flashcards.parse(NOTE, "Q :: one").getFirst().id();
		String b = Flashcards.parse(NOTE, "Q   :: two").getFirst().id();
		String other = Flashcards.parse(UUID.randomUUID(), "Q :: one").getFirst().id();
		assertEquals(a, b);
		assertNotEquals(a, other);
		assertTrue(a.matches("[0-9a-f]{16}"));
	}

	@Test
	void scheduleGrowsWithGoodAnswersAndRestartsOnAgain() {
		long today = 20_000;
		var p = Scheduler.Progress.NEW;
		assertTrue(Scheduler.isDue(p, today));
		p = Scheduler.next(p, Scheduler.Grade.GOOD, today);
		assertEquals(1, p.interval());
		assertEquals(today + 1, p.due());
		assertFalse(Scheduler.isDue(p, today));
		p = Scheduler.next(p, Scheduler.Grade.GOOD, today + 1);
		assertEquals(3, p.interval());
		p = Scheduler.next(p, Scheduler.Grade.GOOD, today + 4);
		assertEquals(Math.round(3 * 2.5), p.interval());
		var easy = Scheduler.next(p, Scheduler.Grade.EASY, today + 12);
		assertTrue(easy.interval() > Scheduler.next(p, Scheduler.Grade.GOOD, today + 12).interval());
		var again = Scheduler.next(p, Scheduler.Grade.AGAIN, today + 12);
		assertEquals(0, again.reps());
		assertEquals(1, again.lapses());
		assertEquals(today + 12, again.due(), "seen again today");
		assertTrue(again.ease() < p.ease());
		var hard = Scheduler.next(Scheduler.Progress.NEW, Scheduler.Grade.HARD, today);
		assertTrue(hard.ease() >= Scheduler.MIN_EASE);
		var many = Scheduler.Progress.NEW;
		for (int i = 0; i < 30; i++) {
			many = Scheduler.next(many, Scheduler.Grade.AGAIN, today);
		}
		assertEquals(Scheduler.MIN_EASE, many.ease(), 1e-9);
	}

	@Test
	void storeKeepsSchedulesAndSkipsBrokenLines(@TempDir Path dir) throws IOException {
		Path file = dir.resolve("cards/player.tsv");
		ReviewStore store = ReviewStore.load(file);
		assertEquals(Scheduler.Progress.NEW, store.get("0123456789abcdef"));
		var p = new Scheduler.Progress(3, 8, 2.35, 20_010, 1);
		store.put("0123456789abcdef", p);
		store.save();
		Files.writeString(file, Files.readString(file) + "garbage line\n0123\t1\t2\n");
		ReviewStore again = ReviewStore.load(file);
		assertEquals(1, again.size());
		assertEquals(p, again.get("0123456789abcdef"));
	}
}
