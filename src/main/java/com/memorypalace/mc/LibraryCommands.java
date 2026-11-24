package com.memorypalace.mc;

import com.memorypalace.MemoryPalace;
import com.memorypalace.core.library.CourseCodes;
import com.memorypalace.core.library.LibraryPlan;
import com.memorypalace.core.vault.FolderLocks;
import com.memorypalace.core.vault.NoteLocations;
import com.memorypalace.core.vault.NoteSummary;
import com.memorypalace.core.vault.Vault;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Prediction;
import net.minecraft.world.level.block.entity.ChiseledBookShelfBlockEntity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Building a memory palace library for your classes:
 * - /palace library ENGG1101 ENGG1330 …: one room per class in front of you (asks first, see LibraryPlan)
 * - /palace shelve &lt;folder&gt;: put a folder's notes on the chiseled bookshelf you are looking at
 * - /palace index &lt;folder&gt;: a book listing a folder's notes and where they are
 */
final class LibraryCommands {
	private LibraryCommands() {}

	// the offer from /palace library: built exactly where and facing the way the player was when asked
	private record Offer(String dimension, BlockPos origin, Direction forward, List<String> codes, long expiresAt) {}

	private static final long OFFER_MS = 60_000;
	private static final Map<UUID, Offer> OFFERS = new ConcurrentHashMap<>();
	// shelve / index read the whole folder and scan nearby shelves
	private static final RateLimiter RATE = new RateLimiter(0.5, 4);
	// shelves this many chunks around count as "nearby" (covers a whole library)
	private static final int NEARBY_CHUNKS = 3;

	// ---- /palace library ----

	static int prepare(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ServerPlayer player = ctx.getSource().getPlayerOrException();
		if (CommandSupport.requireVault(ctx) == null) {
			return 0;
		}
		CourseCodes.Parsed parsed = CourseCodes.parse(StringArgumentType.getString(ctx, "classes"));
		if (!parsed.invalid().isEmpty()) {
			ctx.getSource().sendFailure(Component.translatable("command.memorypalace.library_invalid",
					String.join(", ", parsed.invalid())));
			return 0;
		}
		if (parsed.codes().size() > CourseCodes.MAX_ROOMS) {
			ctx.getSource().sendFailure(Component.translatable("command.memorypalace.library_too_many", CourseCodes.MAX_ROOMS));
			return 0;
		}
		LibraryPlan.Plan plan = LibraryPlan.of(parsed.codes());
		var target = new LibraryBuilder.Target(player.level(), player.blockPosition(), player.getDirection());
		LibraryBuilder.Check check = LibraryBuilder.check(target, plan);
		if (check.problem() != null) {
			ctx.getSource().sendFailure(check.problem());
			return 0;
		}
		OFFERS.put(player.getUUID(), new Offer(player.level().dimension().identifier().toString(), target.origin(),
				target.forward(), parsed.codes(), System.currentTimeMillis() + OFFER_MS));
		ctx.getSource().sendSuccess(() -> Component.translatable("command.memorypalace.library_confirm",
						parsed.codes().size(), plan.width(), plan.depth(), plan.height(), check.replaced())
				.append(" ").append(CommandSupport.link("command.memorypalace.library_yes",
						"command.memorypalace.library_yes_hover", "/palace libraryconfirm")), false);
		return 1;
	}

	static int confirm(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ServerPlayer player = ctx.getSource().getPlayerOrException();
		Vault vault = CommandSupport.requireVault(ctx);
		if (vault == null) {
			return 0;
		}
		Offer offer = OFFERS.remove(player.getUUID());
		ServerLevel level = offer == null ? null : LocationCommands.findLevel(ctx.getSource().getServer(), offer.dimension());
		if (offer == null || offer.expiresAt() < System.currentTimeMillis() || level == null) {
			ctx.getSource().sendFailure(Component.translatable("command.memorypalace.library_expired"));
			return 0;
		}
		LibraryPlan.Plan plan = LibraryPlan.of(offer.codes());
		var target = new LibraryBuilder.Target(level, offer.origin(), offer.forward());
		// the world may have changed since the question (someone put a chest there)
		LibraryBuilder.Check check = LibraryBuilder.check(target, plan);
		if (check.problem() != null) {
			ctx.getSource().sendFailure(check.problem());
			return 0;
		}
		LibraryBuilder.place(target, plan);

		var allPlaced = new ArrayList<Map.Entry<UUID, NoteLocations.Location>>();
		int books = 0;
		for (LibraryPlan.Room room : plan.rooms()) {
			LibraryBuilder.writeSign(level, target.at(room.sign()), room.code());
			String folder = CourseCodes.folder(room.code());
			ensureFolder(player, vault, folder, room.code());
			List<NoteSummary> notes = vault.listFolder(folder).stream().filter(n -> PalaceAccess.canRead(player, n)).toList();
			List<BlockPos> shelves = room.shelves().stream().map(target::at).toList();
			var placed = LibraryBuilder.fillShelves(level, shelves, notes.iterator());
			allPlaced.addAll(placed);
			books += placed.size();
			LibraryBuilder.putOnLectern(level, target.at(room.lectern()),
					RoomIndexBook.create(room.code(), entries(notes, placed)));
			if (notes.size() > placed.size()) {
				int left = notes.size() - placed.size();
				ctx.getSource().sendSuccess(() -> Component.translatable("command.memorypalace.library_overflow",
						room.code(), left).withStyle(ChatFormatting.GOLD), false);
			}
		}
		record(allPlaced);
		int total = books;
		ctx.getSource().sendSuccess(() -> Component.translatable("command.memorypalace.library_built",
				plan.rooms().size(), total), false);
		MemoryPalace.LOGGER.info("{} 建了一座图书馆：{}（{} 本书）", player.getName().getString(), offer.codes(), total);
		return 1;
	}

	/** Library/CODE exists afterwards; a new, empty room gets a short "start here" note so its shelf is not bare. */
	private static void ensureFolder(ServerPlayer player, Vault vault, String folder, String code) {
		try {
			if (!Files.isDirectory(vault.root().resolve(folder))) {
				vault.createFolder(folder);
			}
		} catch (IOException | IllegalArgumentException e) {
			MemoryPalace.LOGGER.warn("建图书馆文件夹失败：{}", folder, e);
			return;
		}
		if (!vault.listFolder(folder).isEmpty() || FolderLocks.lockedFolder(vault, folder).isPresent()) {
			return;
		}
		String body = "# " + code + "\n\n"
				+ HostLanguage.pick(
				"这里放 " + code + " 的笔记（文件夹 `" + folder + "`）。\n\n"
						+ "- 新建：`/palace new " + folder + "/主题`\n"
						+ "- 放上书架：看着书架输入 `/palace shelve " + folder + "`\n"
						+ "- 目录：`/palace index " + folder + "`\n\n"
						+ "公式照常写：$\\int_0^1 x^2\\,dx = \\frac{1}{3}$\n",
				"Your " + code + " notes live in the folder `" + folder + "`.\n\n"
						+ "- New note: `/palace new " + folder + "/Topic`\n"
						+ "- Put new notes on a shelf: look at it and type `/palace shelve " + folder + "`\n"
						+ "- Room index: `/palace index " + folder + "`\n\n"
						+ "Formulas work as usual: $\\int_0^1 x^2\\,dx = \\frac{1}{3}$\n");
		NoteCommands.createNote(player, folder + "/" + HostLanguage.pick(code + " 从这里开始", code + " start here"), body,
				false, problem -> MemoryPalace.LOGGER.warn("建图书馆起始笔记失败：{} {}", folder, problem.getString()));
	}

	private static List<RoomIndexBook.Entry> entries(List<NoteSummary> notes,
			List<Map.Entry<UUID, NoteLocations.Location>> placed) {
		var where = new java.util.HashMap<UUID, String>();
		for (var p : placed) {
			where.putIfAbsent(p.getKey(), describe(p.getValue()));
		}
		return notes.stream().map(n -> new RoomIndexBook.Entry(n, where.get(n.id()))).toList();
	}

	private static String describe(NoteLocations.Location loc) {
		String place = loc.slot() == NoteLocations.LECTERN_SLOT
				? HostLanguage.pick("讲台", "lectern") : HostLanguage.pick("第 " + (loc.slot() + 1) + " 格", "slot " + (loc.slot() + 1));
		return loc.x() + " " + loc.y() + " " + loc.z() + ", " + place;
	}

	private static void record(List<Map.Entry<UUID, NoteLocations.Location>> placed) {
		NoteLocations locations = PalaceServer.locations();
		if (locations == null || placed.isEmpty()) {
			return;
		}
		try {
			locations.recordAll(placed);
		} catch (IOException e) {
			MemoryPalace.LOGGER.warn("记录书架位置失败", e);
		}
	}

	// ---- /palace shelve ----

	static int shelve(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ServerPlayer player = ctx.getSource().getPlayerOrException();
		Vault vault = CommandSupport.requireVault(ctx);
		if (vault == null) {
			return 0;
		}
		if (!PalaceAccess.admin(player) && !RATE.tryTake(player.getUUID())) {
			ctx.getSource().sendFailure(Component.translatable("command.memorypalace.too_fast"));
			return 0;
		}
		ServerLevel level = player.level();
		HitResult hit = player.pick(player.blockInteractionRange(), 1.0F, false);
		if (!(hit instanceof BlockHitResult blockHit) || hit.getType() != HitResult.Type.BLOCK
				|| !(level.getBlockEntity(blockHit.getBlockPos()) instanceof ChiseledBookShelfBlockEntity)) {
			ctx.getSource().sendFailure(Component.translatable("command.memorypalace.shelve_look"));
			return 0;
		}
		BlockPos pos = blockHit.getBlockPos();
		// same rules as putting a book in by hand: not in adventure mode, not under spawn protection
		if (!player.mayBuild() || !level.mayInteract(player, pos)) {
			ctx.getSource().sendFailure(Component.translatable("command.memorypalace.shelve_denied"));
			return 0;
		}
		String input = StringArgumentType.getString(ctx, "folder").strip();
		String folder = CommandSupport.folderFromInput(vault, player, input);
		List<NoteSummary> notes = vault.listFolder(folder).stream().filter(n -> PalaceAccess.canRead(player, n)).toList();
		if (notes.isEmpty()) {
			ctx.getSource().sendFailure(Component.translatable("command.memorypalace.not_found", input));
			return 0;
		}
		Set<UUID> nearby = LibraryBuilder.notesNearby(level, pos, NEARBY_CHUNKS);
		List<NoteSummary> todo = notes.stream().filter(n -> !nearby.contains(n.id())).toList();
		if (todo.isEmpty()) {
			ctx.getSource().sendSuccess(() -> Component.translatable("command.memorypalace.shelve_nothing", folder), false);
			return 0;
		}
		var placed = LibraryBuilder.fillShelves(level, List.of(pos), todo.iterator());
		if (placed.isEmpty()) {
			ctx.getSource().sendFailure(Component.translatable("command.memorypalace.shelve_full"));
			return 0;
		}
		record(placed);
		int count = placed.size();
		int left = todo.size() - count;
		ctx.getSource().sendSuccess(() -> Component.translatable("command.memorypalace.shelve_done", count, left, folder), false);
		return count;
	}

	// ---- /palace index ----

	static int index(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ServerPlayer player = ctx.getSource().getPlayerOrException();
		Vault vault = CommandSupport.requireVault(ctx);
		if (vault == null) {
			return 0;
		}
		if (!PalaceAccess.admin(player) && !RATE.tryTake(player.getUUID())) {
			ctx.getSource().sendFailure(Component.translatable("command.memorypalace.too_fast"));
			return 0;
		}
		String input = StringArgumentType.getString(ctx, "folder").strip();
		String folder = CommandSupport.folderFromInput(vault, player, input);
		List<NoteSummary> notes = vault.listFolder(folder).stream().filter(n -> PalaceAccess.canRead(player, n)).toList();
		if (notes.isEmpty()) {
			ctx.getSource().sendFailure(Component.translatable("command.memorypalace.not_found", input));
			return 0;
		}
		NoteLocations locations = PalaceServer.locations();
		var entries = new ArrayList<RoomIndexBook.Entry>();
		for (NoteSummary note : notes) {
			entries.add(new RoomIndexBook.Entry(note, locations == null ? null : currentPlace(player, locations, note.id())));
		}
		String name = folder.contains("/") ? folder.substring(folder.lastIndexOf('/') + 1) : folder;
		player.getInventory().placeItemBackInInventory(RoomIndexBook.create(name, entries), Prediction.SERVER_ONLY);
		int count = notes.size();
		ctx.getSource().sendSuccess(() -> Component.translatable("command.memorypalace.index_given", folder, count), false);
		return 1;
	}

	/** The most recent recorded place that still holds this note (checked like /palace where does). */
	private static String currentPlace(ServerPlayer player, NoteLocations locations, UUID id) {
		for (NoteLocations.Location loc : locations.get(id)) {
			ServerLevel level = LocationCommands.findLevel(player.level().getServer(), loc.dimension());
			BlockPos pos = new BlockPos(loc.x(), loc.y(), loc.z());
			if (level != null && level.isLoaded(pos) && LocationCommands.stillThere(level, pos, loc.slot(), id)) {
				return describe(loc);
			}
		}
		return null;
	}
}
