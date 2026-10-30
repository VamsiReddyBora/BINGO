/** Manual maintenance only. Both the console and local script use this implementation. */
import { digest, requireThat } from "./model.js";
export const CLEANUP_FILTERS = [
  "bingo/emqx7/match/#", "bingo/emqx7/metadata/+/+",
  "bingo/emqx7/room/+/availability/+", "bingo/emqx7/room/+",
  "bingo/emqx7/social/invite/+/+", "bingo/v3/invites/+",
  "bingo/v3/registry/+", "bingo/v3/google_account/+",
];
export const CLEANUP_AGE_MS = 86400000;
const LIMIT = 200;
const parse = (s) => { try { const x = JSON.parse(s); return x && typeof x === "object" && !Array.isArray(x) ? x : null; } catch { return null; } };
const exact = (topic) => typeof topic === "string" && topic.startsWith("bingo/") && !/[+#\x00]/.test(topic);
const stamp = (x) => Number.isSafeInteger(x) && x > 0 ? x : 0;
const matches = (topic, prefixes) => prefixes.some(p => p.endsWith("/") ? topic.startsWith(p) : topic === p);
export async function fingerprint(records) {
  return digest(JSON.stringify(Object.entries(records).sort(([a],[b]) => a < b ? -1 : a > b ? 1 : 0)));
}
export async function firebaseCleanupView(store) {
  const [users, usernames, tombstones] = await Promise.all([
    store.collection("users"), store.collection("usernames"), store.collection("adminTombstones"),
  ]);
  return { users, usernames, tombstones };
}
/** Preview does not write Firebase or issue any broker publish. Unknown data is preserved. */
export async function planRetainedCleanup(records, firebase, now = Date.now()) {
  const groups = [], used = new Set();
  const users = new Map(firebase.users.map(p => [p.uid || p._docId, p]));
  const indexes = new Map(firebase.usernames.map(p => [p.username || p._docId, p.uid]));
  const deleted = new Set(firebase.tombstones.map(p => p.uid || p._docId));
  async function add(key, category, prefixes, entries, topics = Object.keys(entries)) {
    if (!topics.length || Object.keys(entries).some(t => !exact(t)) || topics.some(t => used.has(t))) return;
    const hash = await fingerprint(entries);
    groups.push({ key, category, prefixes, hash, topics: topics.sort() });
    topics.forEach(t => used.add(t));
  }
  const matchGroups = new Map();
  for (const [topic, raw] of Object.entries(records)) {
    const m = /^bingo\/emqx7\/match\/([A-Z0-9]{3,12})\/([1-9][0-9]*)\//.exec(topic);
    if (!m) continue;
    const prefix = `bingo/emqx7/match/${m[1]}/${m[2]}/`;
    if (!matchGroups.has(prefix)) matchGroups.set(prefix, {});
    matchGroups.get(prefix)[topic] = raw;
  }
  for (const [prefix, entries] of matchGroups) {
    const [, , , room, seed] = prefix.split("/");
    const packets = Object.entries(entries).map(([topic,raw]) => ({topic, packet:parse(raw)}));
    if (packets.some(({packet:p}) => !p || String(p.seed) !== seed || p.roomCode !== room)) continue;
    const states = packets.filter(({topic,packet:p}) => topic.startsWith(prefix+"state/") && p.type === "GAME_SYNC").map(x=>x.packet);
    if (!states.length || states.some(p => !stamp(p.timestamp))) continue;
    // Use the host clock domain. Implausible/missing times never authorize deletion.
    const activity = packets.flatMap(({packet:p}) => [stamp(p.timestamp) && p.timestamp-(p.clockOffsetMillis||0), stamp(p.lastPlayerActivityAt) && p.lastPlayerActivityAt-(p.clockOffsetMillis||0)]).filter(Boolean);
    if (!activity.length || activity.some(t => !Number.isSafeInteger(t) || t > now + 300000) || Math.max(...activity) > now-CLEANUP_AGE_MS) continue;
    const completed = states.every(p => p.isMatchFinished === true && typeof p.winnerPlayerId === "string" && p.winnerPlayerId && Object.hasOwn(p.boardNumbers || {},p.winnerPlayerId)) && new Set(states.map(p=>p.winnerPlayerId)).size === 1;
    // Abandoned games are only considered after an additional day of quiet, with
    // all known participants explicitly absent and no saved manual pause.
    const ids = new Set(states.flatMap(p => Object.keys(p.boardNumbers || {})));
    const allAbsent = ids.size > 0 && [...ids].every(id => {
      const live = parse(records[`bingo/emqx7/room/${room}/availability/${id}`]);
      const latest = live?.playerPresences?.[id];
      return live?.type === "PLAYER_DISCONNECTED" || ["OFFLINE","BACKGROUND","MINIMIZED"].includes(latest?.status || live?.presenceStatus);
    });
    const expired = !completed && states.every(p => !p.isMatchFinished && !p.isPaused && stamp(p.lastPlayerActivityAt) && now-(p.lastPlayerActivityAt-(p.clockOffsetMillis||0)) > 300000) && allAbsent;
    if (completed) await add(prefix,"finished_matches",[prefix],entries);
    else if (expired) {
      // Presence is a read-only deletion dependency: a returning participant
      // must invalidate the old-match cleanup even before a new state arrives.
      const presenceTopics=[...ids].map(id=>`bingo/emqx7/room/${room}/availability/${id}`);
      await add(prefix,"expired_inactive_matches",[prefix,...presenceTopics],{
        ...entries,...Object.fromEntries(presenceTopics.map(t=>[t,records[t]])),
      },Object.keys(entries));
    }
  }
  // Only explicitly closed room sessions qualify; age alone never closes a lobby.
  for (const [topic, raw] of Object.entries(records)) {
    const m = /^bingo\/emqx7\/metadata\/([A-Z0-9]{3,12})\/state$/.exec(topic), room = m?.[1], state = parse(raw);
    if (!room || !state || state.roomCode !== room || !(state.status === "CLOSED" || state.tc === true || state.isTerminalClosed === true)) continue;
    if (!stamp(state.lastHeartbeat) || state.lastHeartbeat > now-CLEANUP_AGE_MS || stamp(state.createdAt)>now-CLEANUP_AGE_MS) continue;
    if ([...matchGroups].some(([p, entries]) => p.startsWith(`bingo/emqx7/match/${room}/`) && !groups.some(g => g.key === p))) continue;
    const prefixes = [`bingo/emqx7/metadata/${room}/`, `bingo/emqx7/room/${room}/`, `bingo/emqx7/room/${room}`];
    const entries = Object.fromEntries(Object.entries(records).filter(([t]) => matches(t,prefixes)));
    if (Object.entries(entries).some(([t,s]) => t.endsWith("/auth") && parse(s)?.sessionId !== state.sessionId)) continue;
    await add(`room:${room}:${state.sessionId || "legacy"}`, "closed_lobbies", prefixes, entries);
  }
  for (const [topic, raw] of Object.entries(records)) {
    if (used.has(topic) || !exact(topic)) continue;
    const p = parse(raw); if (!p) continue;
    const invite = /^bingo\/emqx7\/social\/invite\/([^/]+)\/([^/]+)$/.exec(topic);
    const legacyInvite = /^bingo\/v3\/invites\/([^/]+)$/.exec(topic);
    if ((invite || legacyInvite) && !p.isPlayRequest && stamp(p.expiresAt) && stamp(p.timestamp) && p.expiresAt >= p.timestamp && p.expiresAt <= p.timestamp+120000 && p.expiresAt < now-60000 && p.recipientUsername === (invite || legacyInvite)[1] && (!invite || p.eventId === invite[2])) {
      await add(topic, "expired_invitations", [topic], {[topic]:raw}); continue;
    }
    const account = /^bingo\/v3\/(registry|google_account)\/([^/]+)$/.exec(topic);
    if (!account || typeof p.uid !== "string" || !p.uid || typeof p.username !== "string") continue;
    if (account[1] === "registry" ? account[2] !== p.username : ![p.uid,p.uid.replace(/^google_/,"")].includes(account[2])) continue;
    const user = users.get(p.uid);
    if (deleted.has(p.uid) && !user) { await add(topic,"removed_accounts",[topic],{[topic]:raw}); continue; }
    if (!user || (user._docId && user._docId !== p.uid) || deleted.has(p.uid) || user.username !== p.username || indexes.get(p.username) !== p.uid) continue;
    const fields = ["displayName","gamesPlayed","gamesWon","currentStreak","bestStreak","level","avatarUrl"];
    if (fields.some(k => (p[k]??null) !== (user[k]??null)) || (p.publicKey && ![user.publicKey,user.installationPublicKey].includes(p.publicKey))) continue;
    await add(topic,"firebase_account_duplicates",[topic],{[topic]:raw});
  }
  const selected = []; let count = 0, totalBytes = 0;
  for (const group of groups) {
    if (count>=LIMIT) break;
    // Keep final snapshots until commands are gone, so another manual batch can
    // still establish completion. Large old matches must not become uncleanable.
    const ordered=[...group.topics].sort((a,b)=>Number(a.includes('/state/'))-Number(b.includes('/state/')) || (a<b?-1:a>b?1:0));
    const topics=[];let bytes=0;
    for (const topic of ordered) {
      const size=new TextEncoder().encode(JSON.stringify({[topic]:records[topic]})).length;
      if(topics.length>=LIMIT-count || totalBytes+bytes+size>700000)break;
      topics.push(topic);bytes+=size;
    }
    if(topics.length){selected.push({...group,topics,totalGroupRecords:group.topics.length});count+=topics.length;totalBytes+=bytes;}
  }
  return { format:1, createdAt:now, expiresAt:now+300000, manualOnly:true, groups:selected, scannedRecords:Object.keys(records).length, eligibleRecords:groups.reduce((n,g)=>n+g.topics.length,0), batchRecords:count,
    categories:Object.fromEntries([...new Set(selected.map(g=>g.category))].map(c=>[c,selected.filter(g=>g.category===c).reduce((n,g)=>n+g.topics.length,0)])) };
}
export function cleanupSummary(plan) {
  const { groups, ...summary } = plan; return {...summary, groups:groups.length};
}
export async function archiveCleanupGroup(store, group, entries, now) {
  requireThat(await fingerprint(entries) === group.hash,409,"Cleanup data changed; preview again");
  const selected=Object.fromEntries([...group.topics].sort().map(t=>[t,entries[t]]));
  const sourceHash=await fingerprint(selected);
  const path = "mqttMaintenanceArchives/"+sourceHash;
  const serialized = JSON.stringify(selected);
  const old = await store.firestoreGet(path);
  if (!old) await store.firestoreSet(path,{format:1,category:group.category,sourceHash,groupKey:group.key,archivedAt:now,recordsJson:serialized});
  const saved = old || await store.firestoreGet(path);
  requireThat(saved?.sourceHash===sourceHash && saved.recordsJson===serialized,503,"Recovery archive could not be verified; nothing deleted");
}
/** Caller must use a manually requested operation. Never called by alarms/schedules. */
export async function executeRetainedCleanup(plan, {broker, store, now=Date.now}) {
  requireThat(plan?.format===1 && plan.manualOnly===true && plan.expiresAt>now(),409,"Cleanup preview expired; preview again");
  const [snapshot, firebase] = await Promise.all([broker({snapshot:true,rawFilters:CLEANUP_FILTERS}),firebaseCleanupView(store)]);
  const current = await planRetainedCleanup(snapshot.retainedRecords || {},firebase,now());
  const approved=[]; let skipped=0;
  for (const group of plan.groups) {
    if (!current.groups.some(g=>g.key===group.key && g.hash===group.hash)) { skipped += group.topics.length; continue; }
    const entries = Object.fromEntries(Object.entries(snapshot.retainedRecords).filter(([t])=>matches(t,group.prefixes)));
    approved.push({...group,entries});
  }
  if (approved.length) {
    const entries=Object.fromEntries(approved.flatMap(g=>g.topics.map(t=>[t,g.entries[t]])));
    const hash=await fingerprint(entries);
    // One verified archive per bounded manual batch avoids a network write per
    // invitation or room. Every payload about to be deleted is included.
    await archiveCleanupGroup(store,{key:"batch:"+hash,category:"manual_cleanup_batch",hash,topics:Object.keys(entries)},entries,now());
  }
  const deletion = approved.length ? await broker({snapshot:true,rawFilters:CLEANUP_FILTERS,guardedGroups:approved}) : {cleanup:{acknowledged:[],skipped:0}};
  const after = await broker({snapshot:true,rawFilters:CLEANUP_FILTERS});
  const acknowledged=deletion.cleanup?.acknowledged || [];
  const absent=acknowledged.filter(t=>!Object.hasOwn(after.retainedRecords || {},t));
  return {manualOnly:true,requested:plan.batchRecords,acknowledged:acknowledged.length,deletedConfirmed:absent.length,recreatedOrStillPresent:acknowledged.length-absent.length,skippedChanged:skipped+(deletion.cleanup?.skipped||0),scannedRecordsAfter:Object.keys(after.retainedRecords||{}).length,archivedGroups:approved.length,completedAt:now(),categories:plan.categories};
}
