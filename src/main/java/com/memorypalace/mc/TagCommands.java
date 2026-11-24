package com.memorypalace.mc;

import com.memorypalace.core.markdown.Tags;
import com.memorypalace.core.search.NoteSearch;
import com.memorypalace.core.vault.NoteSummary;
import com.memorypalace.core.vault.Vault;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * /palace tags lists the #tags of the notes you can read, most used first (click one to list its notes);
 * /palace tag &lt;tag&gt; lists the notes with that tag or one nested under it (click a title to open it).
 * Encrypted notes (the server cannot read them) and templates are left out.
 */
final class TagCommands {
	private static final int MAX_TAGS = 100;
	private static final int MAX_NOTES = 50;

	private TagCommands() {}

	private static List<NoteSummary> readable(CommandContext<CommandSourceStack> ctx, Vault vault) {
		return vault.list().stream()
				.filter(n -> !n.encrypted() && !Tags.inTemplates(n.relativePath()) && CommandSupport.readable(ctx.getSource(), n))
				.toList();
	}

	static int tags(CommandContext<CommandSourceStack> ctx) {
		Vault vault = CommandSupport.requireVault(ctx);
		if (vault == null) {
			return 0;
		}
		Map<String, Integer> counts = new HashMap<>();
		for (NoteSummary note : readable(ctx, vault)) {
			for (String tag : NoteSearch.tagsOf(vault, note)) {
				counts.merge(tag, 1, Integer::sum);
			}
		}
		if (counts.isEmpty()) {
			ctx.getSource().sendSuccess(() -> Component.translatable("command.memorypalace.tags_none"), false);
			return 0;
		}
		List<Map.Entry<String, Integer>> sorted = new ArrayList<>(counts.entrySet());
		sorted.sort(Map.Entry.<String, Integer>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()));
		MutableComponent line = Component.empty();
		for (int i = 0; i < Math.min(MAX_TAGS, sorted.size()); i++) {
			String tag = sorted.get(i).getKey();
			if (i > 0) {
				line.append(Component.literal("  "));
			}
			line.append(Component.literal("#" + tag).withStyle(Style.EMPTY.withColor(ChatFormatting.AQUA)
							.withClickEvent(new ClickEvent.RunCommand("/palace tag " + tag))
							.withHoverEvent(new HoverEvent.ShowText(Component.translatable("command.memorypalace.tags_hover", tag)))))
					.append(Component.literal(" " + sorted.get(i).getValue()).withStyle(ChatFormatting.DARK_GRAY));
		}
		int total = sorted.size();
		ctx.getSource().sendSuccess(() -> Component.translatable("command.memorypalace.tags_header", total), false);
		ctx.getSource().sendSuccess(() -> line, false);
		return total;
	}

	static int tag(CommandContext<CommandSourceStack> ctx, String wanted) {
		Vault vault = CommandSupport.requireVault(ctx);
		if (vault == null) {
			return 0;
		}
		String tag = wanted.strip().startsWith("#") ? wanted.strip().substring(1) : wanted.strip();
		List<NoteSummary> notes = readable(ctx, vault).stream()
				.filter(n -> Tags.matches(NoteSearch.tagsOf(vault, n), tag))
				.sorted(Comparator.comparing(NoteSummary::relativePath))
				.toList();
		if (notes.isEmpty()) {
			ctx.getSource().sendFailure(Component.translatable("command.memorypalace.tag_none", tag));
			return 0;
		}
		ctx.getSource().sendSuccess(() -> Component.translatable("command.memorypalace.tag_header", tag, notes.size()), false);
		for (NoteSummary note : notes.subList(0, Math.min(MAX_NOTES, notes.size()))) {
			String command = "/palace open " + CommandSupport.noteCommandPath(note);
			ctx.getSource().sendSuccess(() -> Component.literal("• ").withStyle(ChatFormatting.DARK_GRAY)
					.append(Component.literal(note.title()).withStyle(Style.EMPTY.withColor(ChatFormatting.AQUA)
							.withClickEvent(new ClickEvent.RunCommand(command))
							.withHoverEvent(new HoverEvent.ShowText(Component.literal(note.relativePath())))))
					.append(Component.literal("  " + note.relativePath()).withStyle(ChatFormatting.DARK_GRAY)), false);
		}
		return notes.size();
	}
}
