import { CLEANUP_FILTERS, firebaseCleanupView, planRetainedCleanup, executeRetainedCleanup } from "./retained-cleanup.js";
import { refreshRetainedUsage } from "./retained-usage.js";

export const AUTO_CLEANUP_INTERVAL_MS = 15 * 60000;
export const AUTO_CLEANUP_BACKLOG_MS = 60000;
const KEY = "mqtt-cleanup:automatic";
export const automaticCleanupEnabled = env => env.MQTT_AUTOCLEAN_ENABLED === "true";

/** Durable Object alarms keep working with the browser and both phones closed. */
export async function ensureCleanupAlarm(control, now = Date.now()) {
  if (!automaticCleanupEnabled(control.env)) return;
  const saved = await control.storage.get(KEY);
  const due = Math.max(now + 1000, saved?.nextRunAt || now + 1000);
  const alarm = await control.storage.getAlarm();
  if (!alarm || alarm > due) await control.storage.setAlarm(due);
}

/** Called inside the object's existing serialized mutation/alarm queue. */
export async function runAutomaticCleanup(control, now = Date.now()) {
  if (!automaticCleanupEnabled(control.env)) return null;
  const previous = await control.storage.get(KEY);
  if (previous?.nextRunAt > now) return previous.nextRunAt;
  // Persist the next slot first: a restart/alarm retry never creates a hot loop.
  const state = { ...previous, enabled: true, startedAt: now, status: "RUNNING",
    nextRunAt: now + AUTO_CLEANUP_INTERVAL_MS };
  await control.storage.put(KEY, state);
  try {
    const [snapshot, firebase] = await Promise.all([
      control.broker({ snapshot: true, rawFilters: CLEANUP_FILTERS }), firebaseCleanupView(control.legacy),
    ]);
    if (!snapshot.retainedRecords) throw Error("Broker inventory unavailable");
    const plan = await planRetainedCleanup(snapshot.retainedRecords, firebase, Date.now(), { automatic: true });
    const result = plan.batchRecords
      ? await executeRetainedCleanup(plan, { broker: control.broker, store: control.legacy })
      : { automatic: true, manualOnly: false, requested: 0, deletedConfirmed: 0,
          scannedRecordsAfter: plan.scannedRecords, completedAt: Date.now(), categories: {} };
    const backlog = plan.eligibleRecords > plan.batchRecords && result.deletedConfirmed > 0;
    const saved = { ...state, status: "OK", result, completedAt: Date.now(),
      nextRunAt: Date.now() + (backlog ? AUTO_CLEANUP_BACKLOG_MS : AUTO_CLEANUP_INTERVAL_MS) };
    await control.storage.put(KEY, saved);
    await refreshRetainedUsage(control, { force: true });
    return saved.nextRunAt;
  } catch (error) {
    // Never acknowledge a failed cleanup as success or consume normal admin
    // retries. A fresh inventory/plan will be made at the next regular slot.
    const saved = { ...state, status: "FAILED", completedAt: Date.now(),
      error: "Cleanup interrupted; verified recovery archives were preserved. A fresh attempt will run automatically." };
    await control.storage.put(KEY, saved);
    await refreshRetainedUsage(control, { force: true });
    return saved.nextRunAt;
  }
}

export async function automaticCleanupStatus(control) {
  return { enabled: automaticCleanupEnabled(control.env), intervalMs: AUTO_CLEANUP_INTERVAL_MS,
    lastRun: await control.storage.get(KEY) || null };
}
