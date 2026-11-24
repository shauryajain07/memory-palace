package com.memorypalace.core.library;

import com.memorypalace.core.library.LibraryPlan.Facing;
import com.memorypalace.core.library.LibraryPlan.Kind;
import com.memorypalace.core.library.LibraryPlan.Placement;
import com.memorypalace.core.library.LibraryPlan.Plan;
import com.memorypalace.core.library.LibraryPlan.Pos;
import com.memorypalace.core.library.LibraryPlan.Room;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class LibraryPlanTest {
	private static final List<String> CLASSES = List.of("ENGG1101", "ENGG1330", "ENGG1000", "CCHU9078", "MATH1851");

	private static Map<Pos, Placement> cells(Plan plan) {
		var map = new HashMap<Pos, Placement>();
		for (Placement p : plan.placements()) {
			assertNull(map.put(p.pos(), p), "two placements at " + p.pos());
		}
		return map;
	}

	private static Kind kind(Map<Pos, Placement> cells, Pos pos) {
		Placement p = cells.get(pos);
		return p == null ? null : p.kind();
	}

	@Test
	void parsesTheClassCodes() {
		CourseCodes.Parsed parsed = CourseCodes.parse("ENGG1101 engg1330, ENGG1000;CCHU9078  MATH1851 ENGG1101");
		assertEquals(CLASSES, parsed.codes(), "upper-cased, in order, without the duplicate");
		assertTrue(parsed.invalid().isEmpty());
		CourseCodes.Parsed bad = CourseCodes.parse("OK1 ../etc bad/slash WAYTOOLONGFORASIGNLINEXXXXXXXXXXX");
		assertEquals(List.of("OK1"), bad.codes());
		assertEquals(3, bad.invalid().size(), bad.invalid().toString());
		assertEquals("Library/MATH1851", CourseCodes.folder("MATH1851"));
	}

	@Test
	void oneRoomPerClassWithNinetyBooksOfShelfSpace() {
		Plan plan = LibraryPlan.of(CLASSES);
		assertEquals(5, plan.rooms().size());
		var allShelves = new HashSet<Pos>();
		for (Room room : plan.rooms()) {
			assertEquals(15, room.shelves().size(), room.code());
			assertTrue(room.shelves().stream().allMatch(allShelves::add), "shelves are not shared between rooms");
		}
		assertEquals(List.of(false, true, false, true, false), plan.rooms().stream().map(Room::rightSide).toList(),
				"rooms alternate left, right");
		assertEquals(CLASSES, plan.rooms().stream().map(Room::code).toList());
	}

	@Test
	void everythingIsReachableAndAttachedToSomething() {
		Plan plan = LibraryPlan.of(CLASSES);
		Map<Pos, Placement> cells = cells(plan);
		for (Placement p : plan.placements()) {
			switch (p.kind()) {
				// you can stand in front of a shelf or lectern to use it
				case SHELF, LECTERN -> assertEquals(Kind.AIR, kind(cells, p.pos().plus(p.facing())), p.toString());
				// a wall sign hangs on the block behind it
				case SIGN -> {
					assertEquals(Kind.WALL, kind(cells, p.pos().minus(p.facing())), p.toString());
					assertEquals(Kind.AIR, kind(cells, p.pos().plus(p.facing())), "readable from the hall: " + p);
				}
				// a hanging lantern needs the ceiling right above it
				case LANTERN -> assertEquals(Kind.CEILING, kind(cells, p.pos().up(1)), p.toString());
				default -> {
				}
			}
		}
		for (Room room : plan.rooms()) {
			int side = room.rightSide() ? 1 : -1;
			Pos doorLow = new Pos(2 * side, 0, room.lectern().z());
			assertEquals(Kind.AIR, kind(cells, doorLow), "door of " + room.code());
			assertEquals(Kind.AIR, kind(cells, doorLow.up(1)), "door is two high: " + room.code());
			assertEquals(Kind.AIR, kind(cells, new Pos(side, 0, room.lectern().z())), "hall in front of the door");
			assertEquals(Kind.SIGN, kind(cells, room.sign()));
		}
	}

	@Test
	void hallIsWalkableFromThePlayerToTheEnd() {
		Plan plan = LibraryPlan.of(CLASSES);
		Map<Pos, Placement> cells = cells(plan);
		assertNull(cells.get(new Pos(0, 0, 0)), "the player's own column is not built on");
		assertNull(cells.get(new Pos(0, 1, 0)));
		int end = plan.max().z();
		for (int z = 1; z < end; z++) {
			assertEquals(Kind.AIR, kind(cells, new Pos(0, 0, z)), "hall floor level at z=" + z);
			assertEquals(Kind.AIR, kind(cells, new Pos(0, 1, z)), "head height at z=" + z);
			assertEquals(Kind.HALL_FLOOR, kind(cells, new Pos(0, -1, z)));
		}
		assertEquals(Kind.WALL, kind(cells, new Pos(0, 0, end)), "the hall is closed at the far end");
	}

	@Test
	void boundingBoxAndSizes() {
		Plan plan = LibraryPlan.of(CLASSES);
		assertEquals(new Pos(-8, -1, 1), plan.min());
		assertEquals(new Pos(8, 4, 19), plan.max());
		assertEquals(17, plan.width());
		assertEquals(6, plan.height());
		assertEquals(19, plan.depth());
		for (Placement p : plan.placements()) {
			Pos q = p.pos();
			assertTrue(q.x() >= plan.min().x() && q.x() <= plan.max().x() && q.y() >= plan.min().y()
					&& q.y() <= plan.max().y() && q.z() >= plan.min().z() && q.z() <= plan.max().z(), q.toString());
		}
	}

	@Test
	void oddNumberOfRoomsClosesTheEmptySide() {
		Plan plan = LibraryPlan.of(List.of("ONLY"));
		Map<Pos, Placement> cells = cells(plan);
		assertEquals(1, plan.rooms().size());
		assertFalse(plan.rooms().getFirst().rightSide());
		for (int z = 1; z <= plan.max().z(); z++) {
			assertEquals(Kind.WALL, kind(cells, new Pos(2, 1, z)), "right side of the hall is a wall at z=" + z);
		}
	}

	@Test
	void refusesNoRoomsOrTooMany() {
		assertThrows(IllegalArgumentException.class, () -> LibraryPlan.of(List.of()));
		List<String> many = java.util.stream.IntStream.rangeClosed(1, CourseCodes.MAX_ROOMS + 1).mapToObj(i -> "C" + i).toList();
		assertThrows(IllegalArgumentException.class, () -> LibraryPlan.of(many));
		assertEquals(CourseCodes.MAX_ROOMS, LibraryPlan.of(many.subList(0, CourseCodes.MAX_ROOMS)).rooms().size());
	}

	@Test
	void shelvesFaceIntoTheirRoom() {
		Plan plan = LibraryPlan.of(CLASSES);
		Map<Pos, Placement> cells = cells(plan);
		for (Room room : plan.rooms()) {
			Facing expected = room.rightSide() ? Facing.LEFT : Facing.RIGHT;
			for (Pos shelf : room.shelves()) {
				assertEquals(expected, cells.get(shelf).facing(), room.code() + " " + shelf);
			}
			assertEquals(1, room.shelves().getFirst().y(), "eye-level shelves are filled first");
		}
	}
}
