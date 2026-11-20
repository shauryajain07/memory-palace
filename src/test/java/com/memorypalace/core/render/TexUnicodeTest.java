package com.memorypalace.core.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TexUnicodeTest {
	private static String inline(String tex) {
		return TexUnicode.convert(tex, false);
	}

	@Test
	void symbolsScriptsAndFractions() {
		assertEquals("½ + x² ≤ √α", inline("\\frac{1}{2} + x^2 \\le \\sqrt{\\alpha}"));
		assertEquals("x₁, xₙ₊₁", inline("x_1, x_{n+1}"));
		assertEquals("∑ᵢ₌₁ⁿ i", inline("\\sum_{i=1}^{n} i"));
		assertEquals("ℝⁿ", inline("\\mathbb{R}^n"));
		assertEquals("(a+b)/c", inline("\\frac{a+b}{c}"));
		assertEquals("∀ε > 0 ∃δ", inline("\\forall\\varepsilon > 0 \\exists\\delta"));
	}

	@Test
	void scriptsWithoutUnicodeFormFallBackToCaret() {
		assertEquals("e^(iπ) + 1 = 0", inline("e^{i\\pi} + 1 = 0"));
		assertEquals("x^Q", inline("x^Q"));
		assertEquals("90°", inline("90^\\circ"));
		assertEquals("f′(x)", inline("f'(x)"));
	}

	@Test
	void namedFunctionsKeepWordsApart() {
		assertEquals("sin θ", inline("\\sin\\theta"));
		assertEquals("sin(x)", inline("\\sin(x)"));
		assertEquals("tr A", inline("\\operatorname{tr} A"));
	}

	@Test
	void rootsAndAccents() {
		assertEquals("√(x+1)", inline("\\sqrt{x+1}"));
		assertEquals("∛x", inline("\\sqrt[3]{x}"));
		assertEquals("v⃗", inline("\\vec{v}"));
		assertEquals("∉", inline("\\not\\in"));
	}

	@Test
	void environments() {
		assertEquals("(1 2; 3 4)", inline("\\begin{pmatrix} 1 & 2 \\\\ 3 & 4 \\end{pmatrix}"));
		assertEquals("[a; b]", inline("\\begin{bmatrix} a \\\\ b \\end{bmatrix}"));
		String cases = "f(x) = \\begin{cases} 1 & x > 0 \\\\ 0 & \\text{otherwise} \\end{cases}";
		assertEquals("f(x) = { 1, x > 0; 0, otherwise", inline(cases));
		assertEquals("f(x) = { 1, x > 0\n  0, otherwise", TexUnicode.convert(cases, true));
		assertEquals("a = b\nc = d", TexUnicode.convert("\\begin{aligned} a &= b \\\\ c &= d \\end{aligned}", true));
	}

	@Test
	void sizingCommandsDisappear() {
		assertEquals("(a/b)", inline("\\left(\\frac{a}{b}\\right)"));
		assertEquals("x", inline("\\left. x \\right."));
	}

	@Test
	void unknownCommandsAreKeptAsWritten() {
		assertEquals("\\ce{H2O}", inline("\\ce{H2O}"));
		assertEquals("\\foo + 1", inline("\\foo + 1"));
	}

	@Test
	void neverThrowsOnBrokenOrHostileInput() {
		for (String bad : new String[]{"\\", "^", "_", "{", "}", "\\begin{pmatrix", "\\frac", "\\sqrt[", "x^{",
				"{".repeat(10_000) + "x" + "}".repeat(10_000), "\\not".repeat(5_000) + "x",
				"\\sqrt".repeat(5_000), "\\begin{matrix}".repeat(3_000), "x^".repeat(5_000)}) {
			String out = assertDoesNotThrow(() -> TexUnicode.convert(bad, true));
			assertNotNull(out);
		}
		assertEquals("", TexUnicode.convert(null, false));
		assertEquals("", TexUnicode.convert("", false));
	}
}
