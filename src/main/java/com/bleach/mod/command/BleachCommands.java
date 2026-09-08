package com.bleach.mod.command;

import java.util.List;

import com.bleach.mod.BleachMod;
import com.bleach.mod.ModToggle;
import com.bleach.mod.ability.Ability;
import com.bleach.mod.ability.AbilityCooldowns;
import com.bleach.mod.ability.AbilityRegistry;
import com.bleach.mod.ability.Kit;
import com.bleach.mod.ability.kits.BleachKits;
import com.bleach.mod.ability.common.Blut;
import com.bleach.mod.ability.common.FlashStep;
import com.bleach.mod.ability.common.SpiritualFlex;
import com.bleach.mod.attachment.BleachAttachments;
import com.bleach.mod.attachment.SpiritualData;
import com.bleach.mod.attachment.SpiritualTicker;
import com.bleach.mod.effect.ReiatsuEffect;
import com.bleach.mod.damage.BleachDamage;
import com.bleach.mod.item.BleachItems;
import com.bleach.mod.item.SpiritWeapon;
import com.bleach.mod.progression.SoulLevel;
import com.bleach.mod.race.Races;
import com.bleach.mod.progression.WorldSoulLevel;
import com.bleach.mod.tuning.BleachTuning;
import com.bleach.mod.util.BlockQueue;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.Arrow;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * The {@code /bleach} tree.
 *
 * <p>Two halves with two different audiences. {@code guide} and {@code help} are player-facing and
 * open to everyone — see {@link BleachGuide}. Everything else is operator debug tooling, gated at
 * {@link #PERMISSION_LEVEL}: not content, but the reason the resource can be driven to any state
 * without waiting out a regen curve, which is what makes any of this testable at all.
 */
public final class BleachCommands {
	private BleachCommands() {
	}

	private static final int PERMISSION_LEVEL = 2;
	private static final String VALUE_ARG = "value";
	private static final String TOPIC_ARG = "topic";

	/** The fraction of max SP the claw-back test starts from — PRD §1.4's worked example is 96/100. */
	private static final double DEFAULT_CLAWBACK_PCT = 0.96;
	/** Floating-point slack when comparing SP before and after. Well under a tick of regen. */
	private static final double CLAWBACK_EPSILON = 1.0e-6;

	/** Any value below the WSL floor releases the pin. See {@code WorldSoulLevel#setOverride}. */
	private static final double WSL_RELEASE_OVERRIDE = 0.0;

	/** Guide topics, for {@code /bleach guide }. */
	private static final SuggestionProvider<CommandSourceStack> TOPIC_SUGGESTIONS = (ctx, builder) ->
			SharedSuggestionProvider.suggest(BleachGuide.topics(), builder);

	/** Registered kits by path, so {@code /bleach kit set } completes without anyone typing a namespace. */
	private static final SuggestionProvider<CommandSourceStack> KIT_SUGGESTIONS = (ctx, builder) ->
			SharedSuggestionProvider.suggest(
					AbilityRegistry.kits().stream().map(kit -> kit.id().getPath()), builder);

	public static void register() {
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
				dispatcher.register(root()));
	}

	/**
	 * Every debug subcommand, gated at operator level.
	 *
	 * <p>The gate used to sit on the root, which meant a player who could not run {@code /bleach}
	 * could not read {@code /bleach guide} either — and the only way to let somebody look up what
	 * the keybinds were was to hand them operator. That is a lot of trust to buy a help page. The
	 * gate is per-branch now, and the two read-only branches below it are open to everyone.
	 */
	/** Day-time tick for full noon, and for full midnight — the two ends of the sky-light sweep. */
	private static final long NOON_TICKS = 6000L;
	private static final long MIDNIGHT_TICKS = 18000L;

	/** Tolerance for the reishi multiplier comparison; it is arithmetic on doubles, not a measurement. */
	private static final double REISHI_EPSILON = 1.0e-9;

	private static LiteralArgumentBuilder<CommandSourceStack> admin(String name) {
		return Commands.literal(name).requires(source -> source.hasPermission(PERMISSION_LEVEL));
	}

	private static LiteralArgumentBuilder<CommandSourceStack> root() {
		return Commands.literal("bleach")
				// Open to every player. Nothing here changes any state.
				.then(Commands.literal("guide")
						.executes(ctx -> BleachGuide.index(ctx.getSource()))
						.then(Commands.argument(TOPIC_ARG, StringArgumentType.word())
								.suggests(TOPIC_SUGGESTIONS)
								.executes(ctx -> BleachGuide.topic(ctx.getSource(),
										StringArgumentType.getString(ctx, TOPIC_ARG)))))
				.then(Commands.literal("help")
						.executes(ctx -> BleachGuide.index(ctx.getSource())))
				.then(admin("reload")
						.executes(BleachCommands::reload))
				.then(admin("toggle")
						.executes(ctx -> setEnabled(ctx, !ModToggle.isEnabled()))
						.then(Commands.argument(VALUE_ARG, BoolArgumentType.bool())
								.executes(ctx -> setEnabled(ctx, BoolArgumentType.getBool(ctx, VALUE_ARG)))))
				.then(admin("sp")
						.then(Commands.literal("get")
								.executes(BleachCommands::report))
						.then(Commands.literal("set")
								.then(Commands.argument(VALUE_ARG, DoubleArgumentType.doubleArg(0.0))
										.executes(BleachCommands::setSp))))
				.then(admin("sl")
						.then(Commands.literal("set")
								.then(Commands.argument(VALUE_ARG, IntegerArgumentType.integer(1))
										.executes(BleachCommands::setSoulLevel))))
				.then(admin("spx")
						.then(Commands.literal("add")
								.then(Commands.argument(VALUE_ARG, IntegerArgumentType.integer())
										.executes(BleachCommands::addSpx))))
				.then(admin("wsl")
						.executes(BleachCommands::reportWorldSoulLevel)
						.then(Commands.literal("set")
								.then(Commands.argument(VALUE_ARG, DoubleArgumentType.doubleArg(1.0))
										.executes(ctx -> setWorldSoulLevel(ctx,
												DoubleArgumentType.getDouble(ctx, VALUE_ARG)))))
						.then(Commands.literal("clear")
								.executes(ctx -> setWorldSoulLevel(ctx, WSL_RELEASE_OVERRIDE))))
				.then(admin("exertion")
						.then(Commands.literal("set")
								.then(Commands.argument(VALUE_ARG, DoubleArgumentType.doubleArg(0.0))
										.executes(BleachCommands::setExertion))))
				.then(admin("test")
						.then(Commands.literal("clawback")
								.executes(ctx -> testClawback(ctx, DEFAULT_CLAWBACK_PCT))
								.then(Commands.argument(VALUE_ARG, DoubleArgumentType.doubleArg(0.0, 1.0))
										.executes(ctx -> testClawback(ctx,
												DoubleArgumentType.getDouble(ctx, VALUE_ARG)))))
						.then(Commands.literal("race").executes(ctx -> testRace(ctx.getSource())))
						.then(Commands.literal("bow").executes(ctx -> testBow(ctx.getSource())))
						.then(Commands.literal("attribution")
								.executes(ctx -> testAttribution(ctx.getSource())))
						.then(Commands.literal("blut").executes(ctx -> testBlut(ctx.getSource())))
						.then(Commands.literal("reishi").executes(ctx -> testReishi(ctx.getSource()))))
				.then(admin("fs")
						.executes(BleachCommands::reportFlashStep))
				.then(admin("flex")
						.executes(BleachCommands::reportFlex))
				.then(admin("cleave")
						.executes(BleachCommands::reportCleave))
				.then(admin("yama")
						.executes(BleachCommands::reportYamamoto))
				.then(admin("rukia")
						.executes(BleachCommands::reportRukia))
				.then(admin("suifeng")
						.executes(BleachCommands::reportSuiFeng))
				.then(admin("shinji")
						.executes(BleachCommands::reportShinji))
				.then(admin("kit")
						.executes(BleachCommands::reportKit)
						.then(Commands.literal("set")
								.then(Commands.argument(VALUE_ARG, StringArgumentType.word())
										.suggests(KIT_SUGGESTIONS)
										.executes(BleachCommands::setKit)))
						.then(Commands.literal("clear")
								.executes(BleachCommands::clearKit)))
				.then(admin("state")
						.then(Commands.literal("set")
								.then(Commands.argument(VALUE_ARG, IntegerArgumentType.integer(
												SpiritualData.STATE_BASE, SpiritualData.STATE_BANKAI))
										.executes(BleachCommands::setState))));
	}

	/** The command half of the master switch. The keybind half lives in {@code BleachNetworking}. */
	private static int setEnabled(CommandContext<CommandSourceStack> ctx, boolean enabled) {
		ModToggle.set(ctx.getSource().getServer(), enabled);
		ctx.getSource().sendSuccess(ModToggle::statusMessage, true);
		return 1;
	}

	private static int reload(CommandContext<CommandSourceStack> ctx) {
		BleachTuning.load();
		ctx.getSource().sendSuccess(() -> Component.literal("Bleach tuning reloaded."), true);

		// MOD_ENABLED is a tuning field, so a reload can flip the master switch out from under
		// everyone. Re-apply whatever the file said so the clients are told and anyone mid-
		// transformation is reverted, rather than being left stranded there.
		ModToggle.reapply(ctx.getSource().getServer());

		// Derived values are all getters, so every online player picks the new numbers up on read —
		// but the client is holding a stale payload until we push one.
		for (ServerPlayer player : ctx.getSource().getServer().getPlayerList().getPlayers()) {
			// SL_HP_PER_TWO_LEVELS is tunable, and the health modifier is the one Soul Level value
			// that is pushed rather than derived on read — so it has to be re-pushed here or a
			// reload leaves everyone carrying the old bonus.
			SoulLevel.applyHealth(player, BleachAttachments.get(player));
			SpiritualTicker.sync(player, true);
		}
		return 1;
	}

	private static int setSp(CommandContext<CommandSourceStack> ctx) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
		ServerPlayer player = ctx.getSource().getPlayerOrException();
		SpiritualData data = BleachAttachments.get(player);

		data.sp = Math.min(DoubleArgumentType.getDouble(ctx, VALUE_ARG), data.maxSp());
		SpiritualTicker.sync(player, true);
		return report(ctx);
	}

	private static int setSoulLevel(CommandContext<CommandSourceStack> ctx) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
		ServerPlayer player = ctx.getSource().getPlayerOrException();
		SpiritualData data = BleachAttachments.get(player);

		data.soulLevel = Math.min(IntegerArgumentType.getInteger(ctx, VALUE_ARG), BleachTuning.SL_MAX);
		data.sp = Math.min(data.sp, data.maxSp());

		// The bonus-health modifier is not derived on read the way every other Soul Level value is —
		// it is an attribute the game holds — so setting the level has to push it.
		SoulLevel.applyHealth(player, data);
		WorldSoulLevel worldSoulLevel = WorldSoulLevel.get(player.server);
		worldSoulLevel.record(player, WorldSoulLevel.currentDay(player.server));

		SpiritualTicker.sync(player, true);
		return report(ctx);
	}

	/**
	 * Bank SPX directly, running the same level-up loop a kill would. The acceptance test for the
	 * curve is "does level 19 → 20 cost 1,310?", and earning that honestly is several in-game days.
	 */
	private static int addSpx(CommandContext<CommandSourceStack> ctx)
			throws com.mojang.brigadier.exceptions.CommandSyntaxException {
		ServerPlayer player = ctx.getSource().getPlayerOrException();
		SpiritualData data = BleachAttachments.get(player);

		int granted = IntegerArgumentType.getInteger(ctx, VALUE_ARG);
		data.spx = Math.max(0, data.spx + granted);
		com.bleach.mod.network.BleachNetworking.sendSpxGain(player, granted);
		SoulLevel.levelUp(player, data);

		SpiritualTicker.sync(player, true);
		return report(ctx);
	}

	/**
	 * The World Soul Level readout · PRD §2.5.
	 *
	 * <p>Prints the pinned state explicitly. A pinned WSL is the only way the acceptance test for
	 * mob scaling is runnable at all — the honest route is levelling several players and waiting a
	 * week of Minecraft days — and an operator who forgets the pin is set will spend a long time
	 * wondering why the number never moves.
	 */
	private static int reportWorldSoulLevel(CommandContext<CommandSourceStack> ctx) {
		MinecraftServer server = ctx.getSource().getServer();
		WorldSoulLevel worldSoulLevel = WorldSoulLevel.get(server);
		long day = WorldSoulLevel.currentDay(server);

		String line = String.format("WSL %.2f%s · %d contributor(s) within %d days · day %d",
				worldSoulLevel.value(),
				worldSoulLevel.isOverridden() ? " (pinned)" : "",
				worldSoulLevel.activeContributors(day),
				BleachTuning.WSL_PLAYTIME_WINDOW_DAYS,
				day);

		ctx.getSource().sendSuccess(() -> Component.literal(line), false);
		return 1;
	}

	private static int setWorldSoulLevel(CommandContext<CommandSourceStack> ctx, double pinned) {
		MinecraftServer server = ctx.getSource().getServer();
		WorldSoulLevel.get(server).setOverride(pinned);

		// The WSL is in the payload, so every client is holding a stale one until this goes out.
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			SpiritualTicker.sync(player, true);
		}
		return reportWorldSoulLevel(ctx);
	}

	private static int setExertion(CommandContext<CommandSourceStack> ctx) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
		ServerPlayer player = ctx.getSource().getPlayerOrException();
		SpiritualData data = BleachAttachments.get(player);

		data.exertion = DoubleArgumentType.getDouble(ctx, VALUE_ARG);
		SpiritualTicker.sync(player, true);
		return report(ctx);
	}

	private static int setState(CommandContext<CommandSourceStack> ctx) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
		ServerPlayer player = ctx.getSource().getPlayerOrException();
		SpiritualData data = BleachAttachments.get(player);

		byte target = (byte) IntegerArgumentType.getInteger(ctx, VALUE_ARG);
		if (target == data.state) {
			return report(ctx);
		}

		// Route through the real entry/revert path so the Bankai loan and its claw-back are exercised
		// by the debug command exactly as they will be by the keybind in Phase 6.
		if (data.isTransformed()) {
			SpiritualTicker.forceRevert(player, data);
		}
		if (target != SpiritualData.STATE_BASE) {
			SpiritualTicker.enter(player, data, target);
		}

		SpiritualTicker.sync(player, true);
		return report(ctx);
	}

	/**
	 * The Bankai loan exploit check, run atomically.
	 *
	 * <p>PRD §1.4: entering Bankai refills the pool to full, and reverting claws the surplus back
	 * with {@code sp = min(spOnEntry, sp)}. Without that, a one-tick toggle is a free full refill of
	 * the resource — the worst exploit available in this design.
	 *
	 * <p>It exists as a command because <b>the test is not hand-runnable</b>. Typing
	 * {@code /bleach state set 2} and then {@code /bleach state set 0} puts whole seconds between
	 * the two, during which drain and regen both move the pool and swamp the thing being measured.
	 * The exploit is a <em>same-tick</em> toggle, so the test has to be one too. This runs enter and
	 * revert back to back with nothing in between and compares the pool against where it started.
	 */
	private static int testClawback(CommandContext<CommandSourceStack> ctx, double startPct)
			throws com.mojang.brigadier.exceptions.CommandSyntaxException {
		ServerPlayer player = ctx.getSource().getPlayerOrException();
		SpiritualData data = BleachAttachments.get(player);

		if (data.isTransformed()) {
			SpiritualTicker.forceRevert(player, data);
		}

		double max = data.maxSp();
		double before = max * startPct;
		data.sp = before;

		SpiritualTicker.enter(player, data, SpiritualData.STATE_BANKAI);
		double refilled = data.sp;
		SpiritualTicker.forceRevert(player, data);
		double after = data.sp;

		boolean loaned = Math.abs(refilled - max) < CLAWBACK_EPSILON;
		boolean clawedBack = Math.abs(after - before) < CLAWBACK_EPSILON;
		boolean pass = loaned && clawedBack;

		String detail = String.format(
				"entry %.2f → refilled %.2f (max %.2f) → revert %.2f", before, refilled, max, after);
		String verdict = pass
				? "PASS · claw-back holds: " + detail
				: "FAIL · " + (loaned ? "surplus was kept" : "entry did not refill") + ": " + detail;

		SpiritualTicker.sync(player, true);
		ctx.getSource().sendSuccess(() -> Component.literal(verdict), false);
		return pass ? 1 : 0;
	}

	// --- Quincy acceptance commands ----------------------------------------------------
	//
	// These exist because the Quincy foundation was built with no Minecraft client available: every
	// task substituted a compile gate for the plan's in-world assertion. They are the compensating
	// control — each one asserts something no unit test can reach, prints PASS/FAIL and returns
	// 1/0 so a function file can chain them.

	/** Emit the verdict for a set of accumulated failures and return the 1/0 exit code. */
	private static int verdict(CommandSourceStack source, String passMessage, StringBuilder failures) {
		if (failures.isEmpty()) {
			source.sendSuccess(() -> Component.literal("PASS · " + passMessage), false);
			return 1;
		}
		String detail = failures.toString();
		source.sendFailure(Component.literal("FAIL · " + detail));
		return 0;
	}

	/**
	 * The race seam · design §3.1. Asserts the invariants a unit test cannot see because they only
	 * exist once the registries have actually been populated at startup.
	 */
	private static int testRace(CommandSourceStack source) {
		StringBuilder failures = new StringBuilder();

		// 1. Shinigami must be inert: the race seam must not have moved the existing eight kits.
		if (Races.SHINIGAMI.reishiSensitivity() != 0.0 || Races.SHINIGAMI.hasBlut()) {
			failures.append("Shinigami is not inert; ");
		}

		for (Kit kit : AbilityRegistry.kits()) {
			// 2. A kit states its race twice — on the Kit itself and in BleachKits.RACE_OF. Those two
			// statements disagreeing is silent: the kit would appear on one picker screen and be minted
			// the other race's weapon.
			if (kit.race().id() != BleachKits.raceOf(kit.id()).id()) {
				failures.append(kit.id()).append(" race mismatch; ");
			}

			// 3. Every kit must have a registered weapon, or drawing hands the player nothing.
			if (BleachItems.weaponFor(kit.id()) == null) {
				failures.append(kit.id()).append(" has no weapon; ");
			}
		}

		return verdict(source, "race seam consistent", failures);
	}

	/**
	 * The bow inherits every zanpakutō guarantee · design §4.2. Task 9 widened {@code isZanpakuto}
	 * into {@code isSpiritWeapon}; if that widening were ever narrowed back, nothing would fail to
	 * compile — the bow would simply become droppable. This is the check that would notice.
	 */
	private static int testBow(CommandSourceStack source) {
		StringBuilder failures = new StringBuilder();

		List<Kit> quincy = AbilityRegistry.kitsFor(Races.QUINCY);
		for (Kit kit : quincy) {
			Item item = BleachItems.weaponFor(kit.id());
			if (item == null) {
				failures.append(kit.id()).append(" has no weapon; ");
				continue;
			}

			ItemStack stack = new ItemStack(item);
			if (!SpiritWeapon.isSpiritWeapon(stack)) {
				failures.append(kit.id()).append(" not a spirit weapon; ");
			}
			if (!SpiritWeapon.isUndroppable(stack)) {
				failures.append(kit.id()).append(" is droppable; ");
			}
			if (item.canFitInsideContainerItems()) {
				failures.append(kit.id()).append(" fits in a bundle; ");
			}
		}

		String passMessage = quincy.isEmpty()
				? "no Quincy kit is registered yet — nothing to check"
				: "bows carry the weapon guarantees (" + quincy.size() + " kit(s))";
		return verdict(source, passMessage, failures);
	}

	/**
	 * The Task 13 trap · §4.3. The attribution fix widened the credited blow to the mod's own damage
	 * sources; a correct-looking version of that fix also starts paying SPX for <b>vanilla</b> bow
	 * kills, which contradicts "a bow is a bow at every level" (BALANCE §E). Nothing in the build
	 * catches that — this does.
	 */
	private static int testAttribution(CommandSourceStack source) throws CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		ServerLevel level = player.serverLevel();
		StringBuilder failures = new StringBuilder();

		Arrow vanilla = new Arrow(level, player, new ItemStack(Items.ARROW), null);
		boolean vanillaCredited = BleachDamage.is(level.damageSources().arrow(vanilla, player));
		vanilla.discard();

		boolean oursCredited = BleachDamage.is(
				BleachDamage.source(level, BleachDamage.SPIRIT_PRESSURE, player));

		if (vanillaCredited) {
			failures.append("a vanilla arrow is a credited blow; ");
		}
		if (!oursCredited) {
			failures.append("a reishi arrow is not a credited blow; ");
		}

		return verdict(source, "vanilla arrows pay nothing, reishi arrows pay", failures);
	}

	/** Blut's stance invariants · design §5.4, plus the "no stance without the race" rule. */
	private static int testBlut(CommandSourceStack source) throws CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		SpiritualData data = BleachAttachments.get(player);
		StringBuilder failures = new StringBuilder();

		if (Blut.cycle(Blut.ARTERIE) != Blut.OFF) {
			failures.append("cycle does not return to off; ");
		}
		if (Blut.damageTakenMultiplier(Blut.VENE) >= 1.0) {
			failures.append("Vene does not reduce damage taken; ");
		}
		if (Blut.damageDealtMultiplier(Blut.ARTERIE) <= 1.0) {
			failures.append("Arterie does not raise damage dealt; ");
		}
		if (!Races.byId(data.race).hasBlut() && data.blut != Blut.OFF) {
			failures.append("a race without Blut is holding a stance; ");
		}

		return verdict(source, "Blut consistent", failures);
	}

	/**
	 * Ambient reishi is read from <b>raw</b> sky light, not time-darkened light · §7.2 item 5.
	 *
	 * <p>This is the one check no unit test and no compile gate can make: a wrong light API compiles,
	 * passes every unit test, and is still wrong in play. So the command moves the world clock itself
	 * — sample at noon, sample at midnight, restore — and asserts the two multipliers are identical.
	 * A Quincy standing under open sky must regenerate the same at both.
	 *
	 * <p>It samples {@link SpiritualTicker#environmentMultiplier}, the real regen path, rather than
	 * recomputing the formula here; a local copy would agree with itself while the game was wrong.
	 */
	private static int testReishi(CommandSourceStack source) throws CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		SpiritualData data = BleachAttachments.get(player);
		ServerLevel level = player.serverLevel();
		StringBuilder failures = new StringBuilder();

		if (Races.byId(data.race).reishiSensitivity() <= 0.0) {
			failures.append("run this as a Quincy — this race ignores ambient reishi; ");
			return verdict(source, "", failures);
		}

		long originalTime = level.getDayTime();
		double noon;
		double midnight;
		try {
			level.setDayTime(NOON_TICKS);
			noon = SpiritualTicker.environmentMultiplier(player, data);
			level.setDayTime(MIDNIGHT_TICKS);
			midnight = SpiritualTicker.environmentMultiplier(player, data);
		} finally {
			// Restored in a finally: leaving a player's world stuck at midnight because an assertion
			// threw would be a far worse bug than the one being tested for.
			level.setDayTime(originalTime);
		}

		if (Math.abs(noon - midnight) > REISHI_EPSILON) {
			failures.append(String.format(
					"sky light is time-darkened: noon %.4f vs midnight %.4f; ", noon, midnight));
		}

		return verdict(source, String.format(
				"ambient reishi is time-invariant (multiplier %.4f at this position)", noon), failures);
	}

	/**
	 * The Flash Step readout · {@code BALANCE.md} §G.
	 *
	 * <p>Acceptance for Phase 3 is "range visibly shrinks as SP drains and grows with
	 * {@code /bleach sl set 20}". Eyeballing a blink against a distant hillside measures level
	 * geometry more than it measures the formula, so this prints the number the formula produced
	 * alongside the inputs that produced it.
	 */
	private static int reportFlashStep(CommandContext<CommandSourceStack> ctx)
			throws com.mojang.brigadier.exceptions.CommandSyntaxException {
		ServerPlayer player = ctx.getSource().getPlayerOrException();
		SpiritualData data = BleachAttachments.get(player);

		Ability flashStep = AbilityRegistry.get(AbilityRegistry.FLASH_STEP);
		int remaining = AbilityCooldowns.remaining(player, AbilityRegistry.FLASH_STEP);
		String line = String.format(
				"Flash Step · range %.2f blocks (SL %d · SP %.1f/%.1f) · cost %.1f · cooldown %d/%d ticks",
				FlashStep.range(data), data.soulLevel, data.sp, data.maxSp(),
				flashStep.spCost(data), remaining, flashStep.cooldownTicks(data));

		player.displayClientMessage(Component.literal(line), true);
		return 1;
	}

	/**
	 * The Spiritual Flex readout · PRD §5 · {@code BALANCE.md} §H.
	 *
	 * <p>Same reason {@code /bleach fs} exists. The tier a flex lands on a given target is
	 * {@code flexerSL − targetSL − counterReduction} run through four thresholds, and the only thing
	 * a player can see of that is a debuff icon with a roman numeral on it — which tells you the
	 * answer but never the arithmetic. This prints both, plus what it currently costs to hold.
	 */
	private static int reportFlex(CommandContext<CommandSourceStack> ctx)
			throws com.mojang.brigadier.exceptions.CommandSyntaxException {
		ServerPlayer player = ctx.getSource().getPlayerOrException();
		SpiritualData data = BleachAttachments.get(player);

		int mobTier = SpiritualFlex.mobTier(data);
		String line = String.format(
				"Flex · radius %.2f · drain %.2f/s · counter %.2f/s (−%d levels) · vs mobs %s%s",
				SpiritualFlex.radius(data),
				SpiritualFlex.drainPerSecond(data),
				SpiritualFlex.counterDrainPerSecond(data),
				SpiritualFlex.counterGapReduction(data),
				mobTier == ReiatsuEffect.NO_TIER ? "no tier" : "Reiatsu " + (mobTier + 1),
				data.flexing ? " · HOLDING" : "");

		player.displayClientMessage(Component.literal(line), true);
		return 1;
	}

	/**
	 * The Ichigo cleave arc readout · PRD §6.1 · {@code BALANCE.md} §J.1.
	 *
	 * <p>Validates the cleave cone in-world: reports the player's current interaction reach
	 * (including Shikai bonus), the configured cleave arc, the cosine threshold used for vector
	 * testing, and counts entities currently inside the cone in front of the player.
	 */
	private static int reportCleave(CommandContext<CommandSourceStack> ctx)
			throws com.mojang.brigadier.exceptions.CommandSyntaxException {
		ServerPlayer player = ctx.getSource().getPlayerOrException();

		AttributeInstance reachAttr = player.getAttribute(Attributes.ENTITY_INTERACTION_RANGE);
		double reach = reachAttr != null ? reachAttr.getValue() : BleachTuning.ICHIGO_SHIKAI_REACH;

		double arcDeg = BleachTuning.ICHIGO_SHIKAI_CLEAVE_ARC;
		double cosThreshold = Math.cos(Math.toRadians(arcDeg / 2.0));
		double pct = BleachTuning.ICHIGO_SHIKAI_CLEAVE_PCT * 100.0;

		Vec3 eyePos = player.getEyePosition();
		Vec3 look = player.getLookAngle().normalize();

		AABB searchBox = player.getBoundingBox().inflate(reach);
		List<LivingEntity> candidates = player.level().getEntitiesOfClass(
				LivingEntity.class, searchBox,
				e -> e != player && e.isAlive() && !e.isAlliedTo(player));

		int inCone = 0;
		for (LivingEntity e : candidates) {
			Vec3 toTarget = e.getEyePosition().subtract(eyePos);
			double dist = toTarget.length();
			if (dist <= reach && dist >= 1.0e-5 && look.dot(toTarget.normalize()) >= cosThreshold) {
				inCone++;
			}
		}

		String line = String.format(
				"Cleave Arc · reach %.2fb · arc %.1f° (cos %.4f) · damage %.0f%% · targets in cone: %d",
				reach, arcDeg, cosThreshold, pct, inCone);

		player.displayClientMessage(Component.literal(line), true);
		return 1;
	}

	/**
	 * The Yamamoto kit readout · PRD §6.2 · {@code BALANCE.md} §J.2.
	 *
	 * <p>Reports tuning values for Ryūjin Jakka and Zanka no Tachi: ignite and extinguish radii,
	 * burn durations, Bankai melee damage bonus, block sweep budget, and the live count of active
	 * block queue tasks.
	 */
	private static int reportYamamoto(CommandContext<CommandSourceStack> ctx)
			throws com.mojang.brigadier.exceptions.CommandSyntaxException {
		ServerPlayer player = ctx.getSource().getPlayerOrException();

		String line = String.format(
				"Yamamoto · Shikai: r=%.1fb, burn=%dt, particles=%d · Bankai: r=%.1fb, +%.0f%% dmg, on-hit=%dt "
						+ "· scorch: %d col/t, cap=%d, depth=%d, fire=%.0f%%, active tasks=%d",
				BleachTuning.YAMA_SHIKAI_RADIUS, BleachTuning.YAMA_SHIKAI_BURN_TICKS, BleachTuning.YAMA_SHIKAI_RING_PARTICLES,
				BleachTuning.YAMA_BANKAI_RADIUS, BleachTuning.YAMA_BANKAI_DMG * 100.0, BleachTuning.YAMA_BANKAI_ONHIT_BURN_TICKS,
				BleachTuning.YAMA_SCORCH_COLUMNS_PER_TICK, BleachTuning.YAMA_SCORCH_BLOCK_CAP,
				BleachTuning.YAMA_EVAPORATE_DEPTH, BleachTuning.YAMA_FIRE_CHANCE * 100.0,
				BlockQueue.activeTasksCount());

		player.displayClientMessage(Component.literal(line), true);
		return 1;
	}

	/**
	 * The Rukia kit readout · PRD §6.4 · {@code BALANCE.md} §J.4.
	 *
	 * <p>Reports tuning values for Sode no Shirayuki and Hakka no Togame: Bankai radius, freeze damage,
	 * snow placement budget, max layers, block cap, snowfall particle rate, Shikai on-hit radius,
	 * cooldown, and the live count of active block queue tasks.
	 */
	private static int reportRukia(CommandContext<CommandSourceStack> ctx)
			throws com.mojang.brigadier.exceptions.CommandSyntaxException {
		ServerPlayer player = ctx.getSource().getPlayerOrException();

		String line = String.format(
				"Rukia · Bankai: r=%.1fb, freeze=%.1f/s (slowness %.0f%%), flurry=%d/t, snow=%d/t (max %d layers, cap %d) · Shikai: on-hit r=%.1fb, cd=%dt · active tasks=%d",
				BleachTuning.RUKIA_BANKAI_RADIUS, BleachTuning.RUKIA_FREEZE_DMG_PER_SEC,
				Math.abs(BleachTuning.RUKIA_FREEZE_SPEED_MULT) * 100.0, BleachTuning.RUKIA_SNOWFALL_PARTICLES,
				BleachTuning.RUKIA_SNOW_BLOCKS_PER_TICK, BleachTuning.RUKIA_SNOW_MAX_LAYERS, BleachTuning.RUKIA_SNOW_BLOCK_CAP,
				BleachTuning.RUKIA_SHIKAI_ONHIT_RADIUS, BleachTuning.RUKIA_SHIKAI_ONHIT_COOLDOWN,
				BlockQueue.activeTasksCount());

		player.displayClientMessage(Component.literal(line), true);
		return 1;
	}

	/**
	 * The Suì-Fēng kit readout · PRD §6.3 · {@code BALANCE.md} §J.3.
	 */
	private static int reportSuiFeng(CommandContext<CommandSourceStack> ctx)
			throws com.mojang.brigadier.exceptions.CommandSyntaxException {
		ServerPlayer player = ctx.getSource().getPlayerOrException();

		String line = String.format(
				"Suì-Fēng · Shikai: mark tolerance=%.2f hitbox, kill exertion=+%.0fs · Bankai: windup=%dt "
						+ "(%.1fs), missile %.1fb/t x%d substeps, range=%.0fb · blast full r=%.1fb, "
						+ "falloff r=%.1fb, dmg=[%.1f, %.1f] (Soul Level scaled), self=%.0f%%, "
						+ "sphere crater r=%.1fb (cap %d, %d/t) · active tasks=%d",
				BleachTuning.SUI_MARK_TOLERANCE, BleachTuning.SUI_SHIKAI_KILL_EXERTION,
				BleachTuning.SUI_BANKAI_WINDUP_TICKS, BleachTuning.SUI_BANKAI_WINDUP_TICKS / 20.0,
				BleachTuning.SUI_MISSILE_SPEED, BleachTuning.SUI_MISSILE_SUBSTEPS, BleachTuning.SUI_MISSILE_RANGE,
				BleachTuning.SUI_BANKAI_LETHAL_RADIUS, BleachTuning.SUI_BANKAI_FALLOFF_RADIUS,
				BleachTuning.SUI_BANKAI_DMG_INNER, BleachTuning.SUI_BANKAI_DMG_OUTER,
				BleachTuning.SUI_BANKAI_SELF_DMG_PCT * 100.0,
				BleachTuning.SUI_CRATER_RADIUS,
				BleachTuning.SUI_CRATER_BLOCK_CAP, BleachTuning.SUI_CRATER_BLOCKS_PER_TICK,
				BlockQueue.activeTasksCount());

		player.displayClientMessage(Component.literal(line), true);
		return 1;
	}

	/**
	 * The Shinji kit readout · PRD §6.5 · {@code BALANCE.md} §J.5.
	 */
	private static int reportShinji(CommandContext<CommandSourceStack> ctx)
			throws com.mojang.brigadier.exceptions.CommandSyntaxException {
		ServerPlayer player = ctx.getSource().getPlayerOrException();
		SpiritualData data = BleachAttachments.get(player);
		double bankaiRadius = BleachTuning.SHINJI_RADIUS_BASE + BleachTuning.SHINJI_RADIUS_PER_LEVEL * data.soulLevel;

		String line = String.format(
				"Shinji · Shikai: on-hit dur=%dt (%.1fs), cd=%dt (%.1fs) · Bankai: aura r=%.1fb (base %.1fb + %.2fb/SL, current SL %d), refresh=%dt (%.1fs) · Mob inversion: %.0f%% chance, reroll=%dt",
				BleachTuning.SHINJI_SHIKAI_ONHIT_DURATION, BleachTuning.SHINJI_SHIKAI_ONHIT_DURATION / 20.0,
				BleachTuning.SHINJI_SHIKAI_ONHIT_COOLDOWN, BleachTuning.SHINJI_SHIKAI_ONHIT_COOLDOWN / 20.0,
				bankaiRadius, BleachTuning.SHINJI_RADIUS_BASE, BleachTuning.SHINJI_RADIUS_PER_LEVEL, data.soulLevel,
				BleachTuning.SHINJI_EFFECT_DURATION_TICKS, BleachTuning.SHINJI_EFFECT_DURATION_TICKS / 20.0,
				BleachTuning.SHINJI_MOB_INVERT_CHANCE * 100.0, BleachTuning.SHINJI_MOB_REROLL_TICKS);

		player.displayClientMessage(Component.literal(line), true);
		return 1;
	}

	/**
	 * Kit inspection and assignment · PRD §3.1–3.3.
	 *
	 * <p>The picker is the player-facing path and stays the acceptance test. This is the operator
	 * one: assigning a kit by name skips the Asauchi entirely, which is what makes "does Sui-Feng's
	 * ×1.5 Flash Step range actually apply?" a two-command question rather than a new world.
	 */
	private static int reportKit(CommandContext<CommandSourceStack> ctx)
			throws com.mojang.brigadier.exceptions.CommandSyntaxException {
		ServerPlayer player = ctx.getSource().getPlayerOrException();
		SpiritualData data = BleachAttachments.get(player);

		Kit kit = AbilityRegistry.kitFor(data);
		String line = kit == null
				? "No zanpakutō" + (data.hasCharacter() ? " (unknown kit " + data.characterId + ")" : "")
				: String.format("%s · %s · FS range ×%.2f · FS cooldown ×%.2f",
						kit.displayName(), SpiritWeapon.isDrawn(player) ? "drawn" : "sheathed",
						kit.flashStepRangeMult(), kit.flashStepCooldownMult());

		player.displayClientMessage(Component.literal(line), true);
		return 1;
	}

	private static int setKit(CommandContext<CommandSourceStack> ctx)
			throws com.mojang.brigadier.exceptions.CommandSyntaxException {
		ServerPlayer player = ctx.getSource().getPlayerOrException();
		SpiritualData data = BleachAttachments.get(player);

		String name = StringArgumentType.getString(ctx, VALUE_ARG);
		Kit kit = AbilityRegistry.kit(BleachMod.id(name).toString());
		if (kit == null) {
			ctx.getSource().sendFailure(Component.literal("No such kit: " + name));
			return 0;
		}

		// Through the same doors the picker uses, so the operator path cannot drift from the player
		// one: the old blade is taken away properly, and the new one arrives drawn.
		if (data.isTransformed()) {
			SpiritualTicker.forceRevert(player, data);
		}
		SpiritWeapon.stow(player, data);
		data.characterId = kit.storageId();
		data.zanpakuto = SpiritWeapon.stackFor(kit.id());
		SpiritWeapon.draw(player, data);

		SpiritualTicker.sync(player, true);
		return reportKit(ctx);
	}

	/** Back to a fresh player: no character, no blade, and an Asauchi to choose again with. */
	private static int clearKit(CommandContext<CommandSourceStack> ctx)
			throws com.mojang.brigadier.exceptions.CommandSyntaxException {
		ServerPlayer player = ctx.getSource().getPlayerOrException();
		SpiritualData data = BleachAttachments.get(player);

		if (data.isTransformed()) {
			SpiritualTicker.forceRevert(player, data);
		}
		SpiritWeapon.stow(player, data);
		data.zanpakuto = ItemStack.EMPTY;
		data.characterId = null;
		SpiritWeapon.ensureAsauchi(player, data);

		SpiritualTicker.sync(player, true);
		return reportKit(ctx);
	}

	private static int report(CommandContext<CommandSourceStack> ctx) {
		ServerPlayer player;
		try {
			player = ctx.getSource().getPlayerOrException();
		} catch (com.mojang.brigadier.exceptions.CommandSyntaxException e) {
			return 0;
		}

		SpiritualData data = BleachAttachments.get(player);
		String line = String.format(
				"SP %.1f/%.1f · SL %d · SPX %d · exertion %.2f (×%.2f regen) · state %d",
				data.sp, data.maxSp(), data.soulLevel, data.spx,
				data.exertion, data.regenMultiplier(), data.state);

		// Action bar, not chat. These are read once and thrown away, and driving the resource to a
		// test state takes enough commands that the chat backlog ends up covering the very HUD the
		// commands exist to exercise. Transient output leaves nothing behind.
		player.displayClientMessage(Component.literal(line), true);
		return 1;
	}
}
