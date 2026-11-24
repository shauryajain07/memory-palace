package com.memorypalace.core.library;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The block layout of a library: a hall that starts right in front of the player and runs straight ahead, with one
 * room per class on alternating sides (first on the left, then right, then left …), facing each other in pairs.
 *
 * <pre>
 *   top view (player at P, looking up the page)
 *   ##########+#########       # wall   + hall end wall
 *   #  room 3 #  room 4 #      S sign next to each door, on the hall side
 *   #SSSSS L  D  L SSSSS#      L lectern with the room index, D door
 *   ##########.##########      SSSSS chiseled bookshelves on the back wall (3 rows high)
 *   #  room 1 #  room 2 #
 *   #SSSSS L  D  L SSSSS#
 *   #######  ...  #######
 *             P
 * </pre>
 *
 * Coordinates are local: x to the player's right, y up (0 = the level the player stands on), z straight ahead.
 * The player's own column (x = 0, z = 0) is never built on. The game maps these to world positions and blocks.
 * Note: core/ must not import net.minecraft / net.fabricmc.
 */
public final class LibraryPlan {
	public enum Kind {
		AIR, FLOOR, HALL_FLOOR, WALL, CEILING, SHELF, LECTERN, SIGN, LANTERN;

		/** Needs its neighbour to exist first (a wall sign hangs on a wall, a lantern under the ceiling). */
		public boolean attached() {
			return this == SIGN || this == LANTERN;
		}
	}

	/** Directions seen from the player: FORWARD = straight ahead (+z). */
	public enum Facing {
		NONE(0, 0), FORWARD(0, 1), BACK(0, -1), LEFT(-1, 0), RIGHT(1, 0);

		public final int dx;
		public final int dz;

		Facing(int dx, int dz) {
			this.dx = dx;
			this.dz = dz;
		}
	}

	public record Pos(int x, int y, int z) {
		public Pos plus(Facing f) {
			return new Pos(x + f.dx, y, z + f.dz);
		}

		public Pos minus(Facing f) {
			return new Pos(x - f.dx, y, z - f.dz);
		}

		public Pos up(int dy) {
			return new Pos(x, y + dy, z);
		}
	}

	/** One block: what goes where, and which way it faces (shelves, lecterns, signs). */
	public record Placement(Pos pos, Kind kind, Facing facing) {}

	/**
	 * @param shelves bookshelves in the order to fill them (eye level first)
	 * @param sign    the sign by the door, on the hall side
	 */
	public record Room(String code, boolean rightSide, List<Pos> shelves, Pos lectern, Pos sign) {}

	/** min/max: corners of the box the plan touches (inclusive). */
	public record Plan(List<Placement> placements, List<Room> rooms, Pos min, Pos max) {
		public int width() {
			return max.x() - min.x() + 1;
		}

		public int height() {
			return max.y() - min.y() + 1;
		}

		public int depth() {
			return max.z() - min.z() + 1;
		}
	}

	/** Outer size of a room along both axes, walls included. */
	static final int ROOM = 7;
	/** Rooms on the same side share the wall between them. */
	static final int STEP = ROOM - 1;
	static final int FLOOR_Y = -1;
	static final int CEILING_Y = 4;
	/** Rows of bookshelves on a room's back wall; with 5 per row that is 15 shelves = 90 books. */
	static final int SHELF_ROWS = 3;
	public static final int BOOKS_PER_SHELF = 6;

	private LibraryPlan() {}

	public static Plan of(List<String> codes) {
		if (codes == null || codes.isEmpty() || codes.size() > CourseCodes.MAX_ROOMS) {
			throw new IllegalArgumentException("1 to " + CourseCodes.MAX_ROOMS + " rooms");
		}
		var cells = new LinkedHashMap<Pos, Placement>();
		int pairs = (codes.size() + 1) / 2;
		int zEnd = 1 + STEP * pairs;

		// hall: 3 wide (x -1..1), open towards the player, closed at the far end
		for (int z = 1; z <= zEnd; z++) {
			for (int x = -2; x <= 2; x++) {
				boolean wall = Math.abs(x) == 2 || z == zEnd;
				for (int y = FLOOR_Y; y <= CEILING_Y; y++) {
					Kind kind = wall ? Kind.WALL : y == FLOOR_Y ? Kind.HALL_FLOOR : y == CEILING_Y ? Kind.CEILING : Kind.AIR;
					put(cells, new Pos(x, y, z), kind, Facing.NONE);
				}
			}
		}
		for (int z = 2; z < zEnd; z += 4) {
			put(cells, new Pos(0, CEILING_Y - 1, z), Kind.LANTERN, Facing.NONE);
		}

		var rooms = new ArrayList<Room>();
		var specials = new ArrayList<Placement>();
		for (int i = 0; i < codes.size(); i++) {
			boolean right = i % 2 == 1;
			int side = right ? 1 : -1;
			int z0 = 1 + STEP * (i / 2);
			Facing towardHall = right ? Facing.LEFT : Facing.RIGHT;
			for (int ax = 2; ax <= 2 + ROOM - 1; ax++) {
				for (int z = z0; z <= z0 + ROOM - 1; z++) {
					boolean wall = ax == 2 || ax == 2 + ROOM - 1 || z == z0 || z == z0 + ROOM - 1;
					for (int y = FLOOR_Y; y <= CEILING_Y; y++) {
						Kind kind = wall ? Kind.WALL : y == FLOOR_Y ? Kind.FLOOR : y == CEILING_Y ? Kind.CEILING : Kind.AIR;
						put(cells, new Pos(side * ax, y, z), kind, Facing.NONE);
					}
				}
			}
			int doorZ = z0 + 3;
			// door: 2 high in the wall shared with the hall
			specials.add(new Placement(new Pos(side * 2, 0, doorZ), Kind.AIR, Facing.NONE));
			specials.add(new Placement(new Pos(side * 2, 1, doorZ), Kind.AIR, Facing.NONE));
			// shelves on the back wall, facing into the room; filled eye level (y 1) first, then y 0, then y 2
			var shelves = new ArrayList<Pos>();
			for (int y : new int[]{1, 0, 2}) {
				for (int z = z0 + 1; z <= z0 + ROOM - 2; z++) {
					Pos p = new Pos(side * (2 + ROOM - 2), y, z);
					shelves.add(p);
					specials.add(new Placement(p, Kind.SHELF, towardHall));
				}
			}
			Pos lectern = new Pos(side * 5, 0, doorZ);
			specials.add(new Placement(lectern, Kind.LECTERN, towardHall));
			specials.add(new Placement(new Pos(side * 5, CEILING_Y - 1, doorZ), Kind.LANTERN, Facing.NONE));
			// sign on the hall side of the door wall, just before the door, readable from the hall
			Pos sign = new Pos(side, 1, doorZ - 1);
			specials.add(new Placement(sign, Kind.SIGN, towardHall));
			rooms.add(new Room(codes.get(i), right, List.copyOf(shelves), lectern, sign));
		}
		for (Placement p : specials) {
			cells.put(p.pos(), p);
		}

		int minX = 0, maxX = 0, minZ = Integer.MAX_VALUE, maxZ = 0;
		for (Pos p : cells.keySet()) {
			minX = Math.min(minX, p.x());
			maxX = Math.max(maxX, p.x());
			minZ = Math.min(minZ, p.z());
			maxZ = Math.max(maxZ, p.z());
		}
		return new Plan(List.copyOf(cells.values()), List.copyOf(rooms),
				new Pos(minX, FLOOR_Y, minZ), new Pos(maxX, CEILING_Y, maxZ));
	}

	private static void put(Map<Pos, Placement> cells, Pos pos, Kind kind, Facing facing) {
		Placement old = cells.get(pos);
		// a room's wall may stand where the hall's side wall is; keep walls, never turn a wall back into air
		if (old != null && old.kind() == Kind.WALL && kind != Kind.WALL) {
			return;
		}
		cells.put(pos, new Placement(pos, kind, facing));
	}
}
