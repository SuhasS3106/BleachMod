package com.bleach.mod.command;

import java.util.List;
import java.util.Locale;

import com.bleach.mod.ability.common.FlashStep;
import com.bleach.mod.ability.common.Hover;
import com.bleach.mod.ability.common.SpiritualFlex;
import com.bleach.mod.attachment.BleachAttachments;
import com.bleach.mod.attachment.SpiritualData;
import com.bleach.mod.tuning.BleachTuning;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.server.level.ServerPlayer;

/**
 * The in-game manual · {@code /bleach guide}.
 *
 * <p>Everything this mod does is invisible until somebody tells you about it. There is no recipe
 * book entry for a keybind, no advancement tree for a resource pool, and nothing in the F3 screen
 * that mentions Soul Level — so a player who joins a server running this has a sword they cannot
 * unsheathe and a bar they cannot explain. That is what this is for, and it is why it needs no
 * operator permission: a manual an ordinary player cannot open is not a manual.
 *
 * <p>Written against {@link BleachTuning} rather than against prose. Every number quoted below is
 * read at print time, so a server that has retuned its config gets a guide describing <em>its</em>
 * numbers, and {@code /bleach reload} moves the documentation along with the balance.
 */
public final class BleachGuide {
	private BleachGuide() {
	}

	private static final ChatFormatting HEAD = ChatFormatting.GOLD;
	private static final ChatFormatting KEY = ChatFormatting.AQUA;
	private static final ChatFormatting BODY = ChatFormatting.GRAY;
	private static final ChatFormatting NOTE = ChatFormatting.DARK_GRAY;

	/** Every topic id, in index order. Read by the command's tab completion. */
	public static List<String> topics() {
		return List.of("basics", "keys", "pressure", "level", "flashstep", "hover", "flex",
				"ichigo", "yamamoto", "suifeng", "rukia", "shinji");
	}

	// --- Entry points -------------------------------------------------------------------

	public static int index(CommandSourceStack source) {
		source.sendSuccess(() -> build(index()), false);
		return 1;
	}

	public static int topic(CommandSourceStack source, String topic) {
		String key = topic.toLowerCase(Locale.ROOT);
		SpiritualData data = source.getEntity() instanceof ServerPlayer player
				? BleachAttachments.get(player) : new SpiritualData();

		List<Component> lines = switch (key) {
			case "basics" -> basics();
			case "keys" -> keys();
			case "pressure" -> pressure(data);
			case "level" -> level();
			case "flashstep" -> flashStep(data);
			case "hover" -> hover(data);
			case "flex" -> flex(data);
			case "ichigo" -> ichigo();
			case "yamamoto" -> yamamoto();
			case "suifeng" -> suifeng();
			case "rukia" -> rukia();
			case "shinji" -> shinji();
			default -> null;
		};

		if (lines == null) {
			source.sendFailure(Component.literal("No guide topic named \"" + topic
					+ "\". Try /bleach guide for the index."));
			return 0;
		}

		List<Component> body = lines;
		source.sendSuccess(() -> build(body), false);
		return 1;
	}

	/** The one-line nudge a player gets on their first join, so the guide is discoverable at all. */
	public static Component welcome() {
		return Component.empty()
				.append(Component.literal("Bleach").withStyle(HEAD))
				.append(Component.literal(" — press ").withStyle(BODY))
				.append(Component.literal("K").withStyle(KEY))
				.append(Component.literal(" for your soul stats, or read ").withStyle(BODY))
				.append(link("/bleach guide", "/bleach guide"))
				.append(Component.literal(".").withStyle(BODY));
	}

	// --- Topics -------------------------------------------------------------------------

	private static List<Component> index() {
		return List.of(
				head("The Bleach mod"),
				body("You are a soul reaper. You have a blade, a pool of spiritual pressure, and a"),
				body("level that grows by killing things. Pick a topic:"),
				Component.empty(),
				topicLink("basics", "getting started — Asauchi, choosing a character"),
				topicLink("keys", "every key the mod uses"),
				topicLink("pressure", "spiritual pressure, exertion, Shikai and Bankai"),
				topicLink("level", "Soul Level, Soul Points, the World Soul Level"),
				topicLink("flashstep", "Flash Step"),
				topicLink("hover", "Hover"),
				topicLink("flex", "Spiritual Flex"),
				Component.empty(),
				body("Characters:"),
				topicLink("ichigo", "Ichigo Kurosaki — reach, cleave, raw speed"),
				topicLink("yamamoto", "Genryūsai Yamamoto — fire"),
				topicLink("suifeng", "Suì-Fēng — assassination and one missile"),
				topicLink("rukia", "Rukia Kuchiki — ice"),
				topicLink("shinji", "Shinji Hirako — inversion"));
	}

	private static List<Component> basics() {
		return List.of(
				head("Getting started"),
				body("You start with an "),
				bullet("Asauchi", "a blank blade. Right-click it to open the picker and choose"),
				plain("  one of the five characters. The choice is permanent — an operator can undo it"),
				plain("  with a Reforged Asauchi, and nothing else can."),
				Component.empty(),
				bullet("Your zanpakutō", "cannot be dropped, cannot be put in a chest, and is not"),
				plain("  lost on death. If it ever goes missing it is minted again on your next"),
				plain("  respawn, login, or press of the draw key."),
				Component.empty(),
				bullet("Drawn vs sheathed", "matters. Offensive techniques — everything a character"),
				plain("  has — need the blade in your main hand. Flash Step and Flex do not: they are"),
				plain("  raw pressure, not techniques of the sword."),
				Component.empty(),
				note("You never need operator permission to play. Only /bleach's admin commands do."));
	}

	private static List<Component> keys() {
		return List.of(
				head("Controls"),
				key("V", "Flash Step — blink toward where you are looking"),
				key("R", "Shikai — toggle your first released state"),
				key("G", "Bankai — toggle your second released state"),
				key("X", "Draw / sheathe your zanpakutō"),
				key("Left Alt", "Spiritual Flex — hold to project pressure"),
				key("C", "Aura Sense — hold to read the souls around you"),
				key("Q", "Hover — hold in the air to stop falling"),
				key("K", "Soul stats screen"),
				key("B", "Master on/off switch (operators only)"),
				Component.empty(),
				note("All of these are rebindable in Options → Controls → Bleach."));
	}

	private static List<Component> pressure(SpiritualData data) {
		return List.of(
				head("Spiritual pressure"),
				body("The blue bar above your hotbar. It is your fuel for everything."),
				Component.empty(),
				bullet("Max", fmt(data.maxSp()) + " at Soul Level " + data.soulLevel
						+ " (+" + fmt(BleachTuning.SP_MAX_PER_LEVEL) + " per level)"),
				bullet("Regen", fmt(data.regenPerSecond()) + "/s, paused for "
						+ secs(BleachTuning.SP_REGEN_PAUSE_TICKS) + "s after any spend or damage"),
				Component.empty(),
				head("Shikai and Bankai"),
				bullet("Shikai", "needs " + pct(SpiritualData.gatePercent(SpiritualData.STATE_SHIKAI, data.soulLevel))
						+ " of your pool to enter, then drains " + fmt(BleachTuning.DRAIN_SHIKAI) + "/s"),
				bullet("Bankai", "needs " + pct(SpiritualData.gatePercent(SpiritualData.STATE_BANKAI, data.soulLevel))
						+ ", refills you to full on entry, then drains " + fmt(BleachTuning.DRAIN_BANKAI) + "/s"),
				plain("  That refill is a loan. When you drop out of Bankai anything above what you"),
				plain("  entered with is taken straight back — you cannot bank it."),
				Component.empty(),
				head("Exertion"),
				body("Time spent released builds exertion, which slows your regeneration. It clears"),
				body("only when your pool is completely full, and when you respawn. Staying in Bankai"),
				body("is cheap in the moment and expensive for the next several minutes."),
				Component.empty(),
				note("Both states end instantly if you sheathe, die, change dimension, or run dry."));
	}

	private static List<Component> level() {
		return List.of(
				head("Soul Level"),
				body("1 to " + BleachTuning.SL_MAX + ". Raises your maximum pressure, your regeneration,"),
				body("your health (+" + fmt(BleachTuning.SL_HP_PER_TWO_LEVELS) + " per two levels), the damage"),
				body("your zanpakutō deals, and the damage you shrug off."),
				Component.empty(),
				head("Soul Points"),
				body("Earned by killing. Mobs are worth what their difficulty says; a player is worth"),
				body(BleachTuning.SPX_PLAYER_BASE + " plus " + BleachTuning.SPX_PLAYER_PER_LEVEL_GAP
						+ " for every level they had on you."),
				body("There is a daily cap (" + BleachTuning.SPX_DAILY_CAP_BASE + " base) so nobody can"),
				body("grind the whole ladder in one sitting."),
				Component.empty(),
				head("World Soul Level"),
				body("The server's average, weighted by playtime. Two things hang off it: mobs scale"),
				body("with it, and players below it earn faster — up to "
						+ fmt(BleachTuning.CATCHUP_MAX) + "× — so joining late is"),
				body("a delay, not a permanent disadvantage."),
				Component.empty(),
				note("Only your zanpakutō and your techniques scale. A bow is a bow at every level."));
	}

	private static List<Component> flashStep(SpiritualData data) {
		return List.of(
				head("Flash Step — V"),
				body("A blink along your look vector. No sword needed."),
				Component.empty(),
				bullet("Range now", fmt(FlashStep.range(data)) + " blocks"),
				bullet("Cost", pct(BleachTuning.FS_COST_PCT) + " of your maximum pressure"),
				bullet("Cooldown", secs(BleachTuning.FS_COOLDOWN_TICKS) + "s"),
				Component.empty(),
				body("Range scales on both Soul Level and how full your pool is right now, so it"),
				body("visibly shortens as you burn through a fight. Looking at a wall puts you just"),
				body("short of it; looking at open sky puts you out over the drop with Slow Falling."),
				body("The burst of pressure at your destination lands a beat after you do."),
				Component.empty(),
				note("Suì-Fēng steps 50% further and half as often as anyone else."));
	}

	private static List<Component> hover(SpiritualData data) {
		return List.of(
				head("Hover — hold Q in the air"),
				body("Stop falling. WASD still moves you, but along your look vector — forward is"),
				body("wherever you are pointing, so look up to climb and down to dive. There is no"),
				body("separate up or down key: the mouse is your vertical control. No sword needed."),
				Component.empty(),
				bullet("Drain", fmt(Hover.drainPerSecond(data)) + "/s — about "
						+ fmt(data.maxSp() / Math.max(0.01, Hover.drainPerSecond(data)))
						+ "s on a full pool"),
				bullet("Speed", fmt(BleachTuning.HOVER_SPEED * BleachTuning.TICKS_PER_SECOND)
						+ " blocks/s, a little under a sprint"),
				Component.empty(),
				body("It cannot be started from the ground, but the key is patient: hold it before you"),
				body("run off a ledge and it catches you as soon as you are in the air. Landing ends"),
				body("it, and so does letting go, and so does running dry — that last one needs a"),
				body("fresh press rather than starting again as your pressure trickles back."),
				Component.empty(),
				note("No fall damage while you hold it, but the fall starts fresh when you let go."));
	}

	private static List<Component> flex(SpiritualData data) {
		return List.of(
				head("Spiritual Flex — hold Left Alt"),
				body("Project raw pressure. No sword needed. Costs pressure every tick you hold it."),
				Component.empty(),
				bullet("Radius now", fmt(SpiritualFlex.radius(data)) + " blocks"),
				bullet("Drain", fmt(SpiritualFlex.drainPerSecond(data)) + "/s"),
				Component.empty(),
				body("It crushes anything whose Soul Level is below yours — slowness first, then"),
				body("slower attacks, then weaker ones, and at a gap of "
						+ BleachTuning.REIATSU_TIER_4_GAP + " it roots them outright."),
				plain("  Mobs count as level 0, so a field always works on them."),
				Component.empty(),
				body("Mobs caught in it cannot use anything but their hands — no arrows, potions,"),
				body("fireballs, wind charges, fangs or beams, no creeper fuse, no enderman blink."),
				body("Players keep their weapons: being slowed and disarmed at once leaves nothing"),
				body("to play against."),
				Component.empty(),
				bullet("Bleed", "standing in a field costs "
						+ fmt(BleachTuning.REIATSU_DAMAGE_BASE) + " health per second at a gap of "
						+ BleachTuning.REIATSU_DAMAGE_MIN_GAP + ", +"
						+ fmt(BleachTuning.REIATSU_DAMAGE_PER_GAP) + " per level beyond it, to a cap of "
						+ fmt(BleachTuning.REIATSU_DAMAGE_MAX)),
				plain("  It kills, it pays Soul Points, and it does not spare your animals."),
				Component.empty(),
				body("Against a player within " + BleachTuning.REIATSU_TIER_1_GAP + " level of you it does"),
				body("nothing at all — that is not a bug. Someone you outrank can flex back to shave"),
				body("levels off the gap, at a steeper drain than yours."),
				Component.empty(),
				note("Your action bar reports how many targets your field is actually holding."));
	}

	private static List<Component> ichigo() {
		return List.of(
				head("Ichigo Kurosaki"),
				kitLine("Shikai — Zangetsu"),
				bullet("Reach", "+" + fmt(BleachTuning.ICHIGO_SHIKAI_REACH) + " blocks"),
				bullet("Damage", "+" + pct(BleachTuning.ICHIGO_SHIKAI_DMG) + " with the blade"),
				bullet("Cleave", "every swing also hits everything within "
						+ fmt(BleachTuning.ICHIGO_SHIKAI_CLEAVE_ARC) + "° in front of you"),
				plain("  for " + pct(BleachTuning.ICHIGO_SHIKAI_CLEAVE_PCT) + " of the damage."),
				Component.empty(),
				kitLine("Bankai — Tensa Zangetsu"),
				bullet("Speed", "+" + pct(BleachTuning.ICHIGO_BANKAI_SPEED) + " movement, +"
						+ pct(BleachTuning.ICHIGO_BANKAI_ATK_SPEED) + " attack speed"),
				bullet("Damage", "+" + pct(BleachTuning.ICHIGO_BANKAI_DMG) + " with the blade"),
				plain("  The reach bonus does not carry over — Bankai's blade is the small one."));
	}

	private static List<Component> yamamoto() {
		return List.of(
				head("Genryūsai Yamamoto"),
				body("Immune to fire and lava in both states."),
				Component.empty(),
				kitLine("Shikai — Ryūjin Jakka"),
				bullet("Ignite", "everything within " + fmt(BleachTuning.YAMA_SHIKAI_RADIUS)
						+ " blocks burns for " + secs(BleachTuning.YAMA_SHIKAI_BURN_TICKS) + "s"),
				bullet("Scorch", "water, ice and snow boil out of the ground and the surface catches,"),
				plain("  spreading outward in rings from where you are standing."),
				bullet("It follows you", "walk and the fire is laid again around your new position."),
				plain("  Nothing already burning is put out — you leave a trail."),
				Component.empty(),
				kitLine("Bankai — Zanka no Tachi"),
				bullet("Absorb", "every fire within " + fmt(BleachTuning.YAMA_BANKAI_RADIUS)
						+ " blocks is pulled into the blade and goes out,"),
				plain("  including anyone who was burning. The heat is in the sword now."),
				bullet("Damage", "+" + pct(BleachTuning.YAMA_BANKAI_DMG) + " with the blade"),
				bullet("On hit", "targets burn for " + secs(BleachTuning.YAMA_BANKAI_ONHIT_BURN_TICKS) + "s"),
				Component.empty(),
				note("Shikai sets the world alight; Bankai takes it all back. Burn a field with the"),
				note("first and release the second to clear it in one go."));
	}

	private static List<Component> suifeng() {
		return List.of(
				head("Suì-Fēng"),
				body("Flash Steps 50% further, on half the cooldown."),
				Component.empty(),
				kitLine("Shikai — Suzumebachi"),
				bullet("Nigeki Kessatsu", "your strike brands the exact spot on the body it lands on."),
				plain("  Hit that same spot a second time and the target dies outright — armour,"),
				plain("  resistance and Soul Level do not help. A Totem of Undying does."),
				plain("  The mark is a body part, not a compass direction: brand their left hand and"),
				plain("  it stays on their left hand however they turn, walk or crouch."),
				plain("  A black butterfly parks on it — chase the butterfly, not the direction."),
				plain("  Your action bar tells you how far off a missed follow-up was."),
				plain("  Striking somewhere else moves the mark rather than killing."),
				plain("  A blow that does not properly connect marks nothing at all."),
				plain("  There is no time limit; marks are wiped when you leave Shikai."),
				bullet("Cost", "a kill leaves you exhausted — regeneration crawls afterwards,"),
				plain("  and it stays slow until the bar is completely full again. Kill twice in"),
				plain("  a row and the second recovery is far longer than the first."),
				Component.empty(),
				kitLine("Bankai — Jakuhō Raikōben"),
				bullet("Wind-up", secs(BleachTuning.SUI_BANKAI_WINDUP_TICKS)
						+ "s rooted in place, ringed in gold, extremely loud."),
				plain("  You cannot cancel it once it starts."),
				bullet("Missile", "it launches where you were aiming and it can miss."),
				bullet("Blast", fmt(BleachTuning.SUI_BANKAI_DMG_INNER) + " damage inside "
						+ fmt(BleachTuning.SUI_BANKAI_LETHAL_RADIUS) + " blocks of the impact,"),
				plain("  falling to " + fmt(BleachTuning.SUI_BANKAI_DMG_OUTER) + " at "
						+ fmt(BleachTuning.SUI_BANKAI_FALLOFF_RADIUS) + " blocks. It is damage, not a"),
				plain("  death sentence — a high Soul Level survives it."),
				bullet("Cost", "half your remaining health, a crater, and your entire pool"),
				Component.empty(),
				note("There is no timer. Firing empties the bar, and the bar refills at your"),
				note("normal rate — you are ready again when it says you are. Missing costs you"),
				note("the same as hitting, so take the shot."));
	}

	private static List<Component> rukia() {
		return List.of(
				head("Rukia Kuchiki"),
				kitLine("Shikai — Sode no Shirayuki"),
				bullet("On hit", "a burst of frost within " + fmt(BleachTuning.RUKIA_SHIKAI_ONHIT_RADIUS)
						+ " blocks of what you struck:"),
				plain("  heavy slowness, freeze damage, snow on the ground, water frozen solid."),
				Component.empty(),
				kitLine("Bankai — Hakka no Togame"),
				bullet("Aura", "everything within " + fmt(BleachTuning.RUKIA_BANKAI_RADIUS)
						+ " blocks is frozen continuously"),
				bullet("Field", "snow falls, snow stacks, and every water source turns to ice"),
				bullet("It follows you", "walk and the field freezes your new ground too."),
				plain("  What you already froze stays frozen — you leave a trail."),
				Component.empty(),
				note("The ice thaws on its own. The snow does not — bring a shovel."));
	}

	private static List<Component> shinji() {
		return List.of(
				head("Shinji Hirako"),
				kitLine("Shikai — Sakanade"),
				bullet("On hit", "your target's WASD is reversed for "
						+ secs(BleachTuning.SHINJI_SHIKAI_ONHIT_DURATION) + "s"),
				Component.empty(),
				kitLine("Bankai — Sakashima Yokoshima Happō Fusagari"),
				bullet("Aura", "everything living within " + fmt(BleachTuning.SHINJI_RADIUS_BASE
						+ BleachTuning.SHINJI_RADIUS_PER_LEVEL) + "+ blocks is inverted — hostile,"),
				plain("  passive and tamed alike, with no exceptions."),
				bullet("Players", "movement reversed and the camera flipped"),
				bullet("Mobs", "they stagger backwards, and they fight each other instead of you"),
				Component.empty(),
				note("Radius grows with your Soul Level. Leaving the field wears off in about "
						+ secs(BleachTuning.SHINJI_EFFECT_DURATION_TICKS) + "s."));
	}

	// --- Formatting ---------------------------------------------------------------------

	private static Component build(List<Component> lines) {
		MutableComponent out = Component.empty();
		for (int i = 0; i < lines.size(); i++) {
			if (i > 0) {
				out.append(Component.literal("\n"));
			}
			out.append(lines.get(i));
		}
		return out;
	}

	private static Component head(String text) {
		return Component.literal("── " + text + " ──").withStyle(HEAD, ChatFormatting.BOLD);
	}

	private static Component kitLine(String text) {
		return Component.literal(text).withStyle(ChatFormatting.YELLOW);
	}

	private static Component body(String text) {
		return Component.literal(text).withStyle(BODY);
	}

	private static Component plain(String text) {
		return Component.literal(text).withStyle(BODY);
	}

	private static Component note(String text) {
		return Component.literal(text).withStyle(NOTE, ChatFormatting.ITALIC);
	}

	private static Component bullet(String label, String text) {
		return Component.empty()
				.append(Component.literal("• " + label + ": ").withStyle(KEY))
				.append(Component.literal(text).withStyle(BODY));
	}

	private static Component key(String binding, String text) {
		return Component.empty()
				.append(Component.literal("  [" + binding + "] ").withStyle(KEY))
				.append(Component.literal(text).withStyle(BODY));
	}

	private static Component topicLink(String topic, String description) {
		return Component.empty()
				.append(link("  " + topic, "/bleach guide " + topic))
				.append(Component.literal(" — " + description).withStyle(BODY));
	}

	private static Component link(String label, String command) {
		return Component.literal(label).withStyle(Style.EMPTY
				.withColor(ChatFormatting.AQUA)
				.withUnderlined(true)
				.withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, command))
				.withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
						Component.literal(command))));
	}

	private static String fmt(double value) {
		return value == Math.rint(value)
				? String.valueOf((long) value)
				: String.format(Locale.ROOT, "%.1f", value);
	}

	private static String pct(double fraction) {
		return Math.round(fraction * 100.0) + "%";
	}

	private static String secs(int ticks) {
		return fmt(ticks / BleachTuning.TICKS_PER_SECOND);
	}

}
