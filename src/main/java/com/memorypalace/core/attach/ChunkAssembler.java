package com.memorypalace.core.attach;

/**
 * Puts an upload back together on the server. Client → server packets hold only about 32KB, so a file arrives as
 * chunks 0..total-1, in order (one connection keeps the order). The declared size is checked up front against the
 * type's limit and the chunk count, so a client can never make the server hold more than it said it would send.
 * Note: core/ must not import net.minecraft / net.fabricmc.
 */
public final class ChunkAssembler {
	/** Bytes per chunk the client sends (under the 32767-byte limit, with room for the other fields). */
	public static final int CHUNK_BYTES = 30_000;

	public enum Status { MORE, DONE, BAD }

	private final byte[] buffer;
	private final int total;
	private int filled;
	private int next;

	private ChunkAssembler(int size, int total) {
		this.buffer = new byte[size];
		this.total = total;
	}

	/** Chunks needed for size bytes. */
	public static int chunksFor(long size) {
		return (int) Math.max(1, (size + CHUNK_BYTES - 1) / CHUNK_BYTES);
	}

	/** A new upload of size bytes in total chunks; null when the numbers do not fit the limit or each other. */
	public static ChunkAssembler start(long size, int total, long maxBytes) {
		if (size <= 0 || size > maxBytes || size > Integer.MAX_VALUE || total != chunksFor(size)) {
			return null;
		}
		return new ChunkAssembler((int) size, total);
	}

	/** The next chunk: it must be the expected index and fit; the last one must complete the file exactly. */
	public Status accept(int index, byte[] data) {
		if (index != next || data.length == 0 || data.length > CHUNK_BYTES || filled + data.length > buffer.length) {
			return Status.BAD;
		}
		System.arraycopy(data, 0, buffer, filled, data.length);
		filled += data.length;
		next++;
		if (next < total) {
			return Status.MORE;
		}
		return filled == buffer.length ? Status.DONE : Status.BAD;
	}

	public int size() {
		return buffer.length;
	}

	/** The whole file (only after DONE). */
	public byte[] bytes() {
		return buffer;
	}
}
