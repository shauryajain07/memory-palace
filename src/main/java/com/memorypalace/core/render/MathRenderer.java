package com.memorypalace.core.render;

import java.awt.Color;
import java.awt.image.BufferedImage;

import org.scilab.forge.jlatexmath.ParseException;
import org.scilab.forge.jlatexmath.TeXConstants;
import org.scilab.forge.jlatexmath.TeXFormula;
import org.scilab.forge.jlatexmath.TeXParser;

/**
 * LaTeX 公式 → 位图，底层用 JLaTeXMath（org.scilab.forge.jlatexmath）。
 * 注意：本包（core/）不允许 import 任何 net.minecraft / net.fabricmc 的类。
 */
public final class MathRenderer {
	private static final Object RENDER_LOCK = new Object();
	/** 一个公式最多这么多字（正常的公式远到不了），挡住超长公式把解析和排版卡死。 */
	public static final int MAX_TEX_CHARS = 4000;
	/** 位图最大边长和总像素（约 16MB），在分配之前检查：正文可能来自不怀好意的服务端。 */
	public static final int MAX_SIDE = 4096;
	public static final long MAX_PIXELS = 4L * 1024 * 1024;

	private MathRenderer() {}

	/**
	 * 渲染结果。成功时 raster 非 null、error 为 null；失败时相反。
	 * @param depth 基线以下的像素数（例如 y 的尾巴），用来和文字基线对齐；0 ≤ depth ≤ raster.height
	 */
	public record Result(Raster raster, int depth, String error, MathProblem problem) {
		public boolean ok() {
			return raster != null;
		}

		/** problem: the same failure as error, structured so the client can show a translated message. */
		static Result failed(String error, MathProblem problem) {
			return new Result(null, 0, error, problem);
		}
	}

	/**
	 * 要求：
	 * - display = true 用行间公式样式（$$...$$，例如 \sum 上下标在正上下方），false 用行内样式
	 * - pixelSize 是字号（像素），结果高度大致和它成正比
	 * - 背景透明，公式用 color（0xAARRGGBB）画，开抗锯齿
	 * - 位图四周各留 1 像素透明边，避免抗锯齿边缘被切掉
	 * - 语法错误（例如 "\frac{1}"、未知命令 "\notacommand"）返回 error 为非空的失败结果，绝不抛异常
	 * - null、空串或全是空白 → 失败结果
	 * - 必须能在 java.awt.headless=true 下工作（游戏里就是这样）；不要创建任何窗口或 Swing 组件
	 * - 线程安全（可能同时从多个线程调用）
	 */
	public static Result render(String tex, boolean display, float pixelSize, int color) {
		if (tex == null || tex.isBlank()) {
			return Result.failed("公式不能为空", MathProblem.of(MathProblem.Kind.EMPTY));
		}
		if (!Float.isFinite(pixelSize) || pixelSize <= 0) {
			return Result.failed("字号必须是有限正数", new MathProblem(MathProblem.Kind.SYNTAX, "pixelSize"));
		}
		if (tex.length() > MAX_TEX_CHARS) {
			return Result.failed("公式太长（超过 " + MAX_TEX_CHARS + " 字）",
					new MathProblem(MathProblem.Kind.TOO_LONG, String.valueOf(MAX_TEX_CHARS)));
		}
		// Definitions are handled per note by TexMacros. One that is still here could not be read: refuse it, so
		// JLaTeXMath never stores a macro in its game-wide table (it would leak into every other note).
		if (TexMacros.containsDefinition(tex)) {
			return Result.failed("无法读取的宏定义", MathProblem.of(MathProblem.Kind.BAD_DEFINITION));
		}
		// JLaTeXMath 的字体缓存和宏表是共享的，解析和绘制都放在锁内。
		synchronized (RENDER_LOCK) {
			try {
				var formula = new TeXFormula();
				new StrictParser(tex, formula).parse();
				var icon = formula.createTeXIcon(
						display ? TeXConstants.STYLE_DISPLAY : TeXConstants.STYLE_TEXT, pixelSize);
				icon.setForeground(new Color(color, true));
				int width = Math.addExact(icon.getIconWidth(), 2);
				int height = Math.addExact(icon.getIconHeight(), 2);
				if (width > MAX_SIDE || height > MAX_SIDE || (long) width * height > MAX_PIXELS) {
					return Result.failed("公式太大（" + width + "×" + height + " 像素）",
							new MathProblem(MathProblem.Kind.TOO_BIG, width + "×" + height));
				}
				var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
				var graphics = image.createGraphics();
				try {
					// 1.0.7 会检查 null；TeXIcon 本身不是 Swing 组件。
					icon.paintIcon(null, graphics, 1, 1);
				} finally {
					graphics.dispose();
				}
				return new Result(new Raster(width, height,
						image.getRGB(0, 0, width, height, null, 0, width)),
						Math.clamp((long) icon.getIconDepth() + 1, 0, height), null, null);
			} catch (RuntimeException e) {
				String message = e.getMessage();
				message = message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
				return Result.failed(message, problemOf(e, message));
			}
		}
	}

	private static final String UNKNOWN_PREFIX = "Unknown symbol or command or predefined TeXFormula: '";

	/** JLaTeXMath's exception → MathProblem (its messages are English free text). */
	static MathProblem problemOf(RuntimeException e, String message) {
		if (e instanceof MissingArgumentException missing) {
			return new MathProblem(MathProblem.Kind.MISSING_ARGUMENT, String.valueOf(missing.index));
		}
		if (message.startsWith(UNKNOWN_PREFIX)) {
			String name = message.substring(UNKNOWN_PREFIX.length());
			int quote = name.indexOf('\'');
			return new MathProblem(MathProblem.Kind.UNKNOWN_COMMAND, "\\" + (quote >= 0 ? name.substring(0, quote) : name));
		}
		// Some messages span several lines ("Problem with command ...\nCommand R is not defined"): keep them short
		String oneLine = message.strip().replaceAll("\\s*\\R\\s*", " — ");
		return new MathProblem(MathProblem.Kind.SYNTAX, oneLine.length() > 120 ? oneLine.substring(0, 119) + "…" : oneLine);
	}

	private static final class MissingArgumentException extends ParseException {
		private final int index;

		private MissingArgumentException(int index) {
			super("缺少必需参数 " + index);
			this.index = index;
		}
	}

	private static final class StrictParser extends TeXParser {
		private StrictParser(String tex, TeXFormula formula) {
			super(tex, formula);
		}

		@Override
		public String[] getOptsArgs(int count, int optionPosition) {
			String[] args = super.getOptsArgs(count, optionPosition);
			// 1.0.7 将缺失参数返回为 null，显式的空参数 {} 则是空串。
			for (int i = 1; i <= count; i++) {
				if (args[i] == null) {
					throw new MissingArgumentException(i);
				}
			}
			return args;
		}
	}
}
