package com.memorypalace.core.cards;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * One player's flashcard schedule, in a small text file (one card per line: id, reps, interval, ease, due,
 * lapses, separated by tabs). Lines that cannot be read are skipped. Saved by writing a new file and renaming it.
 * Note: core/ must not import net.minecraft / net.fabricmc.
 */
public final class ReviewStore {
	private final Path file;
	private final Map<String, Scheduler.Progress> cards = new HashMap<>();

	private ReviewStore(Path file) {
		this.file = file;
	}

	public static ReviewStore load(Path file) throws IOException {
		var store = new ReviewStore(file);
		if (Files.exists(file)) {
			for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
				String[] f = line.split("\t");
				if (f.length != 6 || !f[0].matches("[0-9a-f]{16}")) {
					continue;
				}
				try {
					store.cards.put(f[0], new Scheduler.Progress(Integer.parseInt(f[1]), Integer.parseInt(f[2]),
							Double.parseDouble(f[3]), Long.parseLong(f[4]), Integer.parseInt(f[5])));
				} catch (NumberFormatException ignored) {
					// a broken line: forget that card's schedule
				}
			}
		}
		return store;
	}

	public Scheduler.Progress get(String id) {
		return cards.getOrDefault(id, Scheduler.Progress.NEW);
	}

	public void put(String id, Scheduler.Progress progress) {
		cards.put(id, progress);
	}

	public int size() {
		return cards.size();
	}

	public void save() throws IOException {
		Files.createDirectories(file.getParent());
		var out = new StringBuilder();
		cards.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e -> {
			Scheduler.Progress p = e.getValue();
			out.append(e.getKey()).append('\t').append(p.reps()).append('\t').append(p.interval()).append('\t')
					.append(String.format(Locale.ROOT, "%.3f", p.ease())).append('\t').append(p.due()).append('\t')
					.append(p.lapses()).append('\n');
		});
		Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
		Files.writeString(temporary, out, StandardCharsets.UTF_8);
		try {
			Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
		} catch (AtomicMoveNotSupportedException e) {
			Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
		}
	}
}
