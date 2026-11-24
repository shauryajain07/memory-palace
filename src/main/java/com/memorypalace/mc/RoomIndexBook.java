package com.memorypalace.mc;

import com.memorypalace.core.export.VanillaBook;
import com.memorypalace.core.vault.NoteSummary;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.server.network.Filterable;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.WrittenBookContent;

import java.util.ArrayList;
import java.util.List;

/**
 * A room's index: an ordinary written book listing the room's notes with where each one sits.
 * Click a title to open the note (/palace open). It is a plain book on purpose, so it reads in the vanilla lectern
 * screen; it is a snapshot, /palace index gives an up-to-date one.
 */
final class RoomIndexBook {
	private RoomIndexBook() {}

	/** @param where "12 64 -30, slot 3" or null when the note is not on a shelf nearby */
	record Entry(NoteSummary note, String where) {}

	private static final int LINES_PER_PAGE = 14;
	// "• " in front of each title takes 14 of the page's 114 pixels
	private static final int TITLE_WIDTH = 96;
	private static final int MAX_TITLE = 32;

	static ItemStack create(String roomName, List<Entry> entries) {
		var pages = new ArrayList<Filterable<Component>>();
		MutableComponent page = Component.empty()
				.append(Component.literal(VanillaBook.fit(roomName, 110, true)).withStyle(ChatFormatting.BOLD))
				.append("\n")
				.append(Component.literal(HostLanguage.pick(entries.size() + " 篇笔记", entries.size() + " notes"))
						.withStyle(ChatFormatting.DARK_GRAY))
				.append("\n\n");
		int lines = 3;
		for (Entry entry : entries) {
			if (lines + 2 > LINES_PER_PAGE) {
				pages.add(Filterable.passThrough(page));
				page = Component.empty();
				lines = 0;
			}
			String title = VanillaBook.fit(entry.note().title(), TITLE_WIDTH, false);
			String command = "/palace open " + CommandSupport.noteCommandPath(entry.note());
			page.append(Component.literal("• ").withStyle(ChatFormatting.DARK_GRAY))
					.append(Component.literal(title).withStyle(Style.EMPTY.withColor(ChatFormatting.DARK_BLUE)
							.withClickEvent(new ClickEvent.RunCommand(command))
							.withHoverEvent(new HoverEvent.ShowText(Component.literal(entry.note().relativePath())))))
					.append("\n")
					.append(Component.literal("  " + (entry.where() == null
							? HostLanguage.pick("（不在附近的书架上）", "(not on a shelf nearby)") : entry.where()))
							.withStyle(ChatFormatting.DARK_GRAY))
					.append("\n");
			lines += 2;
			if (pages.size() == VanillaBook.MAX_PAGES - 1 && lines + 2 > LINES_PER_PAGE) {
				break; // a written book holds 100 pages; the rest are still in /palace list
			}
		}
		pages.add(Filterable.passThrough(page));
		String title = HostLanguage.pick(roomName + " 目录", roomName + " index");
		title = title.length() > MAX_TITLE ? title.substring(0, MAX_TITLE - 1) + "…" : title;
		ItemStack stack = new ItemStack(Items.WRITTEN_BOOK);
		stack.set(DataComponents.WRITTEN_BOOK_CONTENT, new WrittenBookContent(
				Filterable.passThrough(title), "Memory Palace", 0, List.copyOf(pages), true));
		return stack;
	}
}
