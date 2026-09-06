package com.bleach.mod.client;

import com.bleach.mod.network.SpiritualSyncPayload;
import com.bleach.mod.tuning.BleachTuning;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

/**
 * The stats screen · PRD §7.2. Enchanting-table proportions: a dark panel with light inset rows,
 * drawn with {@code fill} and {@code drawString} and nothing else. <b>Zero texture assets.</b>
 *
 * <p><b>It has no networking of its own.</b> Every number on it arrives in the §8 sync payload the
 * HUD is already receiving — no menu type, no container, no round-trip, and nothing to desync. That
 * is the entire reason the three derived figures were added to the payload rather than computed
 * here.
 */
public class SoulStatsScreen extends Screen {
	/** Enchanting-table proportions. Layout, not balance — these are pixel measurements. */
	private static final int PANEL_WIDTH = 176;
	private static final int PANEL_HEIGHT = 166;
	private static final int PADDING = 8;
	private static final int ROW_HEIGHT = 12;
	private static final int ROW_GAP = 2;
	private static final int TEXT_INSET = 4;
	private static final int TEXT_LIFT = 2;
	private static final int BAR_HEIGHT = 6;
	private static final int TITLE_GAP = 6;
	private static final int BORDER = 1;

	private static final int OPAQUE = 0xFF000000;
	private static final int COLOR_TITLE = 0xFFFFFFFF;
	private static final int COLOR_LABEL = 0xFFA8B3C4;
	private static final int COLOR_VALUE = 0xFFFFFFFF;
	private static final int COLOR_BAR_TRACK = 0xFF000000;

	public SoulStatsScreen() {
		super(Component.translatable("screen.bleach_mod.stats"));
	}

	/**
	 * Nothing here is a decision, so singleplayer should not pause behind it. Pausing would also
	 * freeze the very SP regen the screen is reporting, which makes the numbers a lie the moment
	 * anyone looks at them.
	 */
	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
		super.render(graphics, mouseX, mouseY, partialTick);

		SpiritualSyncPayload state = ClientSpiritualState.get();
		if (state == null || !ClientSpiritualState.isEnabled()) {
			// No payload means the mod is off or this is a vanilla server. Drawing an empty panel
			// full of zeroes would look like a bug; drawing nothing is honest.
			return;
		}

		int left = (this.width - PANEL_WIDTH) / 2;
		int top = (this.height - PANEL_HEIGHT) / 2;

		graphics.fill(left - BORDER, top - BORDER, left + PANEL_WIDTH + BORDER, top + PANEL_HEIGHT + BORDER,
				OPAQUE | BleachTuning.STATS_COLOR_ACCENT);
		graphics.fill(left, top, left + PANEL_WIDTH, top + PANEL_HEIGHT,
				OPAQUE | BleachTuning.STATS_COLOR_PANEL);

		int y = top + PADDING;
		graphics.drawCenteredString(this.font, this.title, left + PANEL_WIDTH / 2, y, COLOR_TITLE);
		y += this.font.lineHeight + TITLE_GAP;

		y = row(graphics, left, y, "screen.bleach_mod.stats.wsl", format(state.worldSoulLevel()));
		y = row(graphics, left, y, "screen.bleach_mod.stats.soul_level",
				state.soulLevel() + " / " + BleachTuning.SL_MAX);

		// At the cap spxToNext is zero, which would render as "0 / 0" above a full bar that means
		// nothing. Say so instead.
		boolean capped = state.spxToNext() <= 0;
		y = row(graphics, left, y, "screen.bleach_mod.stats.spx", capped
				? Component.translatable("screen.bleach_mod.stats.capped").getString()
				: state.spx() + " / " + state.spxToNext());

		float progress = capped ? 1.0f : Mth.clamp((float) state.spx() / state.spxToNext(), 0.0f, 1.0f);
		y = progressBar(graphics, left, y, progress);

		y = row(graphics, left, y, "screen.bleach_mod.stats.remaining",
				String.valueOf(state.spxRemainingToday()));
		y = row(graphics, left, y, "screen.bleach_mod.stats.catch_up", multiplier(state.catchUp()));
		y = row(graphics, left, y, "screen.bleach_mod.stats.mob_scalar", multiplier(state.mobScalar()));
		row(graphics, left, y, "screen.bleach_mod.stats.regen", multiplier(state.regenMult()));
	}

	/** One inset row: translated label on the left, value right-aligned. Returns the next y. */
	private int row(GuiGraphics graphics, int left, int y, String labelKey, String value) {
		int rowLeft = left + PADDING;
		int rowRight = left + PANEL_WIDTH - PADDING;

		graphics.fill(rowLeft, y, rowRight, y + ROW_HEIGHT, OPAQUE | BleachTuning.STATS_COLOR_ROW);

		int textY = y + (ROW_HEIGHT - this.font.lineHeight) / 2 + TEXT_LIFT;
		graphics.drawString(this.font, Component.translatable(labelKey),
				rowLeft + TEXT_INSET, textY, COLOR_LABEL, false);
		graphics.drawString(this.font, value,
				rowRight - TEXT_INSET - this.font.width(value), textY, COLOR_VALUE, false);

		return y + ROW_HEIGHT + ROW_GAP;
	}

	private int progressBar(GuiGraphics graphics, int left, int y, float progress) {
		int barLeft = left + PADDING;
		int barRight = left + PANEL_WIDTH - PADDING;

		graphics.fill(barLeft, y, barRight, y + BAR_HEIGHT, COLOR_BAR_TRACK);

		int filled = Math.round((barRight - barLeft) * progress);
		if (filled > 0) {
			graphics.fill(barLeft, y, barLeft + filled, y + BAR_HEIGHT,
					OPAQUE | BleachTuning.STATS_COLOR_ACCENT);
		}

		return y + BAR_HEIGHT + ROW_GAP + ROW_GAP;
	}

	private static String format(float value) {
		return String.format("%.2f", value);
	}

	private static String multiplier(float value) {
		return "×" + format(value);
	}
}
