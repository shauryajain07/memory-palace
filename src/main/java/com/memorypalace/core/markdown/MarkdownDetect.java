package com.memorypalace.core.markdown;

import java.util.List;

/**
 * Does a piece of plain text use Markdown or LaTeX? Used to decide whether an ordinary written book is shown with
 * Memory Palace's reader (formulas, headings, lists …) or left to the vanilla book screen.
 *
 * Plain prose, even over several paragraphs, is not "rich": a book that is just text keeps the vanilla look.
 * Note: core/ must not import net.minecraft / net.fabricmc.
 */
public final class MarkdownDetect {
	private MarkdownDetect() {}

	/** True when the text has a formula, heading, list, quote, code, table, rule, image, link or emphasis. */
	public static boolean isRich(String text) {
		if (text == null || text.isBlank()) {
			return false;
		}
		for (MdBlock block : MarkdownParser.parse(text)) {
			boolean rich = switch (block) {
				case MdBlock.Paragraph p -> richSpans(p.spans());
				case MdBlock.Heading h -> true;
				case MdBlock.ListItem item -> true;
				case MdBlock.Quote q -> true;
				case MdBlock.Code c -> true;
				case MdBlock.Math m -> true;
				case MdBlock.Rule r -> true;
				case MdBlock.Image img -> true;
				case MdBlock.Table t -> true;
			};
			if (rich) {
				return true;
			}
		}
		return false;
	}

	private static boolean richSpans(List<MdSpan> spans) {
		// a #tag alone does not make a hand-written book Markdown
		return spans.stream().anyMatch(s -> s.styles() != 0 || s.link() != null && !Tags.isTagLink(s.link()));
	}
}
