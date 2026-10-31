import test from "node:test";
import assert from "node:assert/strict";
import {planRetainedCleanup,executeRetainedCleanup,fingerprint} from "../retained-cleanup.js";
import {brokerExchange,mqttPublish,decodePackets} from "../mqtt.js";
import {AdminControl} from "../worker.js";
import {MemoryStorage,FakeLegacy} from "./fixtures.js";
import {hasGameplayActivity,REJOIN_GRACE_MS} from "../cleanup-activity.js";
const now=Date.now(),old=now-2*86400000,empty={users:[],usernames:[],tombstones:[]};
const root="bingo/emqx7/match/GUARD123/123/";
function records(){const packet={type:"GAME_SYNC",roomCode:"GUARD123",seed:123,timestamp:old,lastPlayerActivityAt:old,isMatchFinished:true,winnerPlayerId:"a",boardNumbers:{a:[1],b:[1]}};return{[root+"state/a"]:JSON.stringify(packet),[root+"state/b"]:JSON.stringify(packet),[root+"command/1/a/PICK_NUMBER"]:JSON.stringify({...packet,type:"PICK_NUMBER"})};}
for(const status of ["PLAYING","WAITING"]){
 test("MC-005 fresh "+status+" room protects its older finished match",async()=>{
  const r=records();r["bingo/emqx7/metadata/GUARD123/state"]=JSON.stringify({roomCode:"GUARD123",status,lastHeartbeat:now});
  assert.equal((await planRetainedCleanup(r,empty,now)).batchRecords,0);
 });
}
test("MC-005 recent reconnection protects finished receipts before a new snapshot",async()=>{
 const r=records();r["bingo/emqx7/room/GUARD123/availability/a"]=JSON.stringify({type:"PLAYER_RECONNECTED",presenceStatus:"ONLINE",timestamp:now});
 assert.equal((await planRetainedCleanup(r,empty,now)).batchRecords,0);
});
test("MC-005 all-offline rejoin grace and manual pause are preserved",async()=>{
 const r=records();for(const t of Object.keys(r))r[t]=JSON.stringify({...JSON.parse(r[t]),isMatchFinished:false,winnerPlayerId:"",timestamp:now-120000,lastPlayerActivityAt:now-120000});
 for(const id of ["a","b"])r[`bingo/emqx7/room/GUARD123/availability/${id}`]=JSON.stringify({type:"PLAYER_DISCONNECTED",timestamp:0});
 assert.equal(hasGameplayActivity(r,now),true);assert.equal((await planRetainedCleanup(r,empty,now)).batchRecords,0);
 for(const t of Object.keys(r).filter(t=>t.includes("/state/")))r[t]=JSON.stringify({...JSON.parse(r[t]),timestamp:old,lastPlayerActivityAt:old,isPaused:true});
 for(const t of Object.keys(r).filter(t=>t.includes("/command/")))r[t]=JSON.stringify({...JSON.parse(r[t]),timestamp:old,lastPlayerActivityAt:old});
 assert.equal((await planRetainedCleanup(r,empty,now)).batchRecords,0);
 assert.equal(hasGameplayActivity({[root+"state/a"]:JSON.stringify({timestamp:now-REJOIN_GRACE_MS-1})},now),false);
});
test("MC-005 reconnecting player keeps an old CLOSED lobby",async()=>{
 const r={"bingo/emqx7/metadata/GUARD123/state":JSON.stringify({roomCode:"GUARD123",status:"CLOSED",lastHeartbeat:old,createdAt:old}),"bingo/emqx7/room/GUARD123/availability/a":JSON.stringify({type:"PLAYER_RECONNECTED",timestamp:now,presenceStatus:"ONLINE"})};
 assert.equal((await planRetainedCleanup(r,empty,now)).batchRecords,0);
});
test("MC-005 invalid clock normalization cannot make data eligible early",async()=>{
 const r=records();for(const t of Object.keys(r))r[t]=JSON.stringify({...JSON.parse(r[t]),timestamp:1,lastPlayerActivityAt:1,clockOffsetMillis:2});
 assert.equal((await planRetainedCleanup(r,empty,now)).batchRecords,0);
});
test("MC-005 presence change after archive invalidates a finished-match cleanup group",async()=>{
 const r=records(),topic="bingo/emqx7/room/GUARD123/availability/a";r[topic]=JSON.stringify({type:"PLAYER_DISCONNECTED",timestamp:old});
 const archive=new Map();let writes=0;
 const store={collection:async()=>[],firestoreGet:async p=>archive.get(p),firestoreSet:async(p,v)=>{archive.set(p,v);r[topic]=JSON.stringify({type:"PLAYER_RECONNECTED",presenceStatus:"ONLINE",timestamp:now});}};
 const broker=async args=>{if(args.guardedGroups){let skipped=0;const ack=[];for(const g of args.guardedGroups){const current=Object.fromEntries(Object.entries(r).filter(([t])=>g.prefixes.some(p=>p.endsWith("/")?t.startsWith(p):t===p)));if(await fingerprint(current)!==g.hash){skipped+=g.topics.length;continue;}for(const t of g.topics){writes++;delete r[t];ack.push(t);}}return{cleanup:{acknowledged:ack,skipped}};}return{retainedRecords:structuredClone(r)};};
 const p=await planRetainedCleanup(r,empty,now),result=await executeRetainedCleanup(p,{store,broker,now:()=>now});
 assert.equal(writes,0);assert.equal(result.deletedConfirmed,0);assert.equal(result.skippedChanged,3);
});
test("MC-006 actual automatic alarm defers bulk work while any recent match is active",async()=>{
 const storage=new MemoryStorage(),legacy=new FakeLegacy(),r=records();let ready,bulk=0;
 r["bingo/emqx7/metadata/LIVE123/state"]=JSON.stringify({roomCode:"LIVE123",status:"PLAYING",lastHeartbeat:now});
 const env={PUBLIC_URL:"https://admin.test",ALLOWED_ORIGIN:"https://example.test",MQTT_AUTOCLEAN_ENABLED:"true",TEST_LEGACY:legacy,
  TEST_BROKER:async args=>{if(args.guardedGroups)bulk++;return{retainedRecords:structuredClone(r)};}};
 const c=new AdminControl({storage,blockConcurrencyWhile:f=>(ready=f())},env);await ready;await c.alarm();
 assert.equal(bulk,0);assert.equal((await storage.get("mqtt-cleanup:automatic")).status,"DEFERRED");
});
test("MC-006 actual broker stops old-record deletion when a different room starts gameplay",async()=>{
 const r=records(),listeners=new Map(),writes=[];let started=false;
 const emit=data=>queueMicrotask(()=>listeners.get("message")?.({data}));
 const socket={accept(){},close(){},addEventListener(n,f){listeners.set(n,f);},send(frame){
  const type=frame[0]>>4;
  if(type===1)emit(Uint8Array.of(32,2,0,0));
  if(type===8){const b=decodePackets(frame)[0].body;let o=2,count=0;while(o<b.length){const n=(b[o]<<8)|b[o+1];o+=n+3;count++;}emit(Uint8Array.from([144,count+2,0,1,...Array(count).fill(0)]));for(const[t,p]of Object.entries(r))emit(mqttPublish(t,p,true,31));}
  if(type===3){const p=decodePackets(frame)[0];if(p.topic.includes("/probe/"))emit(mqttPublish(p.topic,p.payload,false,p.id));else if(p.payload===""){writes.push(p.topic);delete r[p.topic];if(!started){started=true;emit(mqttPublish("bingo/emqx7/room/LIVE123","MHB|1|match|PING|guest|host|g|1|c|"+Date.now(),false,32));}}emit(Uint8Array.of(64,2,p.id>>8,p.id&255));}
 }};
 const p=await planRetainedCleanup(r,empty,now),g={...p.groups[0],entries:structuredClone(r)};
 const result=await brokerExchange({MQTT_URL:"wss://fixture/mqtt",MQTT_USERNAME:"fixture",MQTT_PASSWORD:"fixture"},{snapshot:true,rawFilters:["bingo/emqx7/match/#","bingo/emqx7/room/+"],guardedGroups:[g],deferOnGameplay:true},async()=>({webSocket:socket}));
 assert.equal(writes.length,1);assert.equal(result.cleanup.deferred,true);assert.equal(result.cleanup.acknowledged.length,1);
});
