import test from "node:test";
import assert from "node:assert/strict";
import { planRetainedCleanup, executeRetainedCleanup, archiveCleanupGroup, fingerprint, CLEANUP_AGE_MS } from "../retained-cleanup.js";
import { brokerExchange, mqttPublish, decodePackets } from "../mqtt.js";
const now=1793340000000, old=now-2*CLEANUP_AGE_MS;
const root="bingo/emqx7/match/TEST123/123/";
const empty={users:[],usernames:[],tombstones:[]};
const packet=(id,extra={})=>({type:"GAME_SYNC",roomCode:"TEST123",seed:123,playerId:id,timestamp:old,lastPlayerActivityAt:old,isMatchFinished:true,winnerPlayerId:"a",boardNumbers:{a:[1],b:[1]},...extra});
const records=()=>({[root+"state/a"]:JSON.stringify(packet("a")),[root+"state/b"]:JSON.stringify(packet("b")),[root+"command/1/a/PICK_NUMBER"]:JSON.stringify(packet("a",{type:"PICK_NUMBER"}))});
const plan=(r=records(),f=empty)=>planRetainedCleanup(r,f,now);
test("finished old match is selected without changing its result",async()=>{
 const r=records(),p=await plan(r);assert.equal(p.batchRecords,3);assert.equal(p.categories.finished_matches,3);assert.deepEqual(r,records());assert.equal(p.manualOnly,true);
});
test("recent finished match is preserved",async()=>{const r=records();r[root+"state/a"]=JSON.stringify(packet("a",{timestamp:now-1000}));assert.equal((await plan(r)).batchRecords,0);});
test("future clock data is preserved",async()=>{const r=records();r[root+"state/a"]=JSON.stringify(packet("a",{timestamp:now+600000}));assert.equal((await plan(r)).batchRecords,0);});
test("unfinished active match is preserved",async()=>{const r=Object.fromEntries(Object.entries(records()).map(([t,s])=>[t,JSON.stringify({...JSON.parse(s),isMatchFinished:false,winnerPlayerId:""})]));assert.equal((await plan(r)).batchRecords,0);});
test("abandoned match requires explicit absence for every player",async()=>{
 const r=Object.fromEntries(Object.entries(records()).map(([t,s])=>[t,JSON.stringify({...JSON.parse(s),isMatchFinished:false,winnerPlayerId:""})]));
 r['bingo/emqx7/room/TEST123/availability/a']=JSON.stringify({type:"PLAYER_DISCONNECTED"});assert.equal((await plan(r)).batchRecords,0);
 r['bingo/emqx7/room/TEST123/availability/b']=JSON.stringify({type:"PLAYER_DISCONNECTED"});assert.equal((await plan(r)).categories.expired_inactive_matches,3);
});
test("manual pause remains protected",async()=>{
 const r=Object.fromEntries(Object.entries(records()).map(([t,s])=>[t,JSON.stringify({...JSON.parse(s),isMatchFinished:false,winnerPlayerId:"",isPaused:true})]));
 for(const id of ['a','b'])r[`bingo/emqx7/room/TEST123/availability/${id}`]=JSON.stringify({type:"PLAYER_DISCONNECTED"});assert.equal((await plan(r)).batchRecords,0);
});
test("clock offsets are localized without shortening retention",async()=>{const r=Object.fromEntries(Object.entries(records()).map(([t,s])=>{const p=JSON.parse(s);return[t,JSON.stringify({...p,clockOffsetMillis:60000,timestamp:old+60000,lastPlayerActivityAt:old+60000})]}));assert.equal((await plan(r)).batchRecords,3);});
test("room and seed mismatches remain protected",async()=>{for(const field of ['roomCode','seed']){const r=records();r[root+"state/a"]=JSON.stringify(packet("a",{[field]:"OTHER"}));assert.equal((await plan(r)).batchRecords,0);}});
test("disagreeing winners remain protected",async()=>{const r=records();r[root+"state/a"]=JSON.stringify(packet("a",{winnerPlayerId:"b"}));assert.equal((await plan(r)).batchRecords,0);});
test("corrupt records and unknown families remain protected",async()=>{const r={...records(),[root+'unknown']:'broken','bingo/v3/presence/alice':'null','bingo/v3/friends/alice':'[]'};assert.equal((await plan(r)).batchRecords,0);});
test("old WAITING or PLAYING lobby is not closed by cleanup",async()=>{for(const status of ['WAITING','PLAYING'])assert.equal((await plan({'bingo/emqx7/metadata/ROOM123/state':JSON.stringify({roomCode:'ROOM123',status,lastHeartbeat:old,createdAt:old})})).batchRecords,0);});
test("explicitly closed old lobby and availability records form one group",async()=>{const r={'bingo/emqx7/metadata/ROOM123/state':JSON.stringify({roomCode:'ROOM123',status:'CLOSED',sessionId:'one',lastHeartbeat:old,createdAt:old}),'bingo/emqx7/metadata/ROOM123/auth':JSON.stringify({sessionId:'one'}),'bingo/emqx7/room/ROOM123/availability/a':'{}'};assert.equal((await plan(r)).categories.closed_lobbies,3);});
test("changed room incarnation is protected",async()=>{const r={'bingo/emqx7/metadata/ROOM123/state':JSON.stringify({roomCode:'ROOM123',status:'CLOSED',sessionId:'one',lastHeartbeat:old,createdAt:old}),'bingo/emqx7/metadata/ROOM123/auth':JSON.stringify({sessionId:'two'})};assert.equal((await plan(r)).batchRecords,0);});
test("expired invitation can be removed while fresh invitation stays",async()=>{
 const invite={recipientUsername:'b',eventId:'event',timestamp:old,expiresAt:old+120000};const t='bingo/emqx7/social/invite/b/event';assert.equal((await plan({[t]:JSON.stringify(invite)})).batchRecords,1);
 for(const changed of [{recipientUsername:'x'},{eventId:'other'},{timestamp:now,expiresAt:now+120000},{expiresAt:old+300000},{isPlayRequest:true}])assert.equal((await plan({[t]:JSON.stringify({...invite,...changed})})).batchRecords,0);
});
const entry={uid:'google_example',username:'example',displayName:'Example',gamesPlayed:5,gamesWon:2,currentStreak:1,bestStreak:2,level:1,avatarUrl:null};
const firebase=()=>({users:[{...entry,_docId:entry.uid}],usernames:[{_docId:entry.username,uid:entry.uid}],tombstones:[]});
test("account duplicates require complete canonical Firebase agreement",async()=>{assert.equal((await plan({'bingo/v3/registry/example':JSON.stringify(entry)},firebase())).batchRecords,1);assert.equal((await plan({'bingo/v3/registry/example':JSON.stringify(entry)},empty)).batchRecords,0);});
test("newer local statistics and unbacked public key remain protected",async()=>{for(const changed of [{gamesPlayed:6},{avatarUrl:'unique-photo'},{publicKey:'unique-key'}])assert.equal((await plan({'bingo/v3/registry/example':JSON.stringify({...entry,...changed})},firebase())).batchRecords,0);});
test("explicit deletion marker requires absent current profile",async()=>{const f={users:[],usernames:[],tombstones:[{_docId:entry.uid}]};assert.equal((await plan({'bingo/v3/registry/example':JSON.stringify(entry)},f)).categories.removed_accounts,1);f.users=[entry];assert.equal((await plan({'bingo/v3/registry/example':JSON.stringify(entry)},f)).batchRecords,0);});
test("arbitrary or wildcard account paths are not candidates",async()=>{for(const t of ['bingo/v3/registry/#','bingo/v3/registry/other','bingo/v3/google_account/other','elsewhere/example'])assert.equal((await plan({[t]:JSON.stringify(entry)},firebase())).batchRecords,0);});
test("large old matches are cleaned in bounded batches while retaining completion proof",async()=>{const r={};for(let i=0;i<205;i++)r[root+'command/'+i+'/a/PICK_NUMBER']=JSON.stringify(packet('a',{type:'PICK_NUMBER'}));Object.assign(r,records());const p=await plan(r);assert.equal(p.batchRecords,200);assert.ok(p.eligibleRecords>200);assert.ok(p.groups[0].topics.every(t=>!t.includes('/state/')));});
function executorFixture(initial=records()) {
 const r=structuredClone(initial), archives=new Map(),sent=[];let failArchive=false, changeAtWrite=false;
 const store={collection:async()=>[],firestoreGet:async p=>archives.get(p)||null,firestoreSet:async(p,v)=>{if(failArchive)throw Error('archive unavailable');archives.set(p,v);if(changeAtWrite)r[root+'state/a']=JSON.stringify(packet('a',{timestamp:now}));}};
 const broker=async options=>{
  if(options.guardedGroups){for(const g of options.guardedGroups){const current=Object.fromEntries(Object.entries(r).filter(([t])=>g.prefixes.some(p=>p.endsWith('/')?t.startsWith(p):t===p)));if(await fingerprint(current)!==g.hash)return{cleanup:{acknowledged:[],skipped:g.topics.length}};for(const t of g.topics){sent.push(t);delete r[t];}}return{cleanup:{acknowledged:[...sent],skipped:0}};}
  return{retainedRecords:structuredClone(r)};
 };
 return{r,archives,sent,store,broker,setFailArchive:()=>failArchive=true,setChange:()=>changeAtWrite=true};
}
test("actual executor archives before deleting and verifies absence",async()=>{const f=executorFixture(),p=await plan();const result=await executeRetainedCleanup(p,{...f,now:()=>now});assert.equal(result.deletedConfirmed,3);assert.equal(f.archives.size,1);assert.deepEqual(f.sent.sort(),Object.keys(records()).sort());});
test("archive failure stops deletion",async()=>{const f=executorFixture();f.setFailArchive();await assert.rejects(executeRetainedCleanup(await plan(),{...f,now:()=>now}));assert.equal(f.sent.length,0);});
test("changed data before execution is skipped",async()=>{const f=executorFixture(),p=await plan();f.r[root+'state/a']=JSON.stringify(packet('a',{timestamp:now}));const result=await executeRetainedCleanup(p,{...f,now:()=>now});assert.equal(result.deletedConfirmed,0);assert.equal(f.archives.size,0);});
test("changed data after archive is preserved",async()=>{const f=executorFixture(),p=await plan();f.setChange();const result=await executeRetainedCleanup(p,{...f,now:()=>now});assert.equal(result.deletedConfirmed,0);assert.equal(result.skippedChanged,3);assert.equal(f.sent.length,0);});
test("expired previews never delete",async()=>{const f=executorFixture(),p=await plan();await assert.rejects(executeRetainedCleanup(p,{...f,now:()=>now+300001}));assert.equal(f.sent.length,0);});
test("archive replay preserves original verified recovery data",async()=>{const f=executorFixture(),p=await plan(),g=p.groups[0],r=records();await archiveCleanupGroup(f.store,g,r,now);await archiveCleanupGroup(f.store,g,r,now+1);assert.equal(f.archives.size,1);});

function wireFixture({changed=false, addDuringDelete=false}={}) {
 const r=records(),writes=[],listeners=new Map();
 const emit=data=>queueMicrotask(()=>listeners.get('message')?.({data}));
 const socket={accept(){},close(){},addEventListener(n,f){listeners.set(n,f);},send(frame){
  const type=frame[0]>>4;
  if(type===1)emit(Uint8Array.of(32,2,0,0));
  if(type===8){
   const body=decodePackets(frame)[0].body;let offset=2,filters=[];
   while(offset<body.length){const n=(body[offset]<<8)|body[offset+1];filters.push(new TextDecoder().decode(body.slice(offset+2,offset+2+n)));offset+=3+n;}
   emit(Uint8Array.from([144,2+filters.length,0,1,...filters.map(()=>0)]));
   for(const [t,s]of Object.entries(r))emit(mqttPublish(t,s,true,44));
   if(changed)emit(mqttPublish(root+'state/a',JSON.stringify(packet('a',{timestamp:now})),false,45));
  }
  if(type===3){const p=decodePackets(frame)[0];writes.push(p);if(p.topic.includes('/probe/'))emit(mqttPublish(p.topic,p.payload,false,p.id));else if(p.payload===''){delete r[p.topic];if(addDuringDelete){addDuringDelete=false;emit(mqttPublish(root+'command/new/a/PICK_NUMBER',JSON.stringify(packet('a',{type:'PICK_NUMBER',timestamp:now})),false,46));}}emit(Uint8Array.of(64,2,p.id>>8,p.id&255));}
 }};
 return{r,writes,transport:async()=>({webSocket:socket})};
}
const env={MQTT_URL:'wss://fixture.example/mqtt',MQTT_USERNAME:'fixture',MQTT_PASSWORD:'fixture'};
test("actual MQTT codec preserves retained flag",()=>assert.equal(decodePackets(mqttPublish('bingo/x','{}',true,3))[0].retained,true));
test("actual broker path reads fenced inventory and issues exact empty retained deletes",async()=>{
 const f=wireFixture(),p=await plan(),g={...p.groups[0],entries:records()};const result=await brokerExchange(env,{snapshot:true,rawFilters:['bingo/emqx7/match/#'],guardedGroups:[g]},f.transport);
 assert.equal(result.cleanup.acknowledged.length,3);assert.equal(f.writes.filter(p=>p.retained && p.payload==='').length,3);assert.equal(Object.keys(f.r).length,0);
});
test("actual broker path skips a group changed by a live packet",async()=>{
 const f=wireFixture({changed:true}),p=await plan(),g={...p.groups[0],entries:records()};const result=await brokerExchange(env,{snapshot:true,rawFilters:['bingo/emqx7/match/#'],guardedGroups:[g]},f.transport);
 assert.equal(result.cleanup.acknowledged.length,0);assert.equal(result.cleanup.skipped,3);assert.equal(f.writes.filter(p=>p.retained && p.payload==='').length,0);
});

test("returning player invalidates abandoned cleanup before a new game snapshot",async()=>{
 const r=Object.fromEntries(Object.entries(records()).map(([t,s])=>[t,JSON.stringify({...JSON.parse(s),isMatchFinished:false,winnerPlayerId:""})]));
 for(const id of ['a','b'])r[`bingo/emqx7/room/TEST123/availability/${id}`]=JSON.stringify({type:'PLAYER_DISCONNECTED'});
 const f=executorFixture(r),p=await plan(r),save=f.store.firestoreSet;
 f.store.firestoreSet=async(path,value)=>{await save(path,value);f.r['bingo/emqx7/room/TEST123/availability/a']=JSON.stringify({type:'PLAYER_RECONNECTED',presenceStatus:'ONLINE'});};
 const result=await executeRetainedCleanup(p,{...f,now:()=>now});assert.equal(result.deletedConfirmed,0);assert.equal(result.skippedChanged,3);assert.equal(f.sent.length,0);
});
test("actual broker stops remaining deletes when a new live command appears",async()=>{
 const f=wireFixture({addDuringDelete:true}),p=await plan(),g={...p.groups[0],entries:records()};
 const result=await brokerExchange(env,{snapshot:true,rawFilters:['bingo/emqx7/match/#'],guardedGroups:[g]},f.transport);
 assert.equal(result.cleanup.acknowledged.length,1);assert.equal(result.cleanup.skipped,2);
});
