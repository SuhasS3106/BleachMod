package com.bleach.mod.attachment;

import java.util.Optional;

import com.bleach.mod.tuning.BleachTuning;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.world.item.ItemStack;

/**
 * Per-player spiritual state: the SP pool, Soul Level progression, and transformation bookkeeping.
 *
 * <p>Stored fields are the minimum that must survive a restart. <b>Every derived value is a getter,
 * never a field</b> — max SP, regen, gates and the exertion multiplier are all recomputed from
 * {@link BleachTuning} on each read, so {@code /bleach reload} takes effect immediately and stale
 * values can never be persisted.
 */
public class SpiritualData {
	public static final byte STATE_BASE = 0;
	public static final byte STATE_SHIKAI = 1;
	public static final byte STATE_BANKAI = 2;

	public static final Codec<SpiritualData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
			Codec.DOUBLE.optionalFieldOf("sp", 0.0).forGetter(d -> d.sp),
			Codec.INT.optionalFieldOf("soul_level", 1).forGetter(d -> d.soulLevel),
			Codec.INT.optionalFieldOf("spx", 0).forGetter(d -> d.spx),
			Codec.DOUBLE.optionalFieldOf("exertion", 0.0).forGetter(d -> d.exertion),
			Codec.LONG.optionalFieldOf("last_spx_day", 0L).forGetter(d -> d.lastSpxDay),
			Codec.INT.optionalFieldOf("spx_earned_today", 0).forGetter(d -> d.spxEarnedToday),
			Codec.LONG.optionalFieldOf("playtime_ticks", 0L).forGetter(d -> d.playtimeTicks),
			Codec.BYTE.optionalFieldOf("state", STATE_BASE).forGetter(d -> d.state),
			Codec.INT.optionalFieldOf("regen_pause_ticks", 0).forGetter(d -> d.regenPauseTicks),
			Codec.STRING.optionalFieldOf("character_id").forGetter(d -> Optional.ofNullable(d.characterId)),
			Codec.DOUBLE.optionalFieldOf("sp_on_entry", 0.0).forGetter(d -> d.spOnEntry),
			ItemStack.OPTIONAL_CODEC.optionalFieldOf("zanpakuto", ItemStack.EMPTY).forGetter(d -> d.zanpakuto),
			ItemStack.OPTIONAL_CODEC.optionalFieldOf("stowed_item", ItemStack.EMPTY).forGetter(d -> d.stowedItem),
			Codec.INT.optionalFieldOf("stowed_reforged", 0).forGetter(d -> d.stowedReforged),
			Codec.BYTE.optionalFieldOf("race", (byte) 0).forGetter(d -> d.race)
	).apply(instance, SpiritualData::new));

	/** Current spiritual pressure. */
	public double sp;
	/** Soul Level, 1..{@link BleachTuning#SL_MAX}. */
	public int soulLevel;
	/** Soul Points banked toward the next level. */
	public int spx;
	/** Seconds of accumulated transformation debt. Clears only at 100% SP. */
	public double exertion;
	/** {@code level.getDayTime() / 24000} the last time the daily counter rolled. */
	public long lastSpxDay;
	/** SPX earned so far on {@link #lastSpxDay}, against the daily cap. */
	public int spxEarnedToday;
	/** Total ticks played, weighting this player's contribution to the World Soul Level. */
	public long playtimeTicks;
	/** {@link #STATE_BASE}, {@link #STATE_SHIKAI} or {@link #STATE_BANKAI}. */
	public byte state;
	/** Ticks remaining before regen resumes. Set on any spend or damage. */
	public int regenPauseTicks;
	/** Chosen kit, or null until an Asauchi has been used. */
	public String characterId;
	/**
	 * Which race this player is · design §3.1. {@link com.bleach.mod.race.Races#byId} resolves it.
	 *
	 * <p><b>Defaults to 0, which is Shinigami, and that default is doing real work.</b> Every save
	 * written before races existed omits the field, so every existing player loads as a Shinigami
	 * with no migration step and no config version bump.
	 */
	public byte race;
	/**
	 * SP at the moment Bankai was entered. The entry refill is a loan; on revert the surplus is
	 * clawed back with {@code sp = min(spOnEntry, sp)}. See PRD §1.4.
	 */
	public double spOnEntry;

	/**
	 * The sheathed zanpakutō · PRD §3.2. <b>Empty while drawn</b> — the blade is either here or in the
	 * inventory, never both, and that invariant is the whole duplication guard.
	 *
	 * <p>It lives in the attachment rather than a custom equipment slot by decision (PRD §3.2): a real
	 * slot needs an inventory attachment, an {@code InventoryScreen} mixin, an injected {@code Slot},
	 * container sync and creative/death edge cases — more code than all five kits combined.
	 */
	public ItemStack zanpakuto = ItemStack.EMPTY;
	/**
	 * Whatever the draw displaced out of the selected hotbar slot. Empty while sheathed. Held here
	 * rather than shuffled into a free slot so that drawing with a full inventory cannot drop or eat
	 * anything.
	 */
	public ItemStack stowedItem = ItemStack.EMPTY;
	/**
	 * Reforged Asauchi taken off the player on death and owed back on respawn · PRD §3.1.
	 *
	 * <p>Death strips every selector from the inventory, because an Asauchi lying on the ground is a
	 * character choice anyone can walk over and pick up. The plain one needs no bookkeeping — it is
	 * re-minted unconditionally for anyone without a kit — but a Reforged one belongs to a player who
	 * already has a kit, so nothing would ever give it back. This is that debt.
	 *
	 * <p>A count rather than the stacks themselves: the item is {@code stacksTo(1)} and carries no
	 * state worth preserving, so a number is exactly as faithful and cannot be used to launder a
	 * renamed or enchanted copy through a death.
	 */
	public int stowedReforged;
	/**
	 * Whether the Spiritual Flex key is currently held. <b>Not persisted and not in the codec</b> —
	 * it is an edge-tracked channel, and a stale true restored from disk would drain a player who
	 * is not pressing anything. Cleared on logout by the client's own {@code FLEX_STOP}, and again
	 * on respawn.
	 */
	public boolean flexing;

	/**
	 * Blut stance · design §5.4. 0 off, 1 Vene, 2 Arterie.
	 *
	 * <p>Not persisted and not in the codec, for the same reason as {@link #flexing}: it is a stance
	 * the player is holding, and a stale value restored from disk would bill someone who is not
	 * pressing anything.
	 */
	public byte blut;

	/**
	 * Whether the Aura Sense key is currently held — the player's eyes are shut. Not persisted and
	 * not in the codec, for the same reason as {@link #flexing}: it is an edge-tracked channel, and
	 * a stale true restored from disk would blind a player who is not pressing anything.
	 */
	public boolean sensing;

	/**
	 * Whether the Hover key is held. <b>Intent, not permission</b> — the client reports this and the
	 * server decides what to do about it.
	 *
	 * <p>Separate from {@link #hovering} because the key is almost always pressed a moment before it
	 * can do anything: you hold it as you run off a ledge, not once you are already falling. The
	 * intent survives a refusal and the channel starts on the first tick the player is actually in
	 * the air, rather than the press being spent on a tick that was standing on the ground.
	 */
	public boolean hoverIntent;

	/**
	 * Whether the hover channel is live — the server has agreed to hold this player up and is
	 * billing them for it. Read by the sync payload, and the client's permission to move.
	 *
	 * <p>Neither this nor {@link #hoverIntent} is persisted or in the codec, for the same reason as
	 * {@link #flexing}: they are edge-tracked channel state, and a stale true restored from disk
	 * would leave a player floating with nothing pressing anything — and, worse, with the
	 * {@code NoGravity} flag {@code Hover} sets still on them. {@code Hover.clearStale} undoes both.
	 */
	public boolean hovering;

	/**
	 * Set by Flash Step, cleared the moment the player is back on the ground.
	 *
	 * <p>While it is set the player takes no fall damage · a Shunpo is not a fall. Deliberately not
	 * persisted and not part of {@code copyOnDeath}: it describes the current jump and nothing else,
	 * and a flag that survived a relog would be a permanent feather-falling enchantment.
	 */
	public transient boolean flashStepFallGrace;

	/** A fresh player: Soul Level 1, a full pool, no debt. */
	public SpiritualData() {
		this.soulLevel = 1;
		this.sp = maxSp();
	}

	private SpiritualData(double sp, int soulLevel, int spx, double exertion, long lastSpxDay,
			int spxEarnedToday, long playtimeTicks, byte state, int regenPauseTicks,
			Optional<String> characterId, double spOnEntry, ItemStack zanpakuto, ItemStack stowedItem,
			int stowedReforged, byte race) {
		this.sp = sp;
		this.soulLevel = soulLevel;
		this.spx = spx;
		this.exertion = exertion;
		this.lastSpxDay = lastSpxDay;
		this.spxEarnedToday = spxEarnedToday;
		this.playtimeTicks = playtimeTicks;
		this.state = state;
		this.regenPauseTicks = regenPauseTicks;
		this.characterId = characterId.orElse(null);
		this.spOnEntry = spOnEntry;
		this.zanpakuto = zanpakuto;
		this.stowedItem = stowedItem;
		this.stowedReforged = stowedReforged;
		this.race = race;
	}

	// --- Derived values · BALANCE.md §A–§C -------------------------------------------

	public double maxSp() {
		return BleachTuning.SP_BASE_MAX + BleachTuning.SP_MAX_PER_LEVEL * (soulLevel - 1);
	}

	public double regenPerSecond() {
		return maxSp() * (BleachTuning.SP_REGEN_BASE_PCT
				+ BleachTuning.SP_REGEN_PCT_PER_LEVEL * (soulLevel - 1));
	}

	public double exertionK() {
		return BleachTuning.EXERTION_K_BASE - BleachTuning.EXERTION_K_PER_LEVEL * (soulLevel - 1);
	}

	public double regenMultiplier() {
		return Math.max(BleachTuning.EXERTION_MULT_FLOOR, 1.0 / (1.0 + exertionK() * exertion));
	}

	/**
	 * Entry threshold for a state as a fraction of max SP · {@code BALANCE.md} §C.
	 *
	 * <p>Static because {@code TransformAbility#entryGatePercent} has to answer the same question
	 * from the kit side, and two copies of this formula is exactly how Shikai and Bankai end up
	 * gated differently depending on which code path asked.
	 */
	public static double gatePercent(byte state, int soulLevel) {
		double base = switch (state) {
			case STATE_BANKAI -> BleachTuning.GATE_BANKAI_BASE;
			case STATE_SHIKAI -> BleachTuning.GATE_SHIKAI_BASE;
			default -> 0.0;
		};
		return base <= 0.0 ? 0.0 : base - BleachTuning.GATE_REDUCTION_PER_LEVEL * (soulLevel - 1);
	}

	public double bankaiGate() {
		return maxSp() * gatePercent(STATE_BANKAI, soulLevel);
	}

	public double shikaiGate() {
		return maxSp() * gatePercent(STATE_SHIKAI, soulLevel);
	}

	/** Drain for the current state, SP per second. Zero in the base state. */
	public double drainPerSecond() {
		return switch (state) {
			case STATE_BANKAI -> BleachTuning.DRAIN_BANKAI;
			case STATE_SHIKAI -> BleachTuning.DRAIN_SHIKAI;
			default -> 0.0;
		};
	}

	/** Exertion accrued per second in the current state. Zero in the base state. */
	public double exertionRatePerSecond() {
		return switch (state) {
			case STATE_BANKAI -> BleachTuning.EXERTION_RATE_BANKAI;
			case STATE_SHIKAI -> BleachTuning.EXERTION_RATE_SHIKAI;
			default -> 0.0;
		};
	}

	/** Entry threshold in absolute SP for the given state, or 0 for the base state. */
	public double entryGate(byte target) {
		return switch (target) {
			case STATE_BANKAI -> bankaiGate();
			case STATE_SHIKAI -> shikaiGate();
			default -> 0.0;
		};
	}

	public boolean isTransformed() {
		return state != STATE_BASE;
	}

	public boolean hasCharacter() {
		return characterId != null;
	}

	// --- Mutation helpers -----------------------------------------------------------

	/** Freeze regen for {@link BleachTuning#SP_REGEN_PAUSE_TICKS}. Any spend or damage calls this. */
	public void pauseRegen() {
		regenPauseTicks = BleachTuning.SP_REGEN_PAUSE_TICKS;
	}

	/** Deduct SP and pause regen. Clamped at zero; does not check affordability. */
	public void spend(double amount) {
		sp = Math.max(0.0, sp - amount);
		pauseRegen();
	}

	/**
	 * Carry progression across a respawn. Deliberately excludes the volatile pool state — SP,
	 * exertion and the current transformation are handled by {@link #resetOnRespawn()}.
	 */
	public void copyProgressionFrom(SpiritualData other) {
		this.soulLevel = other.soulLevel;
		this.spx = other.spx;
		this.lastSpxDay = other.lastSpxDay;
		this.spxEarnedToday = other.spxEarnedToday;
		this.playtimeTicks = other.playtimeTicks;
		this.characterId = other.characterId;
		// Race is progression, not pool state: it survives death exactly as the chosen kit does.
		this.race = other.race;

		// PRD §3.2: the zanpakutō is kept on death. It is already back in the attachment by this
		// point — Zanpakuto's ALLOW_DEATH handler sheathes before the inventory is allowed to drop —
		// so carrying the field forward is the whole of "survives death".
		this.zanpakuto = other.zanpakuto;
		this.stowedItem = other.stowedItem;

		// And the same for an unused Reforged Asauchi, which death took off the corpse a moment ago
		// and which only this field remembers is owed · Zanpakuto#stripSelectors.
		this.stowedReforged = other.stowedReforged;
	}

	/** Carry the pool state too, for a non-death copy such as returning from the End. */
	public void copyVolatileFrom(SpiritualData other) {
		this.sp = other.sp;
		this.exertion = other.exertion;
		this.state = other.state;
		this.regenPauseTicks = other.regenPauseTicks;
		this.spOnEntry = other.spOnEntry;
	}

	/** PRD §1.2: respawn refills SP to 100% and clears exertion. */
	public void resetOnRespawn() {
		this.state = STATE_BASE;
		this.exertion = 0.0;
		this.regenPauseTicks = 0;
		this.spOnEntry = 0.0;
		this.flexing = false;
		this.sensing = false;
		this.hoverIntent = false;
		this.hovering = false;
		this.blut = 0;
		this.sp = maxSp();
	}
}
