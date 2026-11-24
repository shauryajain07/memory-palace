package com.memorypalace.core.attach;

import com.memorypalace.core.render.NoteImages;
import com.memorypalace.core.render.Raster;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PdfPagesTest {
	@TempDir
	Path dir;

	/** A PDF with the given pages; each page is a coloured box (no fonts, so no font lookup is needed). */
	private Path pdf(String name, PDRectangle... sizes) throws IOException {
		Path file = dir.resolve(name);
		try (var document = new PDDocument()) {
			for (PDRectangle size : sizes) {
				var page = new PDPage(size);
				document.addPage(page);
				try (var content = new PDPageContentStream(document, page)) {
					content.setNonStrokingColor(0.1F, 0.3F, 0.8F);
					content.addRect(size.getWidth() / 4, size.getHeight() / 4, size.getWidth() / 2, size.getHeight() / 2);
					content.fill();
				}
			}
			document.save(file.toFile());
		}
		return file;
	}

	@Test
	void everyPageBecomesAPictureAtMaxSide() throws IOException {
		Path file = pdf("slides.pdf", PDRectangle.A4, new PDRectangle(PDRectangle.A4.getHeight(), PDRectangle.A4.getWidth()));
		List<int[]> progress = new ArrayList<>();
		PdfPages.Result result = PdfPages.render(file, (done, total) -> progress.add(new int[] {done, total}));
		assertEquals(2, result.pageCount());
		assertEquals(2, result.pages().size());
		assertEquals(List.of(1, 2), progress.stream().map(p -> p[0]).toList());
		assertTrue(progress.stream().allMatch(p -> p[1] == 2));
		Raster portrait = result.pages().get(0).raster();
		Raster landscape = result.pages().get(1).raster();
		assertEquals(Attachments.MAX_SIDE, portrait.height());
		assertTrue(portrait.width() < portrait.height());
		assertEquals(Attachments.MAX_SIDE, landscape.width());
		// the blue box is in the middle of the page, the corner is white
		int middle = portrait.argb()[portrait.height() / 2 * portrait.width() + portrait.width() / 2];
		int corner = portrait.argb()[5 * portrait.width() + 5];
		assertTrue((middle & 0xFF) > 150 && (middle >> 16 & 0xFF) < 80, Integer.toHexString(middle));
		assertEquals(0xFFFFFF, corner & 0xFFFFFF);
		// and the encoded picture decodes again
		assertTrue(NoteImages.fromBytes(result.pages().get(0).data(), 1L << 24, 4096).isPresent());
	}

	@Test
	void longPdfsStopAtMaxPages() throws IOException {
		PDRectangle[] sizes = new PDRectangle[PdfPages.MAX_PAGES + 3];
		java.util.Arrays.fill(sizes, new PDRectangle(200, 100));
		PdfPages.Result result = PdfPages.render(pdf("long.pdf", sizes), (done, total) -> {});
		assertEquals(PdfPages.MAX_PAGES + 3, result.pageCount());
		assertEquals(PdfPages.MAX_PAGES, result.pages().size());
	}

	@Test
	void passwordProtectedPdfIsReportedAsLocked() throws IOException {
		Path file = dir.resolve("secret.pdf");
		try (var document = new PDDocument()) {
			document.addPage(new PDPage(PDRectangle.A6));
			var policy = new StandardProtectionPolicy("owner", "user", new AccessPermission());
			policy.setEncryptionKeyLength(128);
			document.protect(policy);
			document.save(file.toFile());
		}
		assertThrows(PdfPages.Locked.class, () -> PdfPages.render(file, (done, total) -> {}));
	}

	@Test
	void notAPdfFails() throws IOException {
		Path file = dir.resolve("fake.pdf");
		Files.writeString(file, "this is not a PDF");
		assertThrows(IOException.class, () -> PdfPages.render(file, (done, total) -> {}));
	}
}
