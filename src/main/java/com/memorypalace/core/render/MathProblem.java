package com.memorypalace.core.render;

/**
 * Why a formula could not be drawn, in a form the client can translate (core/ has no language files).
 * MathRenderer.Result.error() keeps its old free-text message; this is the structured version of it.
 *
 * @param kind   what went wrong
 * @param detail extra information for the message: the unknown command ("\lam"), the macro name, the size limit,
 *               or JLaTeXMath's own (English) message for SYNTAX. Never null; may be "".
 */
public record MathProblem(Kind kind, String detail) {
	public enum Kind {
		/** Formula is empty or only whitespace. */
		EMPTY,
		/** Source longer than MathRenderer.MAX_TEX_CHARS (detail = the limit). */
		TOO_LONG,
		/** Rendered image would be larger than the allowed size (detail = "W×H"). */
		TOO_BIG,
		/** A command such as \frac is missing a required {argument} (detail = argument number). */
		MISSING_ARGUMENT,
		/** JLaTeXMath does not know this command (detail = "\name"). */
		UNKNOWN_COMMAND,
		/** Any other parse error (detail = JLaTeXMath's message). */
		SYNTAX,
		/** A \newcommand macro kept expanding into itself (detail = "\name"). */
		MACRO_LOOP,
		/** Expanding macros made the formula longer than MathRenderer.MAX_TEX_CHARS (detail = the limit). */
		MACRO_TOO_LONG,
		/** A \newcommand / \DeclareMathOperator that could not be read, e.g. a missing {body}. */
		BAD_DEFINITION
	}

	public MathProblem {
		detail = detail == null ? "" : detail;
	}

	public static MathProblem of(Kind kind) {
		return new MathProblem(kind, "");
	}
}
