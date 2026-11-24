package com.memorypalace.core.attach;

import com.memorypalace.core.render.NoteImages;
import com.memorypalace.core.render.Raster;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Optional;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class AttachmentsTest {
	@TempDir
	Path vault;

	private static Raster gradient(int w, int h, boolean alpha) {
		int[] argb = new int[w * h];
		for (int i = 0; i < argb.length; i++) {
			int x = i % w, y = i / w;
			argb[i] = (alpha ? (x * 255 / w) << 24 : 0xFF000000) | (x * 7 & 0xFF) << 16 | (y * 13 & 0xFF) << 8 | (x * y & 0xFF);
		}
		return new Raster(w, h, argb);
	}

	@Test
	void safeNames() {
		assertEquals("Lecture-3-notes", Attachments.baseName("Lecture 3 (notes).pdf", "file"));
		assertEquals("photo", Attachments.baseName("/Users/me/Desktop/photo.JPG", "file"));
		assertEquals("photo", Attachments.baseName("C:\\Users\\me\\photo.png", "file"));
		assertEquals("a-b", Attachments.baseName("a..b.png", "file"));
		assertEquals("特征值", Attachments.baseName("特征值.png", "file"));
		assertEquals("image", Attachments.baseName("???.png", "image"));
		assertEquals("png", Attachments.baseName(".png", "image"), "a hidden file called .png");
		assertEquals("image", Attachments.baseName("...", "image"));
		assertEquals("file-CON", Attachments.baseName("CON.png", "file"));
		assertEquals(Attachments.MAX_NAME, Attachments.baseName("x".repeat(200) + ".png", "f").length());
		assertFalse(Attachments.baseName("[x](y)#%?.png", "f").matches(".*[\\[\\]()#%?.].*"));
	}

	@Test
	void typesAndSignatures() {
		assertEquals(Optional.of(Attachments.Type.JPG), Attachments.typeOf("a.JPEG"));
		assertEquals(Optional.of(Attachments.Type.PDF), Attachments.typeOf("a.pdf"));
		assertTrue(Attachments.typeOf("a.gif").isEmpty(), "the client converts gif to png first");
		assertTrue(Attachments.typeOf("a.md").isEmpty());
		assertTrue(Attachments.isPicture("a.gif"));
		assertTrue(Attachments.magicMatches(Attachments.Type.PDF, "%PDF-1.7\n".getBytes(StandardCharsets.US_ASCII)));
		assertFalse(Attachments.magicMatches(Attachments.Type.PDF, "<html>".getBytes(StandardCharsets.US_ASCII)));
		byte[] png = NoteImages.toPng(gradient(4, 4, false));
		assertTrue(Attachments.magicMatches(Attachments.Type.PNG, png));
		assertFalse(Attachments.magicMatches(Attachments.Type.JPG, png));
	}

	@Test
	void verifyReencodesRealPicturesAndRejectsTheRest() {
		byte[] png = NoteImages.toPng(gradient(40, 30, true));
		byte[] checked = Attachments.verify(Attachments.Type.PNG, png).orElseThrow();
		assertEquals(40, NoteImages.fromBytes(checked).orElseThrow().width());

		// a PNG header followed by junk is refused
		byte[] junk = Arrays.copyOf(png, 64);
		var random = new Random(1);
		for (int i = 16; i < junk.length; i++) {
			junk[i] = (byte) random.nextInt();
		}
		assertTrue(Attachments.verify(Attachments.Type.PNG, junk).isEmpty());
		// something hidden after a real picture never reaches the disk: the picture is re-encoded
		byte[] polyglot = Arrays.copyOf(png, png.length + 20);
		System.arraycopy("<script>x</script>xx".getBytes(StandardCharsets.US_ASCII), 0, polyglot, png.length, 20);
		assertFalse(new String(Attachments.verify(Attachments.Type.PNG, polyglot).orElseThrow(), StandardCharsets.ISO_8859_1)
				.contains("<script>"));

		assertTrue(Attachments.verify(Attachments.Type.JPG, png).isEmpty(), "type must match the bytes");
		assertTrue(Attachments.verify(Attachments.Type.PNG, new byte[0]).isEmpty());
		assertTrue(Attachments.verify(Attachments.Type.PDF, "%PDF-1.4 ...".getBytes(StandardCharsets.US_ASCII)).isPresent());
		assertTrue(Attachments.verify(Attachments.Type.PDF, "MZ...".getBytes(StandardCharsets.US_ASCII)).isEmpty());
	}

	@Test
	void bigPhotosBecomeJpegButTransparentPicturesStayPng() {
		Raster photo = gradient(1400, 1000, false);
		// noise so the PNG cannot compress well
		var random = new Random(7);
		for (int i = 0; i < photo.argb().length; i++) {
			photo.argb()[i] = 0xFF000000 | random.nextInt(0xFFFFFF);
		}
		assertEquals(Attachments.Type.JPG, Attachments.encode(photo).type());
		assertEquals(Attachments.Type.PNG, Attachments.encode(gradient(50, 50, false)).type());
		assertEquals(Attachments.Type.PNG, Attachments.encode(gradient(50, 50, true)).type());
	}

	@Test
	void storesBesideTheNoteWithoutOverwriting() throws IOException {
		Files.createDirectories(vault.resolve("ENGG1330"));
		Files.writeString(vault.resolve("ENGG1330/Week 1.md"), "# hi");
		byte[] data = {1, 2, 3};
		assertEquals("attachments/graph.png",
				Attachments.store(vault, "ENGG1330/Week 1.md", "graph", Attachments.Type.PNG, data));
		assertEquals("attachments/graph-2.png",
				Attachments.store(vault, "ENGG1330/Week 1.md", "graph", Attachments.Type.PNG, new byte[] {9}));
		assertArrayEquals(data, Files.readAllBytes(vault.resolve("ENGG1330/attachments/graph.png")));
		assertEquals("attachments/slides.pdf",
				Attachments.store(vault, "root note.md", "../../slides", Attachments.Type.PDF, data));
		assertTrue(Files.exists(vault.resolve("attachments/slides.pdf")));
	}

	@Test
	void refusesToWriteOutsideTheVault(@TempDir Path elsewhere) throws IOException {
		assertThrows(IOException.class,
				() -> Attachments.store(vault, "../outside.md", "x", Attachments.Type.PNG, new byte[] {1}));
		Files.createDirectories(vault.resolve("linked"));
		Files.createSymbolicLink(vault.resolve("linked/attachments"), elsewhere);
		assertThrows(IOException.class,
				() -> Attachments.store(vault, "linked/note.md", "x", Attachments.Type.PNG, new byte[] {1}));
		try (var files = Files.list(elsewhere)) {
			assertEquals(0, files.count());
		}
	}

	@Test
	void assemblesChunksInOrderWithinTheDeclaredSize() {
		byte[] file = new byte[ChunkAssembler.CHUNK_BYTES * 2 + 5];
		new Random(3).nextBytes(file);
		int total = ChunkAssembler.chunksFor(file.length);
		assertEquals(3, total);
		ChunkAssembler a = ChunkAssembler.start(file.length, total, 1 << 20);
		assertNotNull(a);
		assertEquals(ChunkAssembler.Status.MORE, a.accept(0, Arrays.copyOfRange(file, 0, ChunkAssembler.CHUNK_BYTES)));
		assertEquals(ChunkAssembler.Status.BAD, a.accept(2, new byte[5]), "out of order");
		ChunkAssembler b = ChunkAssembler.start(file.length, total, 1 << 20);
		for (int i = 0; i < total; i++) {
			int from = i * ChunkAssembler.CHUNK_BYTES;
			var status = b.accept(i, Arrays.copyOfRange(file, from, Math.min(file.length, from + ChunkAssembler.CHUNK_BYTES)));
			assertEquals(i == total - 1 ? ChunkAssembler.Status.DONE : ChunkAssembler.Status.MORE, status);
		}
		assertArrayEquals(file, b.bytes());
	}

	@Test
	void rejectsLiesAboutSize() {
		assertNull(ChunkAssembler.start(10, 1, 5), "over the type's limit");
		assertNull(ChunkAssembler.start(10, 2, 100), "chunk count does not match the size");
		assertNull(ChunkAssembler.start(0, 1, 100));
		ChunkAssembler a = ChunkAssembler.start(10, 1, 100);
		assertEquals(ChunkAssembler.Status.BAD, a.accept(0, new byte[11]), "more bytes than declared");
		ChunkAssembler b = ChunkAssembler.start(10, 1, 100);
		assertEquals(ChunkAssembler.Status.BAD, b.accept(0, new byte[9]), "last chunk leaves the file short");
	}
}
