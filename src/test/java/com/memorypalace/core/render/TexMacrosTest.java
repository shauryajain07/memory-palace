package com.memorypalace.core.render;

import com.memorypalace.core.markdown.MarkdownParser;
import com.memorypalace.core.markdown.MdBlock;
import com.memorypalace.core.markdown.MdSpan;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TexMacrosTest {
	@BeforeAll
	static void headless() {
		System.setProperty("java.awt.headless", "true");
	}

	/** Collects the definitions of a one-formula "note" and returns the macros. */
	private static TexMacros macros(String definitions) {
		return TexMacros.collect(List.of(new MdBlock.Math(definitions))).macros();
	}

	private static String expand(String definitions, String tex) {
		TexMacros.Expansion e = macros(definitions).expand(tex);
		assertTrue(e.ok(), () -> "unexpected problem: " + e.problem());
		return e.tex();
	}

	// ---- reading definitions ----

	@Test
	void simpleMacroWithBracedAndBareName() {
		assertEquals("\\mathbb{R}^n", expand("\\newcommand{\\R}{\\mathbb{R}}", "\\R^n"));
		assertEquals("\\mathbb{R}^n", expand("\\newcommand\\R{\\mathbb{R}}", "\\R^n"));
		assertEquals("\\mathbb{R}^n", expand("\\newcommand*{\\R}{\\mathbb{R}}", "\\R^n"));
		assertEquals("\\mathbb{R}^n", expand("\\renewcommand{ \\R }{\\mathbb{R}}", "\\R^n"));
	}

	@Test
	void argumentsAreGroupsOrSingleTokens() {
		String def = "\\newcommand{\\norm}[1]{\\left\\lVert #1 \\right\\rVert}";
		assertEquals("\\left\\lVert x+y \\right\\rVert", expand(def, "\\norm{x+y}"));
		assertEquals("\\left\\lVert x \\right\\rVert", expand(def, "\\norm x"));
		assertEquals("\\left\\lVert \\alpha \\right\\rVert", expand(def, "\\norm\\alpha"));
		assertEquals("\\frac{\\partial f}{\\partial x}",
				expand("\\newcommand{\\pd}[2]{\\frac{\\partial #1}{\\partial #2}}", "\\pd{f}{x}"));
	}

	@Test
	void optionalFirstArgumentUsesDefault() {
		String def = "\\newcommand{\\deriv}[2][x]{\\frac{d #2}{d #1}}";
		assertEquals("\\frac{d f}{d x}", expand(def, "\\deriv{f}"));
		assertEquals("\\frac{d f}{d t}", expand(def, "\\deriv[t]{f}"));
	}

	@Test
	void macrosMayUseOtherMacros() {
		String defs = "\\newcommand{\\R}{\\mathbb{R}} \\newcommand{\\Rn}{\\R^n}";
		assertEquals("x \\in \\mathbb{R}^n", expand(defs, "x \\in \\Rn"));
	}

	@Test
	void declareMathOperator() {
		assertEquals("\\operatorname{tr} A", expand("\\DeclareMathOperator{\\tr}{tr}", "\\tr A"));
		assertEquals("\\operatorname*{argmax}_x", expand("\\DeclareMathOperator*{\\argmax}{argmax}", "\\argmax_x"));
	}

	@Test
	void escapesAndHashes() {
		assertEquals("\\{x\\}", expand("\\newcommand{\\set}[1]{\\{#1\\}}", "\\set{x}"));
		assertEquals("#", expand("\\newcommand{\\hash}{##}", "\\hash"));
		// "\\" is a line break, so "\\R" is a line break followed by the letter R, not the macro \R
		assertEquals("a \\\\R", expand("\\newcommand{\\R}{X}", "a \\\\R"));
		assertEquals("a \\\\ X", expand("\\newcommand{\\R}{X}", "a \\\\ \\R"));
	}

	@Test
	void doesNotGlueCommandsTogether() {
		// "\alpha" + "A" must stay two tokens, and so must "\bar" + "a"
		assertEquals("\\alpha A", expand("\\newcommand{\\A}{A}", "\\alpha\\A"));
		assertEquals("\\bar a", expand("\\newcommand{\\vb}[1]{\\bar#1}", "\\vb{a}"));
		assertEquals("\\varepsilon x", expand("\\newcommand{\\eps}{\\varepsilon}", "\\eps x"));
		assertEquals("\\varepsilon_0", expand("\\newcommand{\\eps}{\\varepsilon}", "\\eps_0"));
		assertEquals("\\varepsilon x", expand("\\newcommand{\\epsx}[1]{#1x}", "\\epsx\\varepsilon"));
	}

	@Test
	void malformedDefinitionsStayInTheFormula() {
		for (String bad : new String[]{"\\newcommand{\\R}", "\\newcommand{\\R}[x]{y}", "\\newcommand{R}{y}",
				"\\newcommand{\\R}{unclosed"}) {
			TexMacros.Extraction e = TexMacros.extract(bad);
			assertTrue(e.definitions().isEmpty(), bad);
			assertEquals(bad, e.rest());
			assertTrue(TexMacros.containsDefinition(e.rest()), bad);
		}
	}

	@Test
	void unknownNamesAndPlainTextAreUntouched() {
		TexMacros m = macros("\\newcommand{\\R}{X}");
		assertEquals("\\Re + \\alpha + R", m.expand("\\Re + \\alpha + R").tex());
		assertEquals("plain", TexMacros.empty().expand("plain").tex());
		assertNull(TexMacros.empty().expand(null).tex());
	}

	// ---- runaway macros (bodies may come from an untrusted server) ----

	@Test
	void recursiveMacroStopsWithLoopProblem() {
		TexMacros.Expansion e = macros("\\newcommand{\\a}{\\a}").expand("x + \\a");
		assertFalse(e.ok());
		assertEquals(MathProblem.Kind.MACRO_LOOP, e.problem().kind());
		assertEquals("\\a", e.problem().detail());
	}

	@Test
	void mutualRecursionIsCaughtBeforeExpanding() {
		TexMacros m = macros("\\newcommand{\\x}{\\y} \\newcommand{\\y}{(\\x)} \\newcommand{\\ok}{1} \\newcommand{\\uses}{\\x}");
		assertEquals(MathProblem.Kind.MACRO_LOOP, m.expand("\\x").problem().kind());
		assertEquals(MathProblem.Kind.MACRO_LOOP, m.expand("\\uses").problem().kind());
		assertEquals("1", m.expand("\\ok").tex());
		// a macro that only passes its argument through is not recursive, even when nested
		assertEquals("x", expand("\\newcommand{\\id}[1]{#1}", "\\id{\\id{x}}"));
	}

	@Test
	void manyFormulasUsingARecursiveMacroStayCheap() {
		TexMacros m = macros("\\newcommand{\\a}{\\a\\a}");
		long start = System.nanoTime();
		for (int i = 0; i < 5_000; i++) {
			assertFalse(m.expand("x + \\a").ok());
		}
		assertTrue(System.nanoTime() - start < 2_000_000_000L, "5000 failing formulas should take well under 2 s");
	}

	@Test
	void growingMacroStopsAtLengthLimit() {
		TexMacros.Expansion e = macros("\\newcommand{\\b}{\\b\\b}").expand("\\b");
		assertFalse(e.ok());
		assertTrue(e.problem().kind() == MathProblem.Kind.MACRO_TOO_LONG
				|| e.problem().kind() == MathProblem.Kind.MACRO_LOOP, e.problem().toString());
		// a deep but finite chain is fine
		assertEquals("xxxxxxxx", expand("\\newcommand{\\c}{xx}\\newcommand{\\d}{\\c\\c}\\newcommand{\\e}{\\d\\d}", "\\e"));
	}

	// ---- collecting from a whole note ----

	@Test
	void definitionsApplyToTheWholeNoteAndDisappear() {
		String note = """
				Let $x \\in \\R^n$ and $\\norm{x} = 1$.

				$$
				\\newcommand{\\R}{\\mathbb{R}}
				\\newcommand{\\norm}[1]{\\lVert #1 \\rVert}
				$$

				Inline only: $\\newcommand{\\one}{1}$

				$$\\newcommand{\\two}{2} \\one + \\one = \\two$$
				""";
		TexMacros.Collected c = TexMacros.collect(MarkdownParser.parse(note));
		assertEquals(List.of("norm", "one", "R", "two"),
				c.macros().definitions().keySet().stream().sorted(String.CASE_INSENSITIVE_ORDER).toList());
		// the definition-only display formula is gone; "Inline only:" keeps its text
		assertEquals(3, c.blocks().size(), c.blocks().toString());
		var first = (MdBlock.Paragraph) c.blocks().get(0);
		List<String> formulas = first.spans().stream().filter(s -> s.has(MdSpan.MATH)).map(MdSpan::text).toList();
		assertEquals(List.of("x \\in \\mathbb{R}^n", "\\lVert x \\rVert = 1"),
				formulas.stream().map(f -> c.macros().expand(f).tex()).toList());
		var inlineOnly = (MdBlock.Paragraph) c.blocks().get(1);
		assertTrue(inlineOnly.spans().stream().noneMatch(s -> s.has(MdSpan.MATH)), inlineOnly.toString());
		var last = (MdBlock.Math) c.blocks().get(2);
		assertEquals("\\one + \\one = \\two", last.tex());
		assertEquals("1 + 1 = 2", c.macros().expand(last.tex()).tex());
	}

	@Test
	void paragraphOfOnlyDefinitionsDisappears() {
		TexMacros.Collected c = TexMacros.collect(MarkdownParser.parse("$\\newcommand{\\x}{y}$\n\nText"));
		assertEquals(1, c.blocks().size(), c.blocks().toString());
		assertEquals("y", c.macros().expand("\\x").tex());
	}

	@Test
	void provideCommandDoesNotOverride() {
		assertEquals("A", expand("\\newcommand{\\x}{A} \\providecommand{\\x}{B}", "\\x"));
		assertEquals("B", expand("\\providecommand{\\x}{B}", "\\x"));
		assertEquals("C", expand("\\newcommand{\\x}{A} \\renewcommand{\\x}{C}", "\\x"));
	}

	@Test
	void noteWithoutDefinitionsIsReturnedAsIs() {
		List<MdBlock> blocks = MarkdownParser.parse("# Title\n\n$x^2$ and $$y$$");
		TexMacros.Collected c = TexMacros.collect(blocks);
		assertSame(blocks, c.blocks());
		assertTrue(c.macros().isEmpty());
	}

	@Test
	void definitionsInHeadingsListsQuotesAndTables() {
		String note = """
				# Title $\\newcommand{\\h}{H}$

				- item $\\newcommand{\\l}{L}$ text

				> quote $\\newcommand{\\q}{Q}$

				| a | b |
				|---|---|
				| $\\newcommand{\\t}{T}$ | x |
				""";
		TexMacros.Collected c = TexMacros.collect(MarkdownParser.parse(note));
		assertEquals(4, c.macros().definitions().size(), c.macros().definitions().toString());
		for (MdBlock block : c.blocks()) {
			assertFalse(block.toString().contains("newcommand"), block.toString());
		}
	}

	// ---- MathRenderer integration ----

	@Test
	void rendererRefusesLeftoverDefinitionsSoNothingLeaksBetweenNotes() {
		var result = MathRenderer.render("\\newcommand{\\leak}{X}\\leak", false, 20, 0xFF000000);
		assertFalse(result.ok());
		assertEquals(MathProblem.Kind.BAD_DEFINITION, result.problem().kind());
		// JLaTeXMath never saw the definition, so the name is still unknown everywhere else
		var later = MathRenderer.render("\\leak", false, 20, 0xFF000000);
		assertFalse(later.ok());
		assertEquals(MathProblem.Kind.UNKNOWN_COMMAND, later.problem().kind());
		assertEquals("\\leak", later.problem().detail());
	}

	@Test
	void rendererReportsStructuredProblems() {
		assertEquals(MathProblem.Kind.EMPTY, MathRenderer.render(" ", false, 20, 0).problem().kind());
		var missing = MathRenderer.render("\\frac{1}", false, 20, 0).problem();
		assertEquals(MathProblem.Kind.MISSING_ARGUMENT, missing.kind());
		assertEquals("2", missing.detail());
		assertEquals(MathProblem.Kind.TOO_LONG,
				MathRenderer.render("x".repeat(MathRenderer.MAX_TEX_CHARS + 1), false, 20, 0).problem().kind());
		assertNull(MathRenderer.render("x^2", false, 20, 0).problem());
	}

	@Test
	void expandedMacrosRender() {
		TexMacros m = macros("\\newcommand{\\R}{\\mathbb{R}}\\DeclareMathOperator{\\tr}{tr}");
		var result = MathRenderer.render(m.expand("\\tr A \\in \\R").tex(), false, 20, 0xFF000000);
		assertTrue(result.ok(), () -> String.valueOf(result.problem()));
	}
}
