package com.bleach.mod.client;

import java.util.List;

import com.bleach.mod.attachment.SpiritualData;
import com.bleach.mod.network.SpiritualSyncPayload;
import com.bleach.mod.tuning.BleachTuning;

import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;

/**
 * The SP bar: a second experience bar, sitting directly above the real one.
 *
 * <p>It borrows vanilla's own {@code hud/experience_bar_background} sprite for the frame, so it is
 * pixel-identical to the XP bar at every GUI scale without shipping a texture. Only the fill is
 * ours, because the fill is the one part that has to carry a state colour rather than XP green.
 *
 * <h2>The layout, and why it is derived rather than typed in</h2>
 *
 * <p>Vanilla's bottom-centre stack is fixed and tight, and every number below was read out of
 * {@code Gui}'s own bytecode rather than remembered:
 *
 * <pre>
 *   status rows (health left, food right)   H-39 .. H-30   anchor H-39, 9px icons, 10px pitch
 *   experience bar                          H-29 .. H-24
 *   hotbar                                  H-22 .. H
 * </pre>
 *
 * <p>There is <b>one pixel</b> between the status rows and the XP bar. A 5-pixel bar cannot be put
 * there, so {@code GuiHealthMixin} lifts the whole vanilla block by one row pitch and this bar goes
 * in the vacated band:
 *
 * <pre>
 *   status rows, lifted                     H-49 .. H-40
 *   SP bar                                  H-38 .. H-33   (2px under the rows, 4px over the XP bar)
 *   experience bar                          H-29 .. H-24
 * </pre>
 *
 * <p><b>The position is computed from those vanilla constants, not from a hand-tuned offset.</b>
 * It used to be a raw {@code HUD_BOTTOM_OFFSET} distance from the bottom of the screen, which is a
 * number with no relationship to anything it has to avoid — set to 49 it landed in the middle of
 * the hearts, set to 28 it landed on top of the XP bar, and nothing in the code could tell either
 * was wrong. Deriving it means the bar tracks the row lift automatically and the two can never
 * disagree. {@link BleachTuning#HUD_BOTTOM_OFFSET} survives as an explicit override for anyone who
 * wants the bar somewhere else; at its default of {@code 0} the layout above is used.
 *
 * <p>The SP figure sits on the bar's right flank and the Soul Level on its left, clear of the
 * 182-pixel span the vanilla rows occupy, so neither number can collide with a heart or a drumstick
 * however many rows vanilla decides to draw. Both are then clamped on screen · {@link #flankLeft}:
 * the bar is vanilla's fixed 182 pixels and cannot shrink, so at a high GUI scale or in a narrow
 * window the flanks run out of room and a figure walks off the edge.
 *
 * <p>SPX awards rise off the left flank as {@code +30 SPX} · {@link SpxGainPopups}.
 */
public final class SpiritualHud {
	private SpiritualHud() {
	}

	/** Vanilla's XP bar sprite. Reused, not copied — no asset ships with this mod. */
	private static final ResourceLocation BAR_BACKGROUND =
			ResourceLocation.withDefaultNamespace("hud/experience_bar_background");

	/** Vanilla XP bar dimensions, from {@code Gui#renderExperienceBar}. */
	private static final int BAR_WIDTH = 182;
	private static final int BAR_HEIGHT = 5;
	/** Half the hotbar width — the anchor every centred status element is laid out from. */
	private static final int HOTBAR_HALF_WIDTH = 91;

	/** {@code screenHeight - 39}: the top of vanilla's health/food row · {@code Gui#renderPlayerHealth}. */
	private static final int VANILLA_STATUS_ROW_TOP = 39;
	/** Height of a heart, drumstick or chestplate sprite. The row extends downward from its anchor. */
	private static final int VANILLA_ICON_HEIGHT = 9;
	/** Vanilla's vertical pitch between status rows, from {@code Gui#renderPlayerHealth}. */
	private static final int ROW_PITCH = 10;
	/** {@code screenHeight - 29}: the top of the XP bar · {@code Gui#renderExperienceBar}. */
	private static final int VANILLA_XP_BAR_TOP = 29;

	/** Breathing room between the lifted vanilla rows and the top of this bar, in pixels. */
	private static final int DEFAULT_BAR_GAP = 2;

	private static final int OPAQUE = 0xFF000000;
	private static final int COLOR_TEXT_OUTLINE = 0x000000;

	/** Border alpha sweep while the exertion penalty is active, so the penalty is legible at a glance. */
	private static final int PULSE_ALPHA_MIN = 0x40;
	private static final int PULSE_ALPHA_MAX = 0xFF;
	private static final float PULSE_PERIOD_MILLIS = 900.0f;

	/**
	 * How far {@code GuiHealthMixin} lifts the vanilla status rows — <b>one full vanilla row
	 * pitch</b>, which is the same shuffle vanilla itself performs whenever it has to open a row.
	 *
	 * <p>Not the bar's own height. A heart is anchored at {@code H-39} and drawn <em>downward</em> as
	 * a 9-pixel sprite, so the health block's lowest pixel is at {@code H-30} — six pixels below its
	 * anchor. Lifting by the bar's height alone moved the hearts up without moving them out of the
	 * way, which is how the bar ended up threaded through the middle of them.
	 */
	public static float rowLift() {
		return BleachTuning.HUD_ROW_LIFT > 0 ? BleachTuning.HUD_ROW_LIFT : ROW_PITCH;
	}

	/**
	 * The bar's top edge, in screen pixels.
	 *
	 * <p>Derived from where the lifted vanilla rows actually end: their lowest pixel is
	 * {@code H - (39 - 9) - lift}, and the bar clears it by {@link BleachTuning#HUD_BAR_GAP}.
	 *
	 * <p>{@link BleachTuning#HUD_BOTTOM_OFFSET} still overrides the derivation, but no longer
	 * escapes the clamp on either side of it: the band between the lifted rows and the XP bar is the
	 * only place the bar can legally be, whoever picked the number.
	 */
	public static int barTop(int guiHeight) {
		int gap = BleachTuning.HUD_BAR_GAP > 0 ? BleachTuning.HUD_BAR_GAP : DEFAULT_BAR_GAP;
		int liftedRowsBottom = guiHeight - (VANILLA_STATUS_ROW_TOP - VANILLA_ICON_HEIGHT) - (int) rowLift();

		int top = BleachTuning.HUD_BOTTOM_OFFSET > 0
				? guiHeight - BleachTuning.HUD_BOTTOM_OFFSET
				: liftedRowsBottom + gap;

		// Both clamps apply to the override too. They used to sit after an early return, so
		// HUD_BOTTOM_OFFSET was the one input that could place the bar anywhere at all — and a stale
		// config carrying 49 put it on exactly the row rowLift() had just moved the hearts onto,
		// which is the collision this method exists to make unrepresentable. An override says where
		// the author wants the bar, not that the hearts have stopped being there.
		int bottommostAllowed = guiHeight - VANILLA_XP_BAR_TOP - BAR_HEIGHT - 1;
		// A lift smaller than the bar leaves no band at all between the rows and the XP bar. The XP
		// bar wins that argument: overlapping the hearts is ugly, overlapping the bar the whole
		// design is imitating is incoherent.
		int topmostAllowed = Math.min(liftedRowsBottom, bottommostAllowed);
		return Mth.clamp(top, topmostAllowed, bottommostAllowed);
	}

	public static void register() {
		HudRenderCallback.EVENT.register(SpiritualHud::render);
	}

	private static void render(GuiGraphics graphics, net.minecraft.client.DeltaTracker deltaTracker) {
		Minecraft client = Minecraft.getInstance();
		if (client.player == null || client.options.hideGui || client.player.isSpectator()) {
			return;
		}

		// The master switch has to reach the pixels too, or "is the mod doing this?" still can't be
		// answered by looking at the screen.
		if (!ClientSpiritualState.isEnabled()) {
			return;
		}

		SpiritualSyncPayload state = ClientSpiritualState.get();
		if (state == null || state.maxSp() <= 0.0f) {
			return;
		}

		int left = graphics.guiWidth() / 2 - HOTBAR_HALF_WIDTH;
		int top = barTop(graphics.guiHeight());

		// Everything below is lifted as one unit — frame, fill, pulse border and both numbers.
		// HUD_Z_DEPTH stays at 0 so the bar renders on the same flat plane as the rest of the HUD
		// and cannot punch through chat or the action bar · new_demands §2.
		graphics.pose().pushPose();
		graphics.pose().translate(0.0f, 0.0f, (float) BleachTuning.HUD_Z_DEPTH);

		graphics.blitSprite(BAR_BACKGROUND, left, top, BAR_WIDTH, BAR_HEIGHT);

		// Inset by one pixel on every side so the sprite's own outline stays visible around the fill,
		// which is what makes it read as the same bar rather than a coloured rectangle.
		int innerWidth = BAR_WIDTH - 2;
		float fraction = Mth.clamp(state.sp() / state.maxSp(), 0.0f, 1.0f);
		int filled = Math.round(innerWidth * fraction);

		if (filled > 0) {
			graphics.fill(left + 1, top + 1, left + 1 + filled, top + BAR_HEIGHT - 1,
					OPAQUE | stateColor(state.state()));
		}

		if (state.regenMult() < 1.0f) {
			drawBorder(graphics, left, top, left + BAR_WIDTH, top + BAR_HEIGHT, pulseColor(state));
		}

		drawNumbers(graphics, client.font, state, left, top);
		drawSpxGains(graphics, client.font, left, top);

		graphics.pose().popPose();
	}

	/**
	 * The two figures: current SP on the right flank of the bar, Soul Level on its left.
	 *
	 * <p>Both sit outside the bar's 182-pixel span, which is the same span the vanilla health and
	 * food rows occupy — so no matter how many rows vanilla ends up drawing, neither number can land
	 * on a heart. Vertically they are centred on the bar rather than floated above it, for the same
	 * reason: anything above the bar is in the lifted rows' band.
	 *
	 * <p>Both are outlined rather than shadowed. A drop shadow is one pixel of contrast in one
	 * direction, which is enough over the dark hotbar and not nearly enough over a bright sky, a
	 * snowfield or the bar's own fill. Four black passes and one coloured one is what vanilla does
	 * for the experience level, for the same reason, and it is legible on anything.
	 */
	private static void drawNumbers(GuiGraphics graphics, Font font, SpiritualSyncPayload state,
			int left, int top) {
		// Centre the 9-pixel glyph box on the 5-pixel bar.
		int textY = top - (font.lineHeight - BAR_HEIGHT) / 2;
		int gap = BleachTuning.HUD_LEVEL_TEXT_GAP;

		String sp = String.valueOf(Math.round(state.sp()));
		outlined(graphics, font, sp, flankRight(graphics, font, sp, left, gap), textY,
				stateColor(state.state()));

		String level = String.valueOf(state.soulLevel());
		outlined(graphics, font, level, flankLeft(font, level, left, gap), textY,
				BleachTuning.HUD_COLOR_LEVEL_TEXT);
	}

	/**
	 * X for the figure on the bar's left flank, kept on screen.
	 *
	 * <p>The bar is vanilla's own width and cannot shrink, so as the GUI width falls toward 182 the
	 * flanks run out of room and the figure walks off the edge — which is what was clipping the Soul
	 * Level at high GUI scales and in narrow windows. Clamping trades the gap for legibility: the
	 * number closes on the bar's end and, in the worst case, touches it. That is a far better failure
	 * than half a digit, and it only ever happens where there was no space to be had.
	 *
	 * <p>The floor is 1 rather than 0 because {@link #outlined} paints a pass at {@code x - 1}.
	 */
	private static int flankLeft(Font font, String text, int left, int gap) {
		return Math.max(1, left - gap - font.width(text));
	}

	/** X for the figure on the bar's right flank, kept on screen. Mirror of {@link #flankLeft}. */
	private static int flankRight(GuiGraphics graphics, Font font, String text, int left, int gap) {
		int preferred = left + BAR_WIDTH + gap;
		int rightmost = graphics.guiWidth() - 1 - font.width(text);
		return Math.max(1, Math.min(preferred, rightmost));
	}

	/**
	 * The {@code +30 SPX} figures, rising off the bar's left flank and fading out.
	 *
	 * <p>They go on the <b>left</b>, above the Soul Level, because that is what SPX is buying — the
	 * award and the number it counts toward read as one column. The right flank is SP, which moves for
	 * entirely different reasons, and a figure climbing out of it would suggest the pool had grown.
	 *
	 * <p>The rise eases out, so a figure covers most of its travel while it is still fully opaque and
	 * drifts the last few pixels as it goes. Linear motion under a linear fade reads as a sprite being
	 * dragged; this reads as something thrown.
	 *
	 * <p>Nothing here is clipped to the bar's own span, so the stack is free to climb into the band
	 * above it. That band holds the lifted vanilla rows, but only across the bar's 182 pixels — the
	 * flank above the Soul Level is empty at every row count vanilla draws.
	 */
	private static void drawSpxGains(GuiGraphics graphics, Font font, int left, int top) {
		long now = System.currentTimeMillis();
		List<SpxGainPopups.Popup> popups = SpxGainPopups.active(now);
		if (popups.isEmpty()) {
			return;
		}

		double life = Math.max(1.0, BleachTuning.HUD_SPX_GAIN_DURATION_MILLIS);
		float hold = (float) Mth.clamp(BleachTuning.HUD_SPX_GAIN_HOLD, 0.0, 0.99);
		int gap = BleachTuning.HUD_LEVEL_TEXT_GAP;

		// Newest nearest the bar: it is the one being read, and it should not have to climb past the
		// older figures to be seen.
		for (int i = popups.size() - 1; i >= 0; i--) {
			SpxGainPopups.Popup popup = popups.get(i);
			float age = (float) Mth.clamp((now - popup.bornMillis()) / life, 0.0, 1.0);

			// Ease out: fast off the mark, settling as it fades.
			float eased = 1.0f - (1.0f - age) * (1.0f - age);
			int stack = (popups.size() - 1 - i) * BleachTuning.HUD_SPX_GAIN_STACK_PX;

			int y = top - (font.lineHeight - BAR_HEIGHT) / 2
					- Math.round(eased * BleachTuning.HUD_SPX_GAIN_RISE_PX)
					- BleachTuning.HUD_SPX_GAIN_STACK_PX - stack;

			int alpha = age <= hold
					? 0xFF
					: Math.round(0xFF * (1.0f - (age - hold) / (1.0f - hold)));

			String text = "+" + popup.amount() + " SPX";
			outlined(graphics, font, text, flankLeft(font, text, left, gap), y,
					BleachTuning.HUD_COLOR_SPX_GAIN, alpha);
		}
	}

	private static void outlined(GuiGraphics graphics, Font font, String text, int x, int y, int color) {
		outlined(graphics, font, text, x, y, color, 0xFF);
	}

	/**
	 * The same outline at a given alpha, so a fading popup fades its outline with it. An outline left
	 * opaque under fading text does not read as one thing disappearing; it reads as the text turning
	 * black.
	 */
	private static void outlined(GuiGraphics graphics, Font font, String text, int x, int y,
			int color, int alpha) {
		if (alpha <= 4) {
			return;
		}
		int outline = (alpha << 24) | COLOR_TEXT_OUTLINE;
		graphics.drawString(font, text, x + 1, y, outline, false);
		graphics.drawString(font, text, x - 1, y, outline, false);
		graphics.drawString(font, text, x, y + 1, outline, false);
		graphics.drawString(font, text, x, y - 1, outline, false);
		graphics.drawString(font, text, x, y, (alpha << 24) | (color & 0xFFFFFF), false);
	}

	private static int stateColor(byte state) {
		return switch (state) {
			case SpiritualData.STATE_BANKAI -> BleachTuning.HUD_COLOR_BANKAI;
			case SpiritualData.STATE_SHIKAI -> BleachTuning.HUD_COLOR_SHIKAI;
			default -> BleachTuning.HUD_COLOR_BASE;
		};
	}

	private static int pulseColor(SpiritualSyncPayload state) {
		float phase = (System.currentTimeMillis() % (long) PULSE_PERIOD_MILLIS) / PULSE_PERIOD_MILLIS;
		float wave = 0.5f + 0.5f * Mth.sin(phase * Mth.TWO_PI);
		int alpha = (int) Mth.lerp(wave, PULSE_ALPHA_MIN, PULSE_ALPHA_MAX);
		return (alpha << 24) | stateColor(state.state());
	}

	private static void drawBorder(GuiGraphics graphics, int left, int top, int right, int bottom, int color) {
		graphics.fill(left, top, right, top + 1, color);
		graphics.fill(left, bottom - 1, right, bottom, color);
		graphics.fill(left, top, left + 1, bottom, color);
		graphics.fill(right - 1, top, right, bottom, color);
	}
}
