// Opt-in owned-room integration harness. Never scans/deletes production players.
import fs from "node:fs/promises";
import os from "node:os";
import tls from "node:tls";
import {Readable,Writable} from "node:stream";
import assert from "node:assert/strict";
import {brokerTcp} from "../mqtt.js";
import {LegacyStore} from "../legacy.js";
import {AdminControl} from "../worker.js";
import {MemoryStorage} from "./fixtures.js";
import {planRetainedCleanup,executeRetainedCleanup} from "../retained-cleanup.js";
assert.equal(process.env.BINGO_CLEANUP_LIVE,"1","Explicit live-test opt-in required");
const [mode,manifestPath]=process.argv.slice(2),manifest=JSON.parse(await fs.readFile(manifestPath,"utf8"));
assert.match(manifest.activeRoom,/^CT[A-F0-9]{8}$/);assert.match(manifest.oldRoom,/^CO[A-F0-9]{8}$/);
assert.ok(Number.isSafeInteger(manifest.seed)&&manifest.seed>0);
const env=JSON.parse(await fs.readFile(process.env.BINGO_MAINTENANCE_CONFIG || os.homedir()+"/.config/bingo/maintenance.json","utf8"));
function connectFactory({hostname,port}){const socket=tls.connect({host:hostname,port,servername:hostname,rejectUnauthorized:true});return{opened:new Promise((r,j)=>{socket.once("secureConnect",r);socket.once("error",j)}),readable:Readable.toWeb(socket),writable:Writable.toWeb(socket),close:()=>socket.destroy()};}
const base=args=>brokerTcp(env,args,connectFactory),realStore=new LegacyStore(env),archives=new Set();
const root=room=>`bingo/emqx7/match/${room}/${manifest.seed}/`;
const filters=[...new Set([manifest.activeRoom,manifest.oldRoom].flatMap(room=>[
 root(room)+"#",`bingo/emqx7/metadata/${room}/+`,`bingo/emqx7/room/${room}/#`]))];
const scope=args=>base({...args,...(args.rawFilters?{rawFilters:filters}:{})});
const store={collection:async()=>[],firestoreGet:p=>realStore.firestoreGet(p),firestoreSet:async(p,v)=>{
 assert.ok(p.startsWith("mqttMaintenanceArchives/"));
 for(const topic of Object.keys(JSON.parse(v.recordsJson)))assert.ok(topic.startsWith(root(manifest.oldRoom)),"Only owned old fixture may be archived");
 archives.add(p);await realStore.firestoreSet(p,v);
}};
const saveArchives=async()=>{
 const old=await fs.readFile(manifestPath+".archives","utf8").then(JSON.parse).catch(()=>[]);
 await fs.writeFile(manifestPath+".archives",JSON.stringify([...new Set([...old,...archives])]),{mode:0o600});
};
const messages=async rows=>{for(let i=0;i<rows.length;i+=8)await base({messages:rows.slice(i,i+8)});};
try{
 if(mode==="prepare"){
  const old=Date.now()-2*86400000,p={roomCode:manifest.oldRoom,seed:manifest.seed,timestamp:old,lastPlayerActivityAt:old,isMatchFinished:true,winnerPlayerId:"fixture_a",boardNumbers:{fixture_a:[1],fixture_b:[1]}};
  const entries=["state/fixture_a","state/fixture_b",...Array.from({length:40},(_,i)=>`command/${i}/fixture_a/PICK_NUMBER`)].map((suffix,i)=>({topic:root(manifest.oldRoom)+suffix,payload:JSON.stringify({...p,type:i<2?"GAME_SYNC":"PICK_NUMBER"}),retain:true}));
  await messages(entries);console.log(JSON.stringify({mode,ownedRecords:entries.length}));
 }else if(mode==="automatic"){
  const storage=new MemoryStorage();let ready;
  const c=new AdminControl({storage,blockConcurrencyWhile:f=>(ready=f())},{PUBLIC_URL:"https://cleanup-fixture.invalid",ALLOWED_ORIGIN:"https://cleanup-fixture.invalid",MQTT_AUTOCLEAN_ENABLED:"true",TEST_LEGACY:store,TEST_BROKER:scope});
  await ready;await c.alarm();const result=await storage.get("mqtt-cleanup:automatic");
  console.log(JSON.stringify({mode,status:result.status,deleted:result.result?.deletedConfirmed||0}));
 }else if(mode==="race"){
  const s=await scope({snapshot:true,rawFilters:filters});
  const plan=await planRetainedCleanup(s.retainedRecords,{users:[],usernames:[],tombstones:[]},Date.now(),{automatic:true});
  assert.ok(plan.batchRecords>0,"Old owned fixture must be eligible before clients connect");
  const result=await executeRetainedCleanup(plan,{broker:scope,store});
  console.log(JSON.stringify({mode,...result}));
 }else if(mode==="inspect"){
  const s=await scope({snapshot:true,rawFilters:filters});
  const topics=Object.keys(s.retainedRecords);console.log(JSON.stringify({mode,activeRecords:topics.filter(t=>t.startsWith(root(manifest.activeRoom))).length,oldRecords:topics.filter(t=>t.startsWith(root(manifest.oldRoom))).length}));
 }else if(mode==="finish"){
  const s=await scope({snapshot:true,rawFilters:filters});
  const own=t=>[manifest.activeRoom,manifest.oldRoom].some(room=>t.startsWith(root(room))||t.startsWith(`bingo/emqx7/metadata/${room}/`)||t.startsWith(`bingo/emqx7/room/${room}/`)||t===`bingo/emqx7/room/${room}`);
  const topics=Object.keys(s.retainedRecords).filter(own);await messages(topics.map(topic=>({topic,payload:"",retain:true})));
  for(const path of await fs.readFile(manifestPath+".archives","utf8").then(JSON.parse).catch(()=>[]))await realStore.firestoreDelete(path);
  console.log(JSON.stringify({mode,ownedRecordsRemoved:topics.length}));
 }else throw Error("Unknown owned-fixture mode");
}finally{await saveArchives();}
