package com.memorypalace.core.cards;

/**
 * When to see a card again: the SM-2 method (as in Anki, simplified). Each card has a step count, an interval in
 * days and an ease factor; answering "Good" multiplies the interval by the ease, "Again" starts over and makes the
 * card a little harder. Days are counted as epoch days (LocalDate.toEpochDay), given by the caller.
 * Note: core/ must not import net.minecraft / net.fabricmc.
 */
public final class Scheduler {
	private Scheduler() {}

	public enum Grade { AGAIN, HARD, GOOD, EASY }

	/** due: the epoch day the card is shown again; a new card (reps 0, due 0) is due at once. */
	public record Progress(int reps, int interval, double ease, long due, int lapses) {
		public static final Progress NEW = new Progress(0, 0, 2.5, 0, 0);

		public boolean isNew() {
			return reps == 0 && due == 0 && lapses == 0;
		}
	}

	public static final double MIN_EASE = 1.3;
	public static final int MAX_INTERVAL = 3650;

	public static Progress next(Progress p, Grade grade, long today) {
		int interval;
		double ease = p.ease();
		switch (grade) {
			case AGAIN -> {
				return new Progress(0, 0, Math.max(MIN_EASE, ease - 0.2), today, p.lapses() + 1);
			}
			case HARD -> {
				interval = p.reps() == 0 ? 1 : Math.max(1, (int) Math.round(p.interval() * 1.2));
				ease = Math.max(MIN_EASE, ease - 0.15);
			}
			case GOOD -> interval = p.reps() == 0 ? 1 : p.reps() == 1 ? 3
					: Math.max(p.interval() + 1, (int) Math.round(p.interval() * ease));
			case EASY -> {
				interval = p.reps() == 0 ? 4 : Math.max(p.interval() + 1, (int) Math.round(p.interval() * ease * 1.3));
				ease += 0.15;
			}
			default -> throw new IllegalArgumentException(grade.name());
		}
		interval = Math.min(MAX_INTERVAL, interval);
		return new Progress(p.reps() + 1, interval, ease, today + interval, p.lapses());
	}

	public static boolean isDue(Progress p, long today) {
		return p.due() <= today;
	}
}
