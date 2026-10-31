// Read-only liveness gate. It neither changes a turn nor publishes player state.
export const REJOIN_GRACE_MS = 5 * 60000;
export const ACTIVITY_FILTERS = ["bingo/emqx7/metadata/+/state", "bingo/emqx7/room/+/availability/+",
  "bingo/emqx7/match/+/+/state/+", "bingo/emqx7/reliable/+/+/state"];
const parse = raw => { try { const p=JSON.parse(raw);return p && typeof p==="object" && !Array.isArray(p) ? p : null; } catch {return null;} };
function recent(p,key,now,missing=false) {
  const value=p[key],offset=Number.isSafeInteger(p.clockOffsetMillis)?p.clockOffsetMillis:0;
  if(!Number.isSafeInteger(value)||value<=0)return missing;
  const at=value-offset;
  return at>0 && now-at<REJOIN_GRACE_MS;
}
export function recordProtectsGameplay(topic,raw,now=Date.now()) {
  const p=parse(raw);if(!p)return false;
  if(/^bingo\/emqx7\/metadata\/[^/]+\/state$/.test(topic))
    return ["WAITING","PLAYING"].includes(p.status) && recent(p,"lastHeartbeat",now,true);
  if(/^bingo\/emqx7\/room\/[^/]+\/availability\/[^/]+$/.test(topic)) {
    const online=["PLAYER_RECONNECTED","PLAYER_PRESENCE"].includes(p.type) && !["OFFLINE","BACKGROUND","MINIMIZED"].includes(p.presenceStatus);
    return recent(p,"timestamp",now,online);
  }
  if(/^bingo\/emqx7\/match\/[^/]+\/[^/]+\/state\//.test(topic)||/^bingo\/emqx7\/reliable\/[^/]+\/[^/]+\/state$/.test(topic))
    return recent(p,"timestamp",now) || recent(p,"lastPlayerActivityAt",now);
  return false;
}
export function hasGameplayActivity(records,now=Date.now()) {
  return Object.entries(records).some(([t,p])=>recordProtectsGameplay(t,p,now));
}
export function roomHasGameplayActivity(records,room,now=Date.now()) {
  const prefixes=[`bingo/emqx7/metadata/${room}/`,`bingo/emqx7/room/${room}/`,
    `bingo/emqx7/match/${room}/`,`bingo/emqx7/reliable/${room}/`];
  return Object.entries(records).some(([t,p])=>prefixes.some(prefix=>t.startsWith(prefix))&&recordProtectsGameplay(t,p,now));
}
export function isLiveGameplayTopic(topic,payload) {
  return !!payload && (/^bingo\/emqx7\/room\/[^/]+(?:\/|$)/.test(topic)||
    /^bingo\/emqx7\/(match|metadata|reliable)\//.test(topic)) && !topic.includes("/probe/");
}
