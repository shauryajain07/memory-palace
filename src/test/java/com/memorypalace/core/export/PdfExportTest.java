package com.memorypalace.core.export;

import com.memorypalace.core.render.MathRenderer;
import com.memorypalace.core.render.Raster;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.io.File;
import java.io.IOException;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class PdfExportTest {
	@org.junit.jupiter.api.io.TempDir
	File previewDir;

	static final String NOTE = """
			$$\\newcommand{\\R}{\\mathbb{R}}$$

			# Eigenvalues

			Let $A \\in \\R^{n \\times n}$. A number $\\lambda$ is an **eigenvalue** of $A$ if *some* nonzero vector works:

			$$A\\mathbf{v} = \\lambda \\mathbf{v}$$

			- The trace `tr(A)` is the sum of the eigenvalues.
			- [x] read chapter 5
			- [ ] do the exercises
			  1. nested numbered item

			> A quote about the café and naïve students.

			Chinese 特征值 and Greek α β in running text.

			| Matrix | Eigenvalues |
			| --- | --- |
			| identity | all 1 |
			| $\\begin{pmatrix}2&0\\\\0&3\\end{pmatrix}$ | 2 and 3 |

			```
			import numpy as np
			np.linalg.eig(A)  # {curly} <angle> under_score
			```

			---

			See [the docs](https://numpy.org) and #midterm.

			![graph](graph.png)

			![nope](missing.png)
			""";

	private static PdfExport.Formula render(String tex, boolean display) {
		var r = MathRenderer.render(tex, display, PdfExport.BODY * PdfExport.PX_PER_PT, 0xFF000000);
		return r.ok() ? new PdfExport.Formula(r.raster(), r.depth()) : null;
	}

	private static Optional<Raster> images(String src) {
		if (!src.equals("graph.png")) {
			return Optional.empty();
		}
		int w = 400, h = 200;
		int[] argb = new int[w * h];
		for (int i = 0; i < argb.length; i++) {
			argb[i] = 0xFF000000 | (i % w * 255 / w) << 16 | (i / w * 255 / h) << 8 | 0x80;
		}
		return Optional.of(new Raster(w, h, argb));
	}

	@Test
	void exportsTextFormulasPicturesAndLinks() throws IOException {
		PdfExport.Result result = PdfExport.export("Eigenvalues", NOTE, PdfExportTest::images, PdfExportTest::render);
		assertTrue(result.pages() >= 1);
		assertTrue(result.notes().contains("picture missing.png"), "missing picture reported: " + result.notes());
		try (PDDocument doc = Loader.loadPDF(result.pdf())) {
			String text = new PDFTextStripper().getText(doc);
			for (String expected : new String[] {"Eigenvalues", "eigenvalue", "tr(A)", "read chapter 5", "café", "naïve",
					"identity", "np.linalg.eig(A)", "{curly}", "<angle>", "under_score", "#midterm", "the docs"}) {
				assertTrue(text.contains(expected), "PDF text has " + expected + ":\n" + text);
			}
			assertEquals("Eigenvalues", doc.getDocumentInformation().getTitle());
			boolean webLink = doc.getPage(0).getAnnotations().stream().anyMatch(a -> a instanceof PDAnnotationLink l
					&& l.getAction() instanceof org.apache.pdfbox.pdmodel.interactive.action.PDActionURI u
					&& "https://numpy.org".equals(u.getURI()));
			assertTrue(webLink || doc.getNumberOfPages() > 1, "the web link stays clickable");
			// characters Computer Modern and Helvetica lack come from a Unicode font on this computer, if it has one
			boolean unicodeFont = java.util.stream.Stream.of("/System/Library/Fonts/Supplemental/Arial Unicode.ttf",
					"/Library/Fonts/Arial Unicode.ttf", "C:\\Windows\\Fonts\\arialuni.ttf", "C:\\Windows\\Fonts\\msyh.ttc",
					"C:\\Windows\\Fonts\\simsun.ttc", "/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc",
					"/usr/share/fonts/noto-cjk/NotoSansCJK-Regular.ttc").anyMatch(p -> new File(p).isFile());
			if (unicodeFont) {
				assertTrue(text.contains("特征值") && text.contains("α β"), "Chinese and Greek kept:\n" + text);
			} else {
				assertTrue(text.contains("Chinese ???"), "unknown characters become ?");
			}
			File png = new File(previewDir, "memorypalace-pdf-test.png");
			ImageIO.write(new PDFRenderer(doc).renderImageWithDPI(0, 80), "png", png);
		}
	}

	@Test
	void longNotesGetMorePagesAndMathRequestsMatch() throws IOException {
		String longNote = "# Long\n\n" + "A sentence that fills the page with words and more words. ".repeat(40).concat("\n\n").repeat(12);
		PdfExport.Result result = PdfExport.export("Long", longNote, src -> Optional.empty(), PdfExportTest::render);
		assertTrue(result.pages() > 2, "pages: " + result.pages());
		var requests = PdfExport.mathIn(NOTE);
		assertTrue(requests.contains(new PdfExport.MathRequest("A \\in \\mathbb{R}^{n \\times n}", false)), "macros expanded: " + requests);
		assertTrue(requests.contains(new PdfExport.MathRequest("A\\mathbf{v} = \\lambda \\mathbf{v}", true)));
	}
}
