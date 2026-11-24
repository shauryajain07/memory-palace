package com.memorypalace.core.editor;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Note templates for the editor's /template entries. A template is an ordinary note in a "Templates" folder: one at
 * the top of the vault for every note, or one inside a class folder ("Library/ENGG1101/Templates/Lab.md") for that
 * class only; the nearest one with a given name wins. Placeholders are filled in when the template is inserted:
 * {{date}}, {{time}}, {{title}} (the note's title), {{class}} (the class code for notes under Library/&lt;CODE&gt;/,
 * otherwise the folder's name) and {{folder}}. Unknown placeholders stay as they are.
 * Note: core/ must not import net.minecraft / net.fabricmc.
 */
public final class NoteTemplates {
	private NoteTemplates() {}

	public static final String FOLDER = "Templates";
	public static final int MAX_TEMPLATES = 20;
	public static final int MAX_CHARS = 20_000;

	private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*([A-Za-z]+)\\s*}}");

	/** body with every known {{key}} (any case) replaced by values.get(key in lower case). */
	public static String fill(String body, Map<String, String> values) {
		Matcher m = PLACEHOLDER.matcher(body);
		var out = new StringBuilder();
		while (m.find()) {
			String value = values.get(m.group(1).toLowerCase(Locale.ROOT));
			m.appendReplacement(out, Matcher.quoteReplacement(value != null ? value : m.group()));
		}
		m.appendTail(out);
		return out.toString();
	}

	/**
	 * The Templates folders to look in for a note in folder, nearest first: folder/Templates, then each parent's
	 * Templates, ending with the top-level Templates.
	 */
	public static List<String> searchFolders(String folder) {
		var out = new ArrayList<String>();
		String f = folder == null ? "" : folder.strip();
		while (true) {
			out.add(f.isEmpty() ? FOLDER : f + "/" + FOLDER);
			if (f.isEmpty()) {
				return out;
			}
			int slash = f.lastIndexOf('/');
			f = slash < 0 ? "" : f.substring(0, slash);
		}
	}

	/** "Library/ENGG1101/Week 3" → "ENGG1101"; any other folder → its last part; the top of the vault → "". */
	public static String className(String folder) {
		if (folder == null || folder.isEmpty()) {
			return "";
		}
		String[] parts = folder.split("/");
		if (parts.length >= 2 && parts[0].equals("Library")) {
			return parts[1];
		}
		return parts[parts.length - 1];
	}
}
