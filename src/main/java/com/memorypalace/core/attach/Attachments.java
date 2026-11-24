package com.memorypalace.core.attach;

import com.memorypalace.core.render.NoteImages;
import com.memorypalace.core.render.Raster;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.text.Normalizer;
import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Files people add to a note (pictures, PDFs), stored in an "attachments" folder beside the note and referenced
 * from its Markdown as {@code attachments/name.png}.
 * <ul>
 * <li>The client shrinks a picture to at most MAX_SIDE pixels and sends PNG (or JPEG for big photos).</li>
 * <li>The server picks the file name, decodes and re-encodes every picture (so only a clean image lands on disk),
 *     checks a PDF's header and never parses it, and never overwrites a file.</li>
 * </ul>
 * Note: core/ must not import net.minecraft / net.fabricmc.
 */
public final class Attachments {
	private Attachments() {}

	/** What the server stores. Pictures in other formats (gif, bmp) are converted to PNG by the client first. */
	public enum Type {
		PNG("png", 6L << 20), JPG("jpg", 6L << 20), PDF("pdf", 30L << 20);

		public final String extension;
		public final long maxBytes;

		Type(String extension, long maxBytes) {
			this.extension = extension;
			this.maxBytes = maxBytes;
		}

		public boolean image() {
			return this != PDF;
		}
	}

	public static final String FOLDER = "attachments";
	/** The client shrinks pictures to this many pixels on the longer side (enough for full-screen viewing). */
	public static final int MAX_SIDE = 1600;
	/** The server refuses pictures bigger than this. */
	public static final int MAX_STORED_SIDE = 4096;
	public static final long MAX_STORED_PIXELS = 4096L * 4096;
	public static final int MAX_NAME = 48;

	private static final Set<String> PICTURES = Set.of("png", "jpg", "jpeg", "gif", "bmp");
	private static final Set<String> WINDOWS_RESERVED = Set.of("con", "prn", "aux", "nul",
			"com1", "com2", "com3", "com4", "com5", "com6", "com7", "com8", "com9",
			"lpt1", "lpt2", "lpt3", "lpt4", "lpt5", "lpt6", "lpt7", "lpt8", "lpt9");

	private static String extension(String fileName) {
		int dot = fileName.lastIndexOf('.');
		return dot < 0 ? "" : fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
	}

	/** A picture the client can read and convert (png, jpg, jpeg, gif, bmp). */
	public static boolean isPicture(String fileName) {
		return PICTURES.contains(extension(fileName));
	}

	public static boolean isPdf(String fileName) {
		return extension(fileName).equals("pdf");
	}

	/** What the server would store a file with this name as; empty for anything else. */
	public static Optional<Type> typeOf(String fileName) {
		return switch (extension(fileName)) {
			case "png" -> Optional.of(Type.PNG);
			case "jpg", "jpeg" -> Optional.of(Type.JPG);
			case "pdf" -> Optional.of(Type.PDF);
			default -> Optional.empty();
		};
	}

	/**
	 * A safe file name (without extension) from what the user's file was called: no folders, no dots, no spaces or
	 * Markdown/URL characters, letters and digits of any language kept, at most MAX_NAME characters, never a
	 * reserved Windows name. fallback when nothing is left.
	 */
	public static String baseName(String fileName, String fallback) {
		String name = fileName.replace('\\', '/');
		name = name.substring(name.lastIndexOf('/') + 1);
		int dot = name.lastIndexOf('.');
		if (dot > 0) {
			name = name.substring(0, dot);
		}
		name = Normalizer.normalize(name, Normalizer.Form.NFC);
		var out = new StringBuilder();
		name.codePoints().forEach(c -> {
			boolean keep = Character.isLetterOrDigit(c) || c == '_' || c == '-';
			out.appendCodePoint(keep ? c : '-');
		});
		String cleaned = out.toString().replaceAll("-{2,}", "-").replaceAll("^-|-$", "");
		if (cleaned.codePointCount(0, cleaned.length()) > MAX_NAME) {
			cleaned = cleaned.substring(0, cleaned.offsetByCodePoints(0, MAX_NAME)).replaceAll("-$", "");
		}
		if (cleaned.isEmpty()) {
			return fallback;
		}
		return WINDOWS_RESERVED.contains(cleaned.toLowerCase(Locale.ROOT)) ? "file-" + cleaned : cleaned;
	}

	/** The file starts the way this type must: PNG and JPEG signatures, or "%PDF-". */
	public static boolean magicMatches(Type type, byte[] data) {
		byte[] magic = switch (type) {
			case PNG -> new byte[] {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};
			case JPG -> new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};
			case PDF -> "%PDF-".getBytes(StandardCharsets.US_ASCII);
		};
		return data.length >= magic.length && Arrays.equals(data, 0, magic.length, magic, 0, magic.length);
	}

	/** A picture ready to upload: the encoded bytes and what they decode to (shown in the editor at once). */
	public record Prepared(byte[] data, Type type, Raster raster) {}

	// PNG bigger than this for a picture without transparency (a photo, a scanned page) → JPEG instead
	private static final int PNG_LIMIT = 900 * 1024;

	/** Encodes an already shrunk picture: PNG, or JPEG when it has no transparency and the PNG would be large. */
	public static Prepared encode(Raster raster) {
		byte[] png = NoteImages.toPng(raster);
		if (png.length > PNG_LIMIT && opaque(raster)) {
			byte[] jpg = toJpeg(raster, 0.9F);
			if (jpg.length < png.length) {
				return new Prepared(jpg, Type.JPG, raster);
			}
		}
		return new Prepared(png, Type.PNG, raster);
	}

	/** The picture made smaller (smooth, keeping its shape) so neither side is over maxSide; small ones as they are. */
	public static Raster shrink(Raster raster, int maxSide) {
		int w = raster.width();
		int h = raster.height();
		if (w <= maxSide && h <= maxSide) {
			return raster;
		}
		double scale = (double) maxSide / Math.max(w, h);
		int nw = Math.max(1, (int) Math.round(w * scale));
		int nh = Math.max(1, (int) Math.round(h * scale));
		var source = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
		source.setRGB(0, 0, w, h, raster.argb(), 0, w);
		var target = new BufferedImage(nw, nh, BufferedImage.TYPE_INT_ARGB);
		var g = target.createGraphics();
		try {
			g.setComposite(java.awt.AlphaComposite.Src);
			g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
			g.setRenderingHint(java.awt.RenderingHints.KEY_RENDERING, java.awt.RenderingHints.VALUE_RENDER_QUALITY);
			g.drawImage(source, 0, 0, nw, nh, null);
		} finally {
			g.dispose();
		}
		return new Raster(nw, nh, target.getRGB(0, 0, nw, nh, null, 0, nw));
	}

	private static boolean opaque(Raster raster) {
		for (int argb : raster.argb()) {
			if (argb >>> 24 != 0xFF) {
				return false;
			}
		}
		return true;
	}

	/** Raster → JPEG at the given quality (0..1). Transparency is dropped onto white. */
	public static byte[] toJpeg(Raster raster, float quality) {
		var image = new BufferedImage(raster.width(), raster.height(), BufferedImage.TYPE_INT_RGB);
		int[] rgb = new int[raster.argb().length];
		for (int i = 0; i < rgb.length; i++) {
			int argb = raster.argb()[i];
			int a = argb >>> 24;
			int r = ((argb >> 16 & 0xFF) * a + 255 * (255 - a)) / 255;
			int g = ((argb >> 8 & 0xFF) * a + 255 * (255 - a)) / 255;
			int b = ((argb & 0xFF) * a + 255 * (255 - a)) / 255;
			rgb[i] = r << 16 | g << 8 | b;
		}
		image.setRGB(0, 0, raster.width(), raster.height(), rgb, 0, raster.width());
		ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
		try (var bytes = new ByteArrayOutputStream(); ImageOutputStream out = ImageIO.createImageOutputStream(bytes)) {
			writer.setOutput(out);
			ImageWriteParam param = writer.getDefaultWriteParam();
			param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
			param.setCompressionQuality(quality);
			writer.write(null, new IIOImage(image, null, null), param);
			out.flush();
			return bytes.toByteArray();
		} catch (IOException e) {
			throw new java.io.UncheckedIOException("JPEG encoding failed", e);
		} finally {
			writer.dispose();
		}
	}

	/**
	 * The server's check of an uploaded file. Pictures must decode within MAX_STORED_SIDE / MAX_STORED_PIXELS and
	 * are re-encoded in their own format, so whatever else the bytes contained is dropped. A PDF only needs its
	 * header (it is stored as it is and never opened by the server). Empty when the file is not what it claims.
	 */
	public static Optional<byte[]> verify(Type type, byte[] data) {
		if (data.length == 0 || data.length > type.maxBytes || !magicMatches(type, data)) {
			return Optional.empty();
		}
		if (type == Type.PDF) {
			return Optional.of(data);
		}
		return NoteImages.fromBytes(data, MAX_STORED_PIXELS, Integer.MAX_VALUE)
				.filter(r -> r.width() <= MAX_STORED_SIDE && r.height() <= MAX_STORED_SIDE)
				.map(r -> type == Type.PNG ? NoteImages.toPng(r) : toJpeg(r, 0.92F));
	}

	/**
	 * Writes data into the "attachments" folder beside the note at noteRelativePath, as base.ext or, if taken,
	 * base-2.ext, base-3.ext, … — never over an existing file. Returns the path to put in the note's Markdown,
	 * relative to the note's folder ("attachments/base.png").
	 *
	 * @throws IOException also when the note or the folder would be outside vaultRoot (including through a link)
	 */
	public static String store(Path vaultRoot, String noteRelativePath, String base, Type type, byte[] data)
			throws IOException {
		Path root = vaultRoot.toAbsolutePath().normalize();
		Path note = root.resolve(noteRelativePath).normalize();
		if (!note.startsWith(root) || note.equals(root)) {
			throw new IOException("note outside the vault: " + noteRelativePath);
		}
		Path folder = note.getParent().resolve(FOLDER);
		Files.createDirectories(folder);
		if (!folder.toRealPath().startsWith(root.toRealPath())) {
			throw new IOException("attachments folder leads outside the vault: " + folder);
		}
		String safe = baseName(base, "file");
		for (int n = 1; n < 1000; n++) {
			String name = (n == 1 ? safe : safe + "-" + n) + "." + type.extension;
			try {
				Files.write(folder.resolve(name), data, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
				return FOLDER + "/" + name;
			} catch (FileAlreadyExistsException taken) {
				// try the next number
			}
		}
		throw new IOException("too many files called " + safe);
	}
}
