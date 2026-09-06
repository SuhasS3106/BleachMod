package com.bleach.mod.util;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.server.MinecraftServer;

/**
 * Reusable tick-sliced world task runner · Phases 7, 9, 10.
 *
 * <p>Massive world operations (Yamamoto's scorch sweep over a 30-block radius, Rukia's snow layer
 * accumulation, Suì-Fēng's missile flight and the crater it leaves) cannot run in a single server
 * tick without spiking tick times above 100 ms.
 *
 * <p>This queue runs tasks in budget-limited slices on {@code ServerTickEvents.END_SERVER_TICK},
 * keeping individual tick times well under 40 ms.
 *
 * <h2>Submitting from inside a tick</h2>
 *
 * <p>Tasks are allowed to submit other tasks — Suì-Fēng's missile queues its crater at the moment
 * it detonates, which is necessarily mid-drain. New arrivals therefore land in a staging list and
 * are folded in at the <em>start</em> of the next {@link #tickAll}, rather than mutating the list
 * that is currently being iterated. Adding straight to {@code ACTIVE_TASKS} would throw a
 * {@link java.util.ConcurrentModificationException} out of the server tick.
 */
public final class BlockQueue {
	private BlockQueue() {
	}

	@FunctionalInterface
	public interface Task {
		/**
		 * Executes one tick slice of work.
		 *
		 * @param server the running server instance
		 * @return {@code true} if this task still has work remaining; {@code false} when complete
		 */
		boolean tick(MinecraftServer server);
	}

	private static final List<Task> ACTIVE_TASKS = new ArrayList<>();
	private static final List<Task> PENDING_TASKS = new ArrayList<>();

	/** Submit a tick-sliced task to the queue. Safe to call from inside another task's tick. */
	public static void submit(Task task) {
		PENDING_TASKS.add(task);
	}

	/**
	 * Run {@code action} once, {@code delayTicks} server ticks from now.
	 *
	 * <p>Exists for the handful of effects whose whole point is landing a beat after something else
	 * — Flash Step's arrival burst, which has to reach the client no earlier than the teleport it is
	 * meant to be announcing.
	 */
	public static void submitDelayed(int delayTicks, Runnable action) {
		if (delayTicks <= 0) {
			action.run();
			return;
		}

		int[] remaining = { delayTicks };
		submit(server -> {
			if (--remaining[0] > 0) {
				return true;
			}
			action.run();
			return false;
		});
	}

	/** Drains active tasks. Called once per server tick from {@code SpiritualTicker}. */
	public static void tickAll(MinecraftServer server) {
		if (!PENDING_TASKS.isEmpty()) {
			ACTIVE_TASKS.addAll(PENDING_TASKS);
			PENDING_TASKS.clear();
		}

		if (ACTIVE_TASKS.isEmpty()) {
			return;
		}
		ACTIVE_TASKS.removeIf(task -> !task.tick(server));
	}

	/** Number of active tasks currently draining. */
	public static int activeTasksCount() {
		return ACTIVE_TASKS.size() + PENDING_TASKS.size();
	}

	/** Clears all queued tasks (e.g. on server shutdown or mod disable). */
	public static void clear() {
		ACTIVE_TASKS.clear();
		PENDING_TASKS.clear();
	}
}
