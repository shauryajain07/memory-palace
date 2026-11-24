package com.memorypalace.client.book;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.memorypalace.MemoryPalace;
import net.fabricmc.fabric.api.resource.v1.ResourceLoader;
import net.minecraft.client.gui.screens.inventory.BookViewScreen;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;

import java.io.Reader;
import java.util.Optional;

/**
 * How note books look: the page and the colours of text, code, links, formulas and the editor's pop-up menus.
 * Read from {@code assets/memorypalace/book_theme.json}, so a resource pack can restyle the books by shipping its own
 * copy (the mod's built-in "Clean white" pack does). Every field is optional; anything missing or invalid keeps
 * the default (the vanilla parchment book), and a broken file never stops the game.
 * <pre>
 * {
 *   "page": { "style": "texture", "texture": "minecraft:textures/gui/book.png", "texture_size": [256, 256],
 *             "u": [20, 36, 148, 166], "v": [1, 28, 88, 148, 182] },
 *   // or  "page": { "style": "flat", "paper": "#FFFFFF", "border": "#E3E2E0", "shadow": "#22000000", "spine": "#EDECE9" }
 *   "colors": { "text": "#000000", "heading": "#3B2410", ... }   // #RRGGBB or #AARRGGBB
 * }
 * </pre>
 * For a texture page, u gives the columns (spine edge start, paper start, paper end, outer edge end) and v the rows
 * (top, middle start, middle end, bottom start, bottom end) of a 3×3 grid; the middle is repeated to fill big pages.
 */
public record BookTheme(Page page, int text, int heading, int muted, int code, int codeBackground, int math, int link,
		int brokenLink, int error, int quoteBar, int rule, int menuBackground, int menuBorder, int menuSelected,
		int menuText, int menuHint) {

	public sealed interface Page permits Textured, Flat {}

	public record Textured(Identifier texture, int textureWidth, int textureHeight, int[] u, int[] v) implements Page {}

	/** A plain sheet: paper colour, a one-pixel border, a soft shadow under it and a line down the spine. */
	public record Flat(int paper, int border, int shadow, int spine) implements Page {}

	public static final Identifier FILE = Identifier.fromNamespaceAndPath("memorypalace", "book_theme.json");

	public static final BookTheme DEFAULT = new BookTheme(
			new Textured(BookViewScreen.BOOK_LOCATION, 256, 256, new int[] {20, 36, 148, 166}, new int[] {1, 28, 88, 148, 182}),
			0xFF000000, 0xFF3B2410, 0xFF5A4A3A, 0xFF1F4A2A, 0x1A3A2A10, 0xFF1A3A7A, 0xFF1A3AA0, 0xFF9A3A2A, 0xFFA02020,
			0x55704A2A, 0x66704A2A, 0xFFF4E8CC, 0xFF704A2A, 0x40704A2A, 0xFF000000, 0xFF5A4A3A);

	private static volatile BookTheme current = DEFAULT;

	public static BookTheme get() {
		return current;
	}

	/** For tests: use this theme until the next resource reload. */
	public static void set(BookTheme theme) {
		current = theme == null ? DEFAULT : theme;
	}

	/** Reads the theme again whenever resource packs are (re)loaded. */
	public static void register() {
		ResourceLoader.get(PackType.CLIENT_RESOURCES).registerReloadListener(
				Identifier.fromNamespaceAndPath("memorypalace", "book_theme"),
				(ResourceManagerReloadListener) manager -> current = load(manager));
	}

	private static BookTheme load(ResourceManager manager) {
		Optional<Resource> resource = manager.getResource(FILE);
		if (resource.isEmpty()) {
			return DEFAULT;
		}
		try (Reader reader = resource.get().openAsReader()) {
			return parse(JsonParser.parseReader(reader).getAsJsonObject());
		} catch (Exception e) {
			MemoryPalace.LOGGER.warn("Ignoring {} from pack {}: {}", FILE, resource.get().sourcePackId(), e.toString());
			return DEFAULT;
		}
	}

	/** The theme in json over the default; bad values are skipped (and logged). */
	public static BookTheme parse(JsonObject json) {
		JsonObject c = json.has("colors") && json.get("colors").isJsonObject() ? json.getAsJsonObject("colors") : new JsonObject();
		BookTheme d = DEFAULT;
		return new BookTheme(page(json.get("page")),
				color(c, "text", d.text), color(c, "heading", d.heading), color(c, "muted", d.muted),
				color(c, "code", d.code), color(c, "code_background", d.codeBackground), color(c, "math", d.math),
				color(c, "link", d.link), color(c, "broken_link", d.brokenLink), color(c, "error", d.error),
				color(c, "quote_bar", d.quoteBar), color(c, "rule", d.rule),
				color(c, "menu_background", d.menuBackground), color(c, "menu_border", d.menuBorder),
				color(c, "menu_selected", d.menuSelected), color(c, "menu_text", d.menuText),
				color(c, "menu_hint", d.menuHint));
	}

	private static Page page(JsonElement element) {
		if (element == null || !element.isJsonObject()) {
			return DEFAULT.page;
		}
		JsonObject p = element.getAsJsonObject();
		String style = p.has("style") ? p.get("style").getAsString() : "texture";
		if (style.equals("flat")) {
			return new Flat(color(p, "paper", 0xFFFFFFFF), color(p, "border", 0xFFE3E2E0), color(p, "shadow", 0x22000000),
					color(p, "spine", 0xFFEDECE9));
		}
		try {
			Textured d = (Textured) DEFAULT.page;
			Identifier texture = p.has("texture") ? Identifier.parse(p.get("texture").getAsString()) : d.texture();
			int[] size = ints(p, "texture_size", new int[] {d.textureWidth(), d.textureHeight()});
			int[] u = ints(p, "u", d.u());
			int[] v = ints(p, "v", d.v());
			if (size.length != 2 || size[0] <= 0 || size[1] <= 0 || !increasing(u, 4, size[0]) || !increasing(v, 5, size[1])) {
				throw new IllegalArgumentException("page: u needs 4 and v 5 increasing numbers inside texture_size");
			}
			return new Textured(texture, size[0], size[1], u, v);
		} catch (RuntimeException e) {
			MemoryPalace.LOGGER.warn("Ignoring the page of {}: {}", FILE, e.getMessage());
			return DEFAULT.page;
		}
	}

	private static int[] ints(JsonObject o, String key, int[] fallback) {
		if (!o.has(key)) {
			return fallback;
		}
		var array = o.getAsJsonArray(key);
		int[] out = new int[array.size()];
		for (int i = 0; i < out.length; i++) {
			out[i] = array.get(i).getAsInt();
		}
		return out;
	}

	private static boolean increasing(int[] values, int count, int max) {
		if (values.length != count || values[0] < 0 || values[count - 1] > max) {
			return false;
		}
		for (int i = 1; i < count; i++) {
			if (values[i] <= values[i - 1]) {
				return false;
			}
		}
		return true;
	}

	/** "#RRGGBB" (opaque) or "#AARRGGBB"; fallback when missing or not a colour. */
	static int color(JsonObject o, String key, int fallback) {
		if (!o.has(key)) {
			return fallback;
		}
		try {
			String s = o.get(key).getAsString().trim();
			if (s.startsWith("#")) {
				s = s.substring(1);
			}
			if (s.length() == 6) {
				return 0xFF000000 | Integer.parseUnsignedInt(s, 16);
			}
			if (s.length() == 8) {
				return (int) Long.parseLong(s, 16);
			}
		} catch (RuntimeException ignored) {
			// fall through to the warning
		}
		MemoryPalace.LOGGER.warn("Ignoring {} in {}: not a colour like #RRGGBB", key, FILE);
		return fallback;
	}
}
