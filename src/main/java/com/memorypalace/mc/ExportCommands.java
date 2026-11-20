package com.memorypalace.mc;

import com.memorypalace.MemoryPalace;
import com.memorypalace.core.export.VanillaBook;
import com.memorypalace.core.vault.NoteSummary;
import com.memorypalace.core.vault.Vault;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.Filterable;
import net.minecraft.util.Prediction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.WrittenBookContent;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

/**
 * /palace export &lt;note&gt;: copies a note into an ordinary written book that everyone can read, also players
 * without the mod (LaTeX shown as Unicode, see VanillaBook / TexUnicode).
 *
 * The book is a snapshot, not a note book: it has no memorypalace tag, so right-clicking it opens the vanilla
 * reader and later edits to the note do not change it. Same rules as reading: you can only export notes you can
 * read. Encrypted notes are refused, because the server cannot read them.
 */
final class ExportCommands {
	private ExportCommands() {}

	// each export reads and lays out a whole note: once every 3 seconds, up to 3 in a row
	private static final RateLimiter EXPORT_RATE = new RateLimiter(1.0 / 3, 3);
	// vanilla written book titles are at most 32 characters
	private static final int MAX_TITLE = 32;

	static int export(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		Vault vault = CommandSupport.requireVault(ctx);
		if (vault == null) {
			return 0;
		}
		ServerPlayer player = ctx.getSource().getPlayerOrException();
		String path = StringArgumentType.getString(ctx, "note").strip();
		if (!PalaceAccess.admin(player) && !EXPORT_RATE.tryTake(player.getUUID())) {
			ctx.getSource().sendFailure(Component.translatable("command.memorypalace.too_fast"));
			return 0;
		}
		Optional<NoteSummary> found = CommandSupport.resolveNote(vault, player, path)
				.filter(n -> CommandSupport.readable(ctx.getSource(), n));
		if (found.isEmpty()) {
			ctx.getSource().sendFailure(Component.translatable("command.memorypalace.not_found", path));
			return 0;
		}
		Optional<Vault.Read> read;
		try {
			read = vault.readNote(found.get().id());
		} catch (IOException e) {
			MemoryPalace.LOGGER.error("导出笔记时读取失败", e);
			ctx.getSource().sendFailure(Component.translatable("message.memorypalace.read_failed"));
			return 0;
		}
		// check again with the fresh card: sharing or encryption may have changed on disk since the scan
		if (read.isEmpty() || !CommandSupport.readable(ctx.getSource(), read.get().note())) {
			ctx.getSource().sendFailure(Component.translatable("command.memorypalace.not_found", path));
			return 0;
		}
		NoteSummary note = read.get().note();
		if (note.encrypted()) {
			ctx.getSource().sendFailure(Component.translatable("command.memorypalace.export_encrypted"));
			return 0;
		}

		VanillaBook.Book book = VanillaBook.fromMarkdown(read.get().body(), HostLanguage.pick("图片", "Image"));
		List<Filterable<Component>> pages = book.pages().stream()
				.map(page -> Filterable.passThrough((Component) toComponent(page))).toList();
		String title = note.title().length() > MAX_TITLE ? note.title().substring(0, MAX_TITLE - 1) + "…" : note.title();
		ItemStack stack = new ItemStack(Items.WRITTEN_BOOK);
		stack.set(DataComponents.WRITTEN_BOOK_CONTENT, new WrittenBookContent(
				Filterable.passThrough(title), player.getName().getString(), 0, pages, true));
		// inventory full: it drops at the player's feet
		player.getInventory().placeItemBackInInventory(stack, Prediction.SERVER_ONLY);

		ctx.getSource().sendSuccess(() -> Component.translatable("command.memorypalace.exported", note.title(), pages.size()), false);
		if (book.truncated()) {
			ctx.getSource().sendSuccess(() -> Component.translatable("command.memorypalace.export_truncated",
					VanillaBook.MAX_PAGES).withStyle(ChatFormatting.GOLD), false);
		}
		MemoryPalace.LOGGER.info("{} 导出了笔记 {}（{} 页）", player.getName().getString(), note.relativePath(), pages.size());
		return 1;
	}

	/** One page of runs → a text component; colours chosen to read well on book paper. */
	static MutableComponent toComponent(List<VanillaBook.Run> page) {
		MutableComponent out = Component.empty();
		for (VanillaBook.Run run : page) {
			MutableComponent part = Component.literal(run.text());
			switch (run.kind()) {
				case BOLD -> part.withStyle(ChatFormatting.BOLD);
				case ITALIC -> part.withStyle(ChatFormatting.ITALIC);
				case MATH -> part.withStyle(ChatFormatting.DARK_BLUE);
				case CODE -> part.withStyle(ChatFormatting.DARK_GREEN);
				case QUOTE -> part.withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.ITALIC);
				case MUTED -> part.withStyle(ChatFormatting.DARK_GRAY);
				case TEXT -> {
				}
			}
			out.append(part);
		}
		return out;
	}
}
