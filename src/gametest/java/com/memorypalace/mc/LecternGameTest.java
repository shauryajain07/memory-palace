package com.memorypalace.mc;

import com.memorypalace.core.attach.ChunkAssembler;
import com.memorypalace.core.vault.Vault;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.Filterable;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.WritableBookContent;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LecternBlock;
import net.minecraft.world.level.block.entity.LecternBlockEntity;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Writing in a book and quill on a lectern through the real server handler (LecternPayloads), with a mock player:
 * what gets saved, and every refusal (stale text, too long, too far, no book and quill).
 */
public class LecternGameTest {
	@GameTest(maxTicks = 100)
	public void bookAndQuillOnALecternCanBeWritten(GameTestHelper helper) throws Exception {
		Path config = FabricLoader.getInstance().getConfigDir().resolve(PalaceConfig.FILE_NAME);
		String before = Files.exists(config) ? Files.readString(config) : null;
		Files.createDirectories(config.getParent());
		Files.writeString(config, "multiplayer = true\n");
		PalaceConfig.load();
		try {
			BlockPos pos = helper.absolutePos(new BlockPos(1, 1, 1));
			helper.setBlock(new BlockPos(1, 1, 1), Blocks.LECTERN);
			ItemStack quill = new ItemStack(Items.WRITABLE_BOOK);
			quill.set(DataComponents.WRITABLE_BOOK_CONTENT, new WritableBookContent(List.of(
					Filterable.passThrough("page one"), Filterable.passThrough("page two"))));
			ServerPlayer player = helper.makeMockServerPlayerInLevel();
			player.setPos(pos.getX() + 0.5, pos.getY(), pos.getZ() + 1.5);
			helper.assertTrue(LecternBlock.tryPlaceBook(player, helper.getLevel(), pos, helper.getLevel().getBlockState(pos), quill),
					"the book and quill goes on the lectern");
			LecternBlockEntity lectern = (LecternBlockEntity) helper.getLevel().getBlockEntity(pos);
			helper.assertTrue(LecternPayloads.writable(player, pos).isPresent(), "the player next to it may write");
			String original = LecternPayloads.text(lectern.getBook());
			helper.assertTrue(original.equals("page one\n\npage two"), "pages joined with a blank line: " + original);

			// saving without changes leaves the pages exactly as they were (no repacking)
			var replies = new ArrayList<String[]>();
			save(player, pos, 1, original, Vault.hash(original), replies);
			expect(helper, replies, "OK");
			helper.assertTrue(pages(lectern).equals(List.of("page one", "page two")), "pages untouched: " + pages(lectern));

			// a real edit
			String edited = "# Week 5\n\nNew notes about $\\lambda$";
			save(player, pos, 2, edited, Vault.hash(original), replies);
			expect(helper, replies, "OK");
			helper.assertTrue(LecternPayloads.text(lectern.getBook()).equals(edited), "saved: " + LecternPayloads.text(lectern.getBook()));
			helper.assertTrue(replies.getLast()[1].equals(Vault.hash(edited)), "new fingerprint returned");

			// someone else's change in between: refused
			save(player, pos, 3, "overwrite", Vault.hash(original), replies);
			expect(helper, replies, "CONFLICT");
			helper.assertTrue(LecternPayloads.text(lectern.getBook()).equals(edited), "nothing overwritten");

			// more than a book holds
			String huge = "Paragraph with a few words in it.\n\n".repeat(2000);
			save(player, pos, 4, huge, Vault.hash(edited), replies);
			expect(helper, replies, "BOOK_TOO_LONG");

			// too far away
			player.setPos(pos.getX() + 30.5, pos.getY(), pos.getZ());
			save(player, pos, 5, "far", Vault.hash(edited), replies);
			expect(helper, replies, "DENIED");
			helper.assertTrue(LecternPayloads.writable(player, pos).isEmpty(), "out of reach");

			// six saves in one tick: the rate limit (5 at once) answers BUSY
			player.setPos(pos.getX() + 0.5, pos.getY(), pos.getZ() + 1.5);
			save(player, pos, 6, "again", Vault.hash(edited), replies);
			expect(helper, replies, "BUSY");

			// a written book (or none) is not a book and quill (another player, with a fresh rate limit)
			ServerPlayer other = helper.makeMockServerPlayerInLevel();
			other.setPos(pos.getX() + 0.5, pos.getY(), pos.getZ() + 1.5);
			lectern.setBook(new ItemStack(Items.WRITTEN_BOOK));
			save(other, pos, 7, "x", "", replies);
			expect(helper, replies, "MISSING");
			helper.succeed();
		} finally {
			if (before == null) {
				Files.deleteIfExists(config);
			} else {
				Files.writeString(config, before);
			}
			PalaceConfig.load();
		}
	}

	private static List<String> pages(LecternBlockEntity lectern) {
		return lectern.getBook().getOrDefault(DataComponents.WRITABLE_BOOK_CONTENT, WritableBookContent.EMPTY)
				.pages().stream().map(Filterable::raw).toList();
	}

	private static void save(ServerPlayer player, BlockPos pos, int session, String text, String hash, List<String[]> replies) {
		byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
		int total = ChunkAssembler.chunksFor(bytes.length);
		for (int i = 0; i < total; i++) {
			int from = i * ChunkAssembler.CHUNK_BYTES;
			LecternPayloads.receive(player, new LecternPayloads.SaveChunk(pos, session, i, total, bytes.length, hash,
							Arrays.copyOfRange(bytes, from, Math.min(bytes.length, from + ChunkAssembler.CHUNK_BYTES))),
					(p, s, result, newHash) -> replies.add(new String[] {result, newHash}));
		}
	}

	private static void expect(GameTestHelper helper, List<String[]> replies, String result) {
		helper.assertTrue(!replies.isEmpty() && replies.getLast()[0].equals(result),
				"expected " + result + " but got " + (replies.isEmpty() ? "nothing" : replies.getLast()[0]));
	}
}
