import { CLEANUP_FILTERS, firebaseCleanupView, planRetainedCleanup, executeRetainedCleanup } from "./retained-cleanup.js";
import { refreshRetainedUsage } from "./retained-usage.js";
import { ACTIVITY_FILTERS, hasGameplayActivity } from "./cleanup-activity.js";

export const INTERVAL_IDLE_MS = 60 * 60000;      // 60 minutes (< 25% or < 500 records)
export const INTERVAL_NORMAL_MS = 15 * 60000;    // 15 minutes (25% - 50% or 500-999 records)
export const INTERVAL_ELEVATED_MS = 5 * 60000;   // 5 minutes (50% - 75% or 1000-1499 records)
export const INTERVAL_SURGE_MS = 2 * 60000;      // 2 minutes (>= 75% or >= 1500 records)
export const AUTO_CLEANUP_BACKLOG_MS = 60000;    // 1 minute (backlog or deferred gameplay)
export const AUTO_CLEANUP_INTERVAL_MS = INTERVAL_NORMAL_MS; // default compatibility
const KEY = "mqtt-cleanup:automatic";
const SETTINGS_KEY = "mqtt-cleanup:settings";
export const automaticCleanupEnabled = env => env.MQTT_AUTOCLEAN_ENABLED === "true";

export async function cleanupSettings(control) {
  const saved = await control.storage.get(SETTINGS_KEY);
  return { enabled: automaticCleanupEnabled(control.env) && saved?.enabled !== false,
    configured: automaticCleanupEnabled(control.env), revision: saved?.revision || 0,
    updatedAt: saved?.updatedAt || null, updatedBy: saved?.updatedBy || null };
}

/** Called through the same serialized mutation queue as alarms. No broker writes here. */
export async function setCleanupEnabled(control, enabled, expectedRevision, operator) {
  const current = await cleanupSettings(control);
  if (current.revision !== expectedRevision) return null;
  if (enabled && !current.configured) throw Error("Automatic cleanup is disabled by deployment configuration");
  await control.storage.put(SETTINGS_KEY, { enabled, revision: current.revision + 1,
    updatedAt: Date.now(), updatedBy: operator });
  if (enabled) {
    const previous = await control.storage.get(KEY);
    if (previous) await control.storage.put(KEY, { ...previous, nextRunAt: Date.now() + 1000 });
    await ensureCleanupAlarm(control);
  }
  return cleanupSettings(control);
}

/** Calculate adaptive cleaning interval and load tier from current retained message count. */
export function calculateDynamicInterval(usedCount, limit = 2000) {
  if (!Number.isSafeInteger(usedCount) || usedCount < 0) {
    return { intervalMs: INTERVAL_NORMAL_MS, tier: "normal", reason: "Standard schedule (25–50% broker capacity)" };
  }
  const max = limit && limit > 0 ? limit : 2000;
  const ratio = usedCount / max;
  if (ratio >= 0.75 || usedCount >= 1500) {
    return { intervalMs: INTERVAL_SURGE_MS, tier: "surge", reason: "Surge schedule (≥75% broker capacity)" };
  }
  if (ratio >= 0.50 || usedCount >= 1000) {
    return { intervalMs: INTERVAL_ELEVATED_MS, tier: "elevated", reason: "Elevated schedule (≥50% broker capacity)" };
  }
  if (ratio >= 0.25 || usedCount >= 500) {
    return { intervalMs: INTERVAL_NORMAL_MS, tier: "normal", reason: "Normal schedule (25–50% broker capacity)" };
  }
  return { intervalMs: INTERVAL_IDLE_MS, tier: "idle", reason: "Idle schedule (<25% broker capacity)" };
}

/** Durable Object alarms keep working with the browser and both phones closed. */
export async function ensureCleanupAlarm(control, now = Date.now()) {
  if (!(await cleanupSettings(control)).enabled) return;
  const saved = await control.storage.get(KEY);
  const due = Math.max(now + 1000, saved?.nextRunAt || now + 1000);
  const alarm = await control.storage.getAlarm();
  if (!alarm || alarm > due) await control.storage.setAlarm(due);
}

/** Called inside the object's existing serialized mutation/alarm queue. */
export async function runAutomaticCleanup(control, now = Date.now(), { force = false } = {}) {
  if (!(await cleanupSettings(control)).enabled) return null;
  const previous = await control.storage.get(KEY);
  if (!force && previous?.nextRunAt > now) return previous.nextRunAt;
  
  const lastUsage = await control.storage.get("mqtt-cleanup:usage");
  const baseline = calculateDynamicInterval(lastUsage?.used);
  // Persist the next slot first: a restart/alarm retry never creates a hot loop.
  const state = {
    ...previous,
    enabled: true,
    startedAt: now,
    status: "RUNNING",
    intervalMs: baseline.intervalMs,
    tier: baseline.tier,
    nextRunAt: now + baseline.intervalMs,
  };
  await control.storage.put(KEY, state);
  try {
    // A small read comes first. Bulk inventories and deletes are deferred while
    // anyone is playing, joining or inside the normal five-minute rejoin grace.
    const activity = await control.broker({ snapshot: true, rawFilters: ACTIVITY_FILTERS, deferOnGameplay: true });
    if (!activity.retainedRecords) throw Error("Gameplay activity inventory unavailable");
    if (activity.cleanup?.deferred || hasGameplayActivity(activity.retainedRecords)) {
      const saved = {
        ...state,
        status: "DEFERRED",
        completedAt: Date.now(),
        intervalMs: AUTO_CLEANUP_BACKLOG_MS,
        tier: "backlog",
        nextRunAt: Date.now() + AUTO_CLEANUP_BACKLOG_MS,
        reason: "Gameplay or recent rejoin activity; bulk cleanup will wait.",
      };
      await control.storage.put(KEY, saved);
      return saved.nextRunAt;
    }
    const [snapshot, firebase] = await Promise.all([
      control.broker({ snapshot: true, rawFilters: CLEANUP_FILTERS }),
      firebaseCleanupView(control.legacy),
    ]);
    if (!snapshot.retainedRecords) throw Error("Broker inventory unavailable");
    const plan = await planRetainedCleanup(snapshot.retainedRecords, firebase, Date.now(), { automatic: true });
    const result = plan.batchRecords
      ? await executeRetainedCleanup(plan, { broker: control.broker, store: control.legacy })
      : { automatic: true, manualOnly: false, requested: 0, deletedConfirmed: 0,
          scannedRecordsAfter: plan.scannedRecords, completedAt: Date.now(), categories: {} };
    const backlog = plan.eligibleRecords > plan.batchRecords && result.deletedConfirmed > 0;
    
    let refreshedUsage = null;
    if (!result.deferred) {
      try {
        refreshedUsage = await refreshRetainedUsage(control, { force: true });
      } catch {
        // Fall back to scannedRecordsAfter if usage refresh fails
      }
    }
    const currentCount = Number.isSafeInteger(refreshedUsage?.used)
      ? refreshedUsage.used
      : result.scannedRecordsAfter ?? plan.scannedRecords;

    const dynamicSchedule = calculateDynamicInterval(currentCount);
    const nextInterval = (backlog || result.deferred)
      ? AUTO_CLEANUP_BACKLOG_MS
      : dynamicSchedule.intervalMs;
    const currentTier = (backlog || result.deferred) ? "backlog" : dynamicSchedule.tier;

    const saved = {
      ...state,
      status: result.deferred ? "DEFERRED" : "OK",
      result,
      completedAt: Date.now(),
      intervalMs: nextInterval,
      tier: currentTier,
      tierReason: dynamicSchedule.reason,
      nextRunAt: Date.now() + nextInterval,
    };
    await control.storage.put(KEY, saved);
    return saved.nextRunAt;
  } catch (error) {
    // Never acknowledge a failed cleanup as success or consume normal admin
    // retries. A fresh inventory/plan will be made at the next regular slot.
    const saved = {
      ...state,
      status: "FAILED",
      completedAt: Date.now(),
      error: "Cleanup interrupted; verified recovery archives were preserved. A fresh attempt will run automatically.",
    };
    await control.storage.put(KEY, saved);
    await refreshRetainedUsage(control, { force: true }).catch(() => {});
    return saved.nextRunAt;
  }
}

export async function automaticCleanupStatus(control) {
  const lastRun = await control.storage.get(KEY) || null;
  const usage = await control.storage.get("mqtt-cleanup:usage") || null;
  const dynamic = calculateDynamicInterval(usage?.used);
  return {
    ...(await cleanupSettings(control)),
    intervalMs: lastRun?.intervalMs || dynamic.intervalMs,
    tier: lastRun?.tier || dynamic.tier,
    tierReason: dynamic.reason,
    thresholds: {
      moveReceiptsMs: 3 * 60000,
      finishedMatchesMs: 30 * 60000,
      closedLobbiesMs: 60 * 60000,
      abandonedMatchesMs: 2 * 3600000,
      invitationsMs: 2 * 60000,
    },
    lastRun,
  };
}
