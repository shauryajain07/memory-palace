package com.memorypalace.mc;

import com.memorypalace.core.library.LibraryPlan;
import com.memorypalace.core.library.LibraryPlan.Facing;
import com.memorypalace.core.library.LibraryPlan.Placement;
import com.memorypalace.core.vault.NoteLocations;
import com.memorypalace.core.vault.NoteSummary;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.LanternBlock;
import net.minecraft.world.level.block.LecternBlock;
import net.minecraft.world.level.block.WallSignBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChiseledBookShelfBlockEntity;
import net.minecraft.world.level.block.entity.LecternBlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.entity.SignTextSlot;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Puts a LibraryPlan (core/, pure layout) into the world, and fills bookshelves with note books.
 * Everything here runs on the server thread.
 */
final class LibraryBuilder {
	private LibraryBuilder() {}

	/** Where the plan goes: origin = the player's block, forward = the way the player faces. */
	record Target(ServerLevel level, BlockPos origin, Direction forward) {
		BlockPos at(LibraryPlan.Pos p) {
			return origin.relative(forward.getClockWise(), p.x()).above(p.y()).relative(forward, p.z());
		}

		Direction direction(Facing facing) {
			return switch (facing) {
				case FORWARD, NONE -> forward;
				case BACK -> forward.getOpposite();
				case LEFT -> forward.getCounterClockWise();
				case RIGHT -> forward.getClockWise();
			};
		}
	}

	/** problem is null when the plan can be built; replaced = how many non-air blocks it would overwrite. */
	record Check(Component problem, int replaced) {}

	/**
	 * Looks at the whole area before anything changes. Refuses when part of it is outside the world, not loaded,
	 * or holds a block entity (a chest, furnace, sign, bookshelf with books …): those would be destroyed.
	 */
	static Check check(Target t, LibraryPlan.Plan plan) {
		int replaced = 0;
		for (Placement p : plan.placements()) {
			BlockPos pos = t.at(p.pos());
			if (t.level().isOutsideBuildHeight(pos) || !t.level().isLoaded(pos) || !t.level().getWorldBorder().isWithinBounds(pos)) {
				return new Check(Component.translatable("command.memorypalace.library_unloaded"), 0);
			}
			BlockEntity entity = t.level().getBlockEntity(pos);
			if (entity != null) {
				return new Check(Component.translatable("command.memorypalace.library_blocked",
						entity.getBlockState().getBlock().getName(), pos.getX() + " " + pos.getY() + " " + pos.getZ()), 0);
			}
			if (!t.level().getBlockState(pos).isAir()) {
				replaced++;
			}
		}
		return new Check(null, replaced);
	}

	/** Solid blocks first, then signs and lanterns, which need a wall or ceiling to hang on. */
	static void place(Target t, LibraryPlan.Plan plan) {
		for (boolean attachedPass : new boolean[]{false, true}) {
			for (Placement p : plan.placements()) {
				if (p.kind().attached() == attachedPass) {
					// no neighbour updates: nothing around the build pops off or reacts while it goes up
					t.level().setBlock(t.at(p.pos()), state(p, t), Block.UPDATE_CLIENTS);
				}
			}
		}
	}

	private static BlockState state(Placement p, Target t) {
		Direction dir = t.direction(p.facing());
		return switch (p.kind()) {
			case AIR -> Blocks.AIR.defaultBlockState();
			case FLOOR -> Blocks.SPRUCE_PLANKS.defaultBlockState();
			case HALL_FLOOR -> Blocks.DARK_OAK_PLANKS.defaultBlockState();
			case WALL -> Blocks.STONE_BRICKS.defaultBlockState();
			case CEILING -> Blocks.SPRUCE_PLANKS.defaultBlockState();
			case SHELF -> Blocks.CHISELED_BOOKSHELF.defaultBlockState().setValue(HorizontalDirectionalBlock.FACING, dir);
			case LECTERN -> Blocks.LECTERN.defaultBlockState().setValue(LecternBlock.FACING, dir);
			case SIGN -> Blocks.SPRUCE_WALL_SIGN.defaultBlockState().setValue(WallSignBlock.FACING, dir);
			case LANTERN -> Blocks.LANTERN.defaultBlockState().setValue(LanternBlock.HANGING, true);
		};
	}

	/** The room's sign: its class code in bold, waxed so a stray right-click does not open the sign editor. */
	static void writeSign(ServerLevel level, BlockPos pos, String code) {
		if (!(level.getBlockEntity(pos) instanceof SignBlockEntity sign)) {
			return;
		}
		List<Component> lines = List.of(Component.empty(), Component.literal(code).withStyle(ChatFormatting.BOLD),
				Component.literal(HostLanguage.pick("笔记", "notes")), Component.empty());
		sign.setText(new SignText(lines, lines, DyeColor.BLACK, false), SignTextSlot.FRONT);
		sign.setWaxed(true);
		sign.setChanged();
		BlockState state = level.getBlockState(pos);
		level.sendBlockUpdated(pos, state, state, Block.UPDATE_CLIENTS);
	}

	/** Puts a book on the lectern at pos (replacing nothing: the lectern is new). */
	static void putOnLectern(ServerLevel level, BlockPos pos, ItemStack book) {
		if (!(level.getBlockEntity(pos) instanceof LecternBlockEntity lectern)) {
			return;
		}
		lectern.setBook(book);
		BlockState state = level.getBlockState(pos);
		if (state.hasProperty(LecternBlock.HAS_BOOK)) {
			level.setBlock(pos, state.setValue(LecternBlock.HAS_BOOK, true), Block.UPDATE_CLIENTS);
		}
		lectern.setChanged();
	}

	/**
	 * Fills the empty slots of these shelves, in order, with books for the notes (consumed from the iterator).
	 * Returns the placements to record for /palace where.
	 */
	static List<Map.Entry<UUID, NoteLocations.Location>> fillShelves(ServerLevel level, List<BlockPos> shelves,
			Iterator<NoteSummary> notes) {
		var placed = new ArrayList<Map.Entry<UUID, NoteLocations.Location>>();
		String dimension = level.dimension().identifier().toString();
		long now = System.currentTimeMillis();
		for (BlockPos pos : shelves) {
			if (!notes.hasNext()) {
				break;
			}
			if (!(level.getBlockEntity(pos) instanceof ChiseledBookShelfBlockEntity shelf)) {
				continue;
			}
			boolean changed = false;
			for (int slot = 0; slot < LibraryPlan.BOOKS_PER_SHELF && notes.hasNext(); slot++) {
				if (!shelf.getItem(slot).isEmpty()) {
					continue;
				}
				NoteSummary note = notes.next();
				shelf.setItem(slot, NoteBooks.create(note));
				placed.add(Map.entry(note.id(),
						new NoteLocations.Location(dimension, pos.getX(), pos.getY(), pos.getZ(), slot, now)));
				changed = true;
			}
			if (changed) {
				shelf.setChanged();
			}
		}
		return placed;
	}

	/**
	 * Note ids already sitting on shelves or lecterns in the loaded chunks around pos (radius in chunks).
	 * Looks at what is really there, not at the location log (that only knows books someone has opened).
	 */
	static Set<UUID> notesNearby(ServerLevel level, BlockPos pos, int radius) {
		var ids = new HashSet<UUID>();
		int cx = pos.getX() >> 4;
		int cz = pos.getZ() >> 4;
		for (int x = cx - radius; x <= cx + radius; x++) {
			for (int z = cz - radius; z <= cz + radius; z++) {
				LevelChunk chunk = level.getChunkSource().getChunkNow(x, z);
				if (chunk == null) {
					continue;
				}
				for (BlockEntity entity : chunk.getBlockEntities().values()) {
					if (entity instanceof ChiseledBookShelfBlockEntity shelf) {
						for (int slot = 0; slot < LibraryPlan.BOOKS_PER_SHELF; slot++) {
							NoteBooks.idOf(shelf.getItem(slot)).ifPresent(ids::add);
						}
					} else if (entity instanceof LecternBlockEntity lectern) {
						NoteBooks.idOf(lectern.getBook()).ifPresent(ids::add);
					}
				}
			}
		}
		return ids;
	}
}
