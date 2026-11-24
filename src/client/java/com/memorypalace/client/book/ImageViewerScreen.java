package com.memorypalace.client.book;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import com.memorypalace.client.render.RasterTextures;

import java.util.function.Supplier;

/**
 * A picture from a note on its own, as big as the window allows (PDF pages become readable this way). Scroll to
 * zoom in at the mouse, drag to move around, double-click to fit again, Esc to go back to the book.
 */
public class ImageViewerScreen extends Screen {
	private static final float MAX_ZOOM = 8.0F;

	private final Screen parent;
	// looked up every frame: the cache may drop and fetch the picture again while it is open
	private final Supplier<RasterTextures.Tex> texture;
	private int rasterW = 1;
	private int rasterH = 1;
	private final String name;

	private float zoom = 1.0F; // 1 = fits the window
	private float panX;
	private float panY;

	public ImageViewerScreen(Screen parent, Supplier<RasterTextures.Tex> texture, String name) {
		super(Component.literal(name == null ? "" : name));
		this.parent = parent;
		this.texture = texture;
		this.name = name == null ? "" : name;
	}

	/** GUI units per texture pixel when the picture just fits the window. */
	private float fitScale() {
		return Math.min((this.width - 20F) / rasterW, (this.height - 34F) / rasterH);
	}

	private float scale() {
		return fitScale() * zoom;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
		RasterTextures.Tex tex = texture.get();
		if (tex == null) {
			graphics.centeredText(this.font, Component.translatable("screen.memorypalace.image_loading"), this.width / 2,
					this.height / 2, 0xFFB0B0B0);
			return;
		}
		rasterW = tex.rasterW();
		rasterH = tex.rasterH();
		float s = scale();
		float w = rasterW * s;
		float h = rasterH * s;
		float x = (this.width - w) / 2 + panX;
		float y = (this.height - 14 - h) / 2 + panY;
		graphics.pose().pushMatrix();
		graphics.pose().translate(x, y);
		graphics.pose().scale(s, s);
		graphics.blit(RenderPipelines.GUI_TEXTURED, tex.id(), 0, 0, 0.0F, 0.0F, rasterW, rasterH, rasterW, rasterH);
		graphics.pose().popMatrix();
		String file = this.font.plainSubstrByWidth(name, this.width / 2);
		graphics.text(this.font, file, 6, this.height - 12, 0xFFB0B0B0, false);
		Component hint = Component.translatable("screen.memorypalace.viewer_hint");
		graphics.text(this.font, hint, this.width - 6 - this.font.width(hint), this.height - 12, 0xFFB0B0B0, false);
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		graphics.fill(0, 0, this.width, this.height, 0xF0101010);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		if (scrollY == 0) {
			return false;
		}
		float before = scale();
		zoom = Math.clamp(zoom * (scrollY > 0 ? 1.25F : 0.8F), 1.0F, MAX_ZOOM);
		float ratio = scale() / before;
		// keep the point under the mouse where it is
		float cx = (float) mouseX - this.width / 2.0F;
		float cy = (float) mouseY - (this.height - 14) / 2.0F;
		panX = cx - (cx - panX) * ratio;
		panY = cy - (cy - panY) * ratio;
		if (zoom == 1.0F) {
			panX = 0;
			panY = 0;
		}
		return true;
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
		panX += (float) dx;
		panY += (float) dy;
		return true;
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (doubleClick) {
			zoom = 1.0F;
			panX = 0;
			panY = 0;
			return true;
		}
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public void onClose() {
		this.minecraft.gui.setScreen(parent);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
