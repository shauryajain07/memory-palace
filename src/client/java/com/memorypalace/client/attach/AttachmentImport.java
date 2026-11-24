package com.memorypalace.client.attach;

import com.memorypalace.MemoryPalace;
import com.memorypalace.client.render.NoteImageStore;
import com.memorypalace.core.attach.Attachments;
import com.memorypalace.core.attach.PdfPages;
import com.memorypalace.core.render.NoteImages;
import com.memorypalace.core.render.Raster;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Adds pictures and PDFs to the note open in the editor, one file after another:
 * <ol>
 * <li>A placeholder line ("[Adding photo.png…]") goes in at the cursor straight away.</li>
 * <li>Off the render thread the picture is shrunk and encoded, or the PDF's pages are drawn (PdfPages).</li>
 * <li>Uploads sends the files; the server answers with where it stored each one.</li>
 * <li>The placeholder becomes {@code ![photo](attachments/photo.png)}, or for a PDF a title line with a link to
 *     the PDF and one picture per page. The pictures show in the preview at once, before the note is saved.</li>
 * </ol>
 * Render thread only.
 */
public final class AttachmentImport {
	private AttachmentImport() {}

	/** The editor the files go into. */
	public interface Target {
		UUID noteId();

		/** Puts markdown in at the cursor as a paragraph of its own. */
		void insertBlock(String markdown);

		/** Replaces placeholder with markdown (at the cursor if the placeholder was deleted meanwhile). */
		void replaceBlock(String placeholder, String markdown);

		/** A line of progress or an error under the editor's title; null clears it. */
		void attachStatus(Component status);

		/** A picture now stored on the server but maybe not yet saved into the note. */
		void uploaded(String src);
	}

	// the preview copy kept in memory per picture (the reader asks the server for its own later)
	private static final int PREVIEW_SIDE = 1024;

	private record Job(Target target, String name, Path path, byte[] pasted) {}

	private static final ArrayDeque<Job> JOBS = new ArrayDeque<>();
	private static boolean running;

	/** Files dropped onto the editor or picked in the file dialog. Other kinds of file get a message. */
	public static void importFiles(Target target, List<Path> paths) {
		for (Path path : paths) {
			Path file = path.getFileName();
			JOBS.add(new Job(target, file == null ? path.toString() : file.toString(), path, null));
		}
		next();
	}

	/** A picture pasted from the clipboard (PNG, JPEG, BMP or TIFF bytes). */
	public static void importPasted(Target target, byte[] data) {
		JOBS.add(new Job(target, "pasted.png", null, data));
		next();
	}

	/** A file is being prepared or uploaded, or waits its turn. */
	public static boolean busy() {
		return running || !JOBS.isEmpty();
	}

	private static void next() {
		if (running || JOBS.isEmpty()) {
			return;
		}
		running = true;
		run(JOBS.poll());
	}

	private static void finish() {
		running = false;
		next();
	}

	private static void run(Job job) {
		Target t = job.target();
		String name = job.name();
		boolean pdf = Attachments.isPdf(name);
		if (!pdf && !Attachments.isPicture(name)) {
			t.attachStatus(Component.translatable("screen.memorypalace.attach_unsupported", name));
			finish();
			return;
		}
		String placeholder = "[" + I18n.get("screen.memorypalace.attach_working", name) + "]";
		t.insertBlock(placeholder);
		t.attachStatus(Component.translatable("screen.memorypalace.attach_preparing", name));
		Minecraft mc = Minecraft.getInstance();
		Thread.ofVirtual().name("memorypalace-attach").start(() -> {
			try {
				if (pdf) {
					if (Files.size(job.path()) > Attachments.Type.PDF.maxBytes) {
						mc.execute(() -> fail(job, placeholder, "TOO_BIG"));
						return;
					}
					byte[] original = Files.readAllBytes(job.path());
					if (!Attachments.magicMatches(Attachments.Type.PDF, original)) {
						mc.execute(() -> fail(job, placeholder, "BAD_FILE"));
						return;
					}
					PdfPages.Result pages = PdfPages.render(job.path(), (done, drawn) -> mc.execute(() -> t.attachStatus(
							Component.translatable("screen.memorypalace.attach_pdf_page", done, drawn))));
					mc.execute(() -> uploadPdf(job, placeholder, original, pages));
				} else {
					Optional<Raster> raster = job.pasted() != null
							? NoteImages.fromBytes(job.pasted(), 64L << 20, Attachments.MAX_SIDE * 4)
									.map(r -> Attachments.shrink(r, Attachments.MAX_SIDE))
							: NoteImages.load(job.path(), Attachments.MAX_SIDE);
					if (raster.isEmpty()) {
						mc.execute(() -> fail(job, placeholder, "BAD_FILE"));
						return;
					}
					Attachments.Prepared prepared = Attachments.encode(raster.get());
					mc.execute(() -> uploadPicture(job, placeholder, prepared));
				}
			} catch (PdfPages.Locked e) {
				mc.execute(() -> fail(job, placeholder, "PDF_LOCKED"));
			} catch (Exception | OutOfMemoryError e) {
				MemoryPalace.LOGGER.warn("Could not read {}", name, e);
				mc.execute(() -> fail(job, placeholder, "BAD_FILE"));
			}
		});
	}

	private static void uploadPicture(Job job, String placeholder, Attachments.Prepared prepared) {
		Target t = job.target();
		String base = Attachments.baseName(job.name(), "image");
		var file = new Uploads.Outgoing(base + "." + prepared.type().extension, prepared.data());
		Uploads.enqueue(t.noteId(), List.of(file), new Uploads.Listener() {
			@Override
			public void progress(int index, int files) {
				t.attachStatus(Component.translatable("screen.memorypalace.attach_uploading", job.name()));
			}

			@Override
			public void done(List<String> srcs) {
				String src = srcs.getFirst();
				NoteImageStore.seed(t.noteId(), src, Attachments.shrink(prepared.raster(), PREVIEW_SIDE));
				t.uploaded(src);
				t.replaceBlock(placeholder, "![" + base + "](" + src + ")");
				t.attachStatus(Component.translatable("screen.memorypalace.attach_done", job.name()));
				finish();
			}

			@Override
			public void failed(String result) {
				fail(job, placeholder, result);
			}
		});
	}

	private static void uploadPdf(Job job, String placeholder, byte[] original, PdfPages.Result pages) {
		Target t = job.target();
		String base = Attachments.baseName(job.name(), "document");
		var files = new ArrayList<Uploads.Outgoing>();
		files.add(new Uploads.Outgoing(base + ".pdf", original));
		for (int i = 0; i < pages.pages().size(); i++) {
			Attachments.Prepared page = pages.pages().get(i);
			files.add(new Uploads.Outgoing(base + "-p" + (i + 1) + "." + page.type().extension, page.data()));
		}
		Uploads.enqueue(t.noteId(), files, new Uploads.Listener() {
			@Override
			public void progress(int index, int count) {
				t.attachStatus(Component.translatable("screen.memorypalace.attach_uploading_n", index + 1, count));
			}

			@Override
			public void done(List<String> srcs) {
				var md = new StringBuilder("**").append(base).append(".pdf** · ")
						.append(pages.pageCount() == 1 ? I18n.get("screen.memorypalace.pdf_one_page")
								: I18n.get("screen.memorypalace.pdf_pages", pages.pageCount()));
				if (pages.pageCount() > pages.pages().size()) {
					md.append(" · ").append(I18n.get("screen.memorypalace.pdf_first", pages.pages().size()));
				}
				md.append(" · [").append(I18n.get("screen.memorypalace.pdf_open")).append("](").append(srcs.getFirst()).append(')');
				for (int i = 1; i < srcs.size(); i++) {
					String src = srcs.get(i);
					NoteImageStore.seed(t.noteId(), src, Attachments.shrink(pages.pages().get(i - 1).raster(), PREVIEW_SIDE));
					t.uploaded(src);
					md.append("\n\n![").append(base).append(" p").append(i).append("](").append(src).append(')');
				}
				t.replaceBlock(placeholder, md.toString());
				t.attachStatus(Component.translatable("screen.memorypalace.attach_done", job.name()));
				finish();
			}

			@Override
			public void failed(String result) {
				fail(job, placeholder, result);
			}
		});
	}

	private static void fail(Job job, String placeholder, String result) {
		String key = switch (result) {
			case "DENIED" -> "attach_denied";
			case "LOCKED" -> "attach_locked";
			case "TOO_BIG" -> "attach_too_big";
			case "BAD_FILE", "BAD_TYPE" -> "attach_bad_file";
			case "PDF_LOCKED" -> "attach_pdf_locked";
			case "BUSY" -> "attach_busy";
			default -> "attach_failed";
		};
		job.target().replaceBlock(placeholder, "");
		job.target().attachStatus(Component.translatable("screen.memorypalace." + key, job.name()));
		finish();
	}
}
