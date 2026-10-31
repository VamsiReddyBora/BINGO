import { ACTIVITY_FILTERS, hasGameplayActivity } from "./cleanup-activity.js";

const KEY = "mqtt-cleanup:usage";
const CACHE_MS = 30000;

/** Count the full Bingo namespace, not just topics eligible for cleanup. */
export async function refreshRetainedUsage(control, { force = false } = {}) {
  const old = await control.storage.get(KEY);
  if (!force && old && !old.stale && Date.now()-old.observedAt < CACHE_MS) return old;
  if (control.usageRequest) return control.usageRequest;
  const request = (async () => {
    try {
      const activity=await control.broker({snapshot:true,rawFilters:ACTIVITY_FILTERS,deferOnGameplay:true});
      if(!activity.retainedRecords)throw Error("Gameplay activity could not be checked");
      if(activity.cleanup?.deferred || hasGameplayActivity(activity.retainedRecords)) {
        const saved={...(old || {used:null,limit:2000,observedAt:null}),stale:true,deferred:true,
          error:"Usage refresh is waiting for gameplay and the rejoin grace period to finish."};
        await control.storage.put(KEY,saved);return saved;
      }
      const snapshot = await control.broker({ snapshot: true, inventory: true });
      const usage = snapshot.retainedUsage;
      if (!usage || !Number.isSafeInteger(usage.used) || usage.used < 0 || usage.limit !== 2000 || !Number.isSafeInteger(usage.observedAt))
        throw Error("Broker usage inventory unavailable");
      const saved = { ...usage, free: Math.max(0, usage.limit-usage.used),
        percent: Math.round(usage.used/usage.limit*1000)/10, stale: false, deferred: false };
      await control.storage.put(KEY, saved);
      return saved;
    } catch {
      const saved = { ...(old || { used: null, limit: 2000, observedAt: null }), stale: true,
        error: "Broker usage could not be refreshed; the previous reading may be outdated.", failedAt: Date.now() };
      await control.storage.put(KEY, saved);
      return saved;
    }
  })();
  control.usageRequest = request;
  try { return await request; }
  finally { if (control.usageRequest === request) control.usageRequest = null; }
}
