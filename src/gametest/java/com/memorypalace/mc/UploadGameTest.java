package com.memorypalace.mc;

import com.memorypalace.core.attach.ChunkAssembler;
import com.memorypalace.core.render.NoteImages;
import com.memorypalace.core.render.Raster;
import com.memorypalace.core.vault.NoteSummary;
import com.memorypalace.core.vault.Vault;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sends uploads through the real server handler (UploadPayloads) with mock players and checks what it answers and
 * what lands on disk: a real picture with a path-like name is stored safely beside the note; a fake picture, an
 * oversized file and a wrong type are refused; someone who may not edit the note is refused before anything else.
 */
public class UploadGameTest {
	@GameTest(maxTicks = 200)
	public void uploadsAreCheckedBeforeAnythingIsWritten(GameTestHelper helper) throws Exception {
		Vault vault = PalaceServer.vault();
		helper.assertTrue(vault != null, "the vault should be open");
		Path config = FabricLoader.getInstance().getConfigDir().resolve(PalaceConfig.FILE_NAME);
		String before = Files.exists(config) ? Files.readString(config) : null;
		// other players than the host need the multiplayer switch on
		Files.createDirectories(config.getParent());
		Files.writeString(config, "multiplayer = true\n");
		PalaceConfig.load();

		String run = UUID.randomUUID().toString().substring(0, 8);
		Map<String, String[]> replies = new ConcurrentHashMap<>();
		Path root = vault.root();

		// 1. a real picture (two chunks), called "../../evil name.png"
		ServerPlayer alice = helper.makeMockServerPlayerInLevel();
		NoteSummary aliceNote = vault.createNote("uploadtest-" + run + "/alice.md", "# Alice", alice.getUUID());
		byte[] png = noisePng(130, 130);
		helper.assertTrue(png.length > ChunkAssembler.CHUNK_BYTES, "picture should need more than one chunk");
		send(alice, aliceNote, 1, "../../evil name.png", png, png.length, replies, "ok");

		// 2. a PDF pretending to be a PNG
		ServerPlayer bob = helper.makeMockServerPlayerInLevel();
		NoteSummary bobNote = vault.createNote("uploadtest-" + run + "/bob/bob.md", "# Bob", bob.getUUID());
		byte[] fake = "%PDF-1.4 this is not a picture".getBytes(StandardCharsets.US_ASCII);
		send(bob, bobNote, 2, "fake.png", fake, fake.length, replies, "fake");

		// 3. a picture declared bigger than the limit: refused on the first chunk, nothing is held
		ServerPlayer carol = helper.makeMockServerPlayerInLevel();
		NoteSummary carolNote = vault.createNote("uploadtest-" + run + "/carol/carol.md", "# Carol", carol.getUUID());
		int huge = 7 << 20;
		UploadPayloads.receive(carol, new UploadPayloads.Chunk(carolNote.id(), 3, 0, ChunkAssembler.chunksFor(huge), huge,
				"big.png", new byte[ChunkAssembler.CHUNK_BYTES]), collect(replies, "big"));

		// 4. a type the server never stores
		ServerPlayer dave = helper.makeMockServerPlayerInLevel();
		NoteSummary daveNote = vault.createNote("uploadtest-" + run + "/dave/dave.md", "# Dave", dave.getUUID());
		send(dave, daveNote, 4, "virus.exe", png, png.length, replies, "exe");

		// 5. someone else's note
		ServerPlayer eve = helper.makeMockServerPlayerInLevel();
		send(eve, aliceNote, 5, "eve.png", png, png.length, replies, "eve");

		helper.succeedWhen(() -> {
			helper.assertTrue(replies.size() == 5, "answers so far: " + replies.keySet());
			try {
				expect(helper, replies, "ok", "OK", "attachments/evil-name.png");
				expect(helper, replies, "fake", "BAD_FILE", "");
				expect(helper, replies, "big", "TOO_BIG", "");
				expect(helper, replies, "exe", "BAD_TYPE", "");
				expect(helper, replies, "eve", "DENIED", "");
				Path stored = root.resolve("uploadtest-" + run + "/attachments/evil-name.png");
				helper.assertTrue(Files.isRegularFile(stored), "stored at " + stored);
				Raster raster = NoteImages.fromBytes(Files.readAllBytes(stored)).orElseThrow();
				helper.assertTrue(raster.width() == 130, "stored picture is " + raster.width() + " wide");
				try (var files = Files.list(root.resolve("uploadtest-" + run + "/attachments"))) {
					helper.assertTrue(files.count() == 1, "only Alice's picture is stored (Eve's upload was refused)");
				}
				for (String other : new String[] {"bob", "carol", "dave"}) {
					helper.assertTrue(!Files.exists(root.resolve("uploadtest-" + run + "/" + other + "/attachments")),
							other + "'s refused upload left a folder behind");
				}
			} catch (java.io.IOException e) {
				throw new RuntimeException(e);
			} finally {
				restore(config, before);
			}
		});
	}

	private static void restore(Path config, String before) {
		try {
			if (before == null) {
				Files.deleteIfExists(config);
			} else {
				Files.writeString(config, before);
			}
			PalaceConfig.load();
		} catch (java.io.IOException e) {
			throw new RuntimeException(e);
		}
	}

	private static UploadPayloads.Reply collect(Map<String, String[]> replies, String key) {
		return (noteId, session, result, src) -> replies.putIfAbsent(key, new String[] {result, src});
	}

	/** Sends data as the client would: chunk after chunk, all with the same session and declared size. */
	private static void send(ServerPlayer player, NoteSummary note, int session, String name, byte[] data, int size,
			Map<String, String[]> replies, String key) {
		int total = ChunkAssembler.chunksFor(size);
		for (int i = 0; i < total; i++) {
			int from = i * ChunkAssembler.CHUNK_BYTES;
			byte[] part = Arrays.copyOfRange(data, from, Math.min(data.length, from + ChunkAssembler.CHUNK_BYTES));
			UploadPayloads.receive(player, new UploadPayloads.Chunk(note.id(), session, i, total, size, name, part),
					collect(replies, key));
		}
	}

	private static void expect(GameTestHelper helper, Map<String, String[]> replies, String key, String result, String src) {
		String[] got = replies.get(key);
		helper.assertTrue(got[0].equals(result) && got[1].equals(src),
				key + ": expected " + result + " " + src + " but got " + got[0] + " " + got[1]);
	}

	private static byte[] noisePng(int w, int h) {
		int[] argb = new int[w * h];
		var random = new Random(42);
		for (int i = 0; i < argb.length; i++) {
			argb[i] = 0xFF000000 | random.nextInt(0xFFFFFF);
		}
		return NoteImages.toPng(new Raster(w, h, argb));
	}
}
