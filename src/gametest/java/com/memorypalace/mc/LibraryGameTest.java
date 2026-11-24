package com.memorypalace.mc;

import com.memorypalace.core.library.LibraryPlan;
import com.memorypalace.core.vault.NoteAccess;
import com.memorypalace.core.vault.NoteSummary;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChiseledBookShelfBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.LanternBlock;
import net.minecraft.world.level.block.LecternBlock;
import net.minecraft.world.level.block.entity.ChiseledBookShelfBlockEntity;
import net.minecraft.world.level.block.entity.LecternBlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignTextSlot;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Builds a two-room library in a real (headless) server world and checks what ended up there.
 * Same package as LibraryBuilder so it can use the package-private builder directly.
 */
public class LibraryGameTest {
	@GameTest(maxTicks = 200)
	public void buildsALibraryRoomPerClass(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		BlockPos origin = helper.absolutePos(new BlockPos(0, 1, 0));
		// the build is wider than the test structure: keep the chunks around it loaded
		for (int dx = -2; dx <= 2; dx++) {
			for (int dz = -2; dz <= 2; dz++) {
				level.setChunkForced((origin.getX() >> 4) + dx, (origin.getZ() >> 4) + dz, true);
			}
		}
		var target = new LibraryBuilder.Target(level, origin, Direction.NORTH);
		var plan = LibraryPlan.of(List.of("ENGG1101", "MATH1851"));
		var check = LibraryBuilder.check(target, plan);
		helper.assertTrue(check.problem() == null, "area should be buildable: " + check.problem());

		LibraryBuilder.place(target, plan);
		for (LibraryPlan.Room room : plan.rooms()) {
			LibraryBuilder.writeSign(level, target.at(room.sign()), room.code());
		}

		LibraryPlan.Room left = plan.rooms().get(0);
		LibraryPlan.Room right = plan.rooms().get(1);
		// facing NORTH, "left" is WEST: the left room's shelves face back towards the hall (EAST)
		for (LibraryPlan.Room room : plan.rooms()) {
			Direction expected = room.rightSide() ? Direction.WEST : Direction.EAST;
			for (LibraryPlan.Pos p : room.shelves()) {
				BlockState state = level.getBlockState(target.at(p));
				helper.assertTrue(state.is(Blocks.CHISELED_BOOKSHELF), "shelf at " + target.at(p) + " is " + state);
				helper.assertTrue(state.getValue(HorizontalDirectionalBlock.FACING) == expected,
						room.code() + " shelf faces " + state.getValue(HorizontalDirectionalBlock.FACING));
			}
			BlockState lectern = level.getBlockState(target.at(room.lectern()));
			helper.assertTrue(lectern.is(Blocks.LECTERN) && lectern.getValue(LecternBlock.FACING) == expected,
					"lectern of " + room.code() + " is " + lectern);
		}

		// 8 notes on the left room's shelves: the first shelf takes 6, the next one 2
		var notes = new ArrayList<NoteSummary>();
		for (int i = 0; i < 8; i++) {
			notes.add(new NoteSummary(UUID.randomUUID(), "Library/ENGG1101/n" + i + ".md", "Note " + i, false, NoteAccess.Rules.NONE));
		}
		List<BlockPos> shelves = left.shelves().stream().map(target::at).toList();
		var placed = LibraryBuilder.fillShelves(level, shelves, notes.iterator());
		helper.assertTrue(placed.size() == 8, "placed " + placed.size());
		var first = (ChiseledBookShelfBlockEntity) level.getBlockEntity(shelves.get(0));
		var second = (ChiseledBookShelfBlockEntity) level.getBlockEntity(shelves.get(1));
		helper.assertTrue(first.count() == 6 && second.count() == 2, "books per shelf " + first.count() + ", " + second.count());
		helper.assertTrue(NoteBooks.idOf(first.getItem(0)).orElseThrow().equals(notes.get(0).id()), "first slot holds the first note");
		BlockState firstState = level.getBlockState(shelves.get(0));
		helper.assertTrue(firstState.getValue(ChiseledBookShelfBlock.SLOT_OCCUPIED_PROPERTIES.get(5)),
				"the shelf shows its books (occupied slot states)");
		// the books the builder put up are what /palace shelve skips
		helper.assertTrue(LibraryBuilder.notesNearby(level, shelves.get(0), 1).containsAll(notes.stream().map(NoteSummary::id).toList()),
				"notesNearby sees the books");

		ItemStack index = RoomIndexBook.create("ENGG1101", notes.stream().map(n -> new RoomIndexBook.Entry(n, null)).toList());
		LibraryBuilder.putOnLectern(level, target.at(left.lectern()), index);
		helper.assertTrue(level.getBlockState(target.at(left.lectern())).getValue(LecternBlock.HAS_BOOK), "lectern shows a book");
		helper.assertTrue(((LecternBlockEntity) level.getBlockEntity(target.at(left.lectern()))).getBook().is(Items.WRITTEN_BOOK),
				"lectern holds the index book");

		// a few ticks later nothing has popped off (signs on walls, lanterns under the ceiling)
		helper.runAfterDelay(10, () -> {
			for (LibraryPlan.Room room : List.of(left, right)) {
				BlockPos signPos = target.at(room.sign());
				helper.assertTrue(level.getBlockEntity(signPos) instanceof SignBlockEntity, "sign of " + room.code() + " is still there");
				var sign = (SignBlockEntity) level.getBlockEntity(signPos);
				String line = sign.getText(SignTextSlot.FRONT).getMessages(false).get(1).getString();
				helper.assertTrue(room.code().equals(line), "sign reads " + line);
				helper.assertTrue(sign.isWaxed(), "sign is waxed");
			}
			for (LibraryPlan.Placement p : plan.placements()) {
				if (p.kind() == LibraryPlan.Kind.LANTERN) {
					BlockState lantern = level.getBlockState(target.at(p.pos()));
					helper.assertTrue(lantern.is(Blocks.LANTERN) && lantern.getValue(LanternBlock.HANGING), "lantern at " + target.at(p.pos()));
				}
			}
			helper.succeed();
		});
	}
}
