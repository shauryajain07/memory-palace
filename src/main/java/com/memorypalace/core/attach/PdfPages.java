package com.memorypalace.core.attach;

import com.memorypalace.core.render.Raster;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.IOUtils;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

/**
 * Turns the pages of a PDF into pictures with Apache PDFBox, on the client only (the server never opens a PDF).
 * Each page is drawn so its longer side is Attachments.MAX_SIDE pixels, then encoded like any other picture.
 * Slow (a page can take a second; the first PDF may wait for a scan of the system's fonts): call off the render
 * thread. Note: core/ must not import net.minecraft / net.fabricmc.
 */
public final class PdfPages {
	private PdfPages() {}

	/** At most this many pages become pictures; the rest of the PDF is still kept beside the note. */
	public static final int MAX_PAGES = 60;

	/** pageCount is the PDF's real page count; pages holds at most MAX_PAGES pictures. */
	public record Result(int pageCount, List<Attachments.Prepared> pages) {}

	/** Thrown when the PDF needs a password to open. */
	public static final class Locked extends IOException {
		Locked(Throwable cause) {
			super(cause);
		}
	}

	/** progress gets the number of pages done so far and how many will be drawn. */
	public static Result render(Path file, BiConsumer<Integer, Integer> progress) throws IOException {
		try (PDDocument document = Loader.loadPDF(file.toFile(), IOUtils.createTempFileOnlyStreamCache())) {
			int count = document.getNumberOfPages();
			var renderer = new PDFRenderer(document);
			renderer.setSubsamplingAllowed(true);
			var pages = new ArrayList<Attachments.Prepared>();
			int drawn = Math.min(count, MAX_PAGES);
			for (int i = 0; i < drawn; i++) {
				PDRectangle box = document.getPage(i).getCropBox();
				float longest = Math.max(1, Math.max(box.getWidth(), box.getHeight()));
				float scale = Math.min(4.0F, Attachments.MAX_SIDE / longest);
				BufferedImage image = renderer.renderImage(i, scale, ImageType.RGB);
				pages.add(Attachments.encode(toRaster(image)));
				progress.accept(i + 1, drawn);
			}
			return new Result(count, pages);
		} catch (org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException e) {
			throw new Locked(e);
		}
	}

	private static Raster toRaster(BufferedImage image) {
		int w = image.getWidth();
		int h = image.getHeight();
		return new Raster(w, h, image.getRGB(0, 0, w, h, null, 0, w));
	}
}
